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

    // Прощание при выключении зажигания ({@link Farewell}): звук и картинка включаются отдельно
    static final String FAREWELL_SOUND = "farewell_sound";
    static final String FAREWELL_PICTURE = "farewell_picture";

    // Сводка ({@link CarSummary}): ключ = префикс случая (приветствие или прощание) + суффикс
    static final String SUMMARY_WELCOME = "summary_";
    static final String SUMMARY_FAREWELL = "farewell_summary_";
    static final String SUMMARY_ENABLED = "enabled";
    static final String SUMMARY_SPEAK = "speak";
    /** К суффиксу добавляется имя пункта (CarSummary.ITEMS). */
    static final String SUMMARY_ITEM = "item_";
    /** Порядок пунктов: имена через запятую. */
    static final String SUMMARY_ORDER = "order";

    // События машины ({@link CarEventsService})
    /** Последним видели выключение зажигания: следующее включение — новая поездка. */
    static final String EVENTS_ACC_OFF = "events_acc_off";

    // Предупреждения сводки ({@link Warnings}): ключ = WARN + имя + WARN_ON или WARN_LIMIT
    static final String WARN = "warn_";
    static final String WARN_ON = "_on";
    static final String WARN_LIMIT = "_limit";

    // Поездка ({@link Trip}): ключ = префикс начала или конца + суффикс значения
    static final String TRIP_START = "trip_start_";
    static final String TRIP_END = "trip_end_";
    static final String TRIP_TIME = "time";
    static final String TRIP_ODOMETER = "odometer";
    static final String TRIP_CHARGE = "charge";
    static final String TRIP_FUEL = "fuel";

    // Диагностика машины
    /** Идёт запись событий ({@link CarDiagService}); после перезагрузки и сна машины запись продолжается. */
    static final String DIAG_RECORDING = "diag_recording";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
