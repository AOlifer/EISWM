package com.eiswm;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения: один файл SharedPreferences и все его ключи в одном месте,
 * чтобы случайно не завести два ключа с одним именем. Имена ключей менять нельзя:
 * по ним читаются настройки, сохранённые прежними версиями.
 */
final class Prefs {
    private static final String FILE = "eiswm";

    // Оформление и первый запуск
    /** Тема: {@link BaseActivity#THEME_AUTO}, THEME_LIGHT или THEME_DARK. */
    static final String THEME = "theme";
    /** Отказ от ответственности уже показан. */
    static final String DISCLAIMER_SHOWN = "disclaimer_shown";

    // Звуки
    /** Встроенные MP3 уже скопированы в Notifications. */
    static final String BUNDLED_COPIED = "bundled_welcome_files_copied";

    // Картинки ({@link WelcomePictures})
    static final String PICTURES_DISABLED = "pictures_disabled";
    /** Записи, отложенные на время выключенного показа (JSON). */
    static final String PICTURES_PARKED = "pictures_parked";
    /** Стандартные картинки, добавленные навсегда. */
    static final String PICTURES_STD = "pictures_std";
    /** Стандартные картинки по временам года. */
    static final String PICTURES_SEASONAL = "pictures_seasonal";
    static final String PICTURES_FIRST_RUN_DONE = "pictures_first_run_done";

    // Обновления
    static final String UPDATE_LAST_CHECK = "update_last_check";
    /** Последняя найденная на сервере версия: строка «Доступна версия N» видна до установки. */
    static final String UPDATE_CODE = "update_available_code";
    static final String UPDATE_NAME = "update_available_name";
    /** Открыть приложение после установки обновления. */
    static final String UPDATE_REOPEN = "update_reopen";

    // Экран выбора файлов
    /** Последняя открытая папка; к ключу добавляется режим выбора. */
    static final String PICKER_LAST_DIR = "picker_last_dir_";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
