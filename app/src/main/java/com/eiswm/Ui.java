package com.eiswm;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Общие элементы интерфейса, которые создаются из кода: строки списков, метки, кнопки. */
final class Ui {
    static final int PILL_OK = 0, PILL_BAD = 1, PILL_WARN = 2, PILL_NEUTRAL = 3;
    private static final int[] PILL_BG = {R.color.pill_ok_bg, R.color.pill_bad_bg, R.color.pill_warn_bg, R.color.pill_neutral_bg};
    private static final int[] PILL_FG = {R.color.pill_ok_fg, R.color.pill_bad_fg, R.color.pill_warn_fg, R.color.pill_neutral_fg};
    /** Названия сезонов по индексу {@link WelcomePictures#seasonIndex}: зима, весна, лето, осень. */
    static final int[] SEASON_TITLES = {
            R.string.season_winter, R.string.season_spring, R.string.season_summer, R.string.season_autumn};

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
        Context c = v.getContext();
        if (ms < 0) setPill(v, c.getString(R.string.duration_unknown), PILL_WARN);
        else if (ms > maxMs) setPill(v, c.getString(R.string.duration_cut, FileUtils.formatDuration(c, ms)), PILL_WARN);
        else setPill(v, FileUtils.formatDuration(c, ms), PILL_OK);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, 1);
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
        parent.addView(box, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    /**
     * Пункт боковой колонки: значок и подпись, выбранный подсвечивается.
     * vertical — значок над подписью (узкая колонка разделов), иначе значок слева.
     */
    static TextView railItem(Context c, int iconRes, String label, boolean vertical) {
        TextView v = new TextView(c, null, 0, R.style.EISWM_RailItem);
        v.setText(label);
        Drawable icon = c.getDrawable(iconRes).mutate();
        int size = dp(c, vertical ? 32 : 26);
        icon.setBounds(0, 0, size, size);
        if (vertical) {
            v.setCompoundDrawables(null, icon, null, null);
            v.setGravity(Gravity.CENTER);
            v.setPadding(dp(c, 4), dp(c, 12), dp(c, 4), dp(c, 10));
            v.setCompoundDrawablePadding(dp(c, 6));
            v.setTextSize(16);
        } else {
            v.setCompoundDrawables(icon, null, null, null);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.bottomMargin = dp(c, 6);
        v.setLayoutParams(lp);
        return v;
    }

    static void setSelected(TextView item, boolean selected) {
        Context c = item.getContext();
        int color = c.getColor(selected ? R.color.accent : R.color.text_secondary);
        item.setTextColor(color);
        item.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        if (selected) {
            item.setBackgroundResource(R.drawable.tab_selected);
            item.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            item.setBackgroundResource(selectableBackground(c));
            item.setTypeface(Typeface.DEFAULT);
        }
    }

    static int selectableBackground(Context c) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ---------------------------------------------------------------- Превью картинок

    /** Рамка превью с пропорциями экрана машины (8:3) на чёрном фоне. */
    static FrameLayout screenFrame(Context c) {
        FrameLayout frame = new FrameLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                int height = width * Images.HEIGHT / Images.WIDTH;
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        frame.setBackgroundColor(Color.BLACK);
        return frame;
    }

    /** Картинка во всю рамку превью, обрезанная по центру. */
    static ImageView addPreviewImage(FrameLayout frame, String description) {
        ImageView image = new ImageView(frame.getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(description);
        frame.addView(image, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        return image;
    }

    /**
     * Галочка выделения в левом верхнем углу превью. Тёмная подложка — чтобы галочку было видно
     * на светлых картинках (снег, небо).
     */
    static CheckBox addPreviewCheck(FrameLayout frame, int marginDp) {
        Context c = frame.getContext();
        CheckBox check = new CheckBox(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(c, 6));
        check.setBackground(bg);
        check.setScaleX(1.3f);
        check.setScaleY(1.3f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(c, marginDp), dp(c, marginDp), 0, 0);
        frame.addView(check, lp);
        return check;
    }
}
