package com.leve.launchertv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Fundo estilo PS2: céu azul-escuro, névoa azul e estrelas que piscam devagar.
 * Desenha só ~12 quadros por segundo e para quando a tela inicial não está visível.
 */
public class StarsView extends View {

    private static final int STARS = 110;
    private static final long FRAME_MS = 80; // ~12 fps: suave e quase sem custo

    private final float[] x = new float[STARS];
    private final float[] y = new float[STARS];
    private final float[] r = new float[STARS];
    private final float[] phase = new float[STARS];
    private final float[] speed = new float[STARS];
    private final float[] drift = new float[STARS];

    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint();
    private final Paint mist = new Paint();
    private boolean animate = true;
    private boolean running = false;
    private long start;

    public StarsView(Context c, AttributeSet a) {
        super(c, a);
        Random rnd = new Random(2000); // o "PS2" saiu em 2000
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < STARS; i++) {
            x[i] = rnd.nextFloat();
            y[i] = rnd.nextFloat();
            float big = rnd.nextFloat();
            r[i] = (big > 0.93f ? 2.2f : big > 0.7f ? 1.4f : 0.9f) * d;
            phase[i] = rnd.nextFloat() * 6.283f;
            speed[i] = 0.4f + rnd.nextFloat() * 1.4f;
            drift[i] = 0.002f + rnd.nextFloat() * 0.006f; // deriva lenta para a esquerda
        }
        star.setColor(0xFFDCE8FF);
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

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        sky.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF01020A, 0xFF040B2A, 0xFF0A1A4A},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        mist.setShader(new RadialGradient(w * 0.72f, h * 1.05f, Math.max(w, h) * 0.7f,
                0x553A6BFF, 0x003A6BFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, sky);
        c.drawRect(0, 0, w, h, mist);

        float t = animate ? (System.currentTimeMillis() - start) / 1000f : 0f;
        for (int i = 0; i < STARS; i++) {
            float px = x[i] - t * drift[i];
            px = px - (float) Math.floor(px);
            float tw = animate ? 0.55f + 0.45f * (float) Math.sin(phase[i] + t * speed[i]) : 0.8f;
            star.setAlpha((int) (40 + 215 * tw * (r[i] > 2f ? 1f : 0.75f)));
            c.drawCircle(px * w, y[i] * h, r[i], star);
        }
        if (animate && running) postInvalidateDelayed(FRAME_MS);
    }
}
