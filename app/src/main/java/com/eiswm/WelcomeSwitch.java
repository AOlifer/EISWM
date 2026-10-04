package com.eiswm;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Системный переключатель звукового приветствия лаунчера — глобальная настройка
 * bw_welcome_voice_switch (1 — включено, 0 — выключено, нет ключа — включено). С system uid читается и пишется
 * напрямую; если нет доступа, пробуем команду settings (запись — через su).
 */
final class WelcomeSwitch {
    private static final String KEY = "bw_welcome_voice_switch";
    /** Значение, которое прочитать не удалось. */
    static final int UNKNOWN = -1;

    private final ContentResolver resolver;

    WelcomeSwitch(ContentResolver resolver) {
        this.resolver = resolver;
    }

    /** Адрес настройки: по нему можно следить за изменениями из других приложений. */
    static Uri uri() {
        return Settings.Global.getUriFor(KEY);
    }

    /**
     * Нет настройки — так лаунчер считает приветствие включённым (Settings.Global.getInt(…, 1)
     * в DFSK_F517_WarningService). На многих машинах ключа нет, пока его никто не менял.
     */
    private static final int LAUNCHER_DEFAULT = 1;

    /** @return 0, 1, другое число из настройки или {@link #UNKNOWN}. */
    int read() {
        String value;
        try {
            value = Settings.Global.getString(resolver, KEY);
        } catch (SecurityException e) {
            value = runCommand("settings", "get", "global", KEY).trim();
            if (value.isEmpty()) return UNKNOWN;
        }
        if (value == null || "null".equals(value)) return LAUNCHER_DEFAULT;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** @return true, если после записи настройка действительно равна value. */
    boolean write(int value) {
        try {
            if (Settings.Global.putInt(resolver, KEY, value)) {
                if (read() == value) return true;
            }
        } catch (SecurityException ignored) {
        }

        String result = runRootCommand("settings", "put", "global", KEY, String.valueOf(value));
        return result != null && read() == value;
    }

    private static String runCommand(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String runRootCommand(String... command) {
        String[] rootCommand = new String[command.length + 2];
        rootCommand[0] = "su";
        rootCommand[1] = "root";
        System.arraycopy(command, 0, rootCommand, 2, command.length);
        return runCommand(rootCommand);
    }
}
