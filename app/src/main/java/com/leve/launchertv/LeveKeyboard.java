package com.leve.launchertv;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

/**
 * Leve Teclado — teclado do sistema que obedece ao celular.
 *
 * Por ser o teclado ativo, ele consegue mandar teclas de verdade (setas, OK, voltar...)
 * para qualquer app, inclusive players de vídeo como o do YouCine, e escrever direto
 * no campo de texto. Na tela ele é só uma barrinha discreta quando um campo pede teclado.
 */
public class LeveKeyboard extends InputMethodService {

    static volatile LeveKeyboard instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView bar;
    protected EditorInfo editor;
    protected boolean inputShown;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            RemoteHub.broadcastStatus();
        } catch (Throwable t) {
            LeveApp.log("LeveTeclado.onCreate", t);
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        RemoteHub.broadcastStatus();
        super.onDestroy();
    }

    /** Ativo = é o teclado atual e está ligado a algum app. */
    static LeveKeyboard active() {
        LeveKeyboard k = instance;
        return k != null && k.getCurrentInputConnection() != null ? k : null;
    }

    // ---------- Tela (só uma barrinha) ----------

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // nunca ocupa a tela inteira
    }

    @Override
    public View onCreateInputView() {
        bar = (TextView) getLayoutInflater().inflate(R.layout.ime_bar, null);
        return bar;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        editor = attribute;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        editor = info;
        inputShown = true;
        try {
            updateBar();
            RemoteHub.keyboard(true, currentText(), info.hintText == null ? "" : info.hintText.toString());
        } catch (Throwable t) {
            LeveApp.log("LeveTeclado.startInputView", t);
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        inputShown = false;
        RemoteHub.keyboard(false, null, null);
    }

    void updateBar() {
        if (bar == null) return;
        String ip = LeveMusicService.localIp();
        if (RemoteHub.hasClients()) {
            bar.setText(getString(R.string.ime_bar_phone));
        } else {
            bar.setText(getString(R.string.ime_bar_nophone,
                    ip.isEmpty() ? "" : ip + ":" + LeveMusicService.PORT));
        }
    }

    /** Sem celular conectado, o OK do controle abre a escolha de teclado (para não ficar preso). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (inputShown && !RemoteHub.hasClients()
                && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && event.getRepeatCount() == 0 && event.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Teclas vindas do celular ----------

    /** Manda uma tecla de verdade para o app aberto. */
    boolean key(int code, boolean longPress) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        boolean dpad = code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER;
        int source = dpad ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_KEYBOARD;
        final long down = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source));
        if (longPress) {
            // segura: repetição com "toque longo", depois solta
            h.postDelayed(() -> {
                InputConnection c = getCurrentInputConnection();
                if (c == null) return;
                long now = SystemClock.uptimeMillis();
                c.sendKeyEvent(new KeyEvent(down, now, KeyEvent.ACTION_DOWN, code, 1, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS, source));
                c.sendKeyEvent(new KeyEvent(down, now + 10, KeyEvent.ACTION_UP, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source));
            }, 650);
        } else {
            long up = SystemClock.uptimeMillis();
            ic.sendKeyEvent(new KeyEvent(down, up, KeyEvent.ACTION_UP, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source));
        }
        return true;
    }

    // ---------- Texto ----------

    String currentText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(5000, 0);
        CharSequence after = ic.getTextAfterCursor(5000, 0);
        return (before == null ? "" : before.toString()) + (after == null ? "" : after.toString());
    }

    /** Troca todo o texto do campo pelo que está no celular. */
    boolean setText(String v) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || !inputShown) return false;
        CharSequence before = ic.getTextBeforeCursor(5000, 0);
        CharSequence after = ic.getTextAfterCursor(5000, 0);
        String now = (before == null ? "" : before.toString()) + (after == null ? "" : after.toString());
        if (now.equals(v)) return true;
        ic.beginBatchEdit();
        ic.finishComposingText();
        ic.deleteSurroundingText(before == null ? 0 : before.length(), after == null ? 0 : after.length());
        ic.commitText(v, 1);
        ic.endBatchEdit();
        return true;
    }

    /** Enter/Buscar/Ir — o que o campo pedir. */
    boolean enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        int action = editor == null ? EditorInfo.IME_ACTION_UNSPECIFIED
                : editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = editor != null && (editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (ic.performEditorAction(action)) return true;
        }
        return key(KeyEvent.KEYCODE_ENTER, false);
    }
}
