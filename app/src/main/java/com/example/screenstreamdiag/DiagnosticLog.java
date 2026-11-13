package com.example.screenstreamdiag;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Keeps two persistent log files, each capped at 2 MiB; never records screen content. */
public final class DiagnosticLog {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static File file;
    private static boolean crashHandlerInstalled;
    private DiagnosticLog() { }

    public static void init(Context context) {
        synchronized (LOCK) {
            file = new File(context.getFilesDir(), "rtsp-diagnostic.log");
        }
    }
    public static void event(String tag, String message) {
        synchronized (LOCK) {
            if (file == null) return;
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
            String line = now + " [" + tag + "] " + message.replace('\r',' ').replace('\n',' ') + "\n";
            Log.i("RTSP-DIAG", line.trim());
            try {
                if (file.exists() && file.length() + line.length() > MAX_BYTES) {
                    File old = new File(file.getParentFile(), "rtsp-diagnostic.previous.log");
                    if (old.exists() && !old.delete()) Log.w("RTSP-DIAG", "Could not remove old log");
                    if (!file.renameTo(old)) Log.w("RTSP-DIAG", "Could not rotate log");
                }
                try (FileOutputStream stream = new FileOutputStream(file, true)) {
                    stream.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException err) { Log.e("RTSP-DIAG", "Log write failed", err); }
            // Mirror independently: an internal log write failure must not suppress the Downloads TXT.
            SessionReport.appendEvent(line);
        }
    }
    public static void error(String tag, Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        for (String s : writer.toString().split("\n")) event(tag, s);
    }
    public static File current(Context context) { return new File(context.getFilesDir(), "rtsp-diagnostic.log"); }
    public static File previous(Context context) { return new File(context.getFilesDir(), "rtsp-diagnostic.previous.log"); }
    public static String tail(Context context, int lines) {
        File source = current(context);
        if (!source.exists()) return "Chưa có nhật ký.";
        java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
        synchronized (LOCK) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
                String text;
                while ((text = reader.readLine()) != null) {
                    if (recent.size() == lines) recent.removeFirst();
                    recent.addLast(text);
                }
            } catch (IOException e) { return "Không đọc được nhật ký: " + e.getMessage(); }
        }
        StringBuilder output = new StringBuilder();
        for (String line : recent) output.append(line).append('\n');
        return output.toString();
    }
    public static synchronized void installCrashHandler(Context context) {
        init(context);
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            error("CRASH thread=" + thread.getName(), failure);
            if (original != null) original.uncaughtException(thread, failure);
        });
    }
}
