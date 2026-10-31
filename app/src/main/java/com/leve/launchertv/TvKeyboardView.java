package com.leve.launchertv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * O teclado inteiro é UMA view desenhada no Canvas: nada de dezenas de botões,
 * então abre rápido e gasta pouca memória. Só redesenha quando o foco ou o texto muda.
 */
public class TvKeyboardView extends View {

    // códigos das teclas especiais
    static final int K_CHAR = 0, K_SHIFT = 1, K_SYM = 2, K_SPACE = 3, K_DEL = 4, K_ENTER = 5,
            K_LEFT = 6, K_RIGHT = 7, K_SUGGEST = 8, K_HIDE = 9;

    static final class Key {
        final int type;
        final String label;   // o que aparece
        final String value;   // o que escreve (letras) ou o texto da sugestão
        final float span;     // largura relativa
        final RectF r = new RectF();
        boolean recent;       // sugestão vinda do histórico de buscas

        Key(int type, String label, String value, float span) {
            this.type = type;
            this.label = label;
            this.value = value;
            this.span = span;
        }
    }

    private static final String[] LETTERS = {"1234567890", "qwertyuiop", "asdfghjklç", "zxcvbnm-."};
    private static final String[] SYMBOLS = {"1234567890", "@#$%&*+=()", "!?'\":;/_~^", "<>[]{}|\\,."};

    // dicas de acento (mostradas no cantinho da tecla; OK segurado escreve)
    static final String ACCENT_KEYS = "aeioucn";
    static final String[][] ACCENTS = {
            {"á", "ã", "â", "à"}, {"é", "ê"}, {"í"}, {"ó", "õ", "ô"}, {"ú", "ü"}, {"ç"}, {"ñ"}};

    private final List<List<Key>> rows = new ArrayList<>();
    private final List<Key> suggestions = new ArrayList<>();
    private int fr = 2, fc = 0;              // tecla com foco (linha, coluna); linha 0 = sugestões
    private boolean shift, caps, symbols;
    private String enterLabel = "OK";
    private String hint = "";

    private final float dp;
    private final Paint keyBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Paint chipBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TvKeyboardView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        keyBg.setColor(0x1AFFFFFF);
        focusBg.setColor(0x6630C0FF);
        focusLine.setStyle(Paint.Style.STROKE);
        focusLine.setStrokeWidth(2 * dp);
        focusLine.setColor(0xFFBFE4FF);
        text.setColor(0xFFEAF1FF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        small.setColor(0xFF7F93C9);
        small.setTextAlign(Paint.Align.RIGHT);
        small.setTextSize(10 * dp);
        chipBg.setColor(0x26FFD27A);
        setFocusable(false);
        build();
    }

    // ---------- Layout ----------

    void build() {
        rows.clear();
        rows.add(new ArrayList<>(suggestions)); // linha 0: sugestões (pode ficar vazia)
        String[] src = symbols ? SYMBOLS : LETTERS;
        for (int i = 0; i < 3; i++) rows.add(charRow(src[i], 1f));
        List<Key> r4 = new ArrayList<>();
        r4.add(new Key(K_SHIFT, symbols ? "" : (caps ? "⇪" : "⇧"), "", 1f));
        for (char ch : src[3].toCharArray()) r4.add(charKey(String.valueOf(ch), 1f));
        if (symbols) r4.remove(0); // sem shift nos símbolos
        rows.add(r4);
        List<Key> r5 = new ArrayList<>();
        r5.add(new Key(K_SYM, symbols ? "ABC" : "?123", "", 1.4f));
        r5.add(new Key(K_LEFT, "◀", "", 0.9f));
        r5.add(new Key(K_SPACE, "espaço", " ", 3.2f));
        r5.add(new Key(K_RIGHT, "▶", "", 0.9f));
        r5.add(new Key(K_DEL, "⌫", "", 1.4f));
        r5.add(new Key(K_ENTER, enterLabel, "", 1.6f));
        r5.add(new Key(K_HIDE, "⌄", "", 0.8f));
        rows.add(r5);
        clampFocus();
        requestLayout();
        invalidate();
    }

    private List<Key> charRow(String s, float span) {
        List<Key> r = new ArrayList<>();
        for (char ch : s.toCharArray()) r.add(charKey(String.valueOf(ch), span));
        return r;
    }

    private Key charKey(String ch, float span) {
        boolean upper = !symbols && (shift || caps);
        String v = upper ? ch.toUpperCase() : ch;
        return new Key(K_CHAR, v, v, span);
    }

    void setSuggestions(List<Key> list) {
        suggestions.clear();
        suggestions.addAll(list);
        rows.set(0, new ArrayList<>(suggestions));
        if (fr == 0 && suggestions.isEmpty()) fr = 1;
        clampFocus();
        layoutKeys(getWidth());
        invalidate();
    }

    void setEnterLabel(String l) {
        enterLabel = l;
        build();
    }

    void setHint(String h) {
        hint = h == null ? "" : h;
        invalidate();
    }

    boolean isShift() { return shift; }
    boolean isCaps() { return caps; }
    boolean isSymbols() { return symbols; }

    void toggleShift() {
        if (symbols) return;
        if (!shift && !caps) shift = true;           // 1x: só a próxima letra
        else if (shift && !caps) { shift = false; caps = true; } // 2x: tudo maiúsculo
        else { shift = false; caps = false; }
        build();
    }

    /** Depois de escrever uma letra com shift simples, volta ao minúsculo. */
    void consumeShift() {
        if (shift && !caps) {
            shift = false;
            build();
        }
    }

    void toggleSymbols() {
        symbols = !symbols;
        build();
    }

    /** Começo de um campo: primeira letra maiúscula quando o campo pede (nomes, frases). */
    void resetCase(boolean capFirst) {
        caps = false;
        shift = capFirst;
        symbols = false;
        build();
    }

    // ---------- Foco (D-pad) ----------

    Key focused() {
        if (fr < 0 || fr >= rows.size()) return null;
        List<Key> r = rows.get(fr);
        if (r.isEmpty()) return null;
        return r.get(Math.max(0, Math.min(fc, r.size() - 1)));
    }

    private void clampFocus() {
        if (fr == 0 && rows.get(0).isEmpty()) fr = 1;
        if (fr >= rows.size()) fr = rows.size() - 1;
        List<Key> r = rows.get(fr);
        if (fc >= r.size()) fc = r.size() - 1;
        if (fc < 0) fc = 0;
    }

    /** Esquerda/direita dão a volta na linha (mais rápido chegar no outro lado). */
    void moveH(int d) {
        List<Key> r = rows.get(fr);
        if (r.isEmpty()) return;
        fc = (fc + d + r.size()) % r.size();
        invalidate();
    }

    /**
     * Cima/baixo vai para a tecla mais próxima na outra linha.
     * Retorna false quando sai pelo topo (para o teclado se esconder e voltar ao app).
     */
    boolean moveV(int d) {
        int target = fr + d;
        if (target == 0 && rows.get(0).isEmpty()) target = -1;
        if (target < 0) return false;
        if (target >= rows.size()) target = 1; // embaixo da última linha volta ao topo
        Key cur = focused();
        float cx = cur == null ? 0 : cur.r.centerX();
        List<Key> r = rows.get(target);
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < r.size(); i++) {
            float dd = Math.abs(r.get(i).r.centerX() - cx);
            if (dd < bestD) {
                bestD = dd;
                best = i;
            }
        }
        fr = target;
        fc = best;
        invalidate();
        return true;
    }

    /** Pula direto para uma tecla especial (ex.: botão Play do controle vai para o espaço). */
    void focusType(int type) {
        for (int i = 0; i < rows.size(); i++) {
            List<Key> r = rows.get(i);
            for (int j = 0; j < r.size(); j++) {
                if (r.get(j).type == type) {
                    fr = i;
                    fc = j;
                    invalidate();
                    return;
                }
            }
        }
    }

    // ---------- Medidas e desenho ----------

    private float keyH() { return 38 * dp; }
    private float chipH() { return 30 * dp; }
    private float gap() { return 5 * dp; }
    private float pad() { return 12 * dp; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float h = pad() + chipH() + gap() + 5 * (keyH() + gap()) + pad() * 0.6f;
        setMeasuredDimension(w, (int) h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xF2040B2A, 0xF20A1A4A, Shader.TileMode.CLAMP));
        layoutKeys(w);
    }

    private void layoutKeys(int w) {
        if (w <= 0) return;
        // teclado com largura máxima confortável e centralizado
        float maxW = Math.min(w - 2 * pad(), 980 * dp);
        float left = (w - maxW) / 2f;
        float y = pad();
        // sugestões
        List<Key> s = rows.get(0);
        if (!s.isEmpty()) {
            float x = left;
            float each = (maxW - gap() * (s.size() - 1)) / s.size();
            for (Key k : s) {
                k.r.set(x, y, x + each, y + chipH());
                x += each + gap();
            }
        }
        y += chipH() + gap();
        for (int i = 1; i < rows.size(); i++) {
            List<Key> r = rows.get(i);
            float units = 0;
            for (Key k : r) units += k.span;
            float unitW = (maxW - gap() * (r.size() - 1)) / Math.max(units, 10f);
            float rowW = units * unitW + gap() * (r.size() - 1);
            float x = left + (maxW - rowW) / 2f;
            for (Key k : r) {
                float kw = k.span * unitW;
                k.r.set(x, y, x + kw, y + keyH());
                x += kw + gap();
            }
            y += keyH() + gap();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        if (rows.get(1).isEmpty() || rows.get(1).get(0).r.width() == 0) layoutKeys(w);
        c.drawRect(0, 0, w, getHeight(), bg);
        float r = 10 * dp;
        Key f = focused();

        // linha das sugestões (ou a dica quando não há)
        if (rows.get(0).isEmpty()) {
            text.setTextSize(13 * dp);
            text.setColor(0xFF7F93C9);
            c.drawText(hint, w / 2f, pad() + chipH() / 2f + 5 * dp, text);
            text.setColor(0xFFEAF1FF);
        }

        for (List<Key> row : rows) {
            for (Key k : row) {
                boolean on = k == f;
                Paint fill = on ? focusBg : (k.type == K_SUGGEST ? chipBg : keyBg);
                c.drawRoundRect(k.r, r, r, fill);
                if (on) c.drawRoundRect(k.r, r, r, focusLine);

                float size = k.type == K_CHAR ? 19 : (k.type == K_SUGGEST ? 14 : 15);
                if (k.type == K_SPACE) size = 13;
                text.setTextSize(size * dp * (on ? 1.08f : 1f));
                String label = k.label;
                if (k.type == K_SUGGEST) label = ellipsize(label, k.r.width() - 16 * dp);
                if (k.type == K_SUGGEST && k.recent) label = "↺ " + label;
                if (k.type == K_SHIFT && (shift || caps)) text.setColor(0xFFFFD27A);
                c.drawText(label, k.r.centerX(), k.r.centerY() + size * dp * 0.36f, text);
                text.setColor(0xFFEAF1FF);

                // acento disponível: mostra no cantinho
                if (k.type == K_CHAR && !symbols) {
                    int ai = ACCENT_KEYS.indexOf(k.value.toLowerCase());
                    if (ai >= 0) {
                        c.drawText(ACCENTS[ai][0], k.r.right - 5 * dp, k.r.top + 11 * dp, small);
                    }
                }
            }
        }
    }

    private String ellipsize(String s, float max) {
        if (text.measureText(s) <= max) return s;
        String e = "…";
        int n = s.length();
        while (n > 1 && text.measureText(s.substring(0, n) + e) > max) n--;
        return s.substring(0, n) + e;
    }
}
