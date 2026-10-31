package com.leve.launchertv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Leve Rede — diz onde está o problema da internet e aplica o que dá para melhorar
 * sem root: Wi-Fi sem economia de energia, DNS mais rápido com cache, e memória livre
 * antes de abrir o YouCine.
 */
public class NetActivity extends Activity {

    private static final int REQ_VPN = 31;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private StarsView stars;
    private TextView verdict, dSignal, dBand, dRouter, dDns, dSpeed, tips, bTurbo, bDns, bNote, bShare;
    private static final int REQ_SHARE = 32;
    private volatile boolean testing;

    // resultados
    private NetTools.Wifi wifi;
    private int routerMs = -2;
    private final Map<String, Integer> dnsMs = new LinkedHashMap<>();
    private float mbps = -2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.net);
        prefs = getSharedPreferences("leve", MODE_PRIVATE);
        stars = findViewById(R.id.stars);
        verdict = findViewById(R.id.verdict);
        dSignal = findViewById(R.id.d_signal);
        dBand = findViewById(R.id.d_band);
        dRouter = findViewById(R.id.d_router);
        dDns = findViewById(R.id.d_dns);
        dSpeed = findViewById(R.id.d_speed);
        tips = findViewById(R.id.tips);
        bTurbo = findViewById(R.id.b_turbo);
        bDns = findViewById(R.id.b_dns);
        bNote = findViewById(R.id.b_note);
        bShare = findViewById(R.id.b_share);
        bShare.setOnClickListener(v -> shareDialog());

        findViewById(R.id.b_test).setOnClickListener(v -> runTest());
        findViewById(R.id.b_wifi).setOnClickListener(v -> {
            Shortcuts.open(this, Shortcuts.WIFI);
        });
        bTurbo.setOnClickListener(v -> {
            boolean on = !LeveMusicService.turboOn(this);
            LeveMusicService.setTurbo(this, on);
            Toast.makeText(this, on ? R.string.net_turbo_toast_on : R.string.net_turbo_toast_off, Toast.LENGTH_LONG).show();
            updateButtons();
        });
        bDns.setOnClickListener(v -> toggleDns());
        findViewById(R.id.b_youcine).setOnClickListener(v -> youcineTurbo());
        findViewById(R.id.b_youcine).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stars.setAnimate(prefs.getBoolean("stars", true));
        stars.setRunning(true);
        updateButtons();
        if (mbps == -2 && !testing) runTest();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stars.setRunning(false);
    }

    private void updateButtons() {
        bTurbo.setText(LeveMusicService.turboOn(this) ? R.string.net_turbo_on : R.string.net_turbo_off);
        bShare.setText(!ShareLink.on(this) ? getString(R.string.share_off)
                : ShareLink.active(this) ? getString(R.string.share_on) : getString(R.string.share_waiting));
        boolean dnsOn = LeveDnsVpn.enabled(this);
        bDns.setText(dnsOn ? getString(R.string.net_dns_on,
                prefs.getString(LeveDnsVpn.PREF_SERVERS, LeveDnsVpn.DEFAULT_SERVERS).replace(",", ", "))
                : getString(R.string.net_dns_off));
        if (dnsOn && LeveDnsVpn.answered > 0) {
            bNote.setText(getString(R.string.net_dns_stats, LeveDnsVpn.answered, LeveDnsVpn.fromCache));
        } else {
            bNote.setText(R.string.net_note);
        }
    }

    // ---------- Teste ----------

    private void runTest() {
        if (testing) return;
        testing = true;
        verdict.setText(R.string.net_measuring);
        tips.setText("");
        dSignal.setText(""); dBand.setText(""); dRouter.setText(""); dDns.setText(""); dSpeed.setText("");
        new Thread(() -> {
            try {
                wifi = NetTools.wifi(this);
                ui.post(this::showWifi);

                routerMs = NetTools.ping(wifi.gateway);
                ui.post(this::showRouter);

                dnsMs.clear();
                String[] hosts = {"google.com", "youtube.com", "cloudflare.com"};
                List<String> cands = new ArrayList<>();
                if (!wifi.dns1.isEmpty()) cands.add(wifi.dns1);
                for (String s : new String[]{"1.1.1.1", "8.8.8.8", "9.9.9.9", "208.67.222.222"}) {
                    if (!cands.contains(s)) cands.add(s);
                }
                for (String s : cands) {
                    dnsMs.put(s, NetTools.dnsTime(s, hosts));
                    ui.post(this::showDns);
                }

                mbps = -1;
                ui.post(() -> dSpeed.setText(getString(R.string.net_speed_running)));
                mbps = NetTools.downloadMbps(v -> ui.post(() -> dSpeed.setText(getString(R.string.net_speed_live, v))));
                ui.post(this::showSpeed);
            } catch (Throwable t) {
                LeveApp.log("LeveRede.test", t);
            } finally {
                testing = false;
                ui.post(this::conclude);
            }
        }).start();
    }

    private void showWifi() {
        if (wifi.cable) {
            dSignal.setText(R.string.net_cable);
            dBand.setText("");
            return;
        }
        if (!wifi.connected) {
            dSignal.setText(R.string.net_no_wifi);
            return;
        }
        String[] grades = {"", "muito fraco", "fraco", "bom", "ótimo"};
        int g = NetTools.signalGrade(wifi.rssi);
        dSignal.setText(getString(R.string.net_signal, grades[g], wifi.rssi, bars(g)));
        String band = wifi.freqMhz >= 4900 ? "5 GHz" : wifi.freqMhz >= 2400 ? "2,4 GHz" : "?";
        dBand.setText(getString(R.string.net_band, band, wifi.linkMbps));
    }

    private static String bars(int g) {
        return "▮▮▮▮".substring(0, g) + "▯▯▯▯".substring(0, 4 - g);
    }

    private void showRouter() {
        dRouter.setText(routerMs < 0 ? getString(R.string.net_router_fail)
                : getString(R.string.net_router, routerMs, routerMs <= 10 ? "rápido" : routerMs <= 40 ? "ok" : "lento"));
    }

    private void showDns() {
        StringBuilder sb = new StringBuilder("DNS: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : dnsMs.entrySet()) {
            if (!first) sb.append("  ·  ");
            first = false;
            String name = e.getKey().equals(wifi.dns1) ? "atual" : label(e.getKey());
            sb.append(name).append(' ').append(e.getValue() < 0 ? "falhou" : e.getValue() + " ms");
        }
        dDns.setText(sb.toString());
    }

    private static String label(String ip) {
        switch (ip) {
            case "1.1.1.1": return "Cloudflare";
            case "8.8.8.8": return "Google";
            case "9.9.9.9": return "Quad9";
            case "208.67.222.222": return "OpenDNS";
            default: return ip;
        }
    }

    private void showSpeed() {
        dSpeed.setText(mbps < 0 ? getString(R.string.net_speed_fail) : getString(R.string.net_speed, mbps));
    }

    /** Junta tudo numa conclusão simples e em dicas práticas. */
    private void conclude() {
        if (wifi == null) return;
        List<String> t = new ArrayList<>();
        String v;
        int g = NetTools.signalGrade(wifi.rssi);
        boolean five = wifi.freqMhz >= 4900;
        Integer cur = dnsMs.get(wifi.dns1);
        String best = bestDns(null);
        Integer bestMs = best == null ? null : dnsMs.get(best);

        if (!wifi.connected && !wifi.cable) {
            v = getString(R.string.net_v_offline);
        } else if (!wifi.cable && g == 1) {
            v = getString(R.string.net_v_far);
        } else if (mbps >= 0 && mbps < 3) {
            v = getString(R.string.net_v_slow);
        } else if (routerMs > 60) {
            v = getString(R.string.net_v_router);
        } else if (mbps >= 5) {
            v = getString(R.string.net_v_good);
        } else {
            v = getString(R.string.net_v_ok);
        }

        if (!wifi.cable && g <= 2) {
            if (five) t.add(getString(R.string.net_t_use24));
            t.add(getString(R.string.net_t_closer));
            t.add(getString(R.string.net_t_repeater));
        }
        if (!LeveMusicService.turboOn(this)) t.add(getString(R.string.net_t_turbo));
        if (cur != null && (cur < 0 || (bestMs != null && bestMs >= 0 && cur - bestMs > 25)) && !LeveDnsVpn.enabled(this)) {
            t.add(getString(R.string.net_t_dns));
        }
        if (mbps >= 0 && mbps < 5) t.add(getString(R.string.net_t_quality));
        t.add(getString(R.string.net_t_youcine));

        verdict.setText(v);
        StringBuilder sb = new StringBuilder();
        for (String s : t) sb.append("•  ").append(s).append('\n');
        tips.setText(sb.toString().trim());
        updateButtons();
    }

    /** Os dois DNS públicos mais rápidos medidos (ou o padrão). */
    private String bestDns(String exclude) {
        String best = null;
        int bestMs = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : dnsMs.entrySet()) {
            if (e.getKey().equals(exclude) || e.getKey().equals(wifi == null ? "" : wifi.dns1)) continue;
            if (e.getValue() >= 0 && e.getValue() < bestMs) {
                bestMs = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    // ---------- DNS rápido ----------

    private void toggleDns() {
        if (LeveDnsVpn.enabled(this)) {
            prefs.edit().putBoolean(LeveDnsVpn.PREF_ON, false).apply();
            LeveDnsVpn.restart(this);
            updateButtons();
            return;
        }
        String a = bestDns(null);
        String b = a == null ? null : bestDns(a);
        String servers = a == null ? LeveDnsVpn.DEFAULT_SERVERS : (b == null ? a + ",8.8.8.8" : a + "," + b);
        prefs.edit().putString(LeveDnsVpn.PREF_SERVERS, servers).apply();
        try {
            Intent ask = VpnService.prepare(this);
            if (ask != null) {
                Toast.makeText(this, R.string.net_dns_allow, Toast.LENGTH_LONG).show();
                startActivityForResult(ask, REQ_VPN);
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.net_dns_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        enableDns();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_SHARE) {
            if (res == RESULT_OK) enableShare();
            else Toast.makeText(this, R.string.net_dns_denied, Toast.LENGTH_LONG).show();
            return;
        }
        if (req == REQ_VPN) {
            if (res == RESULT_OK) enableDns();
            else Toast.makeText(this, R.string.net_dns_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void enableDns() {
        prefs.edit().putBoolean(LeveDnsVpn.PREF_ON, true).apply();
        LeveDnsVpn.restart(this);
        Toast.makeText(this, R.string.net_dns_toast_on, Toast.LENGTH_LONG).show();
        ui.postDelayed(this::updateButtons, 800);
    }

    // ---------- Internet pelo Leve Share (celular antigo) ----------

    private void shareDialog() {
        String ip = LeveMusicService.localIp();
        String link = ip.isEmpty() ? ShareLink.APK : ip + ":" + LeveMusicService.PORT + "/share";
        boolean on = ShareLink.on(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.share_title)
                .setMessage(getString(R.string.share_steps, link))
                .setPositiveButton(on ? R.string.share_turn_off : R.string.share_turn_on, (d, w) -> {
                    if (on) {
                        ShareLink.set(this, false);
                        LeveDnsVpn.restart(this);
                        Toast.makeText(this, R.string.share_toast_off, Toast.LENGTH_LONG).show();
                        updateButtons();
                    } else {
                        askShare();
                    }
                })
                .setNeutralButton(R.string.net_wifi_btn, (d, w) -> Shortcuts.open(this, Shortcuts.WIFI))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private void askShare() {
        try {
            Intent ask = VpnService.prepare(this);
            if (ask != null) {
                Toast.makeText(this, R.string.share_allow, Toast.LENGTH_LONG).show();
                startActivityForResult(ask, REQ_SHARE);
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.net_dns_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        enableShare();
    }

    private void enableShare() {
        ShareLink.set(this, true);
        LeveDnsVpn.restart(this);
        Toast.makeText(this, android.os.Build.VERSION.SDK_INT >= 29
                ? R.string.share_toast_on : R.string.share_toast_on_old, Toast.LENGTH_LONG).show();
        ui.postDelayed(this::updateButtons, 800);
    }

    // ---------- YouCine turbo ----------

    private void youcineTurbo() {
        final String yc = findApp("youcine");
        Toast.makeText(this, R.string.net_freeing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            long freed = freeMemory(yc);
            ui.post(() -> {
                Toast.makeText(this, getString(R.string.net_freed, freed), Toast.LENGTH_LONG).show();
                if (yc == null) {
                    Toast.makeText(this, R.string.net_no_youcine, Toast.LENGTH_LONG).show();
                    return;
                }
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(yc);
                if (i == null) i = pm.getLaunchIntentForPackage(yc);
                if (i != null) {
                    try {
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                    }
                }
            });
        }).start();
    }

    private String findApp(String nameContains) {
        PackageManager pm = getPackageManager();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                CharSequence l = ri.loadLabel(pm);
                if (l != null && l.toString().toLowerCase(Locale.ROOT).replace(" ", "").contains(nameContains)) {
                    return ri.activityInfo.packageName;
                }
            }
        }
        return null;
    }

    /** Fecha o que está parado em segundo plano. Retorna MB liberados (aproximado). */
    private long freeMemory(String keep) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
            String p = ai.packageName;
            if (p.equals(getPackageName()) || p.equals(keep) || p.equals("android")) continue;
            if (p.startsWith("com.android.systemui") || p.contains("inputmethod")) continue;
            try {
                am.killBackgroundProcesses(p);
            } catch (Exception ignored) {
            }
        }
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
        }
        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);
        return Math.max(0, (after.availMem - before.availMem) / (1024 * 1024));
    }
}
