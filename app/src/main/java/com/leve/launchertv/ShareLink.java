package com.leve.launchertv;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * "Internet pelo Leve Share": a TV conecta no Wi-Fi do celular antigo e todos os apps
 * passam pelo proxy dele (192.168.49.1:8282). O DNS vai pelo repasse do celular (porta 8053).
 */
final class ShareLink {

    static final String PREF = "share_on";
    static final String HOST = "192.168.49.1";
    static final int PROXY_PORT = 8282;
    static final int DNS_PORT = 8053;
    static final String APK = "https://github.com/" + Updater.REPO + "/releases/latest/download/LeveShare.apk";

    private static volatile Boolean cached;

    private ShareLink() {
    }

    static boolean on(Context c) {
        boolean v = c.getSharedPreferences("leve", Context.MODE_PRIVATE).getBoolean(PREF, false);
        cached = v;
        return v;
    }

    static void set(Context c, boolean v) {
        c.getSharedPreferences("leve", Context.MODE_PRIVATE).edit().putBoolean(PREF, v).apply();
        cached = v;
    }

    /** Ligado E a TV está agora no Wi-Fi do celular (DIRECT-…). Fora dele, nada muda. */
    static boolean active(Context c) {
        if (!on(c)) return false;
        NetTools.Wifi w = NetTools.wifi(c);
        return HOST.equals(w.gateway);
    }

    /** Conexões do próprio Leve (atualizar, capas, teste de velocidade) também usam o celular. */
    static HttpURLConnection open(String url) throws IOException {
        Context app = RemoteHub.app;
        boolean use = app != null && (cached == null || cached) && active(app);
        if (use) {
            Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(HOST, PROXY_PORT));
            return (HttpURLConnection) new URL(url).openConnection(p);
        }
        return (HttpURLConnection) new URL(url).openConnection();
    }
}
