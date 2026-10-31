package com.leve.launchertv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DNS rápido do Leve: um "VPN" local que pega SÓ as consultas de DNS (porta 53),
 * responde do cache quando pode e pergunta ao servidor mais rápido quando não.
 * Nenhum outro tráfego passa por aqui (vídeos e downloads seguem direto).
 */
public class LeveDnsVpn extends VpnService {

    static final String PREF_ON = "dns_on";
    static final String PREF_SERVERS = "dns_servers";
    static final String DEFAULT_SERVERS = "1.1.1.1,8.8.8.8";

    private static final String VPN_ADDR = "172.31.255.2";
    private static final String VPN_DNS = "172.31.255.1";
    private static final String CHANNEL = "leve_dns";

    static volatile LeveDnsVpn instance;
    static volatile long answered, fromCache;

    private ParcelFileDescriptor tun;
    private volatile boolean running;
    private Thread reader;
    private ExecutorService pool;
    private FileOutputStream out;
    private InetAddress[] servers;

    /** cache: pergunta (sem o ID) → resposta + validade */
    private final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > 600;
        }
    };

    static boolean enabled(Context c) {
        return c.getSharedPreferences("leve", MODE_PRIVATE).getBoolean(PREF_ON, false);
    }

    /** O VPN local precisa rodar para o DNS rápido OU para usar o Leve Share. */
    static boolean needed(Context c) {
        return enabled(c) || ShareLink.active(c);
    }

    /** Reinicia para aplicar mudanças (DNS rápido / Leve Share). */
    static void restart(Context c) {
        LeveDnsVpn v = instance;
        if (v != null) v.shutdown();
        if (needed(c)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> start(c), 700);
        }
    }

    private int[] ports;
    private boolean shareMode;

    boolean isShareMode() {
        return shareMode;
    }

    static void start(Context c) {
        try {
            if (VpnService.prepare(c) != null) return; // ainda sem permissão do usuário
            Intent i = new Intent(c, LeveDnsVpn.class);
            if (Build.VERSION.SDK_INT >= 26 && c instanceof android.app.Activity) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            LeveApp.log("LeveDnsVpn.start", t);
        }
    }

    static void stop(Context c) {
        LeveDnsVpn v = instance;
        if (v != null) v.shutdown();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        try {
            goForeground();
            shareMode = ShareLink.active(this);
            String list = shareMode
                    ? ShareLink.HOST + ":" + ShareLink.DNS_PORT
                    : getSharedPreferences("leve", MODE_PRIVATE).getString(PREF_SERVERS, DEFAULT_SERVERS);
            String[] parts = list.split(",");
            servers = new InetAddress[parts.length];
            ports = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String[] hp = parts[i].trim().split(":");
                servers[i] = InetAddress.getByName(hp[0]);
                ports[i] = hp.length > 1 ? Integer.parseInt(hp[1]) : 53;
            }

            Builder b = new Builder()
                    .setSession("Leve DNS / Share")
                    .addAddress(VPN_ADDR, 32)
                    .addDnsServer(VPN_DNS)
                    .addRoute(VPN_DNS, 32)
                    .setBlocking(true);
            try {
                b.addDisallowedApplication(getPackageName()); // o próprio Leve fala direto com a internet
            } catch (Exception ignored) {
            }
            if (Build.VERSION.SDK_INT >= 29) {
                b.setMetered(false);
                if (shareMode) {
                    // todos os apps passam a usar o proxy do celular (Leve Share)
                    b.setHttpProxy(android.net.ProxyInfo.buildDirectProxy(ShareLink.HOST, ShareLink.PROXY_PORT));
                }
            }
            tun = b.establish();
            if (tun == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            out = new FileOutputStream(tun.getFileDescriptor());
            pool = Executors.newFixedThreadPool(4);
            running = true;
            instance = this;
            reader = new Thread(this::loop, "leve-dns");
            reader.start();
        } catch (Throwable t) {
            LeveApp.log("LeveDnsVpn.onStart", t);
            shutdown();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        // o usuário desligou pelo sistema
        getSharedPreferences("leve", MODE_PRIVATE).edit().putBoolean(PREF_ON, false).apply();
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    void shutdown() {
        running = false;
        if (instance == this) instance = null;
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {
        }
        tun = null;
        if (pool != null) pool.shutdownNow();
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    private void goForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Leve DNS", NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(ch);
                nb = new Notification.Builder(this, CHANNEL);
            } else {
                nb = new Notification.Builder(this);
            }
            startForeground(2, nb.setSmallIcon(R.drawable.icon).setContentTitle("Leve DNS rápido").setOngoing(true).build());
        } catch (Throwable ignored) {
        }
    }

    // ---------- Pacotes ----------

    private void loop() {
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            byte[] buf = new byte[32767];
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                final byte[] pkt = java.util.Arrays.copyOf(buf, n);
                try {
                    pool.execute(() -> handle(pkt));
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            if (running) LeveApp.log("LeveDnsVpn.loop", t);
        }
    }

    /** IPv4 + UDP + porta 53 → responde. Qualquer outra coisa é ignorada. */
    private void handle(byte[] p) {
        try {
            if (p.length < 28 || (p[0] >> 4) != 4) return;
            int ihl = (p[0] & 0x0F) * 4;
            if (p[9] != 17 || p.length < ihl + 8) return; // só UDP
            int dport = u16(p, ihl + 2);
            if (dport != 53) return;
            int udpLen = u16(p, ihl + 4);
            int qLen = Math.min(udpLen - 8, p.length - ihl - 8);
            if (qLen < 12) return;
            byte[] query = java.util.Arrays.copyOfRange(p, ihl + 8, ihl + 8 + qLen);

            byte[] answer = fromCacheOrNull(query);
            if (answer == null) {
                answer = ask(query);
                if (answer == null) return;
                store(query, answer);
            } else {
                fromCache++;
            }
            answered++;
            reply(p, ihl, answer);
        } catch (Throwable t) {
            // pacote estranho: ignora
        }
    }

    private byte[] ask(byte[] query) {
        for (int round = 0; round < 2; round++) {
            for (int si = 0; si < servers.length; si++) {
                InetAddress s = servers[si];
                try (DatagramSocket sock = new DatagramSocket()) {
                    protect(sock); // sai direto pela rede, não volta para o VPN
                    sock.setSoTimeout(round == 0 ? 1500 : 3000);
                    sock.send(new DatagramPacket(query, query.length, s, ports[si]));
                    byte[] buf = new byte[4096];
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    sock.receive(r);
                    if (r.getLength() >= 12 && buf[0] == query[0] && buf[1] == query[1]) {
                        return java.util.Arrays.copyOf(buf, r.getLength());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private void reply(byte[] req, int ihl, byte[] dns) throws Exception {
        int total = 20 + 8 + dns.length;
        byte[] r = new byte[total];
        // IPv4
        r[0] = 0x45;
        r[2] = (byte) (total >> 8);
        r[3] = (byte) total;
        r[6] = 0x40; // não fragmentar
        r[8] = 64;   // TTL
        r[9] = 17;   // UDP
        System.arraycopy(req, 16, r, 12, 4); // origem = destino do pedido
        System.arraycopy(req, 12, r, 16, 4); // destino = quem pediu
        int sum = checksum(r, 0, 20);
        r[10] = (byte) (sum >> 8);
        r[11] = (byte) sum;
        // UDP
        r[20] = req[ihl + 2];
        r[21] = req[ihl + 3];   // porta de origem = 53
        r[22] = req[ihl];
        r[23] = req[ihl + 1];   // porta de destino = a do app
        int ulen = 8 + dns.length;
        r[24] = (byte) (ulen >> 8);
        r[25] = (byte) ulen;
        // checksum UDP 0 = não usado (permitido no IPv4)
        System.arraycopy(dns, 0, r, 28, dns.length);
        synchronized (this) {
            if (out != null && running) out.write(r);
        }
    }

    // ---------- Cache ----------

    private static String key(byte[] q) {
        // ignora os 2 bytes do ID
        StringBuilder sb = new StringBuilder(q.length * 2);
        for (int i = 2; i < q.length; i++) sb.append((char) (q[i] & 0xFF));
        return sb.toString();
    }

    private byte[] fromCacheOrNull(byte[] q) {
        Object[] e;
        synchronized (cache) {
            e = cache.get(key(q));
        }
        if (e == null || System.currentTimeMillis() > (Long) e[1]) return null;
        byte[] a = ((byte[]) e[0]).clone();
        a[0] = q[0];
        a[1] = q[1];
        return a;
    }

    private void store(byte[] q, byte[] a) {
        int rcode = a[3] & 0x0F;
        if (rcode != 0 && rcode != 3) return; // só guarda respostas válidas (ou "não existe")
        long ttl = Math.max(30, Math.min(1800, minTtl(a)));
        synchronized (cache) {
            cache.put(key(q), new Object[]{a, System.currentTimeMillis() + ttl * 1000});
        }
    }

    /** Menor TTL entre as respostas (padrão 120 s se não der para ler). */
    static long minTtl(byte[] m) {
        try {
            int qd = u16(m, 4), an = u16(m, 6);
            int pos = 12;
            for (int i = 0; i < qd; i++) pos = skipName(m, pos) + 4;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < an; i++) {
                pos = skipName(m, pos);
                long ttl = ((long) u16(m, pos + 4) << 16) | u16(m, pos + 6);
                min = Math.min(min, ttl);
                pos += 10 + u16(m, pos + 8);
            }
            return min == Long.MAX_VALUE ? 120 : min;
        } catch (Exception e) {
            return 120;
        }
    }

    private static int skipName(byte[] m, int pos) {
        while (true) {
            int len = m[pos] & 0xFF;
            if (len == 0) return pos + 1;
            if ((len & 0xC0) == 0xC0) return pos + 2; // ponteiro
            pos += len + 1;
        }
    }

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    static int checksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len - 1; i += 2) sum += u16(b, i);
        if ((len & 1) == 1) sum += (b[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
