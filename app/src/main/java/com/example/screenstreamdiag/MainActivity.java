package com.example.screenstreamdiag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** One-view compact interface; sections expand only when tapped. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION=101,REQUEST_EXPORT=102,REQUEST_MIC=103;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state,url,clientSummary,device,logs,warningCounter;
    private Spinner fps,bitrate,scale,audio;
    private ExpandSection configSection,deviceSection,clientsSection,logSection;
    private long lastReport;
    private final Runnable refresher=new Runnable(){
        @Override public void run(){
            if(state!=null){
                state.setText(CaptureService.summary());
                url.setText("rtsp://"+DeviceInspector.wifiIpv4()+":8554/live");
                clientSummary.setText(CaptureService.clientSummary());
                if(logSection!=null && logSection.isExpanded())logs.setText(DiagnosticLog.tail(MainActivity.this,16));
                if(android.os.SystemClock.elapsedRealtime()-lastReport>10000){
                    lastReport=android.os.SystemClock.elapsedRealtime();refreshDevice(false);
                }
            }
            handler.postDelayed(this,2000);
        }
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        DiagnosticLog.installCrashHandler(getApplicationContext());
        DiagnosticLog.event("APP","OPEN model="+Build.MODEL+" android="+Build.VERSION.RELEASE+
                " version="+BuildConfig.VERSION_NAME);
        if(getSharedPreferences("diag_state",MODE_PRIVATE).getBoolean("session_active",false) && !CaptureService.isRunning){
            DiagnosticLog.event("UNCLEAN_STOP","Previous session may have been killed by Android/crash");
            getSharedPreferences("diag_state",MODE_PRIVATE).edit().putBoolean("session_active",false).apply();
        }
        buildUi();refreshDevice(false);
        if(Build.VERSION.SDK_INT>=35){
            getWindow().getDecorView().setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
            });
        }
    }
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private TextView text(String value,int size,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);
        t.setTextColor(Color.rgb(23,38,57));t.setPadding(0,dp(4),0,dp(4));
        if(bold)t.setTypeface(null,1);return t;
    }
    private Button button(String caption,View.OnClickListener click){
        Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setTextSize(13);
        b.setOnClickListener(click);return b;
    }
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(12),dp(14),dp(18));root.setBackgroundColor(Color.rgb(246,249,252));
        scroll.addView(root);
        TextView title=text("◉ RTSP DIAGNOSTIC   v"+BuildConfig.VERSION_NAME,19,true);root.addView(title);
        state=text("Chưa phát",15,true);root.addView(state);
        url=text("rtsp://...:8554/live",14,true);url.setTextIsSelectable(true);root.addView(url);
        warningCounter=text("Kiểm tra thiết bị...",12,false);root.addView(warningCounter);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button start=button("▶ Phát màn hình",v->requestCapture());
        Button stop=button("■ Dừng phát",v->stopCapture());
        actions.addView(start,new LinearLayout.LayoutParams(0,dp(54),1));
        actions.addView(stop,new LinearLayout.LayoutParams(0,dp(54),1));root.addView(actions);
        root.addView(button("⚑ Đánh dấu client bị mất hình / out",v->markIncident()));
        configSection=new ExpandSection(root,"⚙  Cấu hình hình ảnh & âm thanh",false);
        fps=spinner(configSection.content,"Tần số khung hình",new String[]{"15 FPS","20 FPS","25 FPS"},"fps",1);
        bitrate=spinner(configSection.content,"Bitrate H.264",new String[]{"1 Mbps","2 Mbps","3 Mbps"},"bitrate",1);
        scale=spinner(configSection.content,"Độ phân giải (giới hạn 720p)",new String[]{"100%","75%","50%"},"scale",0);
        String[] sources=Build.VERSION.SDK_INT>=29
                ?new String[]{"Tắt âm thanh","Micro điện thoại (AAC)","Âm thanh ứng dụng (AAC; Android 10+)"}
                :new String[]{"Tắt âm thanh","Micro điện thoại (AAC)"};
        audio=spinner(configSection.content,"Nguồn âm thanh (cần cấp quyền ghi âm)",sources,"audio",0);
        configSection.content.addView(text("Đổi cấu hình cần dừng và phát lại. Android 9: chỉ có micro; Android 10+: ghi âm ứng dụng tùy quyền của ứng dụng nguồn.",12,false));
        clientsSection=new ExpandSection(root,"▣  Client / trạng thái RTSP",false);
        clientSummary=text("Chưa có client",12,false);clientSummary.setTextIsSelectable(true);
        clientsSection.content.addView(clientSummary);
        deviceSection=new ExpandSection(root,"☑  Kiểm tra Android / cảnh báo",false);
        deviceSection.content.addView(button("↻ Kiểm tra lại",v->refreshDevice(true)));
        device=text("",12,false);device.setTextIsSelectable(true);deviceSection.content.addView(device);
        deviceSection.content.addView(button("Cài đặt pin / tối ưu pin",v->openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        deviceSection.content.addView(button("Thông tin ứng dụng / quản lý pin ZTE",v->{
            Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));
            try{startActivity(i);}catch(Exception e){toast("Không mở được: "+e.getMessage());}
        }));
        deviceSection.content.addView(button("Cài đặt Wi-Fi",v->openSettings(Settings.ACTION_WIFI_SETTINGS)));
        logSection=new ExpandSection(root,"▤  Nhật ký và xuất ZIP",false);
        logSection.content.addView(button("Xuất ZIP nhật ký",v->exportDialog()));
        logs=text("",11,false);logs.setTextIsSelectable(true);
        logs.setPadding(dp(8),dp(8),dp(8),dp(8));logs.setBackgroundColor(Color.rgb(229,235,242));
        logSection.content.addView(logs);
        root.addView(text("LAN tin cậy • RTSP không mã hóa • Tối đa 5 client",11,false));
        setContentView(scroll);
    }
    private Spinner spinner(LinearLayout base,String label,String[] values,String key,int selection){
        base.addView(text(label,13,true));
        Spinner s=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);
        s.setAdapter(adapter);
        int previous=getPreferences(MODE_PRIVATE).getInt("pref_"+key,selection);
        s.setSelection(Math.min(previous,values.length-1));
        base.addView(s,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(42)));
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                getPreferences(MODE_PRIVATE).edit().putInt("pref_"+key,position).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        return s;
    }
    private final class ExpandSection {
        final LinearLayout content;
        final Button toggle;
        ExpandSection(LinearLayout parent,String heading,boolean open){
            toggle=button("",v->setExpanded(!isExpanded()));
            content=new LinearLayout(MainActivity.this);content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(8),dp(4),dp(8),dp(8));
            parent.addView(toggle);parent.addView(content);
            this.heading=heading;setExpanded(open);
        }
        final String heading;
        void setExpanded(boolean yes){content.setVisibility(yes?View.VISIBLE:View.GONE);toggle.setText((yes?"▼  ":"▶  ")+heading);}
        boolean isExpanded(){return content.getVisibility()==View.VISIBLE;}
    }
    private void stopCapture(){
        try{
            Intent stop=new Intent(this,CaptureService.class).setAction(CaptureService.STOP);startService(stop);
            toast("Đang dừng truyền màn hình");
        }catch(Exception e){DiagnosticLog.error("SERVICE_STOP_FAILED",e);toast(e.getMessage());}
    }
    private void requestCapture(){
        if(CaptureService.isRunning){toast("Hãy dừng phiên trước khi thay cấu hình.");return;}
        if(audio.getSelectedItemPosition()>0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);return;
        }
        requestProjection();
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] result){
        super.onRequestPermissionsResult(request,perms,result);
        if(request==REQUEST_MIC){
            if(result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED)requestProjection();
            else {toast("Chưa có quyền micro. Hãy chọn Tắt âm thanh hoặc cấp quyền.");DiagnosticLog.event("AUDIO","RECORD_AUDIO permission denied");}
        }
    }
    private void requestProjection(){
        try{
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            DiagnosticLog.event("PROJECTION","Request consent audio="+audioMode());
            startActivityForResult(manager.createScreenCaptureIntent(),REQUEST_PROJECTION);
        }catch(Exception e){DiagnosticLog.error("PROJECTION",e);toast("Lỗi xin quyền: "+e.getMessage());}
    }
    private String audioMode(){
        int selected=audio.getSelectedItemPosition();return selected==1?AudioEngine.MIC:selected==2?AudioEngine.PLAYBACK:AudioEngine.NONE;
    }
    private void refreshDevice(boolean manual){
        DeviceInspector.Report r=DeviceInspector.inspect(this);device.setText(r.text);
        warningCounter.setText(r.warnings==0?"✓ Kiểm tra Android: chưa thấy cảnh báo":
                "⚠ "+r.warnings+" cảnh báo/lưu ý — nhấn 'Kiểm tra Android' để xem");
        if(manual)DiagnosticLog.event("MANUAL_DEVICE_CHECK","warnings="+r.warnings+" "+r.text.replace('\n',';'));
    }
    private void markIncident(){
        String snapshot=CaptureService.clientSummary();
        LinkedHashSet<String> ips=new LinkedHashSet<>();
        Matcher matcher=Pattern.compile("\\bip=([0-9.]+)").matcher(snapshot);
        while(matcher.find())ips.add(matcher.group(1));
        ArrayList<String> choices=new ArrayList<>();
        choices.add("Không rõ / tất cả client");
        choices.addAll(ips);
        new AlertDialog.Builder(this).setTitle("Client nào vừa bị mất hình / out?")
            .setItems(choices.toArray(new String[0]),(dialog,which)->{
                String selected=which==0?"unknown-or-all":choices.get(which);
                DiagnosticLog.event("INCIDENT","USER_MARKED client="+selected+
                        " status="+CaptureService.summary()+
                        " stats="+snapshot.replace('\n',';'));
                toast("Đã ghi mốc lỗi cho "+selected+". Hãy xuất ZIP khi cần.");
            })
            .setNegativeButton("Hủy",null).show();
    }
    private void openSettings(String action){
        try{startActivity(new Intent(action));}catch(Exception e){toast("Thiết bị không hỗ trợ trang cài đặt này.");}
    }
    private void exportDialog(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"RTSPDiagnostic-v"+BuildConfig.VERSION_NAME+
                "-log-"+System.currentTimeMillis()+".zip");startActivityForResult(i,REQUEST_EXPORT);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==REQUEST_PROJECTION){
            if(result!=RESULT_OK || data==null){DiagnosticLog.event("PROJECTION","User cancelled");toast("Chưa cấp quyền chia sẻ màn hình");return;}
            Intent start=new Intent(this,CaptureService.class).setAction(CaptureService.START);
            start.putExtra(CaptureService.EXTRA_RESULT_CODE,result);
            start.putExtra(CaptureService.EXTRA_RESULT_DATA,data);
            start.putExtra(CaptureService.EXTRA_FPS,15+fps.getSelectedItemPosition()*5);
            start.putExtra(CaptureService.EXTRA_BITRATE,(1+bitrate.getSelectedItemPosition())*1000000);
            start.putExtra(CaptureService.EXTRA_SCALE,scale.getSelectedItemPosition()==0?100:scale.getSelectedItemPosition()==1?75:50);
            start.putExtra(CaptureService.EXTRA_AUDIO,audioMode());
            try{startForegroundService(start);toast("Đang khởi tạo RTSP...");}
            catch(Exception e){DiagnosticLog.error("SERVICE_START",e);toast("Không khởi động được: "+e.getMessage());}
        }else if(req==REQUEST_EXPORT && result==RESULT_OK && data!=null && data.getData()!=null)exportZip(data.getData());
    }
    private void exportZip(Uri destination){
        try(OutputStream out=getContentResolver().openOutputStream(destination);
            ZipOutputStream zip=new ZipOutputStream(out)){
            addFile(zip,DiagnosticLog.current(this));addFile(zip,DiagnosticLog.previous(this));
            addText(zip,"device-report.txt",DeviceInspector.inspect(this).text);
            addText(zip,"about.txt","RTSP Diagnostic "+BuildConfig.VERSION_NAME+
                    "\nDiagnostic logs may contain IP addresses. No screen/audio content is recorded to log.\n"+
                    "A sender's successful UDP send does not prove packet delivery or playback.\n");
            zip.finish();DiagnosticLog.event("EXPORT","User exported log ZIP");toast("Đã xuất ZIP nhật ký.");
        }catch(Exception e){DiagnosticLog.error("EXPORT",e);toast("Lỗi xuất ZIP: "+e.getMessage());}
    }
    private void addFile(ZipOutputStream zip,File f)throws IOException{
        if(!f.isFile())return;zip.putNextEntry(new ZipEntry(f.getName()));
        try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)zip.write(buf,0,n);}
        zip.closeEntry();
    }
    private void addText(ZipOutputStream zip,String name,String content)throws IOException{
        zip.putNextEntry(new ZipEntry(name));zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresher);handler.post(refresher);}
    @Override protected void onPause(){handler.removeCallbacks(refresher);super.onPause();}
}
