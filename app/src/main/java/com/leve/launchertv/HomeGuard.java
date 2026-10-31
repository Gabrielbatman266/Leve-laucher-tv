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
    private static Set<String> otherHomes;        // pacotes de outros launchers
    private static Set<String> otherHomeClasses;  // as telas iniciais exatas desses launchers
    private static long lastBring;
    private static long pausedUntil;

    /** Pausa o guarda por 3 minutos (ao abrir configurações pelo Leve). */
    static void pause() {
        pausedUntil = SystemClock.elapsedRealtime() + 3 * 60 * 1000;
    }

    static boolean paused() {
        return SystemClock.elapsedRealtime() < pausedUntil;
    }

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
        return isOtherHome(c, pkg, null);
    }

    /**
     * Só conta como "launcher de fábrica" se for a TELA INICIAL dele
     * (não as configurações nem outras telas do mesmo app).
     */
    static boolean isOtherHome(Context c, String pkg, String cls) {
        if (pkg.equals(c.getPackageName()) || !enabled(c) || paused()) return false;
        if (otherHomes == null) listHomes(c);
        if (!otherHomes.contains(pkg)) return false;
        if (cls == null || cls.isEmpty() || cls.startsWith("android.")) return true;
        return otherHomeClasses.contains(cls) || otherHomeClasses.contains(pkg + cls);
    }

    /** Recalcula a lista de launchers (quando apps são instalados/removidos). */
    static void forget() {
        otherHomes = null;
        otherHomeClasses = null;
    }

    private static void listHomes(Context c) {
        Set<String> pk = new HashSet<>();
        Set<String> cl = new HashSet<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                String p = ri.activityInfo.packageName;
                String lower = p.toLowerCase(java.util.Locale.ROOT);
                String name = ri.activityInfo.name == null ? "" : ri.activityInfo.name;
                // Fora: o seletor do sistema, as Configurações (têm uma "tela inicial reserva"),
                // assistentes de configuração e qualquer tela inicial de prioridade negativa.
                if (p.equals(c.getPackageName()) || p.equals("android")
                        || lower.contains("settings") || lower.contains("provision")
                        || lower.contains("setupwizard") || name.toLowerCase(java.util.Locale.ROOT).contains("fallback")
                        || ri.priority < 0) {
                    continue;
                }
                pk.add(p);
                cl.add(name);
                if (ri.activityInfo.targetActivity != null) cl.add(ri.activityInfo.targetActivity);
            }
        } catch (Exception ignored) {
        }
        otherHomeClasses = cl;
        otherHomes = pk;
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
