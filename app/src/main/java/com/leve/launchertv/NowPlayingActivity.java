package com.leve.launchertv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tela "Tocando agora" do Leve Music TV, no estilo do resto do launcher. */
public class NowPlayingActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private StarsView stars;
    private ImageView cover;
    private TextView title, artist, status, time, playBtn, label;
    private ProgressBar bar;
    private String coverFor = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.now_playing);
        stars = findViewById(R.id.stars);
        cover = findViewById(R.id.cover);
        title = findViewById(R.id.np_title);
        artist = findViewById(R.id.np_artist);
        status = findViewById(R.id.np_status);
        label = findViewById(R.id.np_label);
        time = findViewById(R.id.np_time);
        bar = findViewById(R.id.np_bar);
        playBtn = findViewById(R.id.btn_play);

        findViewById(R.id.btn_prev).setOnClickListener(v -> withService(s -> s.remotePrev()));
        findViewById(R.id.btn_back10).setOnClickListener(v -> withService(s -> s.remoteSeekBy(-10000)));
        playBtn.setOnClickListener(v -> withService(s -> s.remoteToggle()));
        findViewById(R.id.btn_fwd10).setOnClickListener(v -> withService(s -> s.remoteSeekBy(10000)));
        findViewById(R.id.btn_next).setOnClickListener(v -> withService(s -> s.remoteNext()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        LeveMusicService.start(this);
        stars.setAnimate(getSharedPreferences("leve", MODE_PRIVATE).getBoolean("stars", true));
        stars.setRunning(true);
        playBtn.requestFocus();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stars.setRunning(false);
        ui.removeCallbacks(tick);
    }

    private interface Action {
        void run(LeveMusicService s);
    }

    private void withService(Action a) {
        LeveMusicService s = LeveMusicService.get();
        if (s != null) a.run(s);
        refresh();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                withService(LeveMusicService::remoteToggle);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                withService(LeveMusicService::remoteNext);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                withService(LeveMusicService::remotePrev);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                withService(s -> s.remoteSeekBy(10000));
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                withService(s -> s.remoteSeekBy(-10000));
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void refresh() {
        LeveMusicService s = LeveMusicService.get();
        LeveMusicService.Track t = s == null ? null : s.current();
        String ip = LeveMusicService.localIp();
        String where = ip.isEmpty() ? "" : getString(R.string.np_ip, ip, LeveMusicService.PORT);

        if (t == null) {
            label.setText(R.string.np_label_idle);
            title.setText(R.string.np_idle_title);
            artist.setText(R.string.np_idle_hint);
            status.setText(where);
            time.setText("");
            bar.setProgress(0);
            cover.setImageResource(R.drawable.play);
            coverFor = "";
            playBtn.setText("▶");
            return;
        }

        String phone = s.phoneName();
        label.setText(phone.isEmpty() ? getString(R.string.np_label)
                : getString(R.string.np_label_from, phone));
        title.setText(t.title.isEmpty() ? "—" : t.title);
        artist.setText(t.artist);

        String err = s.error();
        if (!err.isEmpty()) status.setText(err);
        else if (s.isLoading()) status.setText(R.string.np_loading);
        else status.setText(where);

        long pos = s.position(), dur = s.duration();
        bar.setMax(1000);
        bar.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
        time.setText(fmt(pos) + "  /  " + (dur > 0 ? fmt(dur) : "--:--"));
        playBtn.setText(s.isPlaying() ? "❚❚" : "▶");

        if (!t.thumb.equals(coverFor)) {
            coverFor = t.thumb;
            loadCover(t.thumb);
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void loadCover(final String url) {
        cover.setImageResource(R.drawable.play);
        if (url == null || !url.startsWith("http")) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565; // metade da memória
                Bitmap bmp;
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                c.disconnect();
                if (bmp == null) return;
                // capa grande demais gasta RAM à toa
                int max = 480;
                if (bmp.getWidth() > max) {
                    Bitmap small = Bitmap.createScaledBitmap(bmp, max,
                            bmp.getHeight() * max / bmp.getWidth(), true);
                    if (small != bmp) bmp.recycle();
                    bmp = small;
                }
                final Bitmap fin = bmp;
                ui.post(() -> {
                    if (url.equals(coverFor)) cover.setImageBitmap(fin);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }
}
