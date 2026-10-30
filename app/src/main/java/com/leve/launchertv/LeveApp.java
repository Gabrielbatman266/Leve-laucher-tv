package com.leve.launchertv;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Guarda o motivo quando o Leve fecha sozinho (ou quando algo falha por dentro),
 * para mostrar na próxima vez que o launcher abrir.
 */
public class LeveApp extends Application {

    private static File crashFile;

    @Override
    public void onCreate() {
        super.onCreate();
        crashFile = new File(getFilesDir(), "crash.txt");
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, err) -> {
            write("FECHOU (" + thread.getName() + ")", err, false);
            if (previous != null) previous.uncaughtException(thread, err);
        });
    }

    /** Falha que foi contida (o app não fechou), mas vale registrar. */
    static void log(String where, Throwable t) {
        write(where, t, true);
    }

    private static synchronized void write(String where, Throwable t, boolean append) {
        if (crashFile == null) return;
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println(new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date())
                    + "  " + where + "  · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                    + Build.MANUFACTURER + " " + Build.MODEL);
            t.printStackTrace(pw);
            pw.println();
            pw.flush();
            if (append && crashFile.length() > 16000) append = false;
            try (FileOutputStream out = new FileOutputStream(crashFile, append)) {
                out.write(sw.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    static String read() {
        if (crashFile == null || !crashFile.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(crashFile.length(), 16000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(crashFile)) {
                int r = 0;
                while (r < b.length) {
                    int n = in.read(b, r, b.length - r);
                    if (n < 0) break;
                    r += n;
                }
                return new String(b, 0, r, "UTF-8");
            }
        } catch (Throwable e) {
            return null;
        }
    }

    static void clear() {
        if (crashFile != null) crashFile.delete();
    }
}
