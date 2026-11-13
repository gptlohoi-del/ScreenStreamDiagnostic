package com.example.screenstreamdiag;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DeviceInspector {
    private DeviceInspector() { }

    public static final class Report {
        public final String text;
        public final int warnings;
        Report(String text, int warnings) { this.text = text; this.warnings = warnings; }
    }

    public static String wifiIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = network.getName();
                if (!network.isUp() || network.isLoopback() ||
                        !(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan"))) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "0.0.0.0";
    }
    public static String localIpv4() {
        try {
            // Prefer wlan interfaces; other interfaces are fallback for hotspot/USB tethering.
            String fallback = null;
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (network.getName().startsWith("wlan") || network.getName().startsWith("ap")) return address.getHostAddress();
                    if (fallback == null) fallback = address.getHostAddress();
                }
            }
            return fallback == null ? "0.0.0.0" : fallback;
        } catch (Exception e) { return "0.0.0.0"; }
    }

    private static boolean hasAvcEncoder() {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (type.equalsIgnoreCase("video/avc")) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    public static Report inspect(Context context) {
        StringBuilder s = new StringBuilder();
        int warnings = 0;
        s.append("THIẾT BỊ VÀ CHẨN ĐOÁN\n");
        s.append("Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        s.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        s.append("IP IPv4 Wi-Fi: ").append(wifiIpv4()).append("\n");
        if ("0.0.0.0".equals(wifiIpv4())) {
            s.append("[CẢNH BÁO] Không tìm thấy IPv4 Wi-Fi; server RTSP không thể khởi động.\n");
            warnings++;
        }
        if (!hasAvcEncoder()) { s.append("[LỖI] Không tìm thấy bộ mã hóa H.264.\n"); warnings++; }
        else s.append("[OK] Có bộ mã hóa H.264.\n");

        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            if (power.isPowerSaveMode()) { s.append("[CẢNH BÁO] Chế độ tiết kiệm pin đang bật; nên tắt khi phát.\n"); warnings++; }
            else s.append("[OK] Tiết kiệm pin hệ thống không bật.\n");
            if (!power.isIgnoringBatteryOptimizations(context.getPackageName())) {
                s.append("[CẢNH BÁO] Ứng dụng chưa được miễn tối ưu pin; mở cài đặt pin để xem xét.\n"); warnings++;
            } else s.append("[OK] Ứng dụng được miễn tối ưu pin Android.\n");
            if (power.isDeviceIdleMode()) { s.append("[CẢNH BÁO] Doze đang hoạt động.\n"); warnings++; }
            if (Build.VERSION.SDK_INT >= 29) {
                int thermal = power.getCurrentThermalStatus();
                s.append("Trạng thái nhiệt Android: ").append(thermal).append(" (0=không giới hạn, 4+=nghiêm trọng)\n");
                if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                    s.append("[CẢNH BÁO] Hệ thống đang hạn chế hiệu năng vì nhiệt.\n"); warnings++;
                }
            }
            if (!power.isInteractive()) { s.append("[LƯU Ý] Màn hình đang tắt; một số ROM có thể giới hạn chiếu màn hình.\n"); warnings++; }
        }

        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            s.append("[LƯU Ý] Màn hình đang khóa.\n"); warnings++;
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) {
            s.append("[CẢNH BÁO] Không phát hiện kết nối mạng đang hoạt động.\n"); warnings++;
        } else if (ni.getType() != ConnectivityManager.TYPE_WIFI) {
            s.append("[CẢNH BÁO] Mạng mặc định không phải Wi-Fi: ").append(ni.getTypeName())
                    .append(". Có thể đang dùng hotspot hoặc mạng khác.\n"); warnings++;
        } else s.append("[OK] Mạng mặc định là Wi-Fi.\n");

        if (cm != null && Build.VERSION.SDK_INT >= 24 &&
                cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            s.append("[LƯU Ý] Trình tiết kiệm dữ liệu đang hạn chế dữ liệu nền (ảnh hưởng tùy loại mạng).\n"); warnings++;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            s.append("[THÔNG TIN] Chưa cấp quyền micro: chỉ ảnh hưởng khi chọn phát âm thanh.\n");
        s.append("Âm thanh nội bộ: ").append(Build.VERSION.SDK_INT >= 29 ?
                "Android hỗ trợ API, nhưng ứng dụng nguồn có thể cấm thu âm\n" :
                "không hỗ trợ (cần Android 10+)\n");
        if (Build.VERSION.SDK_INT >= 33) {
            NotificationManager notificationManager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null && !notificationManager.areNotificationsEnabled()) {
                s.append("[LƯU Ý] Thông báo ứng dụng bị tắt: cảnh báo có thể không hiển thị.\n");warnings++;
            }
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            WifiInfo info = null;
            try { info = wifi.getConnectionInfo(); }
            catch (SecurityException e) {s.append("Không đọc được Wi-Fi RSSI (thiếu quyền trên ROM).\n");}
            if (info != null) {
                int rssi = info.getRssi();
                int speed = info.getLinkSpeed();
                s.append("Wi-Fi RSSI: ").append(rssi == -127 ? "không xác định" : rssi + " dBm")
                        .append("; tốc độ liên kết: ").append(speed).append(" Mbps\n");
                if (ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI && rssi <= -75 && rssi > -127) {
                    s.append("[CẢNH BÁO] Tín hiệu Wi-Fi yếu; thử đưa thiết bị gần điểm phát.\n"); warnings++;
                }
            }
        }
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temp10 = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (scale > 0 && level >= 0) {
                int percent = Math.round(100f * level / scale);
                s.append("Pin: ").append(percent).append("%\n");
                if (percent < 15) { s.append("[CẢNH BÁO] Pin thấp.\n"); warnings++; }
            }
            if (temp10 > 0) {
                float temp = temp10 / 10f;
                s.append(String.format(Locale.US, "Nhiệt độ pin: %.1f°C (không phải nhiệt độ CPU)\n", temp));
                if (temp >= 43) { s.append("[CẢNH BÁO] Pin nóng, có thể gây giới hạn hiệu năng.\n"); warnings++; }
            }
        }
        try {
            int timeout = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
            s.append("Thời gian tự tắt màn hình: ").append(timeout / 1000).append(" giây\n");
            if (timeout < 60000) {
                s.append("[LƯU Ý] Màn hình tự tắt sớm; ROM ZTE có thể dừng hoạt động khi khóa.\n");
                warnings++;
            }
        } catch (Settings.SettingNotFoundException ignored) { }
        try {
            int rotation = Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            if (rotation == 1) {
                s.append("[LƯU Ý] Tự xoay màn hình đang bật; bản thử nghiệm không đổi kích thước phiên phát khi xoay.\n");
                warnings++;
            }
        } catch (Settings.SettingNotFoundException ignored) { }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mem);
            s.append("RAM còn trống (hệ thống): ").append(mem.availMem / 1024 / 1024).append(" MiB\n");
            if (mem.lowMemory) { s.append("[CẢNH BÁO] Hệ thống đang thiếu RAM.\n"); warnings++; }
        }
        s.append("\nKhông thể tự đọc các cài đặt tiết kiệm pin riêng của ZTE/SoftBank, client isolation trên router,")
                .append(" hay chất lượng Wi-Fi của từng client. Cần kiểm tra thủ công.\n")
                .append("Khuyến nghị: RTP qua TCP, H.264, 720p/20fps, 2 Mbps, 1–5 client.\n");
        return new Report(s.toString(), warnings);
    }
}
