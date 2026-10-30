package com.leve.launchertv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * Também faz setas/OK (movendo o foco do app), segurar-e-arrastar, abre apps e
 * escreve no campo de texto que a TV pedir.
 *
 * Leveza: o movimento que chega do celular é somado e aplicado no máximo ~30x por segundo,
 * e sem celular conectado o serviço ignora todos os eventos.
 */
public class LeveMouseService extends AccessibilityService {

    private static final long HIDE_AFTER_MS = 5000;
    private static final long MOVE_INTERVAL_MS = 33; // ~30 quadros por segundo
    private static final long DRAG_SEGMENT_MS = 40;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ImageView cursor;
    private WindowManager.LayoutParams lp;
    private float x, y;
    private int screenW = 1920, screenH = 1080;
    private boolean kbShown;
    private boolean editableFocused;

    // movimento acumulado (vem da thread do WebSocket)
    private final Object moveLock = new Object();
    private float pendDx, pendDy;
    private boolean movePosted;
    private long lastMoveApplied;

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
        disconnect();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    private void disconnect() {
        if (RemoteHub.mouse == this) RemoteHub.mouse = null;
        dragging = false;
        removeCursor();
        RemoteHub.broadcastStatus();
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
        int size = Math.round(30 * getResources().getDisplayMetrics().density);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = (int) x;
        lp.y = (int) y;
        // sem animação de janela: mover o cursor não dispara transições
        lp.windowAnimations = 0;
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
        int nx = Math.round(x), ny = Math.round(y);
        boolean wasHidden = cursor.getVisibility() != View.VISIBLE;
        if (wasHidden) cursor.setVisibility(View.VISIBLE);
        if (wasHidden || nx != lp.x || ny != lp.y) {
            lp.x = nx;
            lp.y = ny;
            try {
                wm.updateViewLayout(cursor, lp);
            } catch (Exception ignored) {
            }
        }
        h.removeCallbacks(hide);
        h.postDelayed(hide, dragging ? HIDE_AFTER_MS * 3 : HIDE_AFTER_MS);
    }

    private void hideCursorNow() {
        h.removeCallbacks(hide);
        hide.run();
    }

    /** Chamado na thread do WebSocket: só soma; a tela é atualizada em lote. */
    void queueMove(float dx, float dy) {
        synchronized (moveLock) {
            pendDx += dx;
            pendDy += dy;
            if (movePosted) return;
            movePosted = true;
        }
        long wait = MOVE_INTERVAL_MS - (SystemClock.uptimeMillis() - lastMoveApplied);
        h.postDelayed(applyMove, Math.max(0, wait));
    }

    private final Runnable applyMove = () -> {
        float dx, dy;
        synchronized (moveLock) {
            dx = pendDx;
            dy = pendDy;
            pendDx = pendDy = 0;
            movePosted = false;
        }
        lastMoveApplied = SystemClock.uptimeMillis();
        float k = screenW / 1280f * 1.4f;
        x = clamp(x + dx * k, 0, screenW - 2);
        y = clamp(y + dy * k, 0, screenH - 2);
        showCursor();
    };

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- Cliques e rolagem ----------

    void click(boolean longPress) {
        showCursor();
        tapAt(x, y, longPress);
    }

    private void tapAt(float px, float py, boolean longPress) {
        if (Build.VERSION.SDK_INT >= 24) {
            Path p = new Path();
            p.moveTo(px, py);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, longPress ? 800 : 40))
                    .build();
            dispatchGesture(g, null, null);
        } else {
            AccessibilityNodeInfo n = nodeAt(Math.round(px), Math.round(py));
            if (n != null) {
                n.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }

    /** Arrasta a tela a partir do cursor (dois dedos ou faixa da borda no celular). */
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

    // ---------- Segurar e arrastar ----------

    private boolean dragging;
    private boolean dragEndRequested;
    private float dragX, dragY, dragStartX, dragStartY;
    private GestureDescription.StrokeDescription dragStroke;

    void dragStart() {
        if (dragging) return;
        // aplica o movimento pendente antes de "apertar"
        h.removeCallbacks(applyMove);
        applyMove.run();
        dragging = true;
        dragEndRequested = false;
        dragX = dragStartX = x;
        dragY = dragStartY = y;
        showCursor();
        if (Build.VERSION.SDK_INT >= 26) {
            Path p = new Path();
            p.moveTo(x, y);
            dragStroke = new GestureDescription.StrokeDescription(p, 0, DRAG_SEGMENT_MS, true);
            dispatchDrag();
        }
    }

    void dragEnd() {
        if (!dragging) return;
        h.removeCallbacks(applyMove);
        applyMove.run();
        if (Build.VERSION.SDK_INT >= 26) {
            dragEndRequested = true; // a próxima etapa solta o "dedo"
        } else {
            // Android 7: um arrasto só, do ponto inicial ao final
            dragging = false;
            if (Build.VERSION.SDK_INT >= 24) {
                Path p = new Path();
                p.moveTo(dragStartX, dragStartY);
                p.lineTo(x, y);
                GestureDescription g = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, 450))
                        .build();
                dispatchGesture(g, null, null);
            }
        }
    }

    private void dispatchDrag() {
        try {
            GestureDescription g = new GestureDescription.Builder().addStroke(dragStroke).build();
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    h.post(LeveMouseService.this::nextDragSegment);
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    dragging = false;
                }
            }, h);
            if (!ok) dragging = false;
        } catch (Throwable t) {
            dragging = false;
            LeveApp.log("LeveMouse.drag", t);
        }
    }

    private void nextDragSegment() {
        if (!dragging || Build.VERSION.SDK_INT < 26) return;
        boolean last = dragEndRequested;
        Path p = new Path();
        p.moveTo(dragX, dragY);
        p.lineTo(x, y);
        dragX = x;
        dragY = y;
        try {
            dragStroke = dragStroke.continueStroke(p, 0, DRAG_SEGMENT_MS, !last);
        } catch (Throwable t) {
            dragging = false;
            return;
        }
        if (last) {
            dragging = false;
            dragEndRequested = false;
        }
        dispatchDrag();
    }

    // ---------- Setas e OK (movem o foco do app, como o controle remoto) ----------

    void dpad(String k) {
        hideCursorNow(); // usando setas, o cursor só atrapalha
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

        if (k.equals("ok") || k.equals("longok")) {
            boolean longPress = k.equals("longok");
            if (f == null) {
                tapAt(x, y, longPress);
                return;
            }
            boolean done = f.performAction(longPress
                    ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
            if (!done) {
                AccessibilityNodeInfo c = f;
                while (c != null && !c.isClickable()) c = c.getParent();
                if (c != null) done = c.performAction(longPress
                        ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
            }
            if (!done) {
                Rect r = new Rect();
                f.getBoundsInScreen(r);
                tapAt(r.exactCenterX(), r.exactCenterY(), longPress);
            }
            return;
        }

        int dir;
        switch (k) {
            case "up": dir = View.FOCUS_UP; break;
            case "down": dir = View.FOCUS_DOWN; break;
            case "left": dir = View.FOCUS_LEFT; break;
            default: dir = View.FOCUS_RIGHT; break;
        }

        if (f == null) {
            AccessibilityNodeInfo first = firstFocusable(getRootInActiveWindow(), 0);
            if (first != null) first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return;
        }
        AccessibilityNodeInfo next = f.focusSearch(dir);
        if (next != null && !next.equals(f) && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return;

        // chegou na borda: tenta rolar a lista
        AccessibilityNodeInfo s = f;
        while (s != null && !s.isScrollable()) s = s.getParent();
        if (s != null) {
            s.performAction(dir == View.FOCUS_DOWN || dir == View.FOCUS_RIGHT
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    private AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isFocusable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstFocusable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    // ---------- Outros ----------

    void global(int action) {
        performGlobalAction(action);
    }

    /** Abre um app da TV (serviço de acessibilidade pode abrir telas por trás). */
    boolean openApp(String pkg) {
        try {
            if (pkg.equals(getPackageName())) {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void openSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            RemoteHub.toast("Não deu para abrir as configurações");
        }
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
        // Sem celular conectado não há por que gastar nada (ex.: vendo filme no YouCine)
        if (!RemoteHub.hasClients()) {
            if (kbShown) kbShown = false;
            return;
        }
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
            h.postDelayed(checkKb, 200);
        } else if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo src = e.getSource();
            boolean editable = src != null && src.isEditable();
            if (editable == editableFocused && !editable) return; // nada mudou para o teclado
            editableFocused = editable;
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

    /** Quando um celular conecta, conta para ele se já tem um campo de texto aberto. */
    void recheckKeyboard() {
        kbShown = false;
        h.removeCallbacks(checkKb);
        h.postDelayed(checkKb, 100);
    }

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
