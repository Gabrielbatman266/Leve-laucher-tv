package com.leve.launchertv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Fundo animado do Leve, com vários temas. Tudo desenhado à mão com poucas formas,
 * ~15 quadros por segundo, e para sozinho quando a tela inicial não está visível.
 */
public class StarsView extends View {

    /** Temas: chave salva, nome mostrado e cor da seleção. */
    static final String[] KEYS = {"amoled", "ps2", "aurora", "neve", "matrix", "oceano", "synthwave", "vagalumes", "sakura"};
    static final String[] NAMES = {"AMOLED preto", "Console azul (estrelas)", "Aurora boreal", "Neve caindo",
            "Chuva digital", "Oceano (ondas)", "Synthwave anos 80", "Vaga-lumes", "Cerejeiras (sakura)"};
    private static final int[] ACCENTS = {0xFFFFFFFF, 0xFF30C0FF, 0xFF1DE9B6, 0xFFB3E5FC, 0xFF00E676,
            0xFF29B6F6, 0xFFFF4FD8, 0xFFC6FF00, 0xFFFF8FB8};

    static int accentFor(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return ACCENTS[i];
        return ACCENTS[0];
    }

    static String nameFor(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return NAMES[0];
    }

    private static final int N = 110;
    private static final long FRAME_MS = 66; // ~15 fps: suave e quase sem custo

    private final float[] x = new float[N];
    private final float[] y = new float[N];
    private final float[] r = new float[N];
    private final float[] phase = new float[N];
    private final float[] speed = new float[N];
    private final float[] drift = new float[N];

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint();
    private final Paint mist = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final float d;

    private String theme = "amoled";
    private boolean animate = true;
    private boolean running = false;
    private long start;
    private int[] skyColors = {0xFF01020A, 0xFF040B2A, 0xFF0A1A4A};

    public StarsView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        Random rnd = new Random(2000);
        for (int i = 0; i < N; i++) {
            x[i] = rnd.nextFloat();
            y[i] = rnd.nextFloat();
            float big = rnd.nextFloat();
            r[i] = (big > 0.93f ? 2.2f : big > 0.7f ? 1.4f : 0.9f) * d;
            phase[i] = rnd.nextFloat() * 6.283f;
            speed[i] = 0.4f + rnd.nextFloat() * 1.4f;
            drift[i] = 0.002f + rnd.nextFloat() * 0.006f;
        }
    }

    public void setTheme(String key) {
        theme = key == null ? "amoled" : key;
        if (getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    /** Compatibilidade: AMOLED liga/desliga. */
    public void setAmoled(boolean on) {
        setTheme(on ? "amoled" : "ps2");
    }

    public void setAnimate(boolean on) {
        animate = on;
        invalidate();
    }

    public void setRunning(boolean on) {
        running = on;
        if (on) {
            if (start == 0) start = System.currentTimeMillis();
            invalidate();
        }
    }

    /** Troca as cores do céu do tema Console (configuração ao vivo). */
    public void setSky(int[] colors) {
        if (colors == null || colors.length != 3 || java.util.Arrays.equals(colors, skyColors)) return;
        skyColors = colors;
        if (getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    private void vertical(int h, int... colors) {
        sky.setShader(new LinearGradient(0, 0, 0, h, colors, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mist.setShader(null);
        switch (theme) {
            case "ps2":
                sky.setShader(new LinearGradient(0, 0, 0, h, skyColors, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                mist.setShader(new RadialGradient(w * 0.72f, h * 1.05f, Math.max(w, h) * 0.7f,
                        0x553A6BFF, 0x003A6BFF, Shader.TileMode.CLAMP));
                break;
            case "neve": vertical(h, 0xFF02060F, 0xFF0B1A33, 0xFF1C2E4A); break;
            case "oceano": vertical(h, 0xFF000814, 0xFF001D3D, 0xFF003566); break;
            case "synthwave": vertical(h, 0xFF0B0019, 0xFF2A0845, 0xFF5B0E5E); break;
            case "vagalumes": vertical(h, 0xFF000000, 0xFF02100A, 0xFF06200F); break;
            case "sakura": vertical(h, 0xFF0D0614, 0xFF2A0F2E, 0xFF4A1A3A); break;
            default: sky.setShader(null); break;
        }
        glow.setShader(new RadialGradient(0, 0, Math.max(w, h) * 0.45f, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = animate ? (System.currentTimeMillis() - start) / 1000f : 0f;
        if (sky.getShader() != null) c.drawRect(0, 0, w, h, sky);
        else c.drawColor(0xFF000000);

        switch (theme) {
            case "amoled":
                if (animate) drawStars(c, w, h, t, 0xFFFFFFFF, 0.6f);
                break;
            case "ps2":
                c.drawRect(0, 0, w, h, mist);
                drawStars(c, w, h, t, 0xFFDCE8FF, 1f);
                break;
            case "aurora": drawAurora(c, w, h, t); break;
            case "neve": drawSnow(c, w, h, t); break;
            case "matrix": drawRain(c, w, h, t); break;
            case "oceano": drawOcean(c, w, h, t); break;
            case "synthwave": drawSynth(c, w, h, t); break;
            case "vagalumes": drawFireflies(c, w, h, t); break;
            case "sakura": drawSakura(c, w, h, t); break;
        }
        if (animate && running && !(theme.equals("amoled") && !animate)) postInvalidateDelayed(FRAME_MS);
    }

    // ---------------- Temas ----------------

    private void drawStars(Canvas c, int w, int h, float t, int color, float k) {
        p.setColor(color);
        for (int i = 0; i < N; i++) {
            float px = x[i] - t * drift[i];
            px = px - (float) Math.floor(px);
            float tw = animate ? 0.55f + 0.45f * (float) Math.sin(phase[i] + t * speed[i]) : 0.8f;
            p.setAlpha((int) ((40 + 215 * tw * (r[i] > 2f ? 1f : 0.75f)) * k));
            c.drawCircle(px * w, y[i] * h, r[i], p);
        }
    }

    /** Três manchas de luz verde/roxa/azul que ondulam devagar. */
    private void drawAurora(Canvas c, int w, int h, float t) {
        int[] cols = {0x6E1DE9B6, 0x5A7C4DFF, 0x502979FF};
        for (int i = 0; i < 3; i++) {
            float cx = w * (0.25f + 0.25f * i + 0.12f * (float) Math.sin(t * 0.13f + i * 2.1f));
            float cy = h * (0.28f + 0.08f * (float) Math.sin(t * 0.21f + i));
            c.save();
            c.translate(cx, cy);
            c.scale(1.6f, 0.55f);
            glow.setColorFilter(new android.graphics.PorterDuffColorFilter(cols[i] | 0xFF000000,
                    android.graphics.PorterDuff.Mode.SRC_IN));
            glow.setAlpha((cols[i] >>> 24));
            c.drawCircle(0, 0, Math.max(w, h) * 0.45f, glow);
            c.restore();
        }
        glow.setColorFilter(null);
        glow.setAlpha(255);
        drawStars(c, w, h, t, 0xFFFFFFFF, 0.35f);
    }

    private void drawSnow(Canvas c, int w, int h, float t) {
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < 90; i++) {
            float fall = speed[i] * 0.035f;
            float py = y[i] + t * fall;
            py = py - (float) Math.floor(py);
            float px = x[i] + 0.012f * (float) Math.sin(t * speed[i] + phase[i]);
            p.setAlpha(r[i] > 2f ? 230 : r[i] > 1.2f ? 170 : 110);
            c.drawCircle(px * w, py * h, r[i] * 1.6f, p);
        }
    }

    /** Colunas verdes caindo, rastro que apaga (chuva digital). */
    private void drawRain(Canvas c, int w, int h, float t) {
        int cols = 46;
        float cw = w / (float) cols, seg = 14 * d;
        for (int i = 0; i < cols; i++) {
            float sp = 0.12f + speed[i] * 0.09f;
            float head = (y[i] + t * sp);
            head = (head - (float) Math.floor(head)) * (h + 12 * seg);
            float cx = i * cw + cw / 2f;
            for (int k = 0; k < 12; k++) {
                float yy = head - k * seg;
                if (yy < -seg || yy > h) continue;
                p.setColor(k == 0 ? 0xFFB9FFD0 : 0xFF00E676);
                p.setAlpha(k == 0 ? 230 : (int) (170 * (1f - k / 12f)));
                c.drawRect(cx - 2.2f * d, yy, cx + 2.2f * d, yy + seg * 0.7f, p);
            }
        }
    }

    private void drawOcean(Canvas c, int w, int h, float t) {
        drawStars(c, w, (int) (h * 0.55f), t, 0xFFFFFFFF, 0.4f);
        int[] cols = {0x5529B6F6, 0x660288D1, 0x8801579B};
        for (int k = 0; k < 3; k++) {
            float base = h * (0.68f + k * 0.1f);
            float amp = (10 + k * 6) * d;
            path.reset();
            path.moveTo(0, h);
            for (int i = 0; i <= 48; i++) {
                float xx = w * i / 48f;
                float yy = base + amp * (float) Math.sin(i * 0.33f + t * (0.8f + k * 0.35f) + k);
                path.lineTo(xx, yy);
            }
            path.lineTo(w, h);
            path.close();
            p.setColor(cols[k]);
            c.drawPath(path, p);
        }
    }

    /** Sol listrado no horizonte e grade neon andando para frente. */
    private void drawSynth(Canvas c, int w, int h, float t) {
        float hz = h * 0.55f;
        // sol
        float sr = h * 0.2f;
        p.setShader(new LinearGradient(0, hz - sr, 0, hz, 0xFFFFD319, 0xFFFF2975, Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(0, 0, w, hz);
        c.drawCircle(w * 0.7f, hz, sr, p);
        p.setShader(null);
        p.setColor(0xFF2A0845);
        for (int i = 0; i < 6; i++) {
            float yy = hz - sr * 0.08f - i * sr * 0.14f;
            c.drawRect(0, yy, w, yy + (6 - i) * 0.9f * d, p);
        }
        c.restore();
        // chão
        p.setColor(0xFF120020);
        c.drawRect(0, hz, w, h, p);
        p.setColor(0xFFFF4FD8);
        p.setStrokeWidth(1.6f * d);
        float off = animate ? (t * 0.35f) % 1f : 0f;
        for (int i = 0; i < 14; i++) {
            float z = (i + 1 - off) / 14f;           // 0 = longe, 1 = perto
            float yy = hz + (h - hz) * z * z;
            p.setAlpha((int) (60 + 195 * z));
            c.drawLine(0, yy, w, yy, p);
        }
        p.setAlpha(170);
        for (int i = -10; i <= 10; i++) {
            c.drawLine(w / 2f + i * w * 0.02f, hz, w / 2f + i * w * 0.16f, h, p);
        }
        p.setAlpha(255);
    }

    private void drawFireflies(Canvas c, int w, int h, float t) {
        for (int i = 0; i < 38; i++) {
            float px = x[i] + 0.04f * (float) Math.sin(t * 0.3f * speed[i] + phase[i]);
            float py = y[i] + 0.05f * (float) Math.cos(t * 0.25f * speed[i] + phase[i] * 1.7f);
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * speed[i] * 1.3f + phase[i]);
            p.setColor(0xFFC6FF00);
            p.setAlpha((int) (35 * pulse));
            c.drawCircle(px * w, py * h, 9 * d, p);
            p.setAlpha((int) (90 + 160 * pulse));
            c.drawCircle(px * w, py * h, 2.2f * d, p);
        }
    }

    private void drawSakura(Canvas c, int w, int h, float t) {
        for (int i = 0; i < 46; i++) {
            float py = y[i] + t * (0.03f + speed[i] * 0.018f);
            py = py - (float) Math.floor(py);
            float px = x[i] + t * 0.012f + 0.03f * (float) Math.sin(t * speed[i] + phase[i]);
            px = px - (float) Math.floor(px);
            float s = (r[i] > 2f ? 7 : r[i] > 1.2f ? 5.5f : 4.2f) * d;
            c.save();
            c.translate(px * w, py * h);
            c.rotate((t * 60 * speed[i] + phase[i] * 57) % 360);
            oval.set(-s, -s * 0.55f, s, s * 0.55f);
            p.setColor(i % 3 == 0 ? 0xFFFFC1D9 : 0xFFFF8FB8);
            p.setAlpha(r[i] > 2f ? 235 : 180);
            c.drawOval(oval, p);
            c.restore();
        }
    }
}
