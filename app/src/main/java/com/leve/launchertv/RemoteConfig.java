package com.leve.launchertv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * Configuração ao vivo: o launcher lê config/leve.json do GitHub e aplica sem reinstalar.
 *
 * Campos (todos opcionais):
 *   rev        número; sobe quando "pin"/"hide" devem ser aplicados de novo
 *   title      texto do canto de cima (padrão "LEVE")
 *   message    aviso curto ao lado do título
 *   accent     cor da seleção, ex. "#30C0FF"
 *   sky        3 cores do fundo, de cima para baixo
 *   stars      true/false (só vale se você nunca mexeu nas estrelas pela TV)
 *   clockSize  tamanho do relógio (sp)
 *   pin        pacotes para colocar nos principais (uma vez por rev)
 *   hide       pacotes para ocultar (uma vez por rev)
 *   rename     { "pacote": "Nome que aparece" }
 *   shortcuts  [ { "label": "...", "url": "https://..." } ou { "label": "...", "package": "..." } ]
 */
final class RemoteConfig {

    static final String URL = "https://raw.githubusercontent.com/" + Updater.REPO + "/main/config/leve.json";
    private static final String KEY_JSON = "rc_json", KEY_ETAG = "rc_etag", KEY_REV = "rc_rev_applied";

    private RemoteConfig() {
    }

    static JSONObject get(Context c) {
        try {
            return new JSONObject(prefs(c).getString(KEY_JSON, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("leve", Context.MODE_PRIVATE);
    }

    /** Busca a versão mais nova (em segundo plano). Retorna true se mudou algo. */
    static boolean fetch(Context c) {
        HttpURLConnection con = null;
        try {
            SharedPreferences p = prefs(c);
            // "?t=" evita o cache de 5 minutos do GitHub
            con = ShareLink.open(URL + "?t=" + (System.currentTimeMillis() / 30000));
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "LeveLauncherTV");
            String etag = p.getString(KEY_ETAG, null);
            if (etag != null) con.setRequestProperty("If-None-Match", etag);
            int code = con.getResponseCode();
            if (code == 304 || code != 200) return false;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            }
            String body = buf.toString("UTF-8");
            new JSONObject(body); // só aceita JSON válido
            boolean changed = !body.equals(p.getString(KEY_JSON, ""));
            p.edit().putString(KEY_JSON, body).putString(KEY_ETAG, con.getHeaderField("ETag")).apply();
            return changed;
        } catch (Throwable t) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---------- Aplicar ----------

    /** pin/hide: aplica uma vez por "rev", para não brigar com o que você muda na TV depois. */
    static void applyOnce(Context c, java.util.List<String> pinned, java.util.List<String> hidden, Runnable save) {
        JSONObject j = get(c);
        int rev = j.optInt("rev", 0);
        SharedPreferences p = prefs(c);
        if (rev == 0 || rev == p.getInt(KEY_REV, 0)) return;
        JSONArray pin = j.optJSONArray("pin");
        if (pin != null) {
            for (int i = pin.length() - 1; i >= 0; i--) {
                String pkg = pin.optString(i);
                if (pkg.isEmpty()) continue;
                pinned.remove(pkg);
                pinned.add(0, pkg);
                hidden.remove(pkg);
            }
        }
        JSONArray hide = j.optJSONArray("hide");
        if (hide != null) {
            for (int i = 0; i < hide.length(); i++) {
                String pkg = hide.optString(i);
                if (!pkg.isEmpty() && !hidden.contains(pkg)) {
                    hidden.add(pkg);
                    pinned.remove(pkg);
                }
            }
        }
        p.edit().putInt(KEY_REV, rev).apply();
        save.run();
    }

    static void rename(Context c, java.util.Map<String, String> apps) {
        JSONObject r = get(c).optJSONObject("rename");
        if (r == null) return;
        java.util.Iterator<String> it = r.keys();
        while (it.hasNext()) {
            String pkg = it.next();
            if (apps.containsKey(pkg)) apps.put(pkg, r.optString(pkg, apps.get(pkg)));
        }
    }

    static int color(String s, int def) {
        try {
            return s == null || s.isEmpty() ? def : Color.parseColor(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
