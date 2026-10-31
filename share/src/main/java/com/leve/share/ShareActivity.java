package com.leve.share;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tela única do Leve Share: um botão grande e os dados para conectar a TV. */
public class ShareActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status, net, stats, help;
    private Button toggle;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF01020A);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (22 * dp);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        TextView title = text(col, "LEVE SHARE", 14, 0xFF7F93C9);
        title.setLetterSpacing(0.3f);
        text(col, "Celular antigo como roteador / repetidor da TV", 20, 0xFFEAF1FF);

        toggle = new Button(this);
        toggle.setTextSize(20);
        toggle.setAllCaps(false);
        toggle.setTextColor(0xFFEAF1FF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x6630C0FF);
        bg.setCornerRadius(16 * dp);
        bg.setStroke((int) (1 * dp), 0xAA8FC8FF);
        toggle.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (64 * dp));
        lp.topMargin = (int) (20 * dp);
        col.addView(toggle, lp);
        toggle.setOnClickListener(v -> {
            if (ShareService.instance != null) ShareService.stop(this);
            else if (hasPermission()) ShareService.start(this);
            else askPermission();
            h.postDelayed(this::refresh, 300);
        });

        status = text(col, "", 16, 0xFFFFD27A);
        net = text(col, "", 22, 0xFFEAF1FF);
        net.setTypeface(Typeface.MONOSPACE);
        net.setTextIsSelectable(true);
        stats = text(col, "", 14, 0xFF7F93C9);
        help = text(col, "", 15, 0xFFB8C6EA);
        help.setLineSpacing(4 * dp, 1f);
        help.setText(
                "Como usar:\n\n"
                + "1. Deixe este celular no meio do caminho entre o roteador e a TV, onde o Wi-Fi de casa ainda chega bem (ou use os dados móveis dele).\n\n"
                + "2. Toque em Ligar. Deixe o Wi-Fi e a Localização ligados (o Android exige para criar a rede).\n\n"
                + "3. Na TV, conecte no Wi-Fi com o nome e a senha que aparecem acima.\n\n"
                + "4. No Leve Launcher da TV: Opções → Leve Rede → \"Internet pelo Leve Share\". Pronto: os apps usam a internet deste celular.\n\n"
                + "Dica: deixe este celular na tomada. Ele pode ficar com a tela apagada.\n"
                + "TV sem o Leve? Nas configurações avançadas do Wi-Fi da TV, coloque proxy manual 192.168.49.1 porta 8282.");

        Button battery = new Button(this);
        battery.setAllCaps(false);
        battery.setText("Não deixar o Android desligar o Leve Share (bateria)");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = (int) (16 * dp);
        col.addView(battery, lp2);
        battery.setOnClickListener(v -> {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            } catch (Exception ignored) {
            }
        });

        setContentView(sv);
    }

    private TextView text(LinearLayout parent, String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        parent.addView(t, lp);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void refresh() {
        boolean on = ShareService.instance != null;
        toggle.setText(on ? "Desligar" : "Ligar");
        status.setText(on ? ShareService.status : "Desligado");
        if (on && !ShareService.ssid.isEmpty()) {
            net.setVisibility(View.VISIBLE);
            net.setText("Wi-Fi:  " + ShareService.ssid + "\nSenha:  " + ShareService.pass);
            ShareProxy px = ShareService.instance.proxy();
            String s = ShareService.clients + " aparelho(s) conectado(s)";
            if (px != null) {
                s += "  ·  ↓ " + mb(px.bytesDown.get()) + "  ↑ " + mb(px.bytesUp.get())
                        + "  ·  " + px.active.get() + " conexões abertas";
            }
            stats.setText(s);
        } else {
            net.setVisibility(View.GONE);
            stats.setText("");
        }
    }

    private static String mb(long b) {
        return b < 1024 * 1024 ? (b / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576f);
    }

    // ---------- Permissão (o Wi-Fi Direct exige Localização) ----------

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (hasPermission()) ShareService.start(this);
        else status.setText("Sem a permissão de Localização o Android não deixa criar a rede Wi-Fi Direct.");
    }
}
