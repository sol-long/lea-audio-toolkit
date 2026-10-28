package com.dsh.leatool;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 纯代码 UI 的小工具集（无任何 XML 布局）。 */
final class Ui {

    static final int C_BG = 0xFFF3F4F6;
    static final int C_CARD = 0xFFFFFFFF;
    static final int C_TXT = 0xFF1F2328;
    static final int C_SUB = 0xFF6B7280;
    static final int C_ACCENT = 0xFF2F6FED;
    static final int C_OK = 0xFF1B8A4B;
    static final int C_WARN = 0xFFB26B00;
    static final int C_ERR = 0xFFC62828;
    static final int C_LINE = 0xFFE3E7ED;
    static final int C_FIELD = 0xFFF2F4F7;
    static final int C_HEADER = 0xFF1B2A41;
    static final int C_BTN2 = 0xFFE8EBF0;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static ViewGroup.MarginLayoutParams lp(int w, int h, int l, int t, int r, int b) {
        ViewGroup.MarginLayoutParams p = new ViewGroup.MarginLayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** 空白卡片外壳（无标题、无内边距覆盖），供需要自定义头部的页面使用。 */
    static LinearLayout cardShell(Context c, LinearLayout parent) {
        LinearLayout card = column(c);
        styleCard(c, card, false);
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        card.setElevation(dp(c, 1.5f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
        parent.addView(card, p);
        return card;
    }

    /** 卡片背景：current=true 时用蓝色描边强调（向导当前步）。 */
    static void styleCard(Context c, LinearLayout card, boolean current) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(current ? 0xFFF7FAFF : C_CARD);
        bg.setCornerRadius(dp(c, 14));
        if (current) {
            bg.setStroke(dp(c, 2), C_ACCENT);
        } else {
            bg.setStroke(dp(c, 1), 0x11000000);
        }
        card.setBackground(bg);
    }

    /** 卡片容器（带标题），返回卡片内部的竖直线性布局。 */
    static LinearLayout card(Context c, LinearLayout parent, String title) {
        LinearLayout card = cardShell(c, parent);
        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(11.5f);
            t.setTextColor(C_SUB);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(0.06f);
            card.addView(t, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(c, 6)));
        }
        return card;
    }

    static TextView caption(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(11.5f);
        t.setTextColor(C_SUB);
        return t;
    }

    static TextView value(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14f);
        t.setTextColor(C_TXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    static TextView note(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(C_SUB);
        t.setLineSpacing(dp(c, 2), 1f);
        return t;
    }

    /** 在卡片里加一组「小标题 + 值」，返回值 TextView 以便刷新。 */
    static TextView kv(Context c, LinearLayout card, String label) {
        card.addView(caption(c, label), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(c, 4), 0, 0));
        TextView v = value(c, "—");
        card.addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(c, 2)));
        return v;
    }

    static void divider(Context c, LinearLayout parent) {
        View v = new View(c);
        v.setBackgroundColor(C_LINE);
        parent.addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1),
                0, dp(c, 8), 0, dp(c, 8)));
    }

    static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFFFFFFFF : C_TXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? C_ACCENT : C_BTN2);
        bg.setCornerRadius(dp(c, 11));
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x55FFFFFF : 0x22000000), bg, null));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        b.setStateListAnimator(null);
        b.setElevation(0);
        return b;
    }

    static Button small(Context c, String text, boolean primary) {
        Button b = button(c, text, primary);
        b.setTextSize(12f);
        b.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        return b;
    }

    /** 横向排列一排按钮（等分）。 */
    static LinearLayout buttonRow(Context c, LinearLayout parent, Button... buttons) {
        LinearLayout r = row(c);
        parent.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(c, 8), 0, 0));
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                p.setMarginStart(dp(c, 8));
            }
            r.addView(buttons[i], p);
        }
        return r;
    }

    static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14f);
        e.setSingleLine(true);
        e.setTypeface(Typeface.MONOSPACE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_FIELD);
        bg.setCornerRadius(dp(c, 10));
        bg.setStroke(dp(c, 1), C_LINE);
        e.setBackground(bg);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    static TextView monoBlock(Context c) {
        TextView t = new TextView(c);
        t.setTextSize(10.5f);
        t.setTextColor(C_TXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setHorizontallyScrolling(true);
        t.setLineSpacing(dp(c, 1), 1f);
        return t;
    }

    static TextView sectionTitle(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(C_SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.05f);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 2));
        return t;
    }

    static String nz(String s) {
        return (s == null || s.trim().isEmpty()) ? "—" : s;
    }
}
