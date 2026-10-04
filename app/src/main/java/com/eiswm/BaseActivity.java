package com.eiswm;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/**
 * Общая основа экранов: применяет выбранную тему.
 * «Авто» следует ночному режиму системы, «Светлая» и «Тёмная» задают его принудительно.
 */
abstract class BaseActivity extends Activity {
    static final int THEME_AUTO = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    static final int THEME_COUNT = 3;

    /** Название темы на языке системы (массив theme_names в strings.xml). */
    String themeName(int mode) {
        return getResources().getStringArray(R.array.theme_names)[mode];
    }

    static int themeMode(Context c) {
        int mode = Prefs.get(c).getInt(Prefs.THEME, THEME_AUTO);
        return mode >= THEME_AUTO && mode <= THEME_DARK ? mode : THEME_AUTO;
    }

    @Override protected void attachBaseContext(Context base) {
        int mode = themeMode(base);
        if (mode != THEME_AUTO) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (mode == THEME_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }
}
