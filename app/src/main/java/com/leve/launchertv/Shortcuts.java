package com.leve.launchertv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Abre telas de configuração tentando vários caminhos, porque cada TV Box
 * tem um app de Configurações diferente (Android TV, AOSP de celular, de fábrica...).
 */
final class Shortcuts {

    static final int KEYBOARD = 1, ACCESSIBILITY = 2, WIFI = 3, HOME = 4, SETTINGS = 5;

    private Shortcuts() {
    }

    /** Retorna true se abriu alguma coisa. Se só deu para abrir a tela geral, mostra o caminho. */
    static boolean open(Context c, int what) {
        HomeGuard.pause(); // o guarda da tela inicial não pode atrapalhar as configurações
        Intent[] tries;
        String howTo;
        switch (what) {
            case KEYBOARD:
                tries = new Intent[]{
                        new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                        comp("com.android.tv.settings", "com.android.tv.settings.inputmethod.KeyboardActivity"),
                        comp("com.android.tv.settings", "com.android.tv.settings.system.InputActivity"),
                        comp("com.android.settings", "com.android.settings.Settings$InputMethodAndLanguageSettingsActivity"),
                        comp("com.android.settings", "com.android.settings.Settings$AvailableVirtualKeyboardActivity"),
                        comp("com.android.settings", "com.android.settings.Settings$ManageAssistActivity"),
                        new Intent(Settings.ACTION_LOCALE_SETTINGS),
                };
                howTo = c.getString(R.string.sc_howto_keyboard);
                break;
            case ACCESSIBILITY:
                tries = new Intent[]{
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        comp("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
                        comp("com.android.tv.settings", "com.android.tv.settings.system.AccessibilityActivity"),
                        comp("com.android.settings", "com.android.settings.Settings$AccessibilitySettingsActivity"),
                };
                howTo = c.getString(R.string.sc_howto_access);
                break;
            case WIFI:
                tries = new Intent[]{
                        new Intent(Settings.ACTION_WIFI_SETTINGS),
                        comp("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity"),
                        new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                };
                howTo = c.getString(R.string.sc_howto_wifi);
                break;
            case HOME:
                tries = new Intent[]{
                        new Intent(Settings.ACTION_HOME_SETTINGS),
                        new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                        comp("com.android.tv.settings", "com.android.tv.settings.device.apps.AppsActivity"),
                };
                howTo = c.getString(R.string.sc_howto_home);
                break;
            default:
                tries = new Intent[0];
                howTo = null;
        }
        PackageManager pm = c.getPackageManager();
        for (Intent i : tries) {
            if (i == null) continue;
            if (start(c, pm, i)) return true;
        }
        // último recurso: a tela geral de configurações + o caminho até a opção
        if (start(c, pm, new Intent(Settings.ACTION_SETTINGS))) {
            if (howTo != null) Toast.makeText(c, howTo, Toast.LENGTH_LONG).show();
            return true;
        }
        Toast.makeText(c, R.string.cant_open, Toast.LENGTH_LONG).show();
        return false;
    }

    private static Intent comp(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    private static boolean start(Context c, PackageManager pm, Intent i) {
        try {
            if (i.resolveActivity(pm) == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
