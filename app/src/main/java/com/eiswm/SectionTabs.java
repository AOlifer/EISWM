package com.eiswm;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Вкладки «Приветствие | Прощание» вверху раздела «Звуки» или «Картинки». Каждая вкладка
 * показывает свой список слева и свою панель справа, остальное скрывает.
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

    /**
     * @param containerId строка вкладок в разметке
     * @param welcomeViews список и панель вкладки «Приветствие»
     * @param farewellViews список и панель вкладки «Прощание»
     */
    SectionTabs(Activity a, int containerId, int[] welcomeViews, int[] farewellViews, Listener listener) {
        this.listener = listener;
        views = new View[][]{find(a, welcomeViews), find(a, farewellViews)};
        LinearLayout row = a.findViewById(containerId);
        int[] icons = {R.drawable.ic_welcome, R.drawable.ic_farewell};
        int[] labels = {R.string.tab_welcome, R.string.tab_farewell};
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
        if (tab != WELCOME && tab != FAREWELL) tab = WELCOME;
        boolean changed = tab != current;
        current = tab;
        for (int i = 0; i < 2; i++) {
            Ui.setSelected(items[i], i == tab);
            for (View v : views[i]) v.setVisibility(i == tab ? View.VISIBLE : View.GONE);
        }
        if (changed) listener.onTabChanged(tab);
    }
}
