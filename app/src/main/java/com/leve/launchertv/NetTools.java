package com.leve.launchertv;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Medições simples da rede, sem bibliotecas (tudo fora da thread principal). */
final class NetTools {

    private NetTools() {
    }

    // ---------- Wi-Fi ----------

    static final class Wifi {
        boolean connected;
        int rssi = -127;      // dBm
        int linkMbps;
        int freqMhz;
        String gateway = "";
        String dns1 = "";
        boolean cable;
    }

    @SuppressWarnings("deprecation")
    static Wifi wifi(Context c) {
        Wifi w = new Wifi();
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo i = wm.getConnectionInfo();
            if (i != null && i.getNetworkId() != -1) {
                w.connected = true;
                w.rssi = i.getRssi();
                w.linkMbps = i.getLinkSpeed();
                w.freqMhz = i.getFrequency();
            }
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null) {
                w.gateway = ip(d.gateway);
                w.dns1 = ip(d.dns1);
            }
        } catch (Exception ignored) {
        }
        if (!w.connected) {
            // pode estar no cabo
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                w.cable = ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET;
            } catch (Exception ignored) {
            }
        }
        return w;
    }

    static String ip(int v) {
        if (v == 0) return "";
        return (v & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 24) & 0xFF);
    }

    /** Nota do sinal: 4 ótimo, 3 bom, 2 fraco, 1 muito fraco. */
    static int signalGrade(int rssi) {
        if (rssi >= -55) return 4;
        if (rssi >= -67) return 3;
        if (rssi >= -75) return 2;
        return 1;
    }

    // ---------- Ping ----------

    /** Tempo médio de resposta em ms (-1 se não respondeu). Usa o ping do sistema; se não tiver, mede conexão TCP. */
    static int ping(String host) {
        if (host == null || host.isEmpty()) return -1;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "4", "-i", "0.3", "-W", "1", host});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            Pattern avg = Pattern.compile("= [\\d.]+/([\\d.]+)/");
            Integer result = null;
            while ((line = r.readLine()) != null) {
                Matcher m = avg.matcher(line);
                if (m.find()) result = Math.round(Float.parseFloat(m.group(1)));
            }
            p.waitFor();
            if (result != null) return result;
        } catch (Exception ignored) {
        }
        // plano B: abrir conexão (porta 53 ou 80)
        for (int port : new int[]{53, 80, 443}) {
            long t0 = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 1500);
                return (int) ((System.nanoTime() - t0) / 1_000_000);
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    // ---------- DNS ----------

    static byte[] dnsQuery(String host, int id) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id >> 8);
        b.write(id & 0xFF);
        b.write(0x01); // recursão desejada
        b.write(0x00);
        b.write(0); b.write(1); // 1 pergunta
        b.write(0); b.write(0);
        b.write(0); b.write(0);
        b.write(0); b.write(0);
        for (String part : host.split("\\.")) {
            b.write(part.length());
            b.write(part.getBytes(), 0, part.length());
        }
        b.write(0);
        b.write(0); b.write(1); // tipo A
        b.write(0); b.write(1); // classe IN
        return b.toByteArray();
    }

    /** Mediana do tempo de resposta (ms) de um servidor DNS, -1 se não respondeu. */
    static int dnsTime(String server, String[] hosts) {
        int[] t = new int[hosts.length];
        int ok = 0;
        Random rnd = new Random();
        for (String h : hosts) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(1500);
                int id = rnd.nextInt(65535);
                byte[] q = dnsQuery(h, id);
                InetAddress addr = InetAddress.getByName(server);
                long t0 = System.nanoTime();
                s.send(new DatagramPacket(q, q.length, addr, 53));
                byte[] buf = new byte[1500];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                s.receive(resp);
                if (resp.getLength() > 2 && ((buf[0] & 0xFF) << 8 | (buf[1] & 0xFF)) == id) {
                    t[ok++] = (int) ((System.nanoTime() - t0) / 1_000_000);
                }
            } catch (Exception ignored) {
            }
        }
        if (ok == 0) return -1;
        int[] v = Arrays.copyOf(t, ok);
        Arrays.sort(v);
        return v[ok / 2];
    }

    // ---------- Velocidade ----------

    interface Progress {
        void on(float mbps);
    }

    /** Baixa por até ~7 s de um servidor rápido e devolve Mbps (-1 se falhou). */
    static float downloadMbps(Progress p) {
        String[] urls = {
                "https://speed.cloudflare.com/__down?bytes=40000000",
                "https://proof.ovh.net/files/100Mb.dat",
        };
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = ShareLink.open(u);
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept-Encoding", "identity");
                long start = System.nanoTime();
                long got = 0;
                long lastReport = start;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        got += n;
                        long now = System.nanoTime();
                        float secs = (now - start) / 1e9f;
                        if (p != null && now - lastReport > 400_000_000L && secs > 0) {
                            lastReport = now;
                            p.on(got * 8 / 1e6f / secs);
                        }
                        if (secs > 7) break;
                    }
                }
                float secs = (System.nanoTime() - start) / 1e9f;
                if (got > 200_000 && secs > 0) return got * 8 / 1e6f / secs;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return -1;
    }
}
