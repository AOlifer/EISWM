package com.eiswm;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Вкладки «Приветствие | Прощание» вверху раздела «Звуки», «Картинки» или «Сводка». Каждая вкладка
 * показывает свой список слева и свою панель справа, остальное скрывает. Пока прощание
 * выключено ({@link Features#FAREWELL}), вкладок нет и видно только «Приветствие».
 */
final class SectionTabs {
    static final int WELCOME = 0, FAREWELL = 1;

    interface Listener {
        void onTabChanged(int tab);
    }

    private final TextView[] items = new TextView[2];
    private final View[][] views;
    private final Listener listener;
    private int current = -1;
    private final boolean enabled;

    /**
     * @param containerId строка вкладок в разметке
     * @param welcomeViews список и панель вкладки «Приветствие»
     * @param farewellViews список и панель вкладки «Прощание»
     */
    SectionTabs(Activity a, int containerId, int[] welcomeViews, int[] farewellViews, Listener listener) {
        this(a, containerId, welcomeViews, farewellViews, Features.FAREWELL,
                new int[]{R.string.tab_welcome, R.string.tab_farewell}, listener);
    }

    /**
     * @param enabled false — вкладок нет, видно только первую (функция второй выключена)
     * @param labels названия вкладок
     */
    SectionTabs(Activity a, int containerId, int[] welcomeViews, int[] farewellViews, boolean enabled,
                int[] labels, Listener listener) {
        this.listener = listener;
        this.enabled = enabled;
        views = new View[][]{find(a, welcomeViews), find(a, farewellViews)};
        LinearLayout row = a.findViewById(containerId);
        if (!enabled) {
            // Без второй функции вкладок нет: строка вкладок и разделитель под ней скрыты.
            row.setVisibility(View.GONE);
            ViewGroup parent = (ViewGroup) row.getParent();
            View divider = parent.getChildAt(parent.indexOfChild(row) + 1);
            if (divider != null) divider.setVisibility(View.GONE);
            for (View v : views[FAREWELL]) v.setVisibility(View.GONE);
            current = WELCOME;
            return;
        }
        int[] icons = {R.drawable.ic_welcome, R.drawable.ic_farewell};
        for (int i = 0; i < 2; i++) {
            final int tab = i;
            TextView item = Ui.railItem(a, icons[i], a.getString(labels[i]), false);
            item.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8));
            item.setCompoundDrawablePadding(Ui.dp(a, 10));
            item.setTextSize(18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(a, 8));
            item.setOnClickListener(v -> select(tab));
            row.addView(item, lp);
            items[i] = item;
        }
        select(WELCOME);
    }

    private static View[] find(Activity a, int[] ids) {
        View[] v = new View[ids.length];
        for (int i = 0; i < ids.length; i++) v[i] = a.findViewById(ids[i]);
        return v;
    }

    int current() {
        return current;
    }

    void select(int tab) {
        if (tab != WELCOME && tab != FAREWELL || !enabled) tab = WELCOME;
        if (items[0] == null) return;
        boolean changed = tab != current;
        current = tab;
        for (int i = 0; i < 2; i++) {
            Ui.setSelected(items[i], i == tab);
            for (View v : views[i]) v.setVisibility(i == tab ? View.VISIBLE : View.GONE);
        }
        if (changed) listener.onTabChanged(tab);
    }
}
