package com.eiswm;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/**
 * Общая основа экранов: применяет выбранную тему.
 * «Авто» следует ночному режиму системы, «Светлая» и «Тёмная» задают его принудительно.
 */
abstract class BaseActivity extends Activity {
    static final String PREFS = "eiswm";
    static final String PREF_THEME = "theme";
    static final int THEME_AUTO = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    static final String[] THEME_NAMES = {"авто", "светлая", "тёмная"};

    static int themeMode(Context c) {
        int mode = c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_THEME, THEME_AUTO);
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
