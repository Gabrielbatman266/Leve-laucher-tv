package com.leve.launchertv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confere a última Release no GitHub, baixa o APK e abre o instalador.
 * Sem bibliotecas: só HttpURLConnection e uma Thread.
 */
final class Updater {

    static final String REPO = "Gabrielbatman266/Leve-laucher-tv";
    static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String APK = PAGE + "/download/LeveLauncherTV.apk";

    private final Activity act;
    private volatile boolean busy;

    Updater(Activity act) {
        this.act = act;
    }

    static int installedVersion(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    void check() {
        if (busy) return;
        busy = true;
        Toast.makeText(act, R.string.upd_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                int latest = fetchLatestNumber();
                int mine = installedVersion(act);
                act.runOnUiThread(() -> {
                    busy = false;
                    if (latest <= 0) {
                        fail(null);
                    } else if (latest <= mine) {
                        Toast.makeText(act, act.getString(R.string.upd_latest, "1.0." + mine),
                                Toast.LENGTH_LONG).show();
                    } else {
                        askDownload(latest);
                    }
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    busy = false;
                    fail(e);
                });
            }
        }).start();
    }

    private int fetchLatestNumber() throws Exception {
        HttpURLConnection c = open(API);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"[^\"]*?(\\d+)\"")
                    .matcher(buf.toString("UTF-8"));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } finally {
            c.disconnect();
        }
    }

    private void askDownload(int latest) {
        new AlertDialog.Builder(act)
                .setTitle(R.string.upd_title)
                .setMessage(act.getString(R.string.upd_found, "1.0." + latest))
                .setPositiveButton(R.string.upd_download, (d, w) -> download())
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private void download() {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, R.string.upd_allow, Toast.LENGTH_LONG).show();
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) {
                fail(null);
            }
            return;
        }

        final AlertDialog progress = new AlertDialog.Builder(act)
                .setTitle(R.string.upd_title)
                .setMessage(act.getString(R.string.upd_progress, 0))
                .setCancelable(false)
                .show();
        busy = true;

        new Thread(() -> {
            try {
                final File apk = target();
                HttpURLConnection c = open(APK);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[16384];
                    int n, done = 0, lastPct = -1;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100L / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                act.runOnUiThread(() ->
                                        progress.setMessage(act.getString(R.string.upd_progress, pct)));
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
                act.runOnUiThread(() -> {
                    busy = false;
                    progress.dismiss();
                    install(apk);
                });
            } catch (final Exception e) {
                act.runOnUiThread(() -> {
                    busy = false;
                    progress.dismiss();
                    fail(e);
                });
            }
        }).start();
    }

    /** Android 7+: cache interno servido pelo ApkProvider. Antes disso: cache externo (file://). */
    private File target() {
        File dir = act.getCacheDir();
        if (Build.VERSION.SDK_INT < 24 && act.getExternalCacheDir() != null) {
            dir = act.getExternalCacheDir();
        }
        return new File(dir, ApkProvider.FILE_NAME);
    }

    private void install(File apk) {
        Uri uri = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uri(act) : Uri.fromFile(apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            fail(e);
        }
    }

    /** Se algo der errado, mostra o link para atualizar manualmente. */
    private void fail(Exception e) {
        new AlertDialog.Builder(act)
                .setTitle(R.string.upd_fail_title)
                .setMessage(act.getString(R.string.upd_fail, APK))
                .setPositiveButton(R.string.upd_open_browser, (d, w) -> {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                        Toast.makeText(act, R.string.upd_no_browser, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "LeveLauncherTV");
        // Segue redirecionamentos entre hosts (github.com -> servidor de arquivos)
        for (int i = 0; i < 5; i++) {
            int code = c.getResponseCode();
            if (code < 300 || code >= 400) break;
            String next = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(next).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "LeveLauncherTV");
        }
        if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
        return c;
    }
}
