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
    private static final String KEY_MUSIC = "music_on";

    // Tipos de linha
    static final int T_SECTION = 0, T_BIG = 1, T_APP = 2, T_ACTION = 3, T_BIG_ACTION = 4;

    // Ações do menu "Opções"
    private static final String A_SETTINGS = "settings", A_HOME = "home", A_STARS = "stars",
            A_ALPHA = "alpha", A_HIDDEN = "hidden", A_UPDATE = "update", A_ST = "smarttube",
            A_NOW = "now_playing", A_MUSIC = "music", A_MOUSE = "mouse";

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

        stars = findViewById(R.id.stars);
        hint = findViewById(R.id.hint);
        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> click(rows.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showOptions(pos);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        disableFakeHomeIfUnused();
        if (prefs.getBoolean(KEY_MUSIC, true)) LeveMusicService.start(this);
        stars.setAnimate(prefs.getBoolean(KEY_STARS, true));
        stars.setRunning(true);
        reload(null);
        list.requestFocus();
        ui.postDelayed(musicWatch, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stars.setRunning(false);
        ui.removeCallbacks(musicWatch);
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
            lastPackages = new HashSet<>(apps.keySet());
        }
        firstRun(apps);
        String smartTube = findSmartTube(apps);

        List<String> pinned = readList(KEY_PINNED);
        final List<String> order = readList(KEY_ORDER);
        Set<String> hidden = new HashSet<>(readList(KEY_HIDDEN));

        rows.clear();

        // Principais (grandes)
        List<Row> main = new ArrayList<>();
        for (String pkg : pinned) {
            String label = apps.get(pkg);
            if (label != null) main.add(new Row(T_BIG, pkg, label, false));
        }
        boolean offerSmartTube = smartTube == null;

        // Música do celular tocando: aparece no topo de tudo
        LeveMusicService ms = LeveMusicService.get();
        LeveMusicService.Track playing = ms == null ? null : ms.current();
        shownTrack = playing == null ? null : playing.id + playing.title;
        if (playing != null) {
            String what = playing.title + (playing.artist.isEmpty() ? "" : "  —  " + playing.artist);
            main.add(0, new Row(T_BIG_ACTION, A_NOW, getString(R.string.lm_now, what), false));
        }

        if (!main.isEmpty() || offerSmartTube) {
            rows.add(new Row(T_SECTION, "", getString(R.string.sec_main), false));
            rows.addAll(main);
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

        // Opções
        rows.add(new Row(T_SECTION, "", getString(R.string.sec_opts), false));
        rows.add(new Row(T_ACTION, A_SETTINGS, getString(R.string.settings), false));
        rows.add(new Row(T_ACTION, A_HOME,
                getString(isDefaultHome() ? R.string.home_yes : R.string.home_no), false));
        boolean musicOn = prefs.getBoolean(KEY_MUSIC, true);
        String ip = LeveMusicService.localIp();
        rows.add(new Row(T_ACTION, A_MUSIC, !musicOn ? getString(R.string.lm_off)
                : ip.isEmpty() ? getString(R.string.lm_on_noip) : getString(R.string.lm_on, ip), false));
        boolean mouseOn = RemoteHub.mouse != null;
        rows.add(new Row(T_ACTION, A_MOUSE, !mouseOn ? getString(R.string.mouse_off)
                : ip.isEmpty() ? getString(R.string.mouse_on_noip)
                : getString(R.string.mouse_on, ip + ":" + LeveMusicService.PORT), false));
        rows.add(new Row(T_ACTION, A_STARS,
                getString(prefs.getBoolean(KEY_STARS, true) ? R.string.stars_on : R.string.stars_off), false));
        if (!order.isEmpty()) {
            rows.add(new Row(T_ACTION, A_ALPHA, getString(R.string.alpha), false));
        }
        if (hiddenCount > 0) {
            rows.add(new Row(T_ACTION, A_HIDDEN, showHidden
                    ? getString(R.string.hide_hidden)
                    : getString(R.string.show_hidden, hiddenCount), false));
        }
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
        if (row.isApp()) {
            launch(row.id);
            return;
        }
        switch (row.id) {
            case A_SETTINGS:
                tryStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case A_HOME:
                chooseHome();
                break;
            case A_STARS:
                boolean on = !prefs.getBoolean(KEY_STARS, true);
                prefs.edit().putBoolean(KEY_STARS, on).apply();
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
            case A_NOW:
                tryStart(new Intent(this, NowPlayingActivity.class));
                break;
            case A_MOUSE:
                if (!prefs.getBoolean(KEY_MUSIC, true)) {
                    prefs.edit().putBoolean(KEY_MUSIC, true).apply();
                    LeveMusicService.start(this);
                    Toast.makeText(this, R.string.mouse_need_music, Toast.LENGTH_LONG).show();
                }
                Toast.makeText(this, R.string.mouse_enable_hint, Toast.LENGTH_LONG).show();
                if (!tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                    Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
                }
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

    private void launch(String pkg) {
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
                    } else {
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
        list.setSelector(R.drawable.selector);
        hint.setText(R.string.title);
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
            TextView label = v.findViewById(R.id.label);
            ImageView icon = v.findViewById(R.id.icon);

            if (A_NOW.equals(r.id)) {
                label.setText(r.label);
            } else if (r.type == T_BIG || r.type == T_BIG_ACTION) {
                label.setText("★  " + r.label);
            } else {
                label.setText(r.label);
            }
            if (icon != null) {
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
            float alpha = r.hidden ? 0.45f : (r.type == T_ACTION ? 0.75f : 1f);
            v.setAlpha(alpha);
            return v;
        }
    }
}
