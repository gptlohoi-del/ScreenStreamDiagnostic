package com.example.screenstreamdiag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
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
    private static final int REQUEST_PROJECTION=101,REQUEST_EXPORT=102,REQUEST_MIC=103,
            REQUEST_STORAGE=104,REQUEST_LOCATION=105;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state,url,clientSummary,device,logs,warningCounter,sessionFileStatus;
    private Button startButton,stopButton;
    private Switch automaticLogSwitch;
    private long startingUntilMs,stoppingUntilMs;
    private Spinner fps,bitrate,scale,audio;
    private ExpandSection configSection,deviceSection,clientsSection,logSection;
    private long lastReport;
    private final Runnable refresher=new Runnable(){
        @Override public void run(){
            if(state!=null){
                state.setText(CaptureService.summary());
                url.setText(currentUrl());
                if(sessionFileStatus!=null)sessionFileStatus.setText(SessionReport.status());
                long now=android.os.SystemClock.elapsedRealtime();
                boolean displayRunning = (CaptureService.isRunning || now<startingUntilMs) && now>=stoppingUntilMs;
                updateActionButtons(displayRunning);
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
        LinearLayout linkRow=new LinearLayout(this);linkRow.setOrientation(LinearLayout.HORIZONTAL);
        url=text(currentUrl(),14,true);url.setTextIsSelectable(true);
        linkRow.addView(url,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        Button copy=button("⧉ Copy link",v->copyLink());
        linkRow.addView(copy,new LinearLayout.LayoutParams(dp(112),dp(45)));
        root.addView(linkRow);
        sessionFileStatus=text(SessionReport.status(),11,false);root.addView(sessionFileStatus);
        warningCounter=text("Kiểm tra thiết bị...",12,false);root.addView(warningCounter);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton=button("▶ Phát màn hình",v->requestCapture());
        stopButton=button("■ Dừng phát",v->stopCapture());
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(54),1);
        left.setMargins(0,0,dp(4),0);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(54),1);
        right.setMargins(dp(4),0,0,0);
        actions.addView(startButton,left);
        actions.addView(stopButton,right);root.addView(actions);
        updateActionButtons(CaptureService.isRunning);
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
        logSection=new ExpandSection(root,"▤  Ghi TXT tự động / xuất nhật ký",false);
        automaticLogSwitch=new Switch(this);
        automaticLogSwitch.setText("Tự ghi TXT trong Downloads mỗi lần phát");
        automaticLogSwitch.setTextSize(14);
        automaticLogSwitch.setChecked(SessionReport.enabled(this));
        logSection.content.addView(automaticLogSwitch);
        automaticLogSwitch.setOnCheckedChangeListener((view,on)->{
            getSharedPreferences(SessionReport.PREFS,MODE_PRIVATE).edit()
                    .putBoolean(SessionReport.ENABLED,on).apply();
            DiagnosticLog.event("SETTING","AUTO_SESSION_TXT="+on);
            if(!on)SessionReport.finish("User disabled automatic TXT log");
            toast(on?"Từ phiên phát tiếp theo sẽ tự lưu TXT trong Downloads":
                    "Đã tắt ghi TXT. Nếu đang phát, file hiện tại đã được đóng.");
            sessionFileStatus.setText(SessionReport.status());
        });
        logSection.content.addView(text("Mỗi lần nhấn Phát sẽ tạo file tên duy nhất. Ghi liên tục cả khi màn hình app đóng. TXT có IP Wi-Fi/client, SSID/BSSID nếu được cấp quyền; không lưu nội dung màn hình.",11,false));
        logSection.content.addView(button("Cấp quyền đọc tên Wi-Fi / BSSID (tùy chọn)",v->requestWifiDetailsPermission()));
        logSection.content.addView(text("Android 9 cần quyền ghi Downloads; Android 10+ lưu qua MediaStore. Cài đặt router như QoS/roaming không thể tự đọc được.",11,false));
        logSection.content.addView(button("Mở Downloads để xem TXT",v->openDownloads()));
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
    private String currentUrl(){return "rtsp://"+DeviceInspector.wifiIpv4()+":8554/live";}
    private void copyLink(){
        String value=currentUrl();
        if(value.contains("0.0.0.0")){toast("Không xác định được IP Wi-Fi. Hãy kết nối mạng trước.");return;}
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null){
            clipboard.setPrimaryClip(ClipData.newPlainText("RTSP Diagnostic URL",value));
            toast("Đã sao chép: "+value);
        }
    }
    private void updateActionButtons(boolean running){
        if(startButton==null||stopButton==null)return;
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        startButton.setAlpha(running?0.50f:1f);
        stopButton.setAlpha(running?1f:0.50f);
        startButton.setTextColor(running?Color.rgb(74,83,94):Color.WHITE);
        stopButton.setTextColor(running?Color.WHITE:Color.rgb(74,83,94));
        startButton.setBackgroundTintList(ColorStateList.valueOf(running?
                Color.rgb(208,215,223):Color.rgb(30,154,78)));
        stopButton.setBackgroundTintList(ColorStateList.valueOf(running?
                Color.rgb(207,47,54):Color.rgb(208,215,223)));
    }
    private void requestWifiDetailsPermission(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            toast("Đã có quyền đọc SSID/BSSID. Cần bật Dịch vụ vị trí của Android để thông tin hiển thị.");return;
        }
        new AlertDialog.Builder(this).setTitle("Thông tin Wi-Fi nâng cao")
                .setMessage("Android yêu cầu quyền Vị trí để đọc tên Wi-Fi và BSSID. Ứng dụng chỉ dùng quyền này cho nhật ký mạng, không lấy tọa độ GPS.")
                .setPositiveButton("Cấp quyền",(d,w)->requestPermissions(
                        new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_LOCATION))
                .setNegativeButton("Bỏ qua",null).show();
    }
    private void stopCapture(){
        stoppingUntilMs=android.os.SystemClock.elapsedRealtime()+5000;
        startingUntilMs=0;updateActionButtons(false);
        try{
            Intent stop=new Intent(this,CaptureService.class).setAction(CaptureService.STOP);startService(stop);
            toast("Đang dừng truyền màn hình");
        }catch(Exception e){stoppingUntilMs=0;updateActionButtons(CaptureService.isRunning);
            DiagnosticLog.error("SERVICE_STOP_FAILED",e);toast(e.getMessage());}
    }
    private void requestCapture(){
        if(CaptureService.isRunning){toast("Hãy dừng phiên trước khi thay cấu hình.");return;}
        if(SessionReport.enabled(this) && Build.VERSION.SDK_INT<=28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_STORAGE);return;
        }
        if(audio.getSelectedItemPosition()>0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);return;
        }
        requestProjection();
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] result){
        super.onRequestPermissionsResult(request,perms,result);
        if(request==REQUEST_MIC){
            if(result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED)requestCapture();
            else {toast("Chưa có quyền micro. Hãy chọn Tắt âm thanh hoặc cấp quyền.");DiagnosticLog.event("AUDIO","RECORD_AUDIO permission denied");}
        } else if(request==REQUEST_STORAGE){
            if(result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED) requestCapture();
            else new AlertDialog.Builder(this).setTitle("Không có quyền ghi Downloads")
                    .setMessage("Android 9 cần quyền ghi bộ nhớ để tự lưu TXT trong Downloads. Bạn có thể phát không ghi log, hoặc cấp quyền trong cài đặt ứng dụng.")
                    .setPositiveButton("Phát không ghi TXT",(d,w)->{
                        automaticLogSwitch.setChecked(false);
                        requestCapture();
                    }).setNegativeButton("Hủy",null).show();
        } else if(request==REQUEST_LOCATION){
            toast(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED?
                    "Đã cấp quyền. Bật Dịch vụ vị trí để có SSID/BSSID trong phiên tới.":
                    "Chưa có quyền vị trí chính xác; SSID/BSSID có thể không ghi được.");
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
    private void openDownloads(){
        try { startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); }
        catch (Exception e) { toast("Máy chưa có ứng dụng mở Downloads. Hãy dùng Quản lý tệp / Files."); }
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
            if(result!=RESULT_OK || data==null){startingUntilMs=0;
                updateActionButtons(false);
                DiagnosticLog.event("PROJECTION","User cancelled");toast("Chưa cấp quyền chia sẻ màn hình");return;}
            Intent start=new Intent(this,CaptureService.class).setAction(CaptureService.START);
            start.putExtra(CaptureService.EXTRA_RESULT_CODE,result);
            start.putExtra(CaptureService.EXTRA_RESULT_DATA,data);
            start.putExtra(CaptureService.EXTRA_FPS,15+fps.getSelectedItemPosition()*5);
            start.putExtra(CaptureService.EXTRA_BITRATE,(1+bitrate.getSelectedItemPosition())*1000000);
            start.putExtra(CaptureService.EXTRA_SCALE,scale.getSelectedItemPosition()==0?100:scale.getSelectedItemPosition()==1?75:50);
            start.putExtra(CaptureService.EXTRA_AUDIO,audioMode());
            try{
                startingUntilMs=android.os.SystemClock.elapsedRealtime()+10000;
                stoppingUntilMs=0;
                updateActionButtons(true);
                startForegroundService(start);toast("Đang khởi tạo RTSP...");
            } catch(Exception e){startingUntilMs=0;updateActionButtons(false);
                DiagnosticLog.error("SERVICE_START",e);toast("Không khởi động được: "+e.getMessage());}
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
