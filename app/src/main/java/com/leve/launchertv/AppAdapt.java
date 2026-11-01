package com.leve.launchertv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Apps de celular "adaptados para a TV" (ex.: Tomato): ao abrir, o Leve libera memória
 * e o Leve Mouse liga o cursor no controle remoto sozinho (setas movem, OK clica).
 */
final class AppAdapt {

    static final String KEY = "adapted";

    private AppAdapt() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("leve", Context.MODE_PRIVATE);
    }

    static Set<String> list(Context c) {
        Set<String> out = new HashSet<>();
        for (String s : prefs(c).getString(KEY, "").split("\\|")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    static boolean is(Context c, String pkg) {
        return list(c).contains(pkg);
    }

    static void set(Context c, String pkg, boolean on) {
        Set<String> s = list(c);
        if (on) s.add(pkg);
        else s.remove(pkg);
        StringBuilder sb = new StringBuilder();
        for (String p : s) {
            if (sb.length() > 0) sb.append('|');
            sb.append(p);
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply();
        LeveMouseService m = RemoteHub.mouse;
        if (m != null) m.refreshRemoteMouse();
    }

    /** Fecha o que está parado em segundo plano (chamar fora da thread principal). */
    static void freeMemory(Context c, String keep) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
                String p = ai.packageName;
                if (p.equals(c.getPackageName()) || p.equals(keep) || p.equals("android")) continue;
                if (p.startsWith("com.android.systemui") || p.contains("inputmethod")) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                try {
                    am.killBackgroundProcesses(p);
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
