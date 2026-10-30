package com.leve.launchertv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;

import java.util.List;

/**
 * Leve Mouse — cursor por cima de qualquer app, controlado pelo celular.
 * Também percebe quando a TV abre o teclado e escreve no campo o que você digitar no celular.
 */
public class LeveMouseService extends AccessibilityService {

    private static final long HIDE_AFTER_MS = 6000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ImageView cursor;
    private WindowManager.LayoutParams lp;
    private float x, y;
    private int screenW = 1920, screenH = 1080;
    private boolean kbShown;
    private boolean editableFocused;

    private final Runnable hide = () -> {
        if (cursor != null) cursor.setVisibility(View.GONE);
    };

    // ---------- Ciclo de vida ----------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            connect();
        } catch (Throwable t) {
            LeveApp.log("LeveMouse.onServiceConnected", t);
        }
    }

    private void connect() {
        RemoteHub.app = getApplicationContext();
        RemoteHub.mouse = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        readScreen();
        x = screenW / 2f;
        y = screenH / 2f;
        addCursor();
        RemoteHub.broadcastStatus();
        LeveMusicService.start(this); // o servidor do controle mora junto do Leve Music TV
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (RemoteHub.mouse == this) RemoteHub.mouse = null;
        removeCursor();
        RemoteHub.broadcastStatus();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (RemoteHub.mouse == this) RemoteHub.mouse = null;
        removeCursor();
        RemoteHub.broadcastStatus();
        return super.onUnbind(intent);
    }

    private void readScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(1, dm.widthPixels);
        screenH = Math.max(1, dm.heightPixels);
    }

    // ---------- Cursor ----------

    private void addCursor() {
        if (Build.VERSION.SDK_INT < 22 || cursor != null) return;
        cursor = new ImageView(this);
        cursor.setImageResource(R.drawable.cursor);
        int size = Math.round(32 * getResources().getDisplayMetrics().density);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = (int) x;
        lp.y = (int) y;
        cursor.setVisibility(View.GONE);
        try {
            wm.addView(cursor, lp);
        } catch (Exception e) {
            cursor = null;
        }
    }

    private void removeCursor() {
        h.removeCallbacks(hide);
        if (cursor != null) {
            try {
                wm.removeView(cursor);
            } catch (Exception ignored) {
            }
            cursor = null;
        }
    }

    private void showCursor() {
        if (cursor == null) return;
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        try {
            if (cursor.getVisibility() != View.VISIBLE) cursor.setVisibility(View.VISIBLE);
            wm.updateViewLayout(cursor, lp);
        } catch (Exception ignored) {
        }
        h.removeCallbacks(hide);
        h.postDelayed(hide, HIDE_AFTER_MS);
    }

    /** dx/dy chegam em "pontos do celular"; escalamos para a tela da TV. */
    void move(float dx, float dy) {
        float k = screenW / 1280f * 1.4f;
        x = clamp(x + dx * k, 0, screenW - 2);
        y = clamp(y + dy * k, 0, screenH - 2);
        showCursor();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- Cliques e rolagem ----------

    void click(boolean longPress) {
        showCursor();
        if (Build.VERSION.SDK_INT >= 24) {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, longPress ? 800 : 40))
                    .build();
            dispatchGesture(g, null, null);
        } else {
            AccessibilityNodeInfo n = nodeAt(Math.round(x), Math.round(y));
            if (n != null) {
                n.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }

    /** Arrasta a tela a partir do cursor (dois dedos no celular). */
    void scroll(float dx, float dy) {
        showCursor();
        float k = screenW / 1280f * 2.2f;
        float ex = clamp(x + dx * k, 1, screenW - 2);
        float ey = clamp(y + dy * k, 1, screenH - 2);
        if (Build.VERSION.SDK_INT >= 24) {
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(ex, ey);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 160))
                    .build();
            dispatchGesture(g, null, null);
        } else {
            AccessibilityNodeInfo n = nodeAt(Math.round(x), Math.round(y));
            while (n != null && !n.isScrollable()) n = n.getParent();
            if (n != null) {
                n.performAction(dy < 0 || dx < 0
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
    }

    void global(int action) {
        performGlobalAction(action);
    }

    /** Para Androids sem gestos (antes do 7): acha o item clicável embaixo do cursor. */
    private AccessibilityNodeInfo nodeAt(int px, int py) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo best = deepest(root, px, py);
        while (best != null && !best.isClickable()) best = best.getParent();
        return best;
    }

    private AccessibilityNodeInfo deepest(AccessibilityNodeInfo n, int px, int py) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(px, py) || !n.isVisibleToUser()) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo d = deepest(c, px, py);
            if (d != null) return d;
        }
        return n;
    }

    // ---------- Teclado ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            handleEvent(e);
        } catch (Throwable t) {
            LeveApp.log("LeveMouse.event", t);
        }
    }

    private void handleEvent(AccessibilityEvent e) {
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            h.removeCallbacks(checkKb);
            h.postDelayed(checkKb, 150);
        } else if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo src = e.getSource();
            editableFocused = src != null && src.isEditable();
            h.removeCallbacks(checkKb);
            h.postDelayed(checkKb, 300);
        }
    }

    private final Runnable checkKb = () -> {
        try {
            checkKeyboard();
        } catch (Throwable t) {
            LeveApp.log("LeveMouse.keyboard", t);
        }
    };

    private void checkKeyboard() {
        boolean imeVisible = false;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    imeVisible = true;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo field = focusedField();
        // Aberto quando o teclado da TV aparece, ou quando um campo de texto ganha foco
        boolean open = imeVisible || (editableFocused && field != null);
        if (open && !kbShown) {
            kbShown = true;
            String text = "", hint = "";
            if (field != null) {
                CharSequence cs = field.getText();
                text = cs == null ? "" : cs.toString();
                if (Build.VERSION.SDK_INT >= 26) {
                    CharSequence hs = field.getHintText();
                    hint = hs == null ? "" : hs.toString();
                    if (field.isShowingHintText()) text = "";
                }
            }
            RemoteHub.keyboard(true, text, hint);
        } else if (!open && kbShown) {
            kbShown = false;
            RemoteHub.keyboard(false, null, null);
        }
    }

    private AccessibilityNodeInfo focusedField() {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return n != null && n.isEditable() ? n : null;
    }

    void setText(String v) {
        AccessibilityNodeInfo n = focusedField();
        if (n == null) {
            RemoteHub.toast("Abra um campo de texto na TV primeiro");
            return;
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        // cursor no fim do texto
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, v.length());
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, v.length());
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
    }

    void enter() {
        AccessibilityNodeInfo n = focusedField();
        if (n != null && Build.VERSION.SDK_INT >= 30) {
            if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return;
        }
        RemoteHub.toast("Use o botão Buscar/Enter do teclado da TV (dá para clicar nele com o mouse)");
    }
}
