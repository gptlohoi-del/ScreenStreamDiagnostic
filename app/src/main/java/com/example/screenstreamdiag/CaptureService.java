package com.example.screenstreamdiag;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Foreground projection service designed for Android 9. No sound or screen recording. */
public final class CaptureService extends Service {
    public static final String START="com.example.screenstreamdiag.START";
    public static final String STOP="com.example.screenstreamdiag.STOP";
    public static final String EXTRA_RESULT_CODE="resultCode";
    public static final String EXTRA_RESULT_DATA="resultData";
    public static final String EXTRA_FPS="fps";
    public static final String EXTRA_BITRATE="bitrate";
    public static final String EXTRA_SCALE="scale";
    public static volatile boolean isRunning;
    public static volatile String status = "Chưa phát";
    private static final String CHANNEL="rtsp_diagnostic_capture";
    private volatile boolean streaming;
    private MediaProjection projection;
    private MediaCodec codec;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;
    private RtspServer server;
    private Thread encoderThread;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long startedAt;
    private int lastWarningCount = -1;
    private long lastWarningNotificationMs;
    private volatile int currentWidth,currentHeight,currentFps,currentBitrate;

    @Override public void onCreate() {
        super.onCreate();
        DiagnosticLog.init(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"Truyền màn hình RTSP",NotificationManager.IMPORTANCE_LOW));
        DiagnosticLog.event("SERVICE","onCreate pid="+android.os.Process.myPid()+" Android="+Build.VERSION.SDK_INT);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(8,notification("Đang chuẩn bị phát RTSP"));
        if (intent == null) {
            DiagnosticLog.event("SERVICE","onStartCommand null intent; not restarting stale MediaProjection token");
            stopSelf(); return START_NOT_STICKY;
        }
        if (STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (streaming) { DiagnosticLog.event("SERVICE","Ignoring duplicate START"); return START_NOT_STICKY; }
        int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);
        Intent resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode!=Activity.RESULT_OK || resultData==null) {
            DiagnosticLog.event("SERVICE","MediaProjection permission data missing");
            stopSelf(); return START_NOT_STICKY;
        }
        int fps=Math.max(10,Math.min(30,intent.getIntExtra(EXTRA_FPS,20)));
        int bitrate=Math.max(500000,Math.min(4000000,intent.getIntExtra(EXTRA_BITRATE,2000000)));
        int scale=Math.max(50,Math.min(100,intent.getIntExtra(EXTRA_SCALE,100)));
        try { startCapture(resultCode,resultData,fps,bitrate,scale); }
        catch (Exception e) {
            DiagnosticLog.error("CAPTURE_START_FAILED",e);
            status="Lỗi khởi tạo: "+e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private Notification notification(String line) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent click=PendingIntent.getActivity(this,0,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,CaptureService.class).setAction(STOP);
        PendingIntent stopAction=PendingIntent.getService(this,1,stop,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("RTSP Diagnostic").setContentText(line)
                .setContentIntent(click).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Dừng phát",stopAction).build())
                .build();
    }
    private void startCapture(int resultCode,Intent resultData,int fps,int bitrate,int scale) throws Exception {
        DiagnosticLog.event("DEVICE",DeviceInspector.inspect(this).text.replace('\n',';'));
        currentFps=fps;currentBitrate=bitrate;
        DisplayMetrics metrics=getResources().getDisplayMetrics();
        int sourceW=metrics.widthPixels,sourceH=metrics.heightPixels;
        int targetW=sourceW>sourceH?1280:720;
        int targetH=sourceW>sourceH?720:1280;
        float factor=Math.min(1f,Math.min((float)targetW/sourceW,(float)targetH/sourceH));
        factor*=scale/100f;
        currentWidth=Math.max(16,Math.round(sourceW*factor/16f)*16);
        currentHeight=Math.max(16,Math.round(sourceH*factor/16f)*16);
        DiagnosticLog.event("ENCODER","CONFIG codec=H264 source="+sourceW+"x"+sourceH+
                " capture="+currentWidth+"x"+currentHeight+" dpi="+metrics.densityDpi+
                " fps="+fps+" bitrate="+bitrate);
        MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=mgr.getMediaProjection(resultCode,resultData);
        if (projection==null) throw new IllegalStateException("MediaProjection null");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                DiagnosticLog.event("PROJECTION","MediaProjection.onStop: Android/user ended screen sharing");
                status="Android đã dừng chia sẻ màn hình";
                stopSelf();
            }
        },new Handler(Looper.getMainLooper()));
        MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,currentWidth,currentHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
        codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        DiagnosticLog.event("ENCODER","Selected encoder="+codec.getName());
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface=codec.createInputSurface();
        codec.start();
        virtualDisplay=projection.createVirtualDisplay("RTSP-DIAGNOSTIC",
                currentWidth,currentHeight,metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,inputSurface,null,null);
        if (virtualDisplay==null) throw new IllegalStateException("VirtualDisplay null");
        server=new RtspServer(); server.start();
        acquireLocks(); registerNetworkMonitor();
        startedAt=SystemClock.elapsedRealtime();
        getSharedPreferences("diag_state",MODE_PRIVATE).edit().putBoolean("session_active",true).commit();
        streaming=true;isRunning=true;
        status="Đang phát "+currentWidth+"x"+currentHeight+" H.264";
        getSystemService(NotificationManager.class).notify(8,notification(status));
        encoderThread=new Thread(this::drainEncoder,"video-encoder-drain");encoderThread.start();
        DiagnosticLog.event("SERVICE","STREAM_STARTED url=rtsp://"+DeviceInspector.localIpv4()+":"+RtspServer.PORT+"/live");
    }
    private void acquireLocks() {
        try {
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            cpuLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RTSPDiagnostic:encoder");
            cpuLock.acquire();
            WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wifi!=null) {
                wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"RTSPDiagnostic:wi-fi");
                wifiLock.acquire();
            }
            DiagnosticLog.event("POWER","CPU partial wakelock and Wi-Fi high perf lock requested");
        } catch (Exception e) {DiagnosticLog.error("POWER_LOCK_FAILED",e);}
    }
    private void registerNetworkMonitor() {
        try {
            cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            networkCallback=new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    DiagnosticLog.event("NETWORK","default network AVAILABLE network="+network);
                }
                @Override public void onLost(Network network) {
                    DiagnosticLog.event("NETWORK","default network LOST network="+network);
                }
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps) {
                    DiagnosticLog.event("NETWORK","capabilities changed transportWiFi="+
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+" metered="+
                            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
            DiagnosticLog.event("NETWORK","monitor initialized ip="+DeviceInspector.localIpv4());
        } catch (Exception e) {DiagnosticLog.error("NETWORK_MONITOR_FAILED",e);}
    }
    private void extractConfig(MediaFormat output) {
        byte[] sps=null,pps=null;
        for(int index=0;index<2;index++) {
            ByteBuffer b=output.getByteBuffer("csd-"+index);
            if(b==null)continue;
            ByteBuffer copy=b.duplicate();
            byte[] raw=new byte[copy.remaining()];copy.get(raw);
            for(byte[] nal:H264.nalUnits(raw)) {
                if(H264.type(nal)==7) sps=nal;
                if(H264.type(nal)==8) pps=nal;
            }
        }
        if(server!=null)server.setCodecConfig(sps,pps);
        DiagnosticLog.event("ENCODER","output format change="+output+" sps="+(sps!=null)+" pps="+(pps!=null));
    }
    private void drainEncoder() {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        long lastReport=SystemClock.elapsedRealtime();
        long frames=0,totalBytes=0;
        long lastFrame=SystemClock.elapsedRealtime();
        int errors=0;
        try {
            while(streaming) {
                int index=codec.dequeueOutputBuffer(info,1000000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    extractConfig(codec.getOutputFormat());
                } else if(index>=0) {
                    ByteBuffer buffer=codec.getOutputBuffer(index);
                    if(buffer!=null && info.size>0) {
                        buffer.position(info.offset);buffer.limit(info.offset+info.size);
                        byte[] raw=new byte[info.size];buffer.get(raw);
                        List<byte[]> nals=H264.nalUnits(raw);
                        byte[] sps=null,pps=null;
                        List<byte[]> video=new ArrayList<>();
                        boolean key=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;
                        for(byte[] nal:nals) {
                            int type=H264.type(nal);
                            if(type==7)sps=nal;
                            else if(type==8)pps=nal;
                            else if(type==5){key=true;video.add(nal);}
                            else if(type==1||type==6||type==9)video.add(nal);
                        }
                        server.setCodecConfig(sps,pps);
                        if(!video.isEmpty()) {
                            server.broadcast(video,key,info.presentationTimeUs);
                            frames++;totalBytes+=info.size;
                            lastFrame=SystemClock.elapsedRealtime();
                        }
                    }
                    codec.releaseOutputBuffer(index,false);
                }
                long now=SystemClock.elapsedRealtime();
                if(now-lastReport>=10000) {
                    double duration=(now-lastReport)/1000.0;
                    String detail=String.format(Locale.US,"fps=%.1f encodedKbps=%.0f frames=%d lastFrameAgoMs=%d uptimeSec=%d ip=%s",
                            frames/duration,totalBytes*8.0/duration/1000,frames,now-lastFrame,
                            (now-startedAt)/1000,DeviceInspector.localIpv4());
                    DiagnosticLog.event("HEALTH",detail+" "+server.statistics().replace('\n',';'));
                    DeviceInspector.Report report=DeviceInspector.inspect(this);
                    if(report.warnings>0)DiagnosticLog.event("DEVICE_WARNINGS",report.text.replace('\n',';'));
                    if (report.warnings > 0 && (report.warnings != lastWarningCount || now-lastWarningNotificationMs>120000)) {
                        lastWarningNotificationMs=now;
                        getSystemService(NotificationManager.class).notify(8,
                                notification("Có "+report.warnings+" lưu ý/cảnh báo hệ thống; chạm để xem"));
                    } else if (report.warnings == 0 && lastWarningCount > 0) {
                        getSystemService(NotificationManager.class).notify(8,notification("RTSP đang hoạt động"));
                    }
                    lastWarningCount=report.warnings;
                    if(now-lastFrame>5000)DiagnosticLog.event("ENCODER_STALL","No video frame for "+(now-lastFrame)+" ms");
                    status="Đang phát: "+server.playingCount()+" client; " +currentWidth+"x"+currentHeight;
                    frames=0;totalBytes=0;lastReport=now;
                }
            }
        } catch (Exception e) {
            if(streaming) {errors++;DiagnosticLog.error("ENCODER_FAILURE",e);status="Encoder lỗi: "+e.getMessage();}
        } finally {
            if(streaming) {
                DiagnosticLog.event("ENCODER","drain thread ended unexpectedly errors="+errors);
                stopSelf();
            }
        }
    }
    public static String summary() { return status; }
    @Override public void onDestroy() {
        DiagnosticLog.event("SERVICE","onDestroy; wasRunning="+streaming+
                (server==null?"":" "+server.statistics().replace('\n',';')));
        streaming=false;isRunning=false;
        getSharedPreferences("diag_state",MODE_PRIVATE).edit().putBoolean("session_active",false).commit();
        if(server!=null){server.stop();server=null;}
        if(networkCallback!=null&&cm!=null)try{cm.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        networkCallback=null;
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}
        if(inputSurface!=null){inputSurface.release();inputSurface=null;}
        if(projection!=null){try{projection.stop();}catch(Exception ignored){}projection=null;}
        if(wifiLock!=null&&wifiLock.isHeld()){wifiLock.release();wifiLock=null;}
        if(cpuLock!=null&&cpuLock.isHeld()){cpuLock.release();cpuLock=null;}
        if(status.startsWith("Đang phát"))status="Đã dừng";
        DiagnosticLog.event("SERVICE","CLEANUP completed");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
