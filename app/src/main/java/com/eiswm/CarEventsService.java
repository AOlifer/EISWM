package com.eiswm;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * События машины для функций приложения: при выключении зажигания — прощание
 * ({@link Farewell}) и сводка с итогами поездки ({@link Trip}), при включении — начало поездки
 * и сводка ({@link CarSummary}). Работает в фоне,
 * пока включена хоть одна из них; после загрузки и пробуждения машины запускается из
 * {@link WakeReceiver}.
 * Зажигание: свойство машины SYSTEM_CAN_ACC_STATUS (0 — выключено); запасной признак
 * выключения — переход сервиса машины в режим ожидания. Магнитола после выключения работает
 * ещё около 30 с.
 */
public class CarEventsService extends Service {
    private static final int ACC_STATUS = 0x21400054;
    private static final int POWER_STANDBY = 0, POWER_RUNNING = 1;
    /** Повторно не прощаться, если оба признака выключения пришли почти одновременно. */
    private static final long REPEAT_GUARD_MS = 10_000;
    /** Процесс запустился вскоре после загрузки машины — зажигание только что включили. */
    private static final long FRESH_BOOT_MS = 180_000;
    /** Сводка ждёт, пока закроется штатный экран приветствия, но не дольше этого. */
    private static final long WELCOME_WAIT_MS = 60_000, WELCOME_POLL_MS = 500, AFTER_WELCOME_MS = 1500;
    /** Сводка на прощании: чуть позже картинки прощания, голос — после звука прощания. */
    private static final long FAREWELL_SUMMARY_DELAY_MS = 1000, SPEECH_AFTER_SOUND_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;
    private CarApi car;
    private Farewell farewell;
    private CarSummary summary;
    /** Последнее известное состояние ACC: null — ещё не пришло. */
    private Integer acc;
    private long lastFarewell;
    private boolean running;

    /** Запустить, если включено прощание или сводка; иначе остановить. */
    static void update(Context c) {
        Intent i = new Intent(c, CarEventsService.class);
        if (Farewell.isEnabled(c) || CarSummary.isAnyEnabled(c)) c.startService(i);
        else c.stopService(i);
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        farewell = new Farewell(this);
        summary = new CarSummary(this);
        thread = new HandlerThread("eiswm-events");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::connectCar);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Farewell.isEnabled(this) && !CarSummary.isAnyEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        ui.post(() -> {
            farewell.stop();
            summary.release();
        });
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            if (car != null) car.disconnect();
            thread.quitSafely();
        });
        super.onDestroy();
    }

    private void connectCar() {
        if (!CarApi.isAvailable()) return;
        car = new CarApi(this);
        try {
            car.connect(handler, this::onCarConnected, () -> acc = null);
        } catch (Throwable e) {
            CarDiag.log(this, "EVENTS car connect failed: " + e);
        }
    }

    private void onCarConnected() {
        if (!running) return;
        Object l = car.registerProperty(ACC_STATUS, 0f, new CarApi.PropertyListener() {
            @Override public void onChange(int id, int area, int status, Object value) {
                if (value instanceof Integer) onAcc((Integer) value);
            }

            @Override public void onError(int id, int area) {
            }
        });
        boolean power = car.registerPower(state -> {
            if (state == POWER_STANDBY) accOff("power standby");
            else if (state == POWER_RUNNING) cancelFarewell();
        });
        CarDiag.log(this, "EVENTS listening: acc " + (l != null) + ", power " + power);
    }

    private void onAcc(int value) {
        Integer prev = acc;
        acc = value;
        if (prev == null) {
            // Первое значение. Если процесс поднялся сразу после загрузки машины и зажигание
            // включено — это и есть старт: глубокий сон машины — фактически выключение.
            if (value != 0 && SystemClock.elapsedRealtime() < FRESH_BOOT_MS) accOn("boot");
            else if (value != 0) Trip.startIfMissing(this, CarSummary.read(car));
            return;
        }
        if (value == 0 && prev != 0) accOff("acc off");
        else if (value != 0 && prev == 0) {
            cancelFarewell();
            accOn("acc on");
        }
    }

    // ---------------------------------------------------------------- Выключение

    private void accOff(String reason) {
        ui.post(summary::hide);
        long now = System.currentTimeMillis();
        if (!running || now - lastFarewell < REPEAT_GUARD_MS) return;
        lastFarewell = now;
        // Итоги поездки — по значениям в момент выключения.
        handler.post(() -> {
            if (car != null && car.isConnected()) Trip.finish(this, CarSummary.read(car));
        });
        if (Farewell.isEnabled(this)) {
            ui.post(() -> {
                boolean played = farewell.play(Farewell.isSoundEnabled(this), Farewell.isPictureEnabled(this));
                CarDiag.log(this, "FAREWELL " + reason + (played ? ", played" : ", nothing to play"));
            });
        }
        // Сводка на прощании — поверх картинки прощания; голос — после звука прощания.
        if (CarSummary.isEnabled(this, Prefs.SUMMARY_FAREWELL)) {
            handler.postDelayed(() -> showSummary(Prefs.SUMMARY_FAREWELL), FAREWELL_SUMMARY_DELAY_MS);
        }
    }

    /** Зажигание снова включили — прощание и сводка прощания обрываются. */
    private void cancelFarewell() {
        lastFarewell = 0;
        handler.removeCallbacksAndMessages(null);
        ui.post(() -> {
            farewell.stop();
            summary.hide();
        });
    }

    // ---------------------------------------------------------------- Включение

    private void accOn(String reason) {
        if (!running) return;
        Trip.start(this, CarSummary.read(car));
        if (!CarSummary.isEnabled(this, Prefs.SUMMARY_WELCOME)) return;
        CarDiag.log(this, "SUMMARY " + reason + ", waiting for the welcome screen");
        waitForWelcome(SystemClock.elapsedRealtime() + WELCOME_WAIT_MS);
    }

    /** Ждать, пока закроется штатный экран приветствия (Settings.Global welcome_is_shown). */
    private void waitForWelcome(long deadline) {
        if (!running || acc == null || acc == 0) return;
        boolean welcomeShown = Settings.Global.getInt(getContentResolver(), "welcome_is_shown", 0) == 1;
        if (welcomeShown && SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed(() -> waitForWelcome(deadline), WELCOME_POLL_MS);
            return;
        }
        handler.postDelayed(() -> showSummary(Prefs.SUMMARY_WELCOME), AFTER_WELCOME_MS);
    }

    /** @param occasion Prefs.SUMMARY_WELCOME (зажигание включено) или Prefs.SUMMARY_FAREWELL (выключено) */
    private void showSummary(String occasion) {
        boolean welcome = Prefs.SUMMARY_WELCOME.equals(occasion);
        if (!running || car == null || acc == null || (welcome ? acc == 0 : acc != 0)) return;
        CarSummary.Values v = CarSummary.read(this, car);
        ui.post(() -> {
            long speechDelay = welcome ? 0 : farewell.soundMs() + SPEECH_AFTER_SOUND_MS;
            boolean shown = summary.show(occasion, v, speechDelay);
            CarDiag.log(this, "SUMMARY " + occasion + (shown ? "shown" : "nothing to show"));
        });
    }
}
