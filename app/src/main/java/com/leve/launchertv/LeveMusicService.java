package com.leve.launchertv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Leve Music TV — recebe músicas do Leve Music do celular pela rede Wi-Fi.
 *
 * O celular manda o link do áudio (já resolvido) e os comandos; a TV toca.
 * Protocolo HTTP simples na porta 8977:
 *   GET  /state            estado atual (JSON)
 *   POST /play   (JSON)    {id,title,artist,thumb,url,headers{},chunk,pos,playing}
 *   POST /pause /resume /stop
 *   POST /seek?ms=N
 *   POST /volume?d=+1|-1
 *   GET  /audio            proxy local do áudio (só para o próprio player da TV)
 *
 * Os comandos dados pelo controle da TV (pular, voltar, pausar) viram "cmd" no
 * estado, e o celular lê e aplica — assim os dois ficam sincronizados.
 */
public class LeveMusicService extends Service {

    static final int PORT = 8977;
    static final String NSD_TYPE = "_levetv._tcp.";
    private static final String CHANNEL = "leve_music";
    private static final long DEFAULT_CHUNK = 1L << 20; // 1 MB

    private static volatile LeveMusicService instance;

    static LeveMusicService get() {
        return instance;
    }

    /** Liga o receptor (chamado pelo launcher). */
    static void start(Context ctx) {
        Intent i = new Intent(ctx, LeveMusicService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, LeveMusicService.class));
    }

    // ---------- Faixa atual ----------

    static final class Track {
        String id = "", title = "", artist = "", thumb = "", url = "";
        Map<String, String> headers = new HashMap<>();
        long chunk;          // 0 = pode pedir tudo de uma vez
        int seq;             // muda a cada música (evita misturar com a anterior)
        long total = -1;     // tamanho do arquivo, descoberto no 1º pedaço
        String mime = "audio/webm";
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;
    private NsdManager.RegistrationListener nsdListener;

    private MediaPlayer player;
    private volatile Track track;
    private int trackSeq;
    private boolean prepared;
    private boolean wantPlay;
    private long pendingSeek;
    private volatile String error = "";
    private volatile boolean ended;
    private volatile String phoneName = "";
    private volatile long lastContact;

    // comando vindo do controle da TV, para o celular aplicar
    private volatile String cmd = "";
    private volatile int cmdSeq;
    private volatile long cmdPos;

    private WifiManager.WifiLock wifiLock;

    // ---------- Ciclo de vida ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        goForeground();
        running = true;
        pool.execute(this::serve);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        unregisterNsd();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        releasePlayer();
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.lm_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        Notification n = b.setSmallIcon(R.drawable.icon)
                .setContentTitle(getString(R.string.lm_title))
                .setContentText(getString(R.string.lm_waiting))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, n);
            }
        } catch (Exception ignored) {
            // Sem foreground o receptor ainda funciona enquanto o launcher estiver vivo.
        }
    }

    // ---------- Servidor ----------

    private void serve() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(PORT));
            server = s;
        } catch (IOException e) {
            error = "Porta ocupada";
            return;
        }
        registerNsd();
        while (running) {
            try {
                final Socket c = server.accept();
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(20000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> h = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String body = "";
            String cl = h.get("content-length");
            if (cl != null) {
                int len = Math.min(Integer.parseInt(cl), 256 * 1024);
                byte[] b = new byte[len];
                int r = 0;
                while (r < len) {
                    int n = in.read(b, r, len - r);
                    if (n < 0) break;
                    r += n;
                }
                body = new String(b, 0, r, StandardCharsets.UTF_8);
            }

            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            if (path.equals("/audio")) {
                // só o próprio player da TV pode puxar o áudio
                if (!c.getInetAddress().isLoopbackAddress()) {
                    reply(out, 403, "text/plain", "");
                } else {
                    serveAudio(out, h.get("range"), method.equals("HEAD"));
                }
                return;
            }

            if (!path.equals("/state")) {
                lastContact = System.currentTimeMillis();
                String who = h.get("x-leve-phone");
                if (who != null && !who.isEmpty()) phoneName = who;
            }

            switch (path) {
                case "/state":
                    reply(out, 200, "application/json", stateJson().toString());
                    return;
                case "/play":
                    onPlay(new JSONObject(body));
                    break;
                case "/pause":
                    onMain(() -> setPlaying(false));
                    break;
                case "/resume":
                    onMain(() -> setPlaying(true));
                    break;
                case "/stop":
                    onMain(this::stopTrack);
                    break;
                case "/seek":
                    final long ms = Long.parseLong(param(query, "ms", "0"));
                    onMain(() -> seekLocal(ms));
                    break;
                case "/volume":
                    final int d = Integer.parseInt(param(query, "d", "0"));
                    onMain(() -> volume(d));
                    break;
                default:
                    reply(out, 404, "text/plain", "");
                    return;
            }
            reply(out, 200, "application/json", stateJson().toString());
        } catch (Exception e) {
            // conexão encerrada ou pedido inválido: ignora
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String param(String query, String key, String def) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) break;
        }
        if (ch < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private void onMain(Runnable r) throws InterruptedException {
        final Object lock = new Object();
        final boolean[] done = {false};
        main.post(() -> {
            try {
                r.run();
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 3000;
            while (!done[0] && System.currentTimeMillis() < until) lock.wait(500);
        }
    }

    // ---------- Proxy de áudio ----------

    /**
     * O MediaPlayer pede "bytes=N-" ao 127.0.0.1; aqui buscamos no servidor do YouTube
     * em pedaços (com os cabeçalhos que o celular mandou) e repassamos.
     */
    private void serveAudio(OutputStream out, String range, boolean headOnly) throws IOException {
        Track t = track;
        if (t == null || t.url.isEmpty()) {
            reply(out, 404, "text/plain", "");
            return;
        }
        long start = 0, end = -1;
        if (range != null && range.startsWith("bytes=")) {
            String[] se = range.substring(6).split("-", -1);
            try {
                if (!se[0].isEmpty()) start = Long.parseLong(se[0].trim());
                if (se.length > 1 && !se[1].trim().isEmpty()) end = Long.parseLong(se[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        long chunk = t.chunk > 0 ? t.chunk : 0;

        // 1º pedido: descobre o tamanho total
        long firstEnd = chunk > 0 ? start + chunk - 1 : end;
        if (end >= 0 && firstEnd > end) firstEnd = end;
        HttpURLConnection up = upstream(t, start, firstEnd);
        int code = up.getResponseCode();
        if (code >= 400) {
            up.disconnect();
            error = "O YouTube recusou o áudio (" + code + ")";
            reply(out, 502, "text/plain", "");
            return;
        }
        String ct = up.getHeaderField("Content-Type");
        if (ct != null && !ct.isEmpty()) t.mime = ct;
        long total = t.total;
        String cr = up.getHeaderField("Content-Range");
        if (cr != null && cr.contains("/")) {
            try {
                total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                t.total = total;
            } catch (NumberFormatException ignored) {
            }
        }
        if (code == 200 || total < 0) {
            // servidor ignorou o Range: repassa como veio
            long len = up.getContentLength();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + t.mime + "\r\n"
                    + (len >= 0 ? "Content-Length: " + len + "\r\n" : "")
                    + "Accept-Ranges: none\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (!headOnly) pipe(up, out);
            up.disconnect();
            return;
        }

        long realEnd = end >= 0 ? Math.min(end, total - 1) : total - 1;
        long length = realEnd - start + 1;
        String head = (range != null ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                + "Content-Type: " + t.mime + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + (range != null ? "Content-Range: bytes " + start + "-" + realEnd + "/" + total + "\r\n" : "")
                + "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (headOnly) {
            up.disconnect();
            return;
        }
        long sent = pipe(up, out);
        up.disconnect();

        long pos = start + sent;
        while (pos <= realEnd && t == track && running) {
            long to = chunk > 0 ? Math.min(pos + chunk - 1, realEnd) : realEnd;
            HttpURLConnection next = null;
            for (int attempt = 0; attempt < 3 && next == null; attempt++) {
                try {
                    HttpURLConnection n = upstream(t, pos, to);
                    if (n.getResponseCode() < 400) next = n;
                    else n.disconnect();
                } catch (IOException ignored) {
                }
            }
            if (next == null) {
                error = "A conexão com o YouTube caiu";
                return;
            }
            long got = pipe(next, out);
            next.disconnect();
            if (got <= 0) return;
            pos += got;
        }
        out.flush();
    }

    private static HttpURLConnection upstream(Track t, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(t.url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : t.headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? to : ""));
        return c;
    }

    private static long pipe(HttpURLConnection c, OutputStream out) throws IOException {
        long n = 0;
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[32 * 1024];
            int r;
            while ((r = in.read(b)) > 0) {
                out.write(b, 0, r);
                n += r;
            }
        }
        return n;
    }

    // ---------- Player ----------

    private void onPlay(JSONObject j) throws InterruptedException {
        final Track t = new Track();
        t.id = j.optString("id");
        t.title = j.optString("title");
        t.artist = j.optString("artist");
        t.thumb = j.optString("thumb");
        t.url = j.optString("url");
        t.chunk = j.optLong("chunk", 0);
        if (t.chunk < 0) t.chunk = DEFAULT_CHUNK;
        JSONObject hs = j.optJSONObject("headers");
        if (hs != null) {
            Iterator<String> it = hs.keys();
            while (it.hasNext()) {
                String k = it.next();
                t.headers.put(k, hs.optString(k));
            }
        }
        final long pos = j.optLong("pos", 0);
        final boolean playing = j.optBoolean("playing", true);
        onMain(() -> startTrack(t, pos, playing));
        openNowPlaying();
    }

    private void startTrack(Track t, long pos, boolean playing) {
        releasePlayer();
        t.seq = ++trackSeq;
        track = t;
        error = "";
        ended = false;
        prepared = false;
        wantPlay = playing;
        pendingSeek = pos;

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            }
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                if (pendingSeek > 1000) p.seekTo((int) pendingSeek);
                pendingSeek = 0;
                if (wantPlay) p.start();
            });
            mp.setOnCompletionListener(p -> {
                if (p == player) ended = true;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player && error.isEmpty()) error = "Não deu para tocar (" + what + "/" + extra + ")";
                return true;
            });
            mp.setDataSource("http://127.0.0.1:" + PORT + "/audio?s=" + t.seq);
            mp.prepareAsync();
        } catch (Exception e) {
            error = "Não deu para tocar";
        }
        holdWifi(true);
    }

    private void setPlaying(boolean play) {
        wantPlay = play;
        if (player == null || !prepared) return;
        try {
            if (play && !player.isPlaying()) {
                if (ended) {
                    player.seekTo(0);
                    ended = false;
                }
                player.start();
            } else if (!play && player.isPlaying()) {
                player.pause();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void seekLocal(long ms) {
        if (player == null) return;
        if (!prepared) {
            pendingSeek = ms;
            return;
        }
        try {
            player.seekTo((int) ms);
            ended = false;
        } catch (IllegalStateException ignored) {
        }
    }

    private void stopTrack() {
        releasePlayer();
        track = null;
        error = "";
        holdWifi(false);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
    }

    private void volume(int d) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null || d == 0) return;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    private void holdWifi(boolean on) {
        try {
            if (on && wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "leve:music");
                wifiLock.acquire();
            } else if (!on && wifiLock != null) {
                wifiLock.release();
                wifiLock = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void openNowPlaying() {
        try {
            startActivity(new Intent(this, NowPlayingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {
            // Android pode bloquear abrir telas por trás; a música toca do mesmo jeito.
        }
    }

    // ---------- Usado pela tela "Tocando agora" (thread principal) ----------

    Track current() {
        return track;
    }

    boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    boolean isLoading() {
        return track != null && player != null && !prepared && error.isEmpty();
    }

    long position() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    long duration() {
        try {
            return player != null && prepared ? Math.max(0, player.getDuration()) : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    String error() {
        return error;
    }

    String phoneName() {
        return phoneName;
    }

    boolean phoneSeenRecently() {
        return System.currentTimeMillis() - lastContact < 15000;
    }

    /** Controle da TV: aplica aqui e avisa o celular. */
    void remoteToggle() {
        boolean play = !isPlaying();
        setPlaying(play);
        queueCmd(play ? "play" : "pause", 0);
    }

    void remoteSeekBy(long deltaMs) {
        long to = Math.max(0, position() + deltaMs);
        long dur = duration();
        if (dur > 0) to = Math.min(to, dur - 500);
        seekLocal(to);
        queueCmd("seek", to);
    }

    void remoteNext() {
        queueCmd("next", 0);
    }

    void remotePrev() {
        queueCmd("prev", 0);
    }

    private void queueCmd(String c, long pos) {
        cmdPos = pos;
        cmd = c;
        cmdSeq++;
    }

    // ---------- Estado ----------

    private JSONObject stateJson() throws Exception {
        final JSONObject j = new JSONObject();
        onMain(() -> {
            try {
                Track t = track;
                j.put("v", 1);
                j.put("name", deviceName());
                j.put("id", t == null ? "" : t.id);
                j.put("title", t == null ? "" : t.title);
                j.put("playing", isPlaying());
                j.put("loading", isLoading());
                j.put("ended", ended);
                j.put("pos", position());
                j.put("dur", duration());
                j.put("error", error);
                j.put("cmd", cmd);
                j.put("cmdSeq", cmdSeq);
                j.put("cmdPos", cmdPos);
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    j.put("vol", max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max : 0);
                }
            } catch (Exception ignored) {
            }
        });
        return j;
    }

    static String deviceName() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        return m.isEmpty() ? "Leve TV" : "Leve TV · " + m;
    }

    // ---------- Descoberta na rede (NSD) ----------

    private void registerNsd() {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(deviceName());
            info.setServiceType(NSD_TYPE);
            info.setPort(PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
            };
            NsdManager nsd = (NsdManager) getSystemService(NSD_SERVICE);
            main.post(() -> {
                try {
                    nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void unregisterNsd() {
        try {
            if (nsdListener != null) {
                ((NsdManager) getSystemService(NSD_SERVICE)).unregisterService(nsdListener);
            }
        } catch (Exception ignored) {
        }
        nsdListener = null;
    }

    /** IP da TV na rede local, para conectar manualmente pelo celular. */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
