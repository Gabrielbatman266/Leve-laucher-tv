package com.leve.launchertv;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Ponte entre a página de controle no celular (WebSocket) e a TV.
 * Celular → TV: move, click, long, scroll, back, home, recents, playpause, vol, text, enter.
 * TV → celular: status (mouse ligado?), kb (teclado aberto/fechado + texto atual), toast.
 */
final class RemoteHub {

    interface Sink {
        void send(String text);
    }

    private static final Set<Sink> clients = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    static volatile LeveMouseService mouse;
    static volatile Context app;
    private static volatile String lastKb;

    private RemoteHub() {
    }

    static void add(Sink s) {
        clients.add(s);
        s.send(status());
        String kb = lastKb;
        if (kb != null) s.send(kb);
    }

    static void remove(Sink s) {
        clients.remove(s);
    }

    static boolean hasClients() {
        return !clients.isEmpty();
    }

    static void broadcast(String json) {
        for (Sink s : clients) s.send(json);
    }

    static void broadcastStatus() {
        broadcast(status());
    }

    static String status() {
        try {
            return new JSONObject()
                    .put("t", "status")
                    .put("mouse", mouse != null)
                    .put("name", LeveMusicService.deviceName())
                    .put("api", Build.VERSION.SDK_INT)
                    .toString();
        } catch (Exception e) {
            return "{\"t\":\"status\"}";
        }
    }

    static void keyboard(boolean open, String text, String hint) {
        try {
            JSONObject j = new JSONObject().put("t", "kb").put("open", open);
            if (open) {
                j.put("text", text == null ? "" : text);
                j.put("hint", hint == null ? "" : hint);
            }
            String s = j.toString();
            lastKb = open ? s : null;
            broadcast(s);
        } catch (Exception ignored) {
        }
    }

    static void toast(String msg) {
        try {
            broadcast(new JSONObject().put("t", "toast").put("msg", msg).toString());
        } catch (Exception ignored) {
        }
    }

    /** Mensagem vinda do celular (thread do WebSocket). */
    static void onMessage(String text) {
        final JSONObject j;
        try {
            j = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        final String t = j.optString("t");
        switch (t) {
            case "ping":
                return;
            case "vol":
                volume(j.optInt("d"));
                return;
            case "playpause":
                main.post(RemoteHub::playPause);
                return;
        }
        main.post(() -> {
          try {
            LeveMouseService m = mouse;
            if (m == null) {
                broadcastStatus();
                toast("Ative o Leve Mouse na TV (Opções do launcher)");
                return;
            }
            switch (t) {
                case "move":
                    m.move((float) j.optDouble("dx"), (float) j.optDouble("dy"));
                    break;
                case "click":
                    m.click(false);
                    break;
                case "long":
                    m.click(true);
                    break;
                case "scroll":
                    m.scroll((float) j.optDouble("dx"), (float) j.optDouble("dy"));
                    break;
                case "back":
                    m.global(LeveMouseService.GLOBAL_ACTION_BACK);
                    break;
                case "home":
                    m.global(LeveMouseService.GLOBAL_ACTION_HOME);
                    break;
                case "recents":
                    m.global(LeveMouseService.GLOBAL_ACTION_RECENTS);
                    break;
                case "text":
                    m.setText(j.optString("v"));
                    break;
                case "enter":
                    m.enter();
                    break;
            }
          } catch (Throwable err) {
            LeveApp.log("RemoteHub." + t, err);
            toast("Erro na TV: " + err.getClass().getSimpleName());
          }
        });
    }

    private static void volume(int d) {
        Context c = app;
        if (c == null || d == 0) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        }
    }

    private static void playPause() {
        // Se o Leve Music TV está tocando, pausa ele; senão manda a tecla para o app aberto
        LeveMusicService ms = LeveMusicService.get();
        if (ms != null && ms.current() != null) {
            ms.remoteToggle();
            return;
        }
        Context c = app;
        if (c == null) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
    }
}
