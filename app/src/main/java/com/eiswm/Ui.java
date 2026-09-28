package com.eiswm;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Общие элементы интерфейса, которые создаются из кода: строки списков, метки, кнопки. */
final class Ui {
    static final int PILL_OK = 0, PILL_BAD = 1, PILL_WARN = 2, PILL_NEUTRAL = 3;
    private static final int[] PILL_BG = {0xFFE6F4EA, 0xFFFCE8E6, 0xFFFEF7E0, 0xFFF1F3F4};
    private static final int[] PILL_FG = {0xFF137333, 0xFFC5221F, 0xFF8A5300, 0xFF5F6368};
    static final int ROW_PLAYING = 0x141A73E8;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Строка списка высотой не меньше 64 dp — удобно нажимать пальцем в машине. */
    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 68));
        row.setPadding(dp(c, 12), dp(c, 4), dp(c, 8), dp(c, 4));
        return row;
    }

    static TextView title(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(19);
        v.setTextColor(c.getColor(R.color.text_primary));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        return v;
    }

    static TextView pill(Context c) {
        TextView v = new TextView(c);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        setPill(v, "…", PILL_NEUTRAL);
        return v;
    }

    static void setPill(TextView v, String text, int kind) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(v.getContext(), 8));
        bg.setColor(PILL_BG[kind]);
        v.setBackground(bg);
        v.setTextColor(PILL_FG[kind]);
        v.setText(text);
    }

    /** Кнопка-значок без рамки (▶, ⋮) размером под палец. */
    static Button iconButton(Context c, String text) {
        Button b = new Button(c, null, 0, android.R.style.Widget_Material_Button_Borderless_Colored);
        b.setText(text);
        b.setTextSize(24);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    static LinearLayout.LayoutParams iconButtonParams(Context c) {
        return new LinearLayout.LayoutParams(dp(c, 64), dp(c, 60));
    }

    static void divider(LinearLayout parent) {
        View v = new View(parent.getContext());
        v.setBackgroundColor(parent.getContext().getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMarginStart(dp(parent.getContext(), 16));
        parent.addView(v, lp);
    }

    /** Сообщение на месте пустого списка: крупный заголовок и пояснение. */
    static void emptyState(LinearLayout parent, String headline, String details) {
        Context c = parent.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 32), dp(c, 48), dp(c, 32), dp(c, 48));
        TextView h = new TextView(c);
        h.setText(headline);
        h.setTextSize(20);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(c.getColor(R.color.text_primary));
        h.setGravity(Gravity.CENTER);
        box.addView(h);
        if (details != null) {
            TextView d = new TextView(c);
            d.setText(details);
            d.setTextSize(17);
            d.setTextColor(c.getColor(R.color.text_secondary));
            d.setGravity(Gravity.CENTER);
            d.setPadding(0, dp(c, 8), 0, 0);
            box.addView(d);
        }
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Вкладка или кнопка накопителя: выбранная подсвечивается фоном и цветом. */
    static void setTabSelected(TextView tab, boolean selected) {
        Context c = tab.getContext();
        if (selected) {
            tab.setBackgroundResource(R.drawable.tab_selected);
            tab.setTextColor(c.getColor(R.color.accent));
            tab.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            TypedValue tv = new TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            tab.setBackgroundResource(tv.resourceId);
            tab.setTextColor(c.getColor(R.color.text_secondary));
            tab.setTypeface(Typeface.DEFAULT);
        }
    }
}
