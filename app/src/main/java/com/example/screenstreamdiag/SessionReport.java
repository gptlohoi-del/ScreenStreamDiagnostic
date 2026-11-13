package com.example.screenstreamdiag;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * One continuously flushed public Downloads .txt per stream session.
 * Called by the foreground service, not the Activity, so background streaming is logged.
 * Screen contents and raw audio are NEVER saved.
 */
public final class SessionReport {
    private static final Object LOCK = new Object();
    public static final String PREFS = "session_reports";
    public static final String ENABLED = "enabled";
    private static OutputStream output;
    private static String filename;
    private static File legacyFile;
    private static Context appContext;
    private static volatile String status = "Chưa có phiên phát.";
    private static long lineCount;

    private SessionReport() { }

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    public static String status() { return status; }

    public static String begin(Context context, String configuration) {
        synchronized (LOCK) {
            closeLocked("Previous report closed before new stream");
            appContext = context.getApplicationContext();
            legacyFile=null;
            if (!enabled(context)) {
                status = "Tự động ghi TXT: Đã tắt";
                return status;
            }
            filename = "RTSPDiagnostic_v" + BuildConfig.VERSION_NAME + "_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + "_" +
                    safeModel(Build.MODEL) + "_" + UUID.randomUUID().toString().substring(0,8) + ".txt";
            Uri inserted = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
                    inserted = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (inserted == null) throw new IOException("Cannot insert into MediaStore.Downloads");
                    output = context.getContentResolver().openOutputStream(inserted, "w");
                    if (output == null) throw new IOException("Cannot open MediaStore output stream");
                } else {
                    if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        throw new SecurityException("Android 9 requires WRITE_EXTERNAL_STORAGE permission for public Downloads");
                    File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create Downloads directory");
                    legacyFile = new File(folder, filename);
                    output = new FileOutputStream(legacyFile, false);
                }
                lineCount=0;
                status = "TXT: Downloads/" + filename;
                writeLocked("RTSP DIAGNOSTIC SESSION REPORT v"+BuildConfig.VERSION_NAME);
                writeLocked("Filename: " + filename);
                writeLocked("Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.US).format(new Date()));
                writeLocked("Configuration: " + configuration);
                writeLocked("Note: IP addresses, SSID/BSSID, device and router gateway details may be sensitive. Share carefully.");
                writeLocked("Limits: Android cannot read router QoS/steering/DFS settings or client device internals without separate access.");
                writeLocked("Limits: UDP send success does not prove a client received or decoded RTP packets.");
                writeLocked("No screen pixels, raw microphone audio, or application audio are stored in this report.");
                writeLocked("\n===== HOST ANDROID CHECK =====");
                for (String line : DeviceInspector.inspect(context).text.split("\n")) writeLocked(line);
                writeLocked("\n===== HOST / WIFI / GATEWAY / ROUTER OBSERVATIONS =====");
                for (String line : NetworkSnapshot.capture(context).split("\n")) writeLocked(line);
                writeLocked("\n===== LIVE EVENTS (timestamps are local time + time zone) =====");
                return status;
            } catch (Exception failure) {
                Log.e("RTSP-DIAG", "Cannot start session TXT in Downloads", failure);
                if (output != null) try { output.close(); } catch (IOException ignored) { }
                output = null;
                if (inserted != null) try { context.getContentResolver().delete(inserted,null,null); } catch (Exception ignored) { }
                status = "KHÔNG ghi được Downloads: " + failure.getClass().getSimpleName() + " " + failure.getMessage();
                return status;
            }
        }
    }

    private static String safeModel(String model) {
        String text = model == null ? "Android" : model.replaceAll("[^A-Za-z0-9_-]", "");
        if (text.isEmpty()) text="Android";
        return text.substring(0,Math.min(22,text.length()));
    }

    /** Receives formatted lines from DiagnosticLog.event(), including per-client RTP/RTCP logs. */
    static void appendEvent(String formattedLine) {
        synchronized (LOCK) {
            if (output != null) writeLocked(formattedLine.trim());
        }
    }

    private static void writeLocked(String text) {
        if (output == null) return;
        try {
            output.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush(); // Keep file useful after a process kill or sudden reboot.
            lineCount++;
        } catch (IOException e) {
            Log.e("RTSP-DIAG","Session report write failed",e);
            try { output.close(); } catch (IOException ignored) { }
            output=null;
            status="LỖI ghi TXT giữa phiên: "+e.getMessage();
        }
    }

    private static void closeLocked(String reason) {
        if (output != null) {
            writeLocked("SESSION_ENDED: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.US)
                    .format(new Date()) + "; reason=" + reason + "; lines=" + lineCount);
            try { output.close(); } catch (IOException e) { Log.e("RTSP-DIAG","Close TXT failed",e); }
            output=null;
            if (legacyFile!=null && appContext!=null) {
                MediaScannerConnection.scanFile(appContext,new String[]{legacyFile.getAbsolutePath()},
                        new String[]{"text/plain"},null);
                legacyFile=null;
            }
        }
    }

    public static void finish(String reason) {
        synchronized (LOCK) {
            closeLocked(reason);
            if (reason.startsWith("User disabled"))
                status = "Tự động ghi TXT: Đã tắt (file phiên trước đã đóng)";
            // Otherwise preserve filename in status so the user can find the completed report.
        }
    }
}
