package com.leve.launchertv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * Sons do controle no Leve: troca o "clique" do sistema por sons próprios, curtinhos.
 * Estilos: "soft" (suave, padrão), "ps2" (sininho de console), "off" (mudo), "system" (o do box).
 */
final class SoundFx {

    static final String KEY = "sound_style";
    static final int MOVE = 0, SIDE = 1, OK = 2;

    private SoundPool pool;
    private final int[] ids = new int[3];
    private String loaded = "";
    private final Context ctx;

    SoundFx(Context c) {
        ctx = c.getApplicationContext();
    }

    static String style(Context c) {
        return c.getSharedPreferences("leve", Context.MODE_PRIVATE).getString(KEY, "soft");
    }

    /** Carrega só o estilo escolhido (3 sons de poucos KB). */
    void prepare(String style) {
        if (style.equals(loaded)) return;
        release();
        loaded = style;
        if (!style.equals("soft") && !style.equals("ps2")) return;
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        boolean ps2 = style.equals("ps2");
        ids[MOVE] = pool.load(ctx, ps2 ? R.raw.fx_ps2_move : R.raw.fx_soft_move, 1);
        ids[SIDE] = pool.load(ctx, ps2 ? R.raw.fx_ps2_side : R.raw.fx_soft_side, 1);
        ids[OK] = pool.load(ctx, ps2 ? R.raw.fx_ps2_ok : R.raw.fx_soft_ok, 1);
    }

    void play(int which) {
        if (pool == null) return;
        try {
            pool.play(ids[which], 0.6f, 0.6f, 1, 0, 1f);
        } catch (Throwable ignored) {
        }
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
        loaded = "";
    }
}
