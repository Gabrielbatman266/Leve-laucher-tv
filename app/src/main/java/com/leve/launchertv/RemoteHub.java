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
        try {
            s.send(status()); // chamado na thread do WebSocket: pode usar a rede
        } catch (Throwable ignored) {
        }
        LeveMouseService m = mouse;
        if (m != null) main.post(m::recheckKeyboard); // já tem campo aberto? avisa o celular
        main.post(RemoteHub::refreshImeBar);
    }

    static void remove(Sink s) {
        clients.remove(s);
        main.post(RemoteHub::refreshImeBar);
    }

    private static void refreshImeBar() {
        LeveKeyboard k = LeveKeyboard.instance;
        if (k != null) k.updateBar();
    }

    /** Teclas de mídia (play, avançar, voltar) para o app que está tocando. */
    static void mediaKey(int code) {
        Context c = app;
        if (c == null) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private static int keyCode(String k) {
        switch (k) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "menu": return KeyEvent.KEYCODE_MENU;
            default: return KeyEvent.KEYCODE_DPAD_CENTER; // ok / longok
        }
    }

    /** Comandos que o Leve Teclado faz melhor (teclas e texto de verdade). Retorna true se tratou. */
    private static boolean viaKeyboard(String t, JSONObject j) {
        LeveKeyboard kb = LeveKeyboard.active();
        if (kb == null) return false;
        switch (t) {
            case "key": {
                String k = j.optString("k");
                return kb.key(keyCode(k), k.equals("longok"));
            }
            case "text":
                return kb.setText(j.optString("v"));
            case "enter":
                return kb.enter();
            default:
                return false;
        }
    }

    static boolean hasClients() {
        return !clients.isEmpty();
    }

    /** Envio para o celular sempre fora da thread principal (Android proíbe rede nela). */
    private static final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    static void broadcast(final String json) {
        if (clients.isEmpty()) return;
        try {
            sender.execute(() -> {
                for (Sink s : clients) {
                    try {
                        s.send(json);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    static void broadcastStatus() {
        broadcast(status());
    }

    static String status() {
        try {
            return new JSONObject()
                    .put("t", "status")
                    .put("mouse", mouse != null)
                    .put("ime", LeveKeyboard.instance != null)
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
            case "move": {
                // não passa pela fila da tela: soma e aplica em lote (mais leve)
                LeveMouseService m = mouse;
                if (m != null) m.queueMove((float) j.optDouble("dx"), (float) j.optDouble("dy"));
                else broadcastStatus();
                return;
            }
            case "apps":
                sendApps();
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
            if (viaKeyboard(t, j)) return;
            LeveMouseService m = mouse;
            if (m == null && t.equals("key")) {
                toast("Ative o Leve Teclado ou o Leve Mouse na TV (Opções do launcher)");
                return;
            }
            if (m == null) {
                broadcastStatus();
                toast("Ative o Leve Mouse na TV (Opções do launcher)");
                return;
            }
            switch (t) {
                case "key":
                    m.dpad(j.optString("k"));
                    break;
                case "dragStart":
                    m.dragStart();
                    break;
                case "dragEnd":
                    m.dragEnd();
                    break;
                case "open":
                    if (!m.openApp(j.optString("p"))) toast("Não deu para abrir esse app");
                    break;
                case "settings":
                    m.openSettings();
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

    /** Lista de apps da TV para abrir pelo celular (principais primeiro). */
    private static void sendApps() {
        Context c = app;
        if (c == null) return;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            java.util.Map<String, String> apps = new java.util.HashMap<>();
            for (String cat : new String[]{android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,
                    android.content.Intent.CATEGORY_LAUNCHER}) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                    String p = ri.activityInfo.packageName;
                    if (!apps.containsKey(p)) {
                        CharSequence l = ri.loadLabel(pm);
                        apps.put(p, l == null ? p : l.toString().trim());
                    }
                }
            }
            apps.remove(c.getPackageName());
            String raw = c.getSharedPreferences("leve", Context.MODE_PRIVATE).getString("pinned", "");
            java.util.List<String> pinned = new java.util.ArrayList<>();
            for (String p : raw.split("\\|")) if (!p.isEmpty() && apps.containsKey(p)) pinned.add(p);
            java.util.List<String> rest = new java.util.ArrayList<>(apps.keySet());
            rest.removeAll(pinned);
            final java.text.Collator col = java.text.Collator.getInstance();
            java.util.Collections.sort(rest, (a, b) -> col.compare(apps.get(a), apps.get(b)));

            org.json.JSONArray arr = new org.json.JSONArray();
            for (String p : pinned) arr.put(new JSONObject().put("p", p).put("n", apps.get(p)).put("main", true));
            for (String p : rest) arr.put(new JSONObject().put("p", p).put("n", apps.get(p)));
            broadcast(new JSONObject().put("t", "apps").put("list", arr).toString());
        } catch (Exception e) {
            LeveApp.log("RemoteHub.apps", e);
        }
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
