package com.leve.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.security.SecureRandom;

/**
 * Mantém tudo ligado com a tela do celular apagada:
 * a rede Wi-Fi Direct (onde a TV conecta) + o proxy + o repasse de DNS.
 */
public class ShareService extends Service {

    static volatile ShareService instance;
    static volatile String status = "";
    static volatile String ssid = "", pass = "";
    static volatile int clients;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ShareProxy proxy;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private int infoTries;

    static void start(Context c) {
        Intent i = new Intent(c, ShareService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, ShareService.class));
    }

    ShareProxy proxy() {
        return proxy;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        foreground("Ligando…");
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leveshare:cpu");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "leveshare:wifi");
            wifiLock.acquire();
        } catch (Throwable ignored) {
        }
        try {
            proxy = new ShareProxy();
            proxy.start();
        } catch (Exception e) {
            status = "Erro ao abrir o proxy: " + e.getMessage();
        }
        startGroup();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (proxy != null) proxy.stop();
        try {
            if (p2p != null && channel != null) p2p.removeGroup(channel, null);
        } catch (Throwable ignored) {
        }
        try {
            if (wake != null && wake.isHeld()) wake.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        ssid = pass = "";
        clients = 0;
        status = "Desligado";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- Wi-Fi Direct ----------

    @SuppressWarnings("MissingPermission")
    private void startGroup() {
        try {
            p2p = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
            if (p2p == null) {
                status = "Este celular não tem Wi-Fi Direct";
                return;
            }
            channel = p2p.initialize(this, getMainLooper(), null);
            status = "Criando a rede…";
            // remove um grupo antigo (se existir) e cria o nosso
            p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { h.postDelayed(ShareService.this::createGroup, 500); }
                @Override public void onFailure(int r) { h.postDelayed(ShareService.this::createGroup, 200); }
            });
        } catch (Throwable t) {
            status = "Erro no Wi-Fi Direct: " + t.getMessage();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void createGroup() {
        WifiP2pManager.ActionListener l = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                status = "Rede criada";
                infoTries = 0;
                h.postDelayed(ShareService.this::readGroup, 800);
            }

            @Override
            public void onFailure(int reason) {
                status = reason == WifiP2pManager.BUSY
                        ? "O Wi-Fi está ocupado. Ligue o Wi-Fi e a Localização do celular e tente de novo."
                        : reason == WifiP2pManager.P2P_UNSUPPORTED ? "Este celular não tem Wi-Fi Direct"
                        : "Não deu para criar a rede (erro " + reason + "). Ligue o Wi-Fi e a Localização.";
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // nome e senha fixos: a TV conecta uma vez e lembra para sempre
                android.content.SharedPreferences sp = getSharedPreferences("share", MODE_PRIVATE);
                String pw = sp.getString("pass", null);
                if (pw == null) {
                    pw = randomPass();
                    sp.edit().putString("pass", pw).apply();
                }
                WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-LV-Leve")
                        .setPassphrase(pw)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) // 2,4 GHz alcança mais
                        .build();
                p2p.createGroup(channel, cfg, l);
            } else {
                p2p.createGroup(channel, l);
            }
        } catch (Throwable t) {
            status = "Erro ao criar a rede: " + t.getMessage();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void readGroup() {
        if (instance == null) return;
        try {
            p2p.requestGroupInfo(channel, g -> {
                if (g != null) {
                    ssid = g.getNetworkName();
                    pass = g.getPassphrase();
                    clients = g.getClientList() == null ? 0 : g.getClientList().size();
                    status = "Ligado";
                    foreground(clients == 0 ? "Esperando a TV conectar" : clients + " aparelho(s) conectado(s)");
                }
                // continua acompanhando quantos aparelhos estão conectados
                h.postDelayed(this::readGroup, g == null && infoTries++ < 10 ? 1000 : 5000);
            });
        } catch (Throwable t) {
            h.postDelayed(this::readGroup, 5000);
        }
    }

    private static String randomPass() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    // ---------- Notificação ----------

    private void foreground(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("share", "Leve Share", NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(this, "share");
            } else {
                b = new Notification.Builder(this);
            }
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ShareActivity.class),
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification n = b.setSmallIcon(R.drawable.ic_share)
                    .setContentTitle("Leve Share ligado")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
            startForeground(1, n);
        } catch (Throwable ignored) {
        }
    }
}
