package com.example.screenstreamdiag;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Diagnostic UI, manual capture permission and privacy-preserving ZIP export. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION=101,REQUEST_EXPORT=102;
    private TextView state,device,logView,url;
    private Spinner fps,bitrate,quality;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int refreshCycle;
    private final Runnable refresher=new Runnable() {
        @Override public void run() {
            if(state!=null) {
                state.setText("Trạng thái: "+CaptureService.summary());
                url.setText("RTSP: rtsp://"+DeviceInspector.wifiIpv4()+":8554/live");
                logView.setText(DiagnosticLog.tail(MainActivity.this,22));
                if (++refreshCycle % 5 == 0) {
                    DeviceInspector.Report report=DeviceInspector.inspect(MainActivity.this);
                    device.setText(report.text);
                }
            }
            handler.postDelayed(this,2000);
        }
    };
    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        DiagnosticLog.installCrashHandler(getApplicationContext());
        DiagnosticLog.event("APP","MainActivity opened model="+Build.MODEL+" Android="+Build.VERSION.RELEASE);
        if (getSharedPreferences("diag_state",MODE_PRIVATE).getBoolean("session_active",false)
                && !CaptureService.isRunning) {
            DiagnosticLog.event("UNCLEAN_STOP", "Previous session was active but no live service exists in this process: possible Android force-stop/crash/kill");
            getSharedPreferences("diag_state",MODE_PRIVATE).edit().putBoolean("session_active",false).commit();
        }
        setTitle("RTSP Diagnostic");
        buildUi();
        showReport();
    }
    private TextView text(String content,int sp,boolean bold) {
        TextView v=new TextView(this);v.setText(content);v.setTextSize(sp);
        v.setTextColor(Color.rgb(27,34,49));v.setPadding(0,7,0,7);
        if(bold)v.setTypeface(null,1);
        return v;
    }
    private Button button(String caption,View.OnClickListener listener) {
        Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setOnClickListener(listener);return b;
    }
    private Spinner choice(LinearLayout base,String title,String[] options,int selected) {
        base.addView(text(title,14,true));
        Spinner spinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);
        spinner.setAdapter(adapter);spinner.setSelection(selected);
        base.addView(spinner,new LinearLayout.LayoutParams(-1,48));
        return spinner;
    }
    private void buildUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,40);
        root.setBackgroundColor(Color.rgb(249,250,252));
        scroll.addView(root);
        root.addView(text("RTSP DIAGNOSTIC",24,true));
        root.addView(text("Phát màn hình H.264 + chẩn đoán Android 9 / ZTE. Tối đa 5 client.",14,false));
        state=text("Trạng thái: Chưa phát",18,true);root.addView(state);
        url=text("RTSP: đang kiểm tra IP",16,true);root.addView(url);
        root.addView(text("CẤU HÌNH PHÁT",18,true));
        quality=choice(root,"Độ phân giải / mức thu nhỏ",new String[]{"Giữ nguyên (tối đa 720p)","75%", "50%"},0);
        fps=choice(root,"Khung hình mỗi giây",new String[]{"15 FPS","20 FPS","25 FPS"},1);
        bitrate=choice(root,"Bitrate video",new String[]{"1 Mbps","2 Mbps","3 Mbps"},1);
        root.addView(button("▶ Bắt đầu phát và xin quyền chia sẻ màn hình",v->requestCapture()));
        root.addView(button("■ Dừng phát",v-> {
            Intent i=new Intent(this,CaptureService.class).setAction(CaptureService.STOP);
            startService(i);
        }));
        root.addView(text("KIỂM TRA HỆ THỐNG",18,true));
        root.addView(button("↻ Kiểm tra lại các cài đặt Android",v->showReport()));
        device=text("",13,false);
        device.setTextIsSelectable(true);root.addView(device);
        root.addView(button("Mở cài đặt tối ưu pin",v->openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(button("Mở cài đặt ứng dụng ZTE (quản lý pin/chạy nền)",v->{
            Intent intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:"+getPackageName()));
            try{startActivity(intent);}catch(Exception e){toast("Không mở được phần cài đặt: "+e.getMessage());}
        }));
        root.addView(button("Mở Wi-Fi",v->openSettings(Settings.ACTION_WIFI_SETTINGS)));
        root.addView(text("NHẬT KÝ & SỰ CỐ",18,true));
        root.addView(button("⚑ Đánh dấu sự cố vừa xảy ra",v->markIncident()));
        root.addView(button("⬇ Xuất ZIP nhật ký để phân tích",v->exportDialog()));
        logView=text("",11,false);
        logView.setTextIsSelectable(true);
        logView.setBackgroundColor(Color.rgb(235,239,245));
        logView.setPadding(12,12,12,12);
        root.addView(logView,new LinearLayout.LayoutParams(-1,-2));
        root.addView(text("Lưu ý: log ghi IP nội bộ, lỗi RTSP/encoder, trạng thái pin/Wi-Fi; không ghi hình màn hình. " +
                "Chế độ phát chỉ hỗ trợ video, không có âm thanh. Kết nối RTSP không mã hóa, chỉ dùng LAN tin cậy.",12,false));
        setContentView(scroll);
    }
    private void showReport() {
        DeviceInspector.Report r=DeviceInspector.inspect(this);
        device.setText(r.text);
        DiagnosticLog.event("MANUAL_DEVICE_CHECK","warnings="+r.warnings+" "+r.text.replace('\n',';'));
    }
    private void requestCapture() {
        if(CaptureService.isRunning) {toast("Ứng dụng đang phát; hãy dừng trước khi đổi cấu hình.");return;}
        try {
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            DiagnosticLog.event("PROJECTION","Request user permission");
            startActivityForResult(manager.createScreenCaptureIntent(),REQUEST_PROJECTION);
        } catch(Exception e) { DiagnosticLog.error("PROJECTION_PERMISSION",e);toast(e.getMessage()); }
    }
    private void markIncident() {
        String report=DeviceInspector.inspect(this).text;
        DiagnosticLog.event("INCIDENT","USER MARKED CONNECTION FAILURE; status="+CaptureService.summary());
        DiagnosticLog.event("INCIDENT_DEVICE",report.replace('\n',';'));
        toast("Đã đánh dấu sự cố vào nhật ký.");
    }
    private void exportDialog() {
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,"rtsp-diagnostic-"+System.currentTimeMillis()+".zip");
        startActivityForResult(intent,REQUEST_EXPORT);
    }
    private void openSettings(String action) {
        try {startActivity(new Intent(action));}
        catch(Exception e){toast("Thiết bị không hỗ trợ mở cài đặt này.");}
    }
    private void toast(String message) {Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==REQUEST_PROJECTION) {
            if(result!=RESULT_OK||data==null) {
                DiagnosticLog.event("PROJECTION","User denied/cancelled permission");
                toast("Bạn chưa cho phép chia sẻ màn hình.");return;
            }
            Intent start=new Intent(this,CaptureService.class).setAction(CaptureService.START);
            start.putExtra(CaptureService.EXTRA_RESULT_CODE,result);
            start.putExtra(CaptureService.EXTRA_RESULT_DATA,data);
            start.putExtra(CaptureService.EXTRA_FPS,15+fps.getSelectedItemPosition()*5);
            start.putExtra(CaptureService.EXTRA_BITRATE,(1+bitrate.getSelectedItemPosition())*1000000);
            start.putExtra(CaptureService.EXTRA_SCALE,quality.getSelectedItemPosition()==0?100:quality.getSelectedItemPosition()==1?75:50);
            try{startForegroundService(start);toast("Đang khởi tạo RTSP Server...");}
            catch(Exception e){DiagnosticLog.error("SERVICE_START",e);toast("Không khởi động được: "+e.getMessage());}
        } else if(request==REQUEST_EXPORT && result==RESULT_OK && data!=null && data.getData()!=null) {
            exportZip(data.getData());
        }
    }
    private void exportZip(Uri destination) {
        try(OutputStream output=getContentResolver().openOutputStream(destination);
            ZipOutputStream zip=new ZipOutputStream(output)) {
            addFile(zip,DiagnosticLog.current(this));
            addFile(zip,DiagnosticLog.previous(this));
            addText(zip,"device-report.txt",DeviceInspector.inspect(this).text);
            addText(zip,"readme.txt","RTSP Diagnostic v0.1.0\n"+
                    "Log may include private LAN IP addresses and device details. Review before sharing.\n"+
                    "INCIDENT entries mark times when the user observed a failure.\n"+
                    "TCP send success does not prove client decoded frames; UDP delivery is not acknowledged.\n");
            zip.finish();
            DiagnosticLog.event("EXPORT","ZIP written by user action");
            toast("Đã xuất ZIP. Bạn có thể gửi tôi file này để phân tích.");
        } catch(Exception e){DiagnosticLog.error("EXPORT",e);toast("Lỗi xuất ZIP: "+e.getMessage());}
    }
    private void addFile(ZipOutputStream zip,File source) throws IOException {
        if(!source.isFile())return;
        zip.putNextEntry(new ZipEntry(source.getName()));
        try(InputStream in=new FileInputStream(source)) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))>0)zip.write(buffer,0,count);
        }
        zip.closeEntry();
    }
    private void addText(ZipOutputStream zip,String name,String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    @Override protected void onResume() {super.onResume();handler.removeCallbacks(refresher);handler.post(refresher);}
    @Override protected void onPause() {handler.removeCallbacks(refresher);super.onPause();}
}
