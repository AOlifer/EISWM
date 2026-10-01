package com.eiswm;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Запись событий машины в журнал диагностики ({@link CarDiag#log}): зажигание и питание,
 * изменения свойств машины, броадкасты, ключи Settings и свойства системы. Работает в фоне,
 * пока включена запись; после загрузки и пробуждения машины запускается из {@link WakeReceiver}.
 * Ничего не пишет в машину — только слушает.
 */
public class CarDiagService extends Service {
    private static final String CHANNEL = "diag";
    private static final int NOTIFICATION_ID = 1;

    /** Минимальный интервал записи одного и того же свойства (по каждой зоне). */
    private static final long ON_CHANGE_INTERVAL_MS = 1000;
    private static final long CONTINUOUS_INTERVAL_MS = 30_000;
    private static final long SYSPROP_POLL_MS = 1000;
    private static final String EXTRA_REASON = "reason";

    /** Броадкасты питания, приветствия и подключений, которые стоит записать. */
    private static final String[] ACTIONS = {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN,
            Intent.ACTION_DREAMING_STARTED, Intent.ACTION_DREAMING_STOPPED, Intent.ACTION_USER_PRESENT,
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
            "autochips.intent.action.QB_POWERON", "autochips.intent.action.QB_POWEROFF",
            "autochips.intent.action.PREQB_POWERON", "autochips.intent.action.PREQB_POWEROFF",
            "android.intent.action.ACTION_BOOT_IPO", "android.intent.action.ACTION_SHUTDOWN_IPO",
            "com.bw.action.LAUNCHER_BOOT_COMPLETED", "com.bw.intent.action.BOOT_COMPLETED",
            "com.bw.intent.action.WAKEUP", "com.bw.intent.action.REBOOT_SOON",
            "com.bw.intent.action.REST_TIME_SLEEP", "BW_EXIT_SPILT_SCREEN", "BW_ENTER_SPILT_SCREEN",
    };

    private HandlerThread thread;
    private Handler handler;
    private CarApi car;
    private BroadcastReceiver receiver;
    private ContentObserver settingsObserver;
    private volatile boolean running;

    private final Map<String, String> sysprops = new HashMap<>();
    private final Map<Integer, Integer> changeModes = new HashMap<>();
    /** Последнее записанное значение и время по ключу «id/зона». */
    private final Map<String, String> lastValue = new HashMap<>();
    private final Map<String, Long> lastTime = new HashMap<>();
    private final Map<String, Integer> skipped = new HashMap<>();

    /** @param reason причина запуска для журнала: кнопка, загрузка, пробуждение машины. */
    static void start(Context c, String reason) {
        Intent i = new Intent(c, CarDiagService.class).putExtra(EXTRA_REASON, reason);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, CarDiagService.class));
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification());
        running = true;
        thread = new HandlerThread("eiswm-diag");
        thread.start();
        handler = new Handler(thread.getLooper());
        CarDiag.log(this, "=== recording started, " + Build.DISPLAY + processAge());
        handler.post(this::connectCar);
        registerBroadcasts();
        registerSettings();
        handler.post(this::pollSysprops);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = intent != null ? intent.getStringExtra(EXTRA_REASON) : null;
        CarDiag.log(this, "START " + (reason != null ? reason : "restart by system") + processAge());
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        CarDiag.log(this, "=== recording stopped");
        if (receiver != null) unregisterReceiver(receiver);
        if (settingsObserver != null) getContentResolver().unregisterContentObserver(settingsObserver);
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            if (car != null) car.disconnect();
            thread.quitSafely();
        });
        super.onDestroy();
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.diag_title), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DiagnosticsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.diag_title))
                .setContentText(getString(R.string.diag_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    // ---------------------------------------------------------------- Машина

    private void connectCar() {
        if (!CarApi.isAvailable()) {
            CarDiag.log(this, "CAR bw.car.proxy library is not available");
            return;
        }
        car = new CarApi(this);
        try {
            car.connect(handler, this::onCarConnected, () -> CarDiag.log(this, "CAR service disconnected"));
        } catch (Throwable e) {
            CarDiag.log(this, "CAR connect failed: " + e);
        }
    }

    private void onCarConnected() {
        if (!running) return;
        CarDiag.log(this, "CAR connected, power state " + CarDiag.powerState(parse(car.query("power", "getPowerState")))
                + ", previous " + CarDiag.powerState(parse(car.query("power", "getPrePowerState")))
                + ", boot reason " + car.query("power", "getBootReason"));
        boolean power = car.registerPower(state -> CarDiag.log(this, "POWER " + CarDiag.powerState(state)));
        if (!power) CarDiag.log(this, "POWER listener failed");

        List<CarApi.PropertyConfig> list;
        try {
            list = car.propertyList();
        } catch (Throwable e) {
            CarDiag.log(this, "CAR property list failed: " + e);
            return;
        }
        int ok = 0, failed = 0;
        for (CarApi.PropertyConfig p : list) {
            if (p.changeMode == 0) continue; // STATIC: не меняется
            changeModes.put(p.id, p.changeMode);
            float rate = p.changeMode == 2 ? Math.max(p.minRate, Math.min(1f, p.maxRate)) : 0f;
            Object l = car.registerProperty(p.id, rate, new CarApi.PropertyListener() {
                @Override public void onChange(int id, int area, int status, Object value) {
                    onProperty(id, area, status, value);
                }

                @Override public void onError(int id, int area) {
                    CarDiag.log(CarDiagService.this, "PROP ERROR " + CarDiag.prop(CarDiagService.this, id)
                            + " area " + CarDiag.hex(area));
                }
            });
            if (l != null) ok++;
            else failed++;
        }
        CarDiag.log(this, "CAR properties: " + list.size() + ", listening " + ok + ", failed " + failed);
    }

    /**
     * Записать изменение свойства. Повторы того же значения пропускаются; частые изменения
     * прореживаются (непрерывные свойства — раз в 30 с, кроме перехода через ноль).
     */
    private void onProperty(int id, int area, int status, Object value) {
        if (!running) return;
        String key = id + "/" + area;
        String v = CarDiag.value(value);
        String prev = lastValue.get(key);
        if (v.equals(prev)) return;
        long now = System.currentTimeMillis();
        Long last = lastTime.get(key);
        boolean continuous = Objects.equals(changeModes.get(id), 2);
        long interval = continuous ? CONTINUOUS_INTERVAL_MS : ON_CHANGE_INTERVAL_MS;
        boolean zeroCrossing = prev != null && isZero(prev) != isZero(v);
        if (last != null && now - last < interval && !zeroCrossing) {
            Integer n = skipped.get(key);
            skipped.put(key, n == null ? 1 : n + 1);
            return;
        }
        Integer n = skipped.remove(key);
        lastValue.put(key, v);
        lastTime.put(key, now);
        CarDiag.log(this, "PROP " + CarDiag.prop(this, id) + " area " + CarDiag.hex(area)
                + " = " + (CarDiag.isSecretProp(this, id) ? "<hidden>" : v) + (status != 0 ? " status=" + status : "")
                + (n != null ? "  (+" + n + " skipped)" : ""));
    }

    private static boolean isZero(String v) {
        return v.equals("0") || v.equals("0.0") || v.equals("false");
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- Android

    private void registerBroadcasts() {
        receiver = new BroadcastReceiver() {
            // Имя устройства — только для журнала; без права BLUETOOTH ловится исключение.
            @SuppressLint("MissingPermission")
            @Override public void onReceive(Context c, Intent i) {
                StringBuilder sb = new StringBuilder("BROADCAST ").append(i.getAction());
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    String name;
                    try {
                        name = d.getName();
                    } catch (Throwable e) {
                        name = "?";
                    }
                    sb.append(" device=").append(name);
                }
                CarDiag.log(c, sb.toString());
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : ACTIONS) f.addAction(a);
        registerReceiver(receiver, f, null, handler);
    }

    /** Любое изменение Settings: имя ключа и новое значение (секретные — скрыты). */
    private void registerSettings() {
        settingsObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                List<String> seg = uri.getPathSegments();
                if (seg.size() < 2) return;
                String table = seg.get(0), name = seg.get(1);
                CarDiag.log(CarDiagService.this, "SETTING " + table + " " + name + " = "
                        + CarDiag.safeValue(name, readSetting(table, name)));
            }
        };
        for (String table : new String[]{"global", "system", "secure"}) {
            getContentResolver().registerContentObserver(Uri.parse("content://settings/" + table),
                    true, settingsObserver);
        }
    }

    private String readSetting(String table, String name) {
        try (Cursor c = getContentResolver().query(Uri.parse("content://settings/" + table),
                new String[]{"value"}, "name=?", new String[]{name}, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return "?";
        }
    }

    private void pollSysprops() {
        if (!running) return;
        for (String k : CarDiag.WATCHED_SYSPROPS) {
            String v = CarDiag.sysprop(k);
            String prev = sysprops.put(k, v);
            if (!v.equals(prev)) CarDiag.log(this, "SYSPROP " + k + " = " + v);
        }
        handler.postDelayed(this::pollSysprops, SYSPROP_POLL_MS);
    }

    /** Сколько живёт процесс: после сна машины процесс запускается заново. */
    private static String processAge() {
        if (Build.VERSION.SDK_INT < 24) return "";
        long ms = SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis();
        return ", process age " + ms + " ms, uptime " + SystemClock.elapsedRealtime() / 1000 + " s";
    }

    /**
     * Запуск записи при загрузке и пробуждении машины. Во время сна машина убивает процесс
     * приложения; этот приёмник поднимает службу как можно раньше, если запись включена.
     */
    public static class WakeReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (Prefs.get(c).getBoolean(Prefs.DIAG_RECORDING, false)) start(c, "broadcast " + i.getAction());
        }
    }
}
