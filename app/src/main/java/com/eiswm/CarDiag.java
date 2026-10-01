package com.eiswm;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Диагностика машины: журнал событий ({@link CarDiagService}) и снимок состояния.
 * Файлы лежат в files/diag/ приложения; пользователь сохраняет их на флешку из
 * {@link DiagnosticsActivity}. Содержимое файлов — технический текст для разбора, не переводится.
 */
final class CarDiag {
    /** Журнал событий; при превышении размера старый переименовывается в events.1.log. */
    static final String LOG_NAME = "events.log";
    private static final String LOG_OLD_NAME = "events.1.log";
    private static final long LOG_MAX_BYTES = 4L * 1024 * 1024;

    /** Свойства системы, которые относятся к приветствию, прощанию и питанию. */
    static final String[] WATCHED_SYSPROPS = {
            "service.bootanim.exit", "service.bootanim.vehicle.exit",
            "init.svc.bootanim", "init.svc.banim_welcome", "init.svc.banim_leave",
            "runtime.backcar.status", "runtime.backcar.type",
            "sys.boot_completed", "vendor.bw.foreground.package",
    };

    /** Файлы анимаций приветствия и прощания, которые показывает BwCarService. */
    static final String[] ANIMATION_FILES = {
            "/system/media/welcomeanimation.zip", "/system/media/leaveanimation.zip",
            "/system/media/bootanimation.zip",
    };

    /**
     * Значения ключей Settings, в имени которых есть одно из этих слов (между _ и .), в файлы
     * не попадают: токены, номера, идентификаторы машины.
     */
    private static final Set<String> SECRET_WORDS = new HashSet<>(Arrays.asList(
            "token", "password", "passwd", "secret", "vin", "uuid", "tuid", "cid", "imei", "iccid",
            "number", "id", "account", "mac", "address", "ssid", "psk", "location", "latitude", "longitude",
            "home", "poiname"));

    /** Свойства машины, значения которых в снимок не попадают (идентификаторы машины). */
    private static final Set<String> SECRET_PROPS = new HashSet<>(Arrays.asList(
            "INFO_VIN", "SYSTEM_FCT_SETTING_VIN", "SYSTEM_FCT_SETTING_UUID", "SYSTEM_FCT_SETTING_TUID",
            "SYSTEM_FCT_SETTING_ICCID", "SYSTEM_FCT_SETTING_SERIAL_NUMBER", "SYSTEM_FCT_SETTING_TBOX_SK_DATA"));

    private static final Object LOCK = new Object();
    private static Map<Integer, String> names;

    private CarDiag() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "diag");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File logFile(Context c) {
        return new File(dir(c), LOG_NAME);
    }

    /** Дописать строку в журнал с меткой времени. */
    static void log(Context c, String line) {
        String stamped = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + line;
        synchronized (LOCK) {
            File f = logFile(c);
            if (f.length() > LOG_MAX_BYTES) {
                File old = new File(dir(c), LOG_OLD_NAME);
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(stamped);
                w.write('\n');
            } catch (Exception ignored) {
            }
        }
    }

    /** Последние строки журнала для экрана (не больше maxBytes с конца файла). */
    static String tail(Context c, int maxBytes) {
        File f = logFile(c);
        if (!f.exists()) return "";
        synchronized (LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long len = raf.length();
                long from = Math.max(0, len - maxBytes);
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                String s = new String(buf, StandardCharsets.UTF_8);
                if (from > 0) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0) s = s.substring(nl + 1);
                }
                return s;
            } catch (Exception e) {
                return "";
            }
        }
    }

    static void clearLog(Context c) {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            logFile(c).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir(c), LOG_OLD_NAME).delete();
        }
    }

    /** Все файлы диагностики: журналы и снимки. */
    static File[] files(Context c) {
        File[] f = dir(c).listFiles();
        if (f == null) return new File[0];
        Arrays.sort(f);
        return f;
    }

    // ---------------------------------------------------------------- Имена и значения

    /** Имя свойства по ID из assets/diag/property_names.txt; неизвестное — пустая строка. */
    static String name(Context c, int id) {
        synchronized (LOCK) {
            if (names == null) {
                names = new HashMap<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getAssets().open("diag/property_names.txt"), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        if (line.startsWith("#") || tab < 0) continue;
                        names.put((int) Long.parseLong(line.substring(0, tab), 16), line.substring(tab + 1));
                    }
                } catch (Exception ignored) {
                }
            }
            String n = names.get(id);
            return n != null ? n : "";
        }
    }

    static String hex(int v) {
        return String.format(Locale.US, "0x%08x", v);
    }

    static String prop(Context c, int id) {
        String n = name(c, id);
        return n.isEmpty() ? hex(id) : hex(id) + " " + n;
    }

    /** Значение свойства для журнала: массивы раскрываются, байты — в hex. */
    static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder("bytes[").append(b.length).append("]");
            for (int i = 0; i < Math.min(b.length, 64); i++) sb.append(String.format(Locale.US, " %02x", b[i]));
            if (b.length > 64) sb.append(" …");
            return sb.toString();
        }
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        if (v instanceof Object[]) return Arrays.deepToString((Object[]) v);
        return String.valueOf(v);
    }

    static String powerState(int s) {
        switch (s) {
            case 0: return "0 APU_STANDBY";
            case 1: return "1 APU_RUNNING";
            case 2: return "2 DEEP_SLEEP_ENTER";
            case 3: return "3 DEEP_SLEEP_EXIT";
            case 4: return "4 SHUTDOWN_ENTER";
            case 5: return "5 SHUTDOWN_CANCELLED";
            default: return String.valueOf(s);
        }
    }

    /** Свойство системы (android.os.SystemProperties закрыт в SDK, берётся через reflection). */
    static String sysprop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- Снимок

    /**
     * Снимок состояния: прошивка, файлы анимаций, ключи Settings, свойства машины с текущими
     * значениями. Долгая операция (сотни запросов к машине), вызывать не в потоке интерфейса.
     * @param car подключённый сервис машины или null
     * @return сохранённый файл
     */
    static File snapshot(Context c, CarApi car) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir(c), "snapshot-" + stamp + ".txt");
        try (FileWriter w = new FileWriter(out)) {
            w.write("EISWM car snapshot " + stamp + "\n");
            w.write("build: " + Build.DISPLAY + " / " + Build.FINGERPRINT + "\n");
            for (String k : new String[]{"persist.bw.car.type", "persist.bw.car.device",
                    "ro.vendor.bw.car_config_id", "ro.boot.selinux", "sys.ipo.disable"}) {
                w.write(k + " = " + sysprop(k) + "\n");
            }

            w.write("\n== System properties\n");
            for (String k : WATCHED_SYSPROPS) w.write(k + " = " + sysprop(k) + "\n");

            w.write("\n== Files\n");
            for (String path : ANIMATION_FILES) {
                File f = new File(path);
                w.write(path + (f.exists() ? "  EXISTS " + f.length() + " bytes" : "  missing") + "\n");
            }
            File[] media = new File("/system/media").listFiles();
            if (media != null) {
                Arrays.sort(media);
                w.write("/system/media:\n");
                for (File f : media) {
                    w.write("  " + f.getName() + (f.isDirectory() ? "/" : "  " + f.length()) + "\n");
                }
            }

            for (String table : new String[]{"global", "system", "secure"}) {
                w.write("\n== Settings." + table + "\n");
                writeSettings(c, table, w);
            }

            w.write("\n== Car service\n");
            if (car == null || !car.isConnected()) {
                w.write(CarApi.isAvailable() ? "not connected\n" : "bw.car.proxy library is not available\n");
                return out;
            }
            w.write("power.getPowerState = " + car.query("power", "getPowerState") + "\n");
            w.write("power.getPrePowerState = " + car.query("power", "getPrePowerState") + "\n");
            w.write("power.getBootReason = " + car.query("power", "getBootReason") + "\n");
            for (String m : new String[]{"isLocalAccOn", "isIllumOn", "isReverseActive", "isSpeedHigh",
                    "isScreenOn", "getNightMode", "getBrightness", "getStandbyMode", "getPowerOffTime",
                    "getScreenSaverTimeout", "getApuStatus", "getRemoteStatus"}) {
                w.write("car_setting." + m + " = " + car.query("car_setting", m) + "\n");
            }

            List<CarApi.PropertyConfig> list = car.propertyList();
            w.write("\n== Properties: " + list.size() + "\n");
            w.write("# id name | type access changeMode rate | area = status value\n");
            for (CarApi.PropertyConfig p : list) {
                w.write(prop(c, p.id) + " | " + p.type + " access=" + p.access + " change=" + p.changeMode
                        + " rate=" + p.minRate + ".." + p.maxRate + "\n");
                for (int area : p.areas) {
                    String v;
                    try {
                        Object[] sv = car.getProperty(p.id, area);
                        v = "status=" + sv[0] + " "
                                + (isSecretProp(c, p.id) ? "<hidden>" : value(sv[1]));
                    } catch (Throwable e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        v = "error " + cause.getClass().getSimpleName();
                    }
                    w.write("    area " + hex(area) + " = " + v + "\n");
                }
            }
        }
        return out;
    }

    /** Все ключи таблицы Settings; значения секретных ключей скрыты. */
    private static void writeSettings(Context c, String table, FileWriter w) throws Exception {
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://settings/" + table),
                new String[]{"name", "value"}, null, null, "name")) {
            if (cur == null) {
                w.write("(no access)\n");
                return;
            }
            while (cur.moveToNext()) {
                String n = cur.getString(0);
                w.write(n + " = " + safeValue(n, cur.getString(1)) + "\n");
            }
        } catch (Exception e) {
            w.write("(error " + e.getClass().getSimpleName() + ")\n");
        }
    }

    static String safeValue(String name, String value) {
        if (value == null) return "null";
        return isSecret(name) ? "<hidden, " + value.length() + " chars>" : value;
    }

    static boolean isSecretProp(Context c, int id) {
        return SECRET_PROPS.contains(name(c, id));
    }

    static boolean isSecret(String name) {
        if (name == null) return false;
        for (String word : name.toLowerCase(Locale.US).split("[_.\\-]")) {
            if (SECRET_WORDS.contains(word)) return true;
        }
        return false;
    }
}
