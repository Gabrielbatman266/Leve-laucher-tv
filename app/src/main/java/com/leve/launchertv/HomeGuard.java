package com.leve.launchertv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * Mantém o Leve como tela inicial mesmo em TV Box que volta para o launcher de fábrica
 * ao reiniciar. Quem vigia é o Leve Mouse (acessibilidade): quando o launcher de fábrica
 * aparece, o Leve é aberto por cima na hora.
 */
final class HomeGuard {

    static final String KEY = "force_home";
    private static Set<String> otherHomes;
    private static long lastBring;

    private HomeGuard() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("leve", Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("leve", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    /** O app que apareceu é outro launcher (o de fábrica)? */
    static boolean isOtherHome(Context c, String pkg) {
        if (pkg.equals(c.getPackageName()) || !enabled(c)) return false;
        if (otherHomes == null) otherHomes = listHomes(c);
        return otherHomes.contains(pkg);
    }

    /** Recalcula a lista de launchers (quando apps são instalados/removidos). */
    static void forget() {
        otherHomes = null;
    }

    private static Set<String> listHomes(Context c) {
        Set<String> out = new HashSet<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                String p = ri.activityInfo.packageName;
                // "android" é a tela de escolher launcher; nunca atrapalhar ela
                if (!p.equals(c.getPackageName()) && !p.equals("android")
                        && !p.equals("com.android.settings") && !p.contains("provision")) {
                    out.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Abre o Leve (no máximo uma vez a cada 1,5 s, para nunca ficar em loop). */
    static void bringLeve(Context c, String why) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBring < 1500) return;
        lastBring = now;
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Throwable t) {
            LeveApp.log("HomeGuard.bringLeve(" + why + ")", t);
        }
    }
}
