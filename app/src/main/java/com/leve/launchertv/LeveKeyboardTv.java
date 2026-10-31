package com.leve.launchertv;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Leve Teclado TV — teclado na tela feito para o controle remoto.
 *
 * - Setas andam pelas teclas e dão a volta nas bordas; "cima" na primeira linha fecha o teclado.
 * - OK segurado numa vogal escreve acento (a → á, ã, â, à).
 * - ⌫ segurado apaga rápido; ◀ ▶ movem o cursor no texto.
 * - Sugestões: palavras que você já digitou e buscas recentes (↺) aparecem em cima.
 * - Números do controle digitam direto; Play/Pausa pula para o espaço.
 * - Continua obedecendo ao celular (setas no player, digitar pelo telefone).
 */
public class LeveKeyboardTv extends LeveKeyboard {

    private static final String PREFS = "leve_kb";
    private TvKeyboardView kb;
    private SharedPreferences prefs;
    private Map<String, Integer> words;   // palavra → quantas vezes usada
    private List<String> recent;          // buscas/textos recentes (mais novo primeiro)

    // OK segurado (acento) e repetição do ⌫
    private boolean centerDown, centerLong;
    private long lastAccentAt;
    private int lastAccentKey = -1, lastAccentIdx = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadHistory();
    }

    @Override
    public View onCreateInputView() {
        kb = new TvKeyboardView(this);
        updateBar();
        return kb;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (kb == null) return;
        try {
            kb.setEnterLabel(enterLabel(info));
            boolean capFirst = (info.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
                    || (info.inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0;
            if (!restarting) kb.resetCase(capFirst && currentText().isEmpty());
            refreshSuggestions();
        } catch (Throwable t) {
            LeveApp.log("LeveTecladoTV.start", t);
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        rememberText(currentText());
        super.onFinishInputView(finishingInput);
    }

    @Override
    void updateBar() {
        if (kb == null) return;
        String ip = LeveMusicService.localIp();
        kb.setHint(RemoteHub.hasClients()
                ? getString(R.string.kbtv_hint_phone)
                : getString(R.string.kbtv_hint, ip.isEmpty() ? "" : ip + ":" + LeveMusicService.PORT));
    }

    private String enterLabel(EditorInfo info) {
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEARCH: return "Buscar";
            case EditorInfo.IME_ACTION_GO: return "Ir";
            case EditorInfo.IME_ACTION_NEXT: return "Próximo";
            case EditorInfo.IME_ACTION_SEND: return "Enviar";
            case EditorInfo.IME_ACTION_DONE: return "OK";
            default: return "Enter";
        }
    }

    // ---------- Controle remoto ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (!isInputViewShown() || kb == null) return super.onKeyDown(keyCode, e);
        try {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    kb.moveH(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    kb.moveH(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!kb.moveV(-1)) requestHideSelf(0); // saiu por cima: volta para o app
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    kb.moveV(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (e.getRepeatCount() == 0) {
                        centerDown = true;
                        centerLong = false;
                        TvKeyboardView.Key k = kb.focused();
                        // ⌫ apaga já no aperto e continua apagando enquanto segura
                        if (k != null && k.type == TvKeyboardView.K_DEL) {
                            press(k);
                            centerLong = true;
                        }
                    } else {
                        TvKeyboardView.Key k = kb.focused();
                        if (k != null && k.type == TvKeyboardView.K_DEL) {
                            press(k); // repetição
                        } else if (!centerLong && e.getRepeatCount() >= 1) {
                            centerLong = true;
                            longPress(k);
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                    commit(" ");
                    kb.focusType(TvKeyboardView.K_SPACE);
                    return true;
                case KeyEvent.KEYCODE_DEL:
                    delete();
                    return true;
                case KeyEvent.KEYCODE_PROG_RED:
                    delete();
                    return true;
                case KeyEvent.KEYCODE_PROG_GREEN:
                    doEnter();
                    return true;
            }
            // números e letras do controle/teclado físico digitam direto
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                commit(String.valueOf((char) ('0' + keyCode - KeyEvent.KEYCODE_0)));
                return true;
            }
            int uc = e.getUnicodeChar();
            if (uc > 0 && !Character.isISOControl(uc)) {
                commit(String.valueOf((char) uc));
                return true;
            }
        } catch (Throwable t) {
            LeveApp.log("LeveTecladoTV.key", t);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (!isInputViewShown() || kb == null) return super.onKeyUp(keyCode, e);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (centerDown && !centerLong) {
                    try {
                        press(kb.focused());
                    } catch (Throwable t) {
                        LeveApp.log("LeveTecladoTV.press", t);
                    }
                }
                centerDown = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_PROG_GREEN:
                return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    // ---------- Teclas ----------

    private void press(TvKeyboardView.Key k) {
        if (k == null) return;
        switch (k.type) {
            case TvKeyboardView.K_CHAR:
                commit(k.value);
                kb.consumeShift();
                break;
            case TvKeyboardView.K_SPACE:
                commit(" ");
                break;
            case TvKeyboardView.K_DEL:
                delete();
                break;
            case TvKeyboardView.K_SHIFT:
                kb.toggleShift();
                break;
            case TvKeyboardView.K_SYM:
                kb.toggleSymbols();
                break;
            case TvKeyboardView.K_LEFT:
                cursor(-1);
                break;
            case TvKeyboardView.K_RIGHT:
                cursor(1);
                break;
            case TvKeyboardView.K_ENTER:
                doEnter();
                break;
            case TvKeyboardView.K_HIDE:
                requestHideSelf(0);
                break;
            case TvKeyboardView.K_SUGGEST:
                pickSuggestion(k);
                break;
        }
    }

    /** OK segurado: acento na vogal (segurar de novo troca o acento), maiúscula nas outras letras. */
    private void longPress(TvKeyboardView.Key k) {
        if (k == null) return;
        if (k.type == TvKeyboardView.K_CHAR) {
            String base = k.value.toLowerCase(Locale.ROOT);
            int ai = TvKeyboardView.ACCENT_KEYS.indexOf(base);
            boolean upper = !k.value.equals(base);
            if (ai >= 0 && !kb.isSymbols()) {
                String[] v = TvKeyboardView.ACCENTS[ai];
                long now = SystemClock.uptimeMillis();
                boolean again = lastAccentKey == ai && now - lastAccentAt < 2500;
                int idx = again ? (lastAccentIdx + 1) % v.length : 0;
                InputConnection ic = getCurrentInputConnection();
                if (again && ic != null) ic.deleteSurroundingText(1, 0); // troca o acento anterior
                String out = upper ? v[idx].toUpperCase(Locale.ROOT) : v[idx];
                commit(out);
                kb.consumeShift();
                lastAccentKey = ai;
                lastAccentIdx = idx;
                lastAccentAt = now;
            } else {
                commit(k.value.toUpperCase(Locale.ROOT));
            }
        } else if (k.type == TvKeyboardView.K_SPACE) {
            commit(". ");
        } else if (k.type == TvKeyboardView.K_SHIFT) {
            kb.toggleShift();
        } else {
            press(k);
        }
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
        refreshSuggestions();
    }

    private void delete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else {
            CharSequence before = ic.getTextBeforeCursor(2, 0);
            // apaga emoji/acentos compostos inteiros
            int n = before != null && before.length() == 2 && Character.isSurrogatePair(before.charAt(0), before.charAt(1)) ? 2 : 1;
            ic.deleteSurroundingText(n, 0);
        }
        refreshSuggestions();
    }

    private void cursor(int d) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        CharSequence after = ic.getTextAfterCursor(10000, 0);
        int pos = before == null ? 0 : before.length();
        int len = pos + (after == null ? 0 : after.length());
        int np = Math.max(0, Math.min(len, pos + d));
        ic.setSelection(np, np);
        refreshSuggestions();
    }

    private void doEnter() {
        rememberText(currentText());
        enter();
    }

    // ---------- Sugestões e histórico ----------

    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence b = ic.getTextBeforeCursor(40, 0);
        if (b == null) return "";
        int i = b.length();
        while (i > 0 && Character.isLetterOrDigit(b.charAt(i - 1))) i--;
        return b.subSequence(i, b.length()).toString();
    }

    private boolean isPassword() {
        if (editor == null) return false;
        int v = editor.inputType & InputType.TYPE_MASK_VARIATION;
        int c = editor.inputType & InputType.TYPE_MASK_CLASS;
        return c == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || c == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private void refreshSuggestions() {
        if (kb == null) return;
        List<TvKeyboardView.Key> out = new ArrayList<>();
        if (!isPassword()) {
            String all = currentText();
            String w = currentWord().toLowerCase(Locale.ROOT);
            if (all.trim().isEmpty()) {
                // campo vazio: buscas recentes
                for (String r : recent) {
                    if (out.size() >= 4) break;
                    TvKeyboardView.Key k = new TvKeyboardView.Key(TvKeyboardView.K_SUGGEST, r, r, 1f);
                    k.recent = true;
                    out.add(k);
                }
            } else if (!w.isEmpty()) {
                // completar a palavra: as mais usadas que começam igual
                List<Map.Entry<String, Integer>> m = new ArrayList<>();
                for (Map.Entry<String, Integer> en : words.entrySet()) {
                    if (en.getKey().startsWith(w) && !en.getKey().equals(w)) m.add(en);
                }
                Collections.sort(m, (a, b) -> b.getValue() - a.getValue());
                for (Map.Entry<String, Integer> en : m) {
                    if (out.size() >= 4) break;
                    out.add(new TvKeyboardView.Key(TvKeyboardView.K_SUGGEST, en.getKey(), en.getKey(), 1f));
                }
                // e buscas recentes que começam com o que já foi digitado
                String allLow = all.toLowerCase(Locale.ROOT);
                for (String r : recent) {
                    if (out.size() >= 5) break;
                    if (r.toLowerCase(Locale.ROOT).startsWith(allLow) && !r.equalsIgnoreCase(all)) {
                        TvKeyboardView.Key k = new TvKeyboardView.Key(TvKeyboardView.K_SUGGEST, r, r, 1f);
                        k.recent = true;
                        out.add(k);
                    }
                }
            }
        }
        kb.setSuggestions(out);
    }

    private void pickSuggestion(TvKeyboardView.Key k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (k.recent) {
            setText(k.value); // busca recente: troca o campo todo
        } else {
            String w = currentWord();
            ic.beginBatchEdit();
            ic.deleteSurroundingText(w.length(), 0);
            boolean upper = !w.isEmpty() && Character.isUpperCase(w.charAt(0));
            String s = upper ? k.value.substring(0, 1).toUpperCase(Locale.ROOT) + k.value.substring(1) : k.value;
            ic.commitText(s + " ", 1);
            ic.endBatchEdit();
        }
        refreshSuggestions();
        kb.focusType(TvKeyboardView.K_ENTER); // depois de escolher, geralmente é buscar
    }

    private void rememberText(String t) {
        if (t == null || isPassword()) return;
        t = t.trim();
        if (t.length() < 2 || t.length() > 80) return;
        recent.remove(t);
        recent.add(0, t);
        while (recent.size() > 10) recent.remove(recent.size() - 1);
        for (String w : t.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 3) continue;
            Integer n = words.get(w);
            words.put(w, n == null ? 1 : n + 1);
        }
        // guarda só as 400 palavras mais usadas
        if (words.size() > 400) {
            List<Map.Entry<String, Integer>> m = new ArrayList<>(words.entrySet());
            Collections.sort(m, (a, b) -> b.getValue() - a.getValue());
            words.clear();
            for (int i = 0; i < 300; i++) words.put(m.get(i).getKey(), m.get(i).getValue());
        }
        saveHistory();
    }

    private void loadHistory() {
        words = new LinkedHashMap<>();
        recent = new ArrayList<>();
        try {
            JSONObject w = new JSONObject(prefs.getString("words", "{}"));
            Iterator<String> it = w.keys();
            while (it.hasNext()) {
                String k = it.next();
                words.put(k, w.optInt(k, 1));
            }
            JSONArray r = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < r.length(); i++) recent.add(r.optString(i));
        } catch (Exception ignored) {
        }
    }

    private void saveHistory() {
        try {
            JSONObject w = new JSONObject();
            for (Map.Entry<String, Integer> en : words.entrySet()) w.put(en.getKey(), en.getValue());
            prefs.edit()
                    .putString("words", w.toString())
                    .putString("recent", new JSONArray(recent).toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }
}
