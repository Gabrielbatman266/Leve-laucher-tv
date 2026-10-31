package com.leve.share;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O "motor" do Leve Share, sem nada de Android (dá para testar no computador):
 *  - proxy HTTP na porta 8282: HTTPS via CONNECT e HTTP normal;
 *  - repasse de DNS em UDP na porta 8053 (para a TV achar os sites).
 */
public final class ShareProxy {

    public static final int PROXY_PORT = 8282;
    public static final int DNS_PORT = 8053;

    public final AtomicLong bytesDown = new AtomicLong();
    public final AtomicLong bytesUp = new AtomicLong();
    public final AtomicInteger active = new AtomicInteger();

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;
    private DatagramSocket dns;
    private final int proxyPort, dnsPort;
    private final String[] dnsUpstream = {"8.8.8.8", "1.1.1.1"};

    public ShareProxy() {
        this(PROXY_PORT, DNS_PORT);
    }

    public ShareProxy(int proxyPort, int dnsPort) {
        this.proxyPort = proxyPort;
        this.dnsPort = dnsPort;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(proxyPort), 64);
        pool.execute(this::acceptLoop);
        try {
            dns = new DatagramSocket(null);
            dns.setReuseAddress(true);
            dns.bind(new InetSocketAddress(dnsPort));
            pool.execute(this::dnsLoop);
        } catch (IOException e) {
            dns = null; // sem DNS o proxy ainda funciona para quem usa proxy
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (dns != null) dns.close();
        pool.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    // ---------- Proxy ----------

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = server.accept();
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket client) {
        active.incrementAndGet();
        Socket up = null;
        try {
            client.setSoTimeout(30000);
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 3) return;
            String method = parts[0], target = parts[1], version = parts[2];

            StringBuilder headers = new StringBuilder();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                String low = h.toLowerCase();
                if (low.startsWith("proxy-connection:") || low.startsWith("proxy-authorization:")) continue;
                if (low.startsWith("connection:") || low.startsWith("keep-alive:")) continue;
                headers.append(h).append("\r\n");
            }

            if (method.equalsIgnoreCase("CONNECT")) {
                // HTTPS: só abre o túnel
                HostPort hp = HostPort.parse(target, 443);
                up = new Socket();
                up.connect(new InetSocketAddress(hp.host, hp.port), 15000);
                up.setTcpNoDelay(true);
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                tunnel(client, up);
                return;
            }

            // HTTP normal: GET http://host/caminho → GET /caminho para o host
            if (!target.startsWith("http://")) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String rest = target.substring(7);
            int slash = rest.indexOf('/');
            String hostPart = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            HostPort hp = HostPort.parse(hostPart, 80);
            up = new Socket();
            up.connect(new InetSocketAddress(hp.host, hp.port), 15000);
            up.setTcpNoDelay(true);
            OutputStream uo = up.getOutputStream();
            String req = method + " " + path + " " + version + "\r\n" + headers + "Connection: close\r\n\r\n";
            uo.write(req.getBytes(StandardCharsets.ISO_8859_1));
            uo.flush();
            tunnel(client, up);
        } catch (Exception ignored) {
        } finally {
            closeQuietly(up);
            closeQuietly(client);
            active.decrementAndGet();
        }
    }

    /** Copia nos dois sentidos até um lado fechar. */
    private void tunnel(Socket a, Socket b) throws IOException {
        a.setSoTimeout(0);
        b.setSoTimeout(0);
        final InputStream ain = a.getInputStream();
        final OutputStream aout = a.getOutputStream();
        final InputStream bin = b.getInputStream();
        final OutputStream bout = b.getOutputStream();
        Thread upload = new Thread(() -> {
            copy(ain, bout, bytesUp);
            try {
                b.shutdownOutput();
            } catch (IOException ignored) {
            }
        }, "share-up");
        upload.start();
        copy(bin, aout, bytesDown);
        try {
            upload.join(2000);
        } catch (InterruptedException ignored) {
        }
    }

    private static void copy(InputStream in, OutputStream out, AtomicLong counter) {
        byte[] buf = new byte[32 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                counter.addAndGet(n);
            }
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) break;
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    static final class HostPort {
        final String host;
        final int port;

        HostPort(String h, int p) {
            host = h;
            port = p;
        }

        static HostPort parse(String s, int def) {
            if (s.startsWith("[")) { // IPv6 [::1]:443
                int end = s.indexOf(']');
                String host = s.substring(1, end);
                int port = s.length() > end + 2 ? Integer.parseInt(s.substring(end + 2)) : def;
                return new HostPort(host, port);
            }
            int i = s.lastIndexOf(':');
            if (i > 0) return new HostPort(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
            return new HostPort(s, def);
        }
    }

    // ---------- DNS ----------

    private void dnsLoop() {
        byte[] buf = new byte[1500];
        while (running && dns != null) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                dns.receive(p);
                final byte[] q = java.util.Arrays.copyOf(p.getData(), p.getLength());
                final InetAddress from = p.getAddress();
                final int fromPort = p.getPort();
                pool.execute(() -> {
                    byte[] a = askDns(q);
                    if (a == null) return;
                    try {
                        dns.send(new DatagramPacket(a, a.length, from, fromPort));
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private byte[] askDns(byte[] q) {
        for (String s : dnsUpstream) {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(2500);
                sock.send(new DatagramPacket(q, q.length, InetAddress.getByName(s), 53));
                byte[] buf = new byte[4096];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                sock.receive(r);
                return java.util.Arrays.copyOf(buf, r.getLength());
            } catch (IOException ignored) {
            }
        }
        return null;
    }
}
