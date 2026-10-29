package com.leve.launchertv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Carrega os ícones em uma única thread de fundo e guarda versões pequenas
 * (no máximo 64dp) na memória. Assim a lista nunca trava esperando um ícone.
 */
final class IconCache {

    private final PackageManager pm;
    private final int sizePx;
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    IconCache(Context ctx) {
        pm = ctx.getPackageManager();
        sizePx = Math.round(64 * ctx.getResources().getDisplayMetrics().density);
    }

    void into(final String pkg, final ImageView view) {
        view.setTag(pkg);
        Bitmap hit;
        synchronized (cache) {
            hit = cache.get(pkg);
        }
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        worker.execute(() -> {
            final Bitmap bmp = load(pkg);
            if (bmp == null) return;
            synchronized (cache) {
                cache.put(pkg, bmp);
            }
            ui.post(() -> {
                if (pkg.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    /** Esquece ícones de apps que foram desinstalados ou atualizados. */
    void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private Bitmap load(String pkg) {
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(c);
            return b;
        } catch (Throwable e) {
            return null;
        }
    }
}
