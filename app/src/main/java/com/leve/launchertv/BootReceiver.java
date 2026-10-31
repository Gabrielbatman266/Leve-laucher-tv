package com.leve.launchertv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Ao ligar a TV: abre o Leve (funciona direto até o Android 9; do 10 em diante,
 * quem garante é o Leve Mouse, ou a permissão de sobrepor apps).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        if (a.equals(Intent.ACTION_PACKAGE_ADDED) || a.equals(Intent.ACTION_PACKAGE_REMOVED)) {
            HomeGuard.forget();
            return;
        }
        if (!HomeGuard.enabled(c)) return;
        HomeGuard.bringLeve(c, "boot");
    }
}
