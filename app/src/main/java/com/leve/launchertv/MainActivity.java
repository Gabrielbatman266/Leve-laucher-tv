package com.leve.launchertv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leve Launcher TV — tela inicial só de texto para Android TV.
 * Sem ícones, sem imagens, sem serviços em segundo plano.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "leve";
    private static final String KEY_PINNED = "pinned";   // pacotes fixados, separados por "|"
    private static final String KEY_HIDDEN = "hidden";   // pacotes ocultos, separados por "|"

    private static final String SETTINGS_ROW = "#settings";
    private static final String UPDATE_ROW = "#update";
    private static final String HIDDEN_ROW = "#hidden";

    private final List<Row> rows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private SharedPreferences prefs;
    private ListView list;
    private boolean showHidden = false;
    private Updater updater;

    static final class Row {
        final String pkg;
        final String label;
        final boolean pinned;
        final boolean hidden;

        Row(String pkg, String label, boolean pinned, boolean hidden) {
            this.pkg = pkg;
            this.label = label;
            this.pinned = pinned;
            this.hidden = hidden;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        updater = new Updater(this);

        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(rows.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showOptions(rows.get(position));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        list.requestFocus();
    }

    // ---------- Lista de apps ----------

    private void reload() {
        PackageManager pm = getPackageManager();
        Map<String, String> apps = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps);
        collect(pm, Intent.CATEGORY_LAUNCHER, apps);
        apps.remove(getPackageName());

        List<String> pinned = readList(KEY_PINNED);
        Set<String> hidden = new HashSet<>(readList(KEY_HIDDEN));

        rows.clear();
        // 1) Fixados, na ordem em que foram fixados
        for (String pkg : pinned) {
            String label = apps.get(pkg);
            if (label != null) rows.add(new Row(pkg, label, true, false));
        }
        // 2) O resto, em ordem alfabética
        List<Row> rest = new ArrayList<>();
        int hiddenCount = 0;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String pkg = e.getKey();
            if (pinned.contains(pkg)) continue;
            boolean isHidden = hidden.contains(pkg);
            if (isHidden) hiddenCount++;
            if (isHidden && !showHidden) continue;
            rest.add(new Row(pkg, e.getValue(), false, isHidden));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(rest, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return collator.compare(a.label, b.label);
            }
        });
        rows.addAll(rest);

        // 3) Linhas fixas no final
        rows.add(new Row(SETTINGS_ROW, getString(R.string.settings), false, false));
        rows.add(new Row(UPDATE_ROW,
                getString(R.string.update_row, "1.0." + Updater.installedVersion(this)), false, false));
        if (hiddenCount > 0) {
            String label = showHidden
                    ? getString(R.string.hide_hidden)
                    : getString(R.string.show_hidden, hiddenCount);
            rows.add(new Row(HIDDEN_ROW, label, false, false));
        }
        adapter.notifyDataSetChanged();
    }

    private static void collect(PackageManager pm, String category, Map<String, String> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(i, 0);
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (!out.containsKey(pkg)) {
                CharSequence label = ri.loadLabel(pm);
                out.put(pkg, label == null ? pkg : label.toString().trim());
            }
        }
    }

    // ---------- Ações ----------

    private void open(Row row) {
        if (SETTINGS_ROW.equals(row.pkg)) {
            startSafely(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        if (UPDATE_ROW.equals(row.pkg)) {
            updater.check();
            return;
        }
        if (HIDDEN_ROW.equals(row.pkg)) {
            showHidden = !showHidden;
            reload();
            return;
        }
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(row.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(row.pkg);
        if (launch == null) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startSafely(launch);
    }

    private void showOptions(final Row row) {
        if (row.pkg.startsWith("#")) return;
        final List<String> options = new ArrayList<>();
        options.add(getString(row.pinned ? R.string.unpin : R.string.pin));
        if (!row.pinned) options.add(getString(row.hidden ? R.string.unhide : R.string.hide));
        options.add(getString(R.string.app_info));

        new AlertDialog.Builder(this)
                .setTitle(row.label)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String chosen = options.get(which);
                    if (chosen.equals(getString(R.string.pin))) {
                        List<String> p = readList(KEY_PINNED);
                        p.add(row.pkg);
                        writeList(KEY_PINNED, p);
                    } else if (chosen.equals(getString(R.string.unpin))) {
                        List<String> p = readList(KEY_PINNED);
                        p.remove(row.pkg);
                        writeList(KEY_PINNED, p);
                    } else if (chosen.equals(getString(R.string.hide))) {
                        List<String> h = readList(KEY_HIDDEN);
                        h.add(row.pkg);
                        writeList(KEY_HIDDEN, h);
                    } else if (chosen.equals(getString(R.string.unhide))) {
                        List<String> h = readList(KEY_HIDDEN);
                        h.remove(row.pkg);
                        writeList(KEY_HIDDEN, h);
                    } else {
                        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + row.pkg)));
                        return;
                    }
                    reload();
                })
                .show();
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    // Tecla MENU do controle também abre as opções do item selecionado
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = list.getSelectedItemPosition();
            if (pos >= 0 && pos < rows.size()) showOptions(rows.get(pos));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // Voltar na tela inicial não faz nada (é a Home)
    @Override
    public void onBackPressed() {
        if (list.getCount() > 0) list.setSelection(0);
    }

    // ---------- Preferências ----------

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

    // ---------- Adapter só de texto ----------

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) (convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.row, parent, false));
            Row r = rows.get(position);
            String prefix = r.pinned ? "★  " : (r.hidden ? "·  " : "");
            tv.setText(prefix + r.label);
            tv.setAlpha(r.pkg.startsWith("#") || r.hidden ? 0.55f : 1f);
            return tv;
        }
    }
}
