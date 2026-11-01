package com.leve.launchertv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Leve Launcher TV — tela inicial leve, com cara de menu do PS2.
 * Principais em destaque, ícones pequenos, ordem do seu jeito.
 */
public class MainActivity extends Activity {

    // Preferências
    private static final String PREFS = "leve";
    private static final String KEY_PINNED = "pinned";   // principais, na ordem escolhida
    private static final String KEY_ORDER = "order";     // ordem personalizada dos outros apps
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_STARS = "stars";
    private static final String KEY_FIRST = "first_done";
    private static final String KEY_ST_DONE = "st_pinned";
    private static final String KEY_LT_DONE = "lt_pinned";
    private static final String KEY_LT_CHECK = "lt_last_check", KEY_LT_AVAIL = "lt_available";
    private static final String KEY_MUSIC = "music_on";
    // Aparência e acessibilidade
    private static final String KEY_THEME = "theme";        // "amoled" (padrão) ou "ps2"
    private static final String KEY_TEXT = "text_scale";    // 0 normal, 1 grande, 2 muito grande
    private static final String KEY_HL = "strong_select";   // borda amarela grossa
    private static final String KEY_TTS = "speak";          // fala o nome do que está selecionado
    private static final String KEY_ICONS = "icons_on";
    private static final float[] TEXT_SCALES = {1f, 1.25f, 1.5f};

    // Tipos de linha
    static final int T_SECTION = 0, T_BIG = 1, T_APP = 2, T_ACTION = 3, T_BIG_ACTION = 4;

    // Ações do menu "Opções"
    private static final String A_SETTINGS = "settings", A_HOME = "home", A_STARS = "stars",
            A_ALPHA = "alpha", A_HIDDEN = "hidden", A_UPDATE = "update", A_ST = "smarttube",
            A_NOW = "now_playing", A_MUSIC = "music", A_MOUSE = "mouse", A_IME = "ime",
            A_GUARD = "guard", A_NET = "net", A_NEW = "new_version", A_SC = "sc:",
            A_LT = "levetube",
            A_TEXT = "text", A_HL = "highlight", A_TTS = "tts", A_ICONS = "icons", A_THEME = "theme",
            A_TV_ACCESS = "tv_access", A_RMOUSE = "remote_mouse", A_SOUND = "sound";

    private static final int REQ_HOME = 7;

    private final List<Row> rows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private final Collator collator = Collator.getInstance();
    private SharedPreferences prefs;
    private ListView list;
    private StarsView stars;
    private TextView hint;
    private IconCache icons;
    private Updater updater;
    private boolean showHidden = false;
    private Set<String> lastPackages = new HashSet<>();

    private SoundFx fx;
    private boolean ownSounds;
    private boolean firstSelect = true;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    private float textScale = 1f;
    private boolean showIcons = true;

    private int moveIndex = -1;          // linha sendo movida (-1 = não está movendo)
    private boolean swallowKeyUp = false;
    private String shownTrack = null;      // música mostrada no topo (para atualizar a linha)

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable musicWatch = new Runnable() {
        @Override
        public void run() {
            LeveMusicService s = LeveMusicService.get();
            LeveMusicService.Track t = s == null ? null : s.current();
            String now = t == null ? null : t.id + t.title;
            if (moveIndex < 0 && (now == null ? shownTrack != null : !now.equals(shownTrack))) {
                int pos = list.getSelectedItemPosition();
                String sel = pos >= 0 && pos < rows.size() ? rows.get(pos).id : null;
                reload(sel);
            }
            ui.postDelayed(this, 2000);
        }
    };

    static final class Row {
        final int type;
        final String id;      // pacote do app ou id da ação
        final String label;
        final boolean hidden;

        Row(int type, String id, String label, boolean hidden) {
            this.type = type;
            this.id = id;
            this.label = label;
            this.hidden = hidden;
        }

        boolean isApp() {
            return type == T_BIG || type == T_APP;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        icons = new IconCache(this);
        updater = new Updater(this);
        fx = new SoundFx(this);

        stars = findViewById(R.id.stars);
        hint = findViewById(R.id.hint);
        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> click(rows.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showOptions(pos);
            return true;
        });
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < rows.size()) speak(rows.get(pos).label);
                growSelected(v);
                if (firstSelect) firstSelect = false;
                else if (android.os.SystemClock.uptimeMillis() > quietUntil) fx.play(SoundFx.MOVE);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
                growSelected(null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        tts = null;
        fx.release();
        super.onDestroy();
    }

    // ================= Acessibilidade =================

    /** Liga a voz só quando a opção está ativa (não gasta nada desligada). */
    private void setupTts(boolean on) {
        if (!on) {
            if (tts != null) tts.shutdown();
            tts = null;
            ttsReady = false;
            return;
        }
        if (tts != null) return;
        tts = new android.speech.tts.TextToSpeech(getApplicationContext(), status -> {
            if (tts == null) return;
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("pt", "BR"));
                if (r < 0) tts.setLanguage(Locale.getDefault());
                ttsReady = true;
            } else {
                Toast.makeText(this, R.string.tts_fail, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null) return;
        String clean = text.replace("★", "").replace("✓", "").replace("·", ",").replace("♪", "").trim();
        tts.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "leve");
    }

    /** Mouse no controle remoto: aperta a tecla que vai ligar/desligar o cursor. */
    private void chooseRemoteMouseKey() {
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.rm_title)
                .setMessage(R.string.rm_learn)
                .setPositiveButton(R.string.rm_use_back, (x, w) -> saveRemoteKey(LeveMouseService.HOLD_BACK))
                .setNeutralButton(R.string.rm_disable, (x, w) -> saveRemoteKey(0))
                .setNegativeButton(R.string.upd_later, null)
                .create();
        d.setOnKeyListener((dlg, code, ev) -> {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
                case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_BUTTON_A:
                    return false;
            }
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                saveRemoteKey(code);
                d.dismiss();
            }
            return true;
        });
        d.show();
    }

    private void saveRemoteKey(int code) {
        prefs.edit().putInt(LeveMouseService.PREF_RM_KEY, code).apply();
        LeveMouseService m = RemoteHub.mouse;
        if (m != null) m.refreshRemoteMouse();
        if (code == 0) {
            Toast.makeText(this, R.string.rm_disabled_toast, Toast.LENGTH_SHORT).show();
        } else if (m == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.mouse_name)
                    .setMessage(R.string.rm_need_service)
                    .setPositiveButton(R.string.mouse_open_settings, (x, w) -> {
                        HomeGuard.pause();
                        Shortcuts.open(this, Shortcuts.ACCESSIBILITY);
                    })
                    .setNegativeButton(R.string.upd_later, null)
                    .show();
        } else {
            Toast.makeText(this, getString(R.string.rm_saved, LeveMouseService.keyName(code)), Toast.LENGTH_LONG).show();
        }
        reload(A_RMOUSE);
    }

    /** Esquerda/direita pulam de seção (Principais → Apps → Acessibilidade…). */
    private long quietUntil;   // seleção mudada pelo próprio Leve (pulo/recarregar): sem som de "mover"

    /** Som próprio no lugar do clique do sistema (ou o do sistema, se escolhido). */
    private void applySoundStyle() {
        String st = SoundFx.style(this);
        ownSounds = !st.equals("system");
        fx.prepare(st);
        list.setSoundEffectsEnabled(!ownSounds);
    }

    private boolean jumpSection(int dir) {
        int pos = list.getSelectedItemPosition();
        if (pos < 0) return false;
        int i = pos;
        // acha o cabeçalho da seção atual
        while (i > 0 && rows.get(i).type != T_SECTION) i--;
        int target = -1;
        if (dir > 0) {
            for (int j = pos + 1; j < rows.size(); j++) {
                if (rows.get(j).type == T_SECTION && j + 1 < rows.size()) { target = j + 1; break; }
            }
        } else {
            int prevHeader = -1;
            for (int j = i - 1; j >= 0; j--) if (rows.get(j).type == T_SECTION) { prevHeader = j; break; }
            target = prevHeader >= 0 ? prevHeader + 1 : firstSelectable();
            if (prevHeader < 0 && target == pos) return true;
        }
        if (target < 0 || target >= rows.size()) return true;
        quietUntil = android.os.SystemClock.uptimeMillis() + 250;
        list.setSelection(target);
        fx.play(SoundFx.SIDE);
        // a voz fala o nome da seção junto
        int h = target - 1;
        if (h >= 0 && rows.get(h).type == T_SECTION) speak(rows.get(h).label + ". " + rows.get(target).label);
        return true;
    }

    private Set<String> adaptedNow = new HashSet<>();
    private View grown;

    /** Item selecionado cresce um pouquinho (animação curta, sem custo). */
    private void growSelected(View v) {
        if (grown != null && grown != v) grown.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
        grown = v;
        if (v == null) return;
        v.setPivotX(0);
        v.setPivotY(v.getHeight() / 2f);
        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(90).start();
    }

    private String theme() {
        return prefs.getString(KEY_THEME, "amoled");
    }

    private boolean amoled() {
        return "amoled".equals(theme());
    }

    @Override
    protected void onResume() {
        super.onResume();
        disableFakeHomeIfUnused();
        if (prefs.getBoolean(KEY_MUSIC, true)) LeveMusicService.start(this);
        textScale = TEXT_SCALES[Math.max(0, Math.min(2, prefs.getInt(KEY_TEXT, 0)))];
        showIcons = prefs.getBoolean(KEY_ICONS, true);
        setupTts(prefs.getBoolean(KEY_TTS, false));
        applySoundStyle();
        stars.setTheme(theme());
        stars.setAnimate(prefs.getBoolean(KEY_STARS, !amoled()));
        stars.setRunning(true);
        reload(null);
        list.requestFocus();
        ui.postDelayed(musicWatch, 2000);
        applyLook();
        ui.post(liveConfig);
        checkUpdateMaybe();
        checkLeveTubeMaybe();
        showLastError();
    }

    // ================= Configuração ao vivo + aviso de versão nova =================

    private volatile boolean fetching;

    /** Relê config/leve.json do GitHub a cada minuto enquanto a tela inicial está aberta. */
    private final Runnable liveConfig = new Runnable() {
        @Override
        public void run() {
            ui.removeCallbacks(this);
            if (!fetching) {
                fetching = true;
                new Thread(() -> {
                    boolean changed = RemoteConfig.fetch(MainActivity.this);
                    fetching = false;
                    if (changed) ui.post(() -> {
                        applyLook();
                        int pos = list.getSelectedItemPosition();
                        reload(pos >= 0 && pos < rows.size() ? rows.get(pos).id : null);
                    });
                }).start();
            }
            ui.postDelayed(this, 60_000);
        }
    };

    /** Aparência vinda da configuração (título, aviso, cores, relógio). */
    private void applyLook() {
        org.json.JSONObject j = RemoteConfig.get(this);
        if (moveIndex < 0) {
            String title = j.optString("title", getString(R.string.title));
            String msg = j.optString("message", "");
            hint.setText(msg.isEmpty() ? title : title + "   ·   " + msg);
            hint.setTextColor(msg.isEmpty() ? getResources().getColor(R.color.dim) : getResources().getColor(R.color.move_line));
        }
        org.json.JSONArray sky = j.optJSONArray("sky");
        if (sky != null && sky.length() == 3 && "ps2".equals(theme())) {
            stars.setSky(new int[]{
                    RemoteConfig.color(sky.optString(0), 0xFF01020A),
                    RemoteConfig.color(sky.optString(1), 0xFF040B2A),
                    RemoteConfig.color(sky.optString(2), 0xFF0A1A4A)});
        }
        if (j.has("stars") && !prefs.getBoolean("stars_user_set", false) && !amoled()) {
            stars.setAnimate(j.optBoolean("stars", true));
        }
        int clock = j.optInt("clockSize", 0);
        if (clock >= 30 && clock <= 160) {
            android.view.View root = findViewById(android.R.id.content);
            setClockSize(root, clock);
        }
        if (moveIndex < 0) list.setSelector(selectorDrawable());
    }

    private void setClockSize(android.view.View v, int sp) {
        if (v instanceof android.widget.TextClock && ((android.widget.TextView) v).getTextSize() > 40 * getResources().getDisplayMetrics().density) {
            ((android.widget.TextView) v).setTextSize(sp);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setClockSize(g.getChildAt(i), sp);
        }
    }

    /** Seleção na cor "accent" da configuração (ou a azul padrão). */
    private android.graphics.drawable.Drawable selectorDrawable() {
        String a = RemoteConfig.get(this).optString("accent", "");
        boolean strong = prefs.getBoolean(KEY_HL, false);
        if (a.isEmpty() && !strong && amoled()) return getResources().getDrawable(R.drawable.selector);
        int c = strong ? 0xFFFFD400 : a.isEmpty() ? StarsView.accentFor(theme()) : RemoteConfig.color(a, 0xFF30C0FF);
        float dp = getResources().getDisplayMetrics().density;
        if (strong) {
            android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
            on.setColor(0x33FFD400);
            on.setCornerRadius(12 * dp);
            on.setStroke((int) (4 * dp), c);
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            s.addState(new int[]{android.R.attr.state_pressed}, on);
            s.addState(new int[]{android.R.attr.state_selected}, on);
            s.addState(new int[]{android.R.attr.state_focused}, on);
            s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
            return s;
        }
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{(c & 0x00FFFFFF) | 0x55000000, (c & 0x00FFFFFF) | 0x22000000});
        on.setCornerRadius(12 * dp);
        on.setStroke((int) dp, (c & 0x00FFFFFF) | 0xAA000000);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return s;
    }

    private static final String KEY_UPD_CHECK = "upd_last_check", KEY_UPD_AVAIL = "upd_available";

    /** Confere versão nova no máximo a cada 3 horas, em segundo plano. */
    private void checkUpdateMaybe() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_UPD_CHECK, 0) < 3 * 3600 * 1000L) return;
        prefs.edit().putLong(KEY_UPD_CHECK, now).apply();
        new Thread(() -> {
            int latest = Updater.latestAvailable();
            if (latest <= 0) return;
            int mine = Updater.installedVersion(this);
            prefs.edit().putInt(KEY_UPD_AVAIL, latest > mine ? latest : 0).apply();
            if (latest > mine) ui.post(() -> {
                Toast.makeText(this, getString(R.string.upd_new_toast, "1.0." + latest), Toast.LENGTH_LONG).show();
                reload(null);
            });
        }).start();
    }

    /** Confere versão nova do Leve Tube no máximo a cada 3 horas, em segundo plano. */
    private void checkLeveTubeMaybe() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LT_CHECK, 0) < 3 * 3600 * 1000L) return;
        prefs.edit().putLong(KEY_LT_CHECK, now).apply();
        new Thread(() -> {
            int latest = Updater.latestLeveTube();
            if (latest <= 0) return;
            int mine = Updater.installedLeveTube(this);
            prefs.edit().putInt(KEY_LT_AVAIL, mine > 0 && latest > mine ? latest : 0).apply();
            if (mine > 0 && latest > mine) ui.post(() -> reload(null));
        }).start();
    }

    /** Se o Leve fechou sozinho, mostra o motivo uma vez (para mandar print ao Claude). */
    private boolean errorShown;

    private void showLastError() {
        if (errorShown) return;
        String err = LeveApp.read();
        if (err == null || err.trim().isEmpty()) return;
        errorShown = true;
        String ip = LeveMusicService.localIp();
        String tip = ip.isEmpty() ? "" : "\n\nNo celular também dá para ver em: " + ip + ":" + LeveMusicService.PORT + "/erro";
        String shown = err.length() > 1800 ? err.substring(0, 1800) + "\n…" : err;
        new AlertDialog.Builder(this)
                .setTitle(R.string.err_title)
                .setMessage(getString(R.string.err_msg) + tip + "\n\n" + shown)
                .setPositiveButton(R.string.err_ok, (d, w) -> LeveApp.clear())
                .setNegativeButton(R.string.err_keep, null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stars.setRunning(false);
        ui.removeCallbacks(musicWatch);
        ui.removeCallbacks(liveConfig);
        if (moveIndex >= 0) endMove();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_HOME) reload(null);
    }

    // ================= Montar a lista =================

    private void reload(String selectId) {
        PackageManager pm = getPackageManager();
        Map<String, String> apps = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps);
        collect(pm, Intent.CATEGORY_LAUNCHER, apps);
        apps.remove(getPackageName());

        if (!apps.keySet().equals(lastPackages)) {
            icons.clear();
            HomeGuard.forget();
            lastPackages = new HashSet<>(apps.keySet());
        }
        firstRun(apps);
        autoAdaptPhoneApps(apps);
        boolean hasLeveTube = findLeveTube(apps);
        String smartTube = hasLeveTube ? null : findSmartTube(apps);
        RemoteConfig.rename(this, apps);

        List<String> pinned = readList(KEY_PINNED);
        final List<String> order = readList(KEY_ORDER);
        final List<String> hiddenList = readList(KEY_HIDDEN);
        final List<String> pinnedRef = pinned;
        RemoteConfig.applyOnce(this, pinned, hiddenList, () -> {
            writeList(KEY_PINNED, pinnedRef);
            writeList(KEY_HIDDEN, hiddenList);
        });
        Set<String> hidden = new HashSet<>(hiddenList);

        quietUntil = android.os.SystemClock.uptimeMillis() + 250;
        rows.clear();
        adaptedNow = AppAdapt.list(this);

        // Principais (grandes)
        List<Row> main = new ArrayList<>();
        for (String pkg : pinned) {
            String label = apps.get(pkg);
            if (label != null) main.add(new Row(T_BIG, pkg, label, false));
        }
        // Leve Tube: oferece instalar se não tem; avisa quando sai versão nova
        boolean offerSmartTube = false;
        String ltRow = null;
        if (!hasLeveTube) {
            ltRow = getString(R.string.lt_install);
        } else {
            int ltNew = prefs.getInt(KEY_LT_AVAIL, 0);
            if (ltNew > Updater.installedLeveTube(this)) ltRow = getString(R.string.lt_update, ltNew);
        }

        // Música do celular tocando: aparece no topo de tudo
        LeveMusicService ms = LeveMusicService.get();
        LeveMusicService.Track playing = ms == null ? null : ms.current();
        shownTrack = playing == null ? null : playing.id + playing.title;
        if (playing != null) {
            String what = playing.title + (playing.artist.isEmpty() ? "" : "  —  " + playing.artist);
            main.add(0, new Row(T_BIG_ACTION, A_NOW, getString(R.string.lm_now, what), false));
        }

        // Versão nova disponível: primeira linha
        int avail = prefs.getInt(KEY_UPD_AVAIL, 0);
        if (avail > Updater.installedVersion(this)) {
            main.add(0, new Row(T_BIG_ACTION, A_NEW, getString(R.string.upd_new_row, "1.0." + avail), false));
        }

        // Atalhos da configuração ao vivo (sites ou apps)
        org.json.JSONArray sc = RemoteConfig.get(this).optJSONArray("shortcuts");
        if (sc != null) {
            for (int i = 0; i < sc.length(); i++) {
                org.json.JSONObject s = sc.optJSONObject(i);
                if (s == null || s.optString("label").isEmpty()) continue;
                main.add(new Row(T_BIG_ACTION, A_SC + i, s.optString("label"), false));
            }
        }

        if (!main.isEmpty() || offerSmartTube || ltRow != null) {
            rows.add(new Row(T_SECTION, "", getString(R.string.sec_main), false));
            rows.addAll(main);
            if (ltRow != null) {
                rows.add(new Row(T_BIG_ACTION, A_LT, ltRow, false));
            }
            if (offerSmartTube) {
                rows.add(new Row(T_BIG_ACTION, A_ST, getString(R.string.st_install), false));
            }
        }

        // Outros apps: ordem personalizada primeiro, novos em ordem alfabética
        List<Row> rest = new ArrayList<>();
        int hiddenCount = 0;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String pkg = e.getKey();
            if (pinned.contains(pkg)) continue;
            boolean isHidden = hidden.contains(pkg);
            if (isHidden) hiddenCount++;
            if (isHidden && !showHidden) continue;
            rest.add(new Row(T_APP, pkg, e.getValue(), isHidden));
        }
        Collections.sort(rest, (a, b) -> {
            int ia = order.indexOf(a.id), ib = order.indexOf(b.id);
            if (ia >= 0 && ib >= 0) return ia - ib;
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
            return collator.compare(a.label, b.label);
        });
        if (!rest.isEmpty()) {
            rows.add(new Row(T_SECTION, "", getString(R.string.sec_apps), false));
            rows.addAll(rest);
        }

        // ---- Acessibilidade
        rows.add(new Row(T_SECTION, "", getString(R.string.sec_access), false));
        int ts = Math.max(0, Math.min(2, prefs.getInt(KEY_TEXT, 0)));
        rows.add(new Row(T_ACTION, A_TEXT, getString(R.string.text_row,
                getString(ts == 0 ? R.string.text_1 : ts == 1 ? R.string.text_2 : R.string.text_3)), false));
        rows.add(new Row(T_ACTION, A_HL, getString(prefs.getBoolean(KEY_HL, false) ? R.string.hl_on : R.string.hl_off), false));
        rows.add(new Row(T_ACTION, A_TTS, getString(prefs.getBoolean(KEY_TTS, false) ? R.string.tts_on : R.string.tts_off), false));
        rows.add(new Row(T_ACTION, A_TV_ACCESS, getString(R.string.tv_access), false));

        // ---- Aparência
        rows.add(new Row(T_SECTION, "", getString(R.string.sec_look), false));
        rows.add(new Row(T_ACTION, A_THEME, getString(R.string.theme_row, StarsView.nameFor(theme())), false));
        rows.add(new Row(T_ACTION, A_STARS,
                getString(prefs.getBoolean(KEY_STARS, !amoled()) ? R.string.stars_on : R.string.stars_off), false));
        rows.add(new Row(T_ACTION, A_ICONS, getString(showIcons ? R.string.icons_on : R.string.icons_off), false));
        String st = SoundFx.style(this);
        rows.add(new Row(T_ACTION, A_SOUND, getString(R.string.sound_row, getString(
                st.equals("ps2") ? R.string.sound_ps2 : st.equals("off") ? R.string.sound_off
                        : st.equals("system") ? R.string.sound_system : R.string.sound_soft)), false));
        if (!order.isEmpty()) {
            rows.add(new Row(T_ACTION, A_ALPHA, getString(R.string.alpha), false));
        }
        if (hiddenCount > 0) {
            rows.add(new Row(T_ACTION, A_HIDDEN, showHidden
                    ? getString(R.string.hide_hidden)
                    : getString(R.string.show_hidden, hiddenCount), false));
        }

        // ---- Celular como controle
        rows.add(new Row(T_SECTION, "", getString(R.string.sec_phone), false));
        boolean musicOn = prefs.getBoolean(KEY_MUSIC, true);
        String ip = LeveMusicService.localIp();
        rows.add(new Row(T_ACTION, A_MUSIC, !musicOn ? getString(R.string.lm_off)
                : ip.isEmpty() ? getString(R.string.lm_on_noip) : getString(R.string.lm_on, ip), false));
        boolean mouseOn = RemoteHub.mouse != null;
        rows.add(new Row(T_ACTION, A_MOUSE, !mouseOn ? getString(R.string.mouse_off)
                : ip.isEmpty() ? getString(R.string.mouse_on_noip)
                : getString(R.string.mouse_on, ip + ":" + LeveMusicService.PORT), false));
        int rk = prefs.getInt(LeveMouseService.PREF_RM_KEY, LeveMouseService.HOLD_BACK);
        rows.add(new Row(T_ACTION, A_RMOUSE, rk == 0 ? getString(R.string.rm_off)
                : RemoteHub.mouse == null ? getString(R.string.rm_need, LeveMouseService.keyName(rk))
                : getString(R.string.rm_on, LeveMouseService.keyName(rk)), false));
        int ime = imeState();
        rows.add(new Row(T_ACTION, A_IME, getString(ime == 3 ? R.string.ime_tv_on
                : ime == 2 ? R.string.ime_on : ime == 1 ? R.string.ime_enabled : R.string.ime_off), false));

        // ---- Sistema
        rows.add(new Row(T_SECTION, "", getString(R.string.sec_opts), false));
        rows.add(new Row(T_ACTION, A_NET, getString(R.string.net_row), false));
        rows.add(new Row(T_ACTION, A_HOME,
                getString(isDefaultHome() ? R.string.home_yes : R.string.home_no), false));
        boolean guard = HomeGuard.enabled(this);
        rows.add(new Row(T_ACTION, A_GUARD, getString(!guard ? R.string.guard_off
                : RemoteHub.mouse == null ? R.string.guard_need_mouse : R.string.guard_on), false));
        rows.add(new Row(T_ACTION, A_SETTINGS, getString(R.string.settings), false));
        rows.add(new Row(T_ACTION, A_UPDATE,
                getString(R.string.update_row, "1.0." + Updater.installedVersion(this)), false));

        adapter.notifyDataSetChanged();

        // Mantém a seleção onde estava (ou no primeiro app)
        int target = -1;
        if (selectId != null) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(selectId)) target = i;
        }
        if (target < 0 && list.getSelectedItemPosition() < 0) target = firstSelectable();
        if (target >= 0) list.setSelection(target);
    }

    private int firstSelectable() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).type != T_SECTION) return i;
        return -1;
    }

    private static void collect(PackageManager pm, String category, Map<String, String> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!out.containsKey(pkg)) {
                CharSequence label = ri.loadLabel(pm);
                out.put(pkg, label == null ? pkg : label.toString().trim());
            }
        }
    }

    /** Leve Tube instalado: entra nos principais uma vez (depois você tira se quiser). */
    private boolean findLeveTube(Map<String, String> apps) {
        if (!apps.containsKey(Updater.LT_PKG)) return false;
        apps.put(Updater.LT_PKG, getString(R.string.lt_name));
        if (!prefs.getBoolean(KEY_LT_DONE, false)) {
            List<String> pinned = readList(KEY_PINNED);
            if (!pinned.contains(Updater.LT_PKG)) pinned.add(0, Updater.LT_PKG);
            writeList(KEY_PINNED, pinned);
            prefs.edit().putBoolean(KEY_LT_DONE, true).apply();
        }
        return true;
    }

    /**
     * YouTube Leve = SmartTube. Se estiver instalado, aparece com esse nome
     * e entra nos principais uma vez (depois você tira se quiser).
     */
    private String findSmartTube(Map<String, String> apps) {
        String found = null;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String pkg = e.getKey();
            String l = e.getValue().toLowerCase(Locale.ROOT).replace(" ", "");
            if (l.contains("smarttube") || pkg.startsWith("com.liskovsoft.smarttube")
                    || pkg.equals("com.teamsmart.videomanager.tv")) {
                found = pkg;
                break;
            }
        }
        if (found == null) return null;
        apps.put(found, getString(R.string.st_name));
        if (!prefs.getBoolean(KEY_ST_DONE, false)) {
            List<String> pinned = readList(KEY_PINNED);
            if (!pinned.contains(found)) pinned.add(found);
            writeList(KEY_PINNED, pinned);
            prefs.edit().putBoolean(KEY_ST_DONE, true).apply();
        }
        return found;
    }

    /** Apps só de celular conhecidos (ex.: Tomato): adapta para TV e põe nos principais, uma vez. */
    private void autoAdaptPhoneApps(Map<String, String> apps) {
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String l = e.getValue().toLowerCase(Locale.ROOT).replace(" ", "");
            String pkg = e.getKey();
            if (!(l.contains("tomato") || pkg.toLowerCase(Locale.ROOT).contains("tomato"))) continue;
            String done = "adapt_done_" + pkg;
            if (prefs.getBoolean(done, false)) continue;
            AppAdapt.set(this, pkg, true);
            List<String> pinned = readList(KEY_PINNED);
            if (!pinned.contains(pkg)) pinned.add(pkg);
            writeList(KEY_PINNED, pinned);
            prefs.edit().putBoolean(done, true).apply();
        }
    }

    /** Na primeira vez, o YouCine já entra nos principais. */
    private void firstRun(Map<String, String> apps) {
        if (prefs.getBoolean(KEY_FIRST, false)) return;
        List<String> pinned = readList(KEY_PINNED);
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String l = e.getValue().toLowerCase(Locale.ROOT).replace(" ", "");
            if (l.contains("youcine") && !pinned.contains(e.getKey())) pinned.add(e.getKey());
        }
        writeList(KEY_PINNED, pinned);
        prefs.edit().putBoolean(KEY_FIRST, true).apply();
    }

    // ================= Cliques =================

    private void click(Row row) {
        fx.play(SoundFx.OK);
        if (row.isApp()) {
            launch(row.id);
            return;
        }
        if (row.id.startsWith(A_SC)) {
            openShortcut(Integer.parseInt(row.id.substring(A_SC.length())));
            return;
        }
        switch (row.id) {
            case A_SETTINGS:
                HomeGuard.pause();
                tryStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case A_HOME:
                chooseHome();
                break;
            case A_TEXT:
                int ts = (prefs.getInt(KEY_TEXT, 0) + 1) % 3;
                prefs.edit().putInt(KEY_TEXT, ts).apply();
                textScale = TEXT_SCALES[ts];
                reload(A_TEXT);
                break;
            case A_HL:
                prefs.edit().putBoolean(KEY_HL, !prefs.getBoolean(KEY_HL, false)).apply();
                list.setSelector(selectorDrawable());
                reload(A_HL);
                break;
            case A_TTS:
                boolean sp = !prefs.getBoolean(KEY_TTS, false);
                prefs.edit().putBoolean(KEY_TTS, sp).apply();
                setupTts(sp);
                if (sp) Toast.makeText(this, R.string.tts_ready, Toast.LENGTH_SHORT).show();
                reload(A_TTS);
                break;
            case A_SOUND: {
                String[] order = {"soft", "ps2", "off", "system"};
                String cur = SoundFx.style(this);
                int i = 0;
                while (i < order.length && !order[i].equals(cur)) i++;
                String next = order[(i + 1) % order.length];
                prefs.edit().putString(SoundFx.KEY, next).apply();
                applySoundStyle();
                fx.play(SoundFx.SIDE); // amostra do som novo
                reload(A_SOUND);
                break;
            }
            case A_RMOUSE:
                chooseRemoteMouseKey();
                break;
            case A_TV_ACCESS:
                HomeGuard.pause();
                Shortcuts.open(this, Shortcuts.ACCESSIBILITY);
                break;
            case A_ICONS:
                showIcons = !showIcons;
                prefs.edit().putBoolean(KEY_ICONS, showIcons).apply();
                reload(A_ICONS);
                break;
            case A_THEME: {
                final String cur = theme();
                int sel = 0;
                for (int t = 0; t < StarsView.KEYS.length; t++) if (StarsView.KEYS[t].equals(cur)) sel = t;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.theme_title)
                        .setSingleChoiceItems(StarsView.NAMES, sel, (dlg, which) -> {
                            String key = StarsView.KEYS[which];
                            prefs.edit().putString(KEY_THEME, key).remove(KEY_STARS).apply();
                            stars.setTheme(key);
                            stars.setAnimate(prefs.getBoolean(KEY_STARS, !"amoled".equals(key)));
                            stars.setRunning(true);
                            list.setSelector(selectorDrawable());
                            fx.play(SoundFx.SIDE);
                            reload(A_THEME);
                        })
                        .setPositiveButton(R.string.theme_done, null)
                        .show();
                break;
            }
            case A_STARS:
                boolean on = !prefs.getBoolean(KEY_STARS, !amoled());
                prefs.edit().putBoolean(KEY_STARS, on).putBoolean("stars_user_set", true).apply();
                stars.setAnimate(on);
                stars.setRunning(true);
                reload(A_STARS);
                break;
            case A_ALPHA:
                writeList(KEY_ORDER, new ArrayList<>());
                reload(null);
                break;
            case A_HIDDEN:
                showHidden = !showHidden;
                reload(A_HIDDEN);
                break;
            case A_UPDATE:
                updater.check();
                break;
            case A_ST:
                updater.installSmartTube();
                break;
            case A_LT:
                prefs.edit().putInt(KEY_LT_AVAIL, 0).apply();
                updater.installLeveTube();
                break;
            case A_NEW:
                updater.check();
                break;
            case A_NOW:
                tryStart(new Intent(this, NowPlayingActivity.class));
                break;
            case A_NET:
                tryStart(new Intent(this, NetActivity.class));
                break;
            case A_GUARD:
                boolean g = HomeGuard.enabled(this);
                if (g && RemoteHub.mouse == null) {
                    explainGuard();
                } else if (g) {
                    HomeGuard.setEnabled(this, false);
                    Toast.makeText(this, R.string.guard_turned_off, Toast.LENGTH_LONG).show();
                    reload(A_GUARD);
                } else {
                    HomeGuard.setEnabled(this, true);
                    if (RemoteHub.mouse == null) explainGuard();
                    reload(A_GUARD);
                }
                break;
            case A_IME:
                if (imeState() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.ime_name)
                            .setMessage(R.string.ime_explain)
                            .setPositiveButton(R.string.mouse_open_settings, (d, w) -> {
                                Shortcuts.open(this, Shortcuts.KEYBOARD);
                            })
                            .setNegativeButton(R.string.upd_later, null)
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.ime_name)
                            .setMessage(R.string.ime_choose_msg)
                            .setPositiveButton(R.string.ime_choose, (d, w) -> {
                                android.view.inputmethod.InputMethodManager imm =
                                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                                if (imm != null) imm.showInputMethodPicker();
                            })
                            .setNeutralButton(R.string.ime_open_settings,
                                    (d, w) -> Shortcuts.open(this, Shortcuts.KEYBOARD))
                            .setNegativeButton(R.string.upd_later, null)
                            .show();
                }
                break;
            case A_MOUSE:
                if (!prefs.getBoolean(KEY_MUSIC, true)) {
                    prefs.edit().putBoolean(KEY_MUSIC, true).apply();
                    LeveMusicService.start(this);
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.mouse_name)
                        .setMessage(RemoteHub.mouse != null ? R.string.mouse_explain_on : R.string.mouse_explain)
                        .setPositiveButton(R.string.mouse_open_settings, (d, w) -> {
                            Shortcuts.open(this, Shortcuts.ACCESSIBILITY);
                        })
                        .setNegativeButton(R.string.upd_later, null)
                        .show();
                break;
            case A_MUSIC:
                boolean m = !prefs.getBoolean(KEY_MUSIC, true);
                prefs.edit().putBoolean(KEY_MUSIC, m).apply();
                if (m) LeveMusicService.start(this);
                else LeveMusicService.stop(this);
                reload(A_MUSIC);
                break;
        }
    }

    /** Atalho da configuração ao vivo: abre um site ou um app. */
    private void openShortcut(int i) {
        org.json.JSONArray sc = RemoteConfig.get(this).optJSONArray("shortcuts");
        org.json.JSONObject s = sc == null ? null : sc.optJSONObject(i);
        if (s == null) return;
        String pkg = s.optString("package", "");
        String url = s.optString("url", "");
        if (!pkg.isEmpty()) {
            launch(pkg);
        } else if (!url.isEmpty()) {
            if (!tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void launch(String pkg) {
        if (AppAdapt.is(this, pkg)) {
            // app de celular: libera memória antes (o box respira) e abre
            new Thread(() -> {
                AppAdapt.freeMemory(this, pkg);
                ui.post(() -> launchNow(pkg));
            }).start();
            return;
        }
        launchNow(pkg);
    }

    private void launchNow(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null || !tryStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showOptions(final int pos) {
        if (pos < 0 || pos >= rows.size()) return;
        final Row row = rows.get(pos);
        if (!row.isApp()) return;

        final boolean isMain = row.type == T_BIG;
        final List<String> opts = new ArrayList<>();
        opts.add(getString(isMain ? R.string.unpin : R.string.pin));
        opts.add(getString(R.string.move));
        if (!isMain) opts.add(getString(row.hidden ? R.string.unhide : R.string.hide));
        final boolean adapted = AppAdapt.is(this, row.id);
        opts.add(getString(adapted ? R.string.adapt_off : R.string.adapt_on));
        opts.add(getString(R.string.app_info));

        new AlertDialog.Builder(this)
                .setTitle(row.label)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    String c = opts.get(which);
                    if (c.equals(getString(R.string.pin))) {
                        List<String> p = readList(KEY_PINNED);
                        p.add(row.id);
                        writeList(KEY_PINNED, p);
                        List<String> h = readList(KEY_HIDDEN);
                        h.remove(row.id);
                        writeList(KEY_HIDDEN, h);
                        reload(row.id);
                    } else if (c.equals(getString(R.string.unpin))) {
                        List<String> p = readList(KEY_PINNED);
                        p.remove(row.id);
                        writeList(KEY_PINNED, p);
                        reload(row.id);
                    } else if (c.equals(getString(R.string.move))) {
                        startMove(row.id);
                    } else if (c.equals(getString(R.string.hide))) {
                        List<String> h = readList(KEY_HIDDEN);
                        h.add(row.id);
                        writeList(KEY_HIDDEN, h);
                        reload(null);
                    } else if (c.equals(getString(R.string.unhide))) {
                        List<String> h = readList(KEY_HIDDEN);
                        h.remove(row.id);
                        writeList(KEY_HIDDEN, h);
                        reload(row.id);
                    } else if (c.equals(getString(R.string.adapt_on)) || c.equals(getString(R.string.adapt_off))) {
                        AppAdapt.set(this, row.id, !adapted);
                        if (!adapted && RemoteHub.mouse == null) {
                            new AlertDialog.Builder(this)
                                    .setTitle(R.string.mouse_name)
                                    .setMessage(R.string.adapt_need_mouse)
                                    .setPositiveButton(R.string.mouse_open_settings, (x, w) -> {
                                        HomeGuard.pause();
                                        Shortcuts.open(this, Shortcuts.ACCESSIBILITY);
                                    })
                                    .setNegativeButton(R.string.upd_later, null)
                                    .show();
                        } else {
                            Toast.makeText(this, !adapted ? R.string.adapt_on_toast : R.string.adapt_off_toast,
                                    Toast.LENGTH_LONG).show();
                        }
                        reload(row.id);
                    } else {
                        HomeGuard.pause();
                        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + row.id)));
                    }
                })
                .show();
    }

    // ================= Modo Mover =================

    private void startMove(String id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) moveIndex = i;
        if (moveIndex < 0) return;
        list.setSelector(R.drawable.sel_move);
        list.setSelection(moveIndex);
        hint.setText(R.string.move_hint);
        hint.setTextColor(getResources().getColor(R.color.move_line));
    }

    private void endMove() {
        moveIndex = -1;
        list.setSelector(selectorDrawable());
        hint.setText(R.string.title);
        applyLook();
        hint.setTextColor(getResources().getColor(R.color.dim));
    }

    private void step(int dir) {
        int from = moveIndex, to = from + dir;
        if (to < 0 || to >= rows.size()) return;
        Row a = rows.get(from), b = rows.get(to);
        if (a.type != b.type) return; // só troca dentro da mesma seção
        rows.set(from, b);
        rows.set(to, a);
        moveIndex = to;
        saveOrder(a.type);
        adapter.notifyDataSetChanged();
        list.setSelection(to);
    }

    private void saveOrder(int type) {
        List<String> ids = new ArrayList<>();
        for (Row r : rows) if (r.type == type) ids.add(r.id);
        writeList(type == T_BIG ? KEY_PINNED : KEY_ORDER, ids);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (moveIndex >= 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_DPAD_UP) step(-1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN) step(1);
                else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                        || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_A
                        || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    endMove();
                    swallowKeyUp = true;
                }
            }
            return true; // no modo mover, nenhuma outra tecla faz nada
        }
        if (swallowKeyUp && e.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = false;
            return true;
        }
        if ((k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_CHANNEL_UP
                || k == KeyEvent.KEYCODE_PAGE_DOWN || k == KeyEvent.KEYCODE_PAGE_UP)
                && list.hasFocus()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                boolean fwd = k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_CHANNEL_DOWN
                        || k == KeyEvent.KEYCODE_PAGE_DOWN;
                jumpSection(fwd ? 1 : -1);
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) {
            showOptions(list.getSelectedItemPosition());
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            // Voltar na tela inicial: volta para o topo
            if (e.getAction() == KeyEvent.ACTION_UP) {
                int first = firstSelectable();
                if (first >= 0) list.setSelection(first);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void explainGuard() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.mouse_name)
                .setMessage(R.string.guard_explain)
                .setPositiveButton(R.string.mouse_open_settings, (d, w) -> {
                    Shortcuts.open(this, Shortcuts.ACCESSIBILITY);
                })
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    /** 0 = Leve Teclado desligado, 1 = ligado mas não escolhido, 2 = é o teclado atual. */
    private int imeState() {
        try {
            String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (current != null && current.startsWith(getPackageName() + "/")) {
                return current.endsWith("LeveKeyboardTv") ? 3 : 2;
            }
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (android.view.inputmethod.InputMethodInfo i : imm.getEnabledInputMethodList()) {
                    if (getPackageName().equals(i.getPackageName())) return 1;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ================= Tela inicial padrão =================

    private boolean isDefaultHome() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && getPackageName().equals(ri.activityInfo.packageName);
    }

    private ComponentName fakeHome() {
        return new ComponentName(getPackageName(), getPackageName() + ".FakeHome");
    }

    /** Abre o seletor de tela inicial, seja para definir o Leve ou para trocar por outro. */
    private void chooseHome() {
        Toast.makeText(this, R.string.home_pick, Toast.LENGTH_LONG).show();
        boolean mine = isDefaultHome();

        // Android 10+: pedido oficial do sistema
        if (!mine && Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        // Se o Leve já é o padrão, limpa a escolha para o sistema perguntar de novo
        if (mine) {
            try {
                getPackageManager().clearPackagePreferredActivities(getPackageName());
            } catch (Exception ignored) {
            }
        }
        HomeGuard.pause();
        if (tryStart(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        if (Build.VERSION.SDK_INT >= 24
                && tryStart(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return;

        // Truque clássico: um "launcher" extra faz o sistema mostrar o seletor
        try {
            getPackageManager().setComponentEnabledSetting(fakeHome(),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
        tryStart(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Desliga o "launcher" extra depois que a escolha foi feita (a menos que ele virou o padrão). */
    private void disableFakeHomeIfUnused() {
        try {
            PackageManager pm = getPackageManager();
            if (pm.getComponentEnabledSetting(fakeHome())
                    != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return;
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
            boolean fakeIsDefault = ri != null && ri.activityInfo != null
                    && fakeHome().getClassName().equals(ri.activityInfo.name);
            if (!fakeIsDefault) {
                pm.setComponentEnabledSetting(fakeHome(),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {
        }
    }

    // ================= Preferências =================

    private List<String> readList(String key) {
        String raw = prefs.getString(key, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String s : raw.split("\\|")) if (!s.isEmpty() && !out.contains(s)) out.add(s);
        return out;
    }

    private void writeList(String key, List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append('|');
            sb.append(v);
        }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    // ================= Adapter =================

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 5; }
        @Override public int getItemViewType(int p) { return rows.get(p).type; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return rows.get(p).type != T_SECTION; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Row r = rows.get(pos);
            if (v == null) {
                int layout = r.type == T_SECTION ? R.layout.row_section
                        : (r.type == T_BIG || r.type == T_BIG_ACTION) ? R.layout.row_big : R.layout.row;
                v = getLayoutInflater().inflate(layout, parent, false);
            }
            if (v != grown) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
            TextView label = v.findViewById(R.id.label);
            ImageView icon = v.findViewById(R.id.icon);
            boolean big = r.type == T_BIG || r.type == T_BIG_ACTION;
            label.setTextSize((r.type == T_SECTION ? 15f : big ? 34f : 22f) * textScale);
            if (icon != null) {
                int px = (int) ((big ? 64 : 34) * textScale * getResources().getDisplayMetrics().density);
                ViewGroup.LayoutParams lp = icon.getLayoutParams();
                if (lp.width != px) {
                    lp.width = px;
                    lp.height = px;
                    icon.setLayoutParams(lp);
                }
            }

            if (A_NOW.equals(r.id)) {
                label.setText(r.label);
            } else if (r.type == T_BIG || r.type == T_BIG_ACTION) {
                label.setText("★  " + r.label + (r.isApp() && adaptedNow.contains(r.id) ? "   ·  modo TV" : ""));
            } else {
                label.setText(r.label + (r.isApp() && adaptedNow.contains(r.id) ? "   ·  modo TV" : ""));
            }
            if (icon != null && !showIcons && r.type != T_BIG_ACTION) {
                icon.setTag(null);
                icon.setImageDrawable(null);
                icon.setVisibility(View.GONE);
            } else if (icon != null) {
                if (r.isApp()) {
                    icon.setVisibility(View.VISIBLE);
                    icons.into(r.id, icon);
                } else if (r.type == T_BIG_ACTION) {
                    icon.setTag(null);
                    icon.setVisibility(View.VISIBLE);
                    icon.setImageResource(A_NOW.equals(r.id) ? R.drawable.note : R.drawable.play);
                } else {
                    icon.setTag(null);
                    icon.setImageDrawable(null);
                    icon.setVisibility(View.INVISIBLE); // mantém o alinhamento do texto
                }
            }
            float alpha = r.hidden ? 0.5f : 1f;
            if (r.type == T_ACTION) label.setTextColor(0xFFDADADA);
            v.setAlpha(alpha);
            return v;
        }
    }
}
