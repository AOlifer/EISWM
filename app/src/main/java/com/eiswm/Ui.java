package com.eiswm;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Общие элементы интерфейса, которые создаются из кода: строки списков, метки, кнопки. */
final class Ui {
    static final int PILL_OK = 0, PILL_BAD = 1, PILL_WARN = 2, PILL_NEUTRAL = 3;
    private static final int[] PILL_BG = {R.color.pill_ok_bg, R.color.pill_bad_bg, R.color.pill_warn_bg, R.color.pill_neutral_bg};
    private static final int[] PILL_FG = {R.color.pill_ok_fg, R.color.pill_bad_fg, R.color.pill_warn_fg, R.color.pill_neutral_fg};

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Строка списка высотой не меньше 68 dp — удобно нажимать пальцем в машине. */
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
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return v;
    }

    static TextView pill(Context c) {
        TextView v = new TextView(c);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setSingleLine(true);
        v.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        setPill(v, "…", PILL_NEUTRAL);
        return v;
    }

    static void setPill(TextView v, String text, int kind) {
        Context c = v.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 8));
        bg.setColor(c.getColor(PILL_BG[kind]));
        v.setBackground(bg);
        v.setTextColor(c.getColor(PILL_FG[kind]));
        v.setText(text);
    }

    /** Метка длительности звука: зелёная — до предела, жёлтая — машина оборвёт звук. */
    static void setDurationPill(TextView v, long ms, long maxMs) {
        if (ms < 0) setPill(v, "длительность неизвестна", PILL_WARN);
        else if (ms > maxMs) setPill(v, FileUtils.formatDuration(ms) + " · оборвётся на 6 с", PILL_WARN);
        else setPill(v, FileUtils.formatDuration(ms), PILL_OK);
    }

    /** Кнопка-значок без рамки (▶, ⋮, ✕) размером под палец. */
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
        Context c = parent.getContext();
        View v = new View(c);
        v.setBackgroundColor(c.getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMarginStart(dp(c, 16));
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

    /** Пункт боковой колонки: значок и подпись, выбранный подсвечивается. */
    static TextView railItem(Context c, String icon, String label) {
        TextView v = new TextView(c, null, 0, R.style.EISWM_RailItem);
        v.setText(icon + "   " + label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 4);
        v.setLayoutParams(lp);
        return v;
    }

    static void setSelected(TextView item, boolean selected) {
        Context c = item.getContext();
        if (selected) {
            item.setBackgroundResource(R.drawable.tab_selected);
            item.setTextColor(c.getColor(R.color.accent));
            item.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            item.setBackgroundResource(selectableBackground(c));
            item.setTextColor(c.getColor(R.color.text_secondary));
            item.setTypeface(Typeface.DEFAULT);
        }
    }

    static int selectableBackground(Context c) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
