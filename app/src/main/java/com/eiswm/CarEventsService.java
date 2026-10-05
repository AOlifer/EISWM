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
import android.util.Log;

/**
 * События машины для функций приложения: при включении зажигания — начало поездки ({@link Trip})
 * и сводка «Старт» ({@link CarSummary}); в конце поездки — сводка «Итоги» с итогами поездки и
 * прощание ({@link Farewell}, пока выключено). Работает в фоне, пока включена хоть одна из них;
 * после загрузки и пробуждения машины запускается из {@link WakeReceiver}.
 * <p>
 * Конец поездки для сводки «Итоги» — событие, выбранное на вкладке ({@link CarSummary#finishTrigger}):
 * переключение в P, P и отстёгнутый ремень водителя, P и открытая дверь водителя или стояночный
 * тормоз; машина при этом стоит. Выключение зажигания не годится: экран и усилитель гаснут сразу
 * (в коде оно оставлено — {@link CarSummary#FINISH_ACC_OFF}). Сводка не повторяется, пока машина
 * снова не поедет, и не показывается после совсем короткой поездки. Если после неё снова поехали,
 * поездка продолжается: итоги всегда от включения зажигания.
 * <p>
 * Зажигание: свойство машины SYSTEM_CAN_ACC_STATUS (0 — выключено); запасной признак
 * выключения — переход сервиса машины в режим ожидания. Подписка присылает только изменения,
 * поэтому при подключении текущее значение читается отдельно: после глубокого сна (это
 * перезагрузка) приложение стартует, когда зажигание уже давно включено.
 */
public class CarEventsService extends Service {
    private static final String TAG = "EISWM";
    private static final int ACC_STATUS = 0x21400054;
    private static final int POWER_STANDBY = 0, POWER_RUNNING = 1;
    private static final int GEAR_SELECTION = 0x11400400, GEAR_PARK = 4;
    private static final int PARKING_BRAKE_ON = 0x11200402;
    private static final int SEAT_BELT_BUCKLED = 0x15200b82, DOOR_POS = 0x16400b00;
    /** Ремень и дверь водителя (area 1, проверено диагностикой). */
    private static final int DRIVER = 1;
    private static final int PERF_VEHICLE_SPEED = 0x11600207;            // м/с
    /** Поехали: быстрее 10 км/ч. Стоим: медленнее 2 км/ч. */
    private static final float MOVING_MS = 2.8f, STOPPED_MS = 0.5f;
    /** Короче — поездки почти не было, итоги не показываются. */
    private static final float MIN_TRIP_KM = 0.5f;
    private static final long MIN_TRIP_MS = 120_000;
    /** Повторно не прощаться, если оба признака выключения пришли почти одновременно. */
    private static final long REPEAT_GUARD_MS = 10_000;
    /** Процесс запустился вскоре после загрузки машины — зажигание только что включили. */
    private static final long FRESH_BOOT_MS = 180_000;
    /** Сводка ждёт, пока закроется штатный экран приветствия, но не дольше этого. */
    private static final long WELCOME_WAIT_MS = 60_000, WELCOME_POLL_MS = 200, AFTER_WELCOME_MS = 300;
    /** Сводка на прощании: чуть позже картинки прощания, голос — после звука прощания. */
    private static final long FAREWELL_SUMMARY_DELAY_MS = 1000, SPEECH_AFTER_SOUND_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;
    private CarApi car;
    private Farewell farewell;
    private CarSummary summary;
    /** Последнее известное состояние ACC: null — ещё не пришло. */
    private volatile Integer acc;
    private long lastFarewell;
    // Последние значения для события «Итоги»; null — ещё не приходили.
    private Integer gear, door;
    private Boolean brake, belt;
    private float speed;
    /** С начала поездки или с прошлой сводки «Итоги» машина ехала. */
    private boolean moved;
    /** Сводка «Итоги» на этой остановке уже была; сбрасывается, когда снова поехали. */
    private boolean finished;
    private volatile boolean running;

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
            Log.d(TAG, "EVENTS car connect failed: " + e);
        }
    }

    private void onCarConnected() {
        if (!running) return;
        Object l = car.registerProperty(ACC_STATUS, 0f, listener(v -> {
            if (v instanceof Integer) onAcc((Integer) v);
        }));
        boolean power = car.registerPower(state -> {
            if (state == POWER_STANDBY) accOff("power standby");
            else if (state == POWER_RUNNING) cancelFarewell();
        });
        if (Features.FAREWELL_SUMMARY) listenFinish();
        // Текущее зажигание: подписка присылает только изменения.
        Integer current = readInt(ACC_STATUS);
        Log.d(TAG, "EVENTS listening: acc " + (l != null) + ", power " + power + ", acc now " + current);
        if (current != null && acc == null) onAcc(current);
    }

    /** Свойства для события «Итоги»: передача, тормоз, ремень и дверь водителя, скорость. */
    private void listenFinish() {
        car.registerProperty(GEAR_SELECTION, 0f, listener(0, v -> {
            if (v instanceof Integer) gear = (Integer) v;
            checkFinish();
        }));
        car.registerProperty(PARKING_BRAKE_ON, 0f, listener(0, v -> {
            if (v instanceof Boolean) brake = (Boolean) v;
            checkFinish();
        }));
        car.registerProperty(SEAT_BELT_BUCKLED, 0f, listener(DRIVER, v -> {
            if (v instanceof Boolean) belt = (Boolean) v;
            checkFinish();
        }));
        car.registerProperty(DOOR_POS, 0f, listener(DRIVER, v -> {
            if (v instanceof Integer) door = (Integer) v;
            checkFinish();
        }));
        car.registerProperty(PERF_VEHICLE_SPEED, 1f, listener(0, v -> {
            if (v instanceof Number) onSpeed(((Number) v).floatValue());
        }));
        // Подписка присылает только изменения: текущие значения — отдельно.
        Object g = read(GEAR_SELECTION, 0), b = read(PARKING_BRAKE_ON, 0);
        Object s = read(SEAT_BELT_BUCKLED, DRIVER), d = read(DOOR_POS, DRIVER);
        if (gear == null && g instanceof Integer) gear = (Integer) g;
        if (brake == null && b instanceof Boolean) brake = (Boolean) b;
        if (belt == null && s instanceof Boolean) belt = (Boolean) s;
        if (door == null && d instanceof Integer) door = (Integer) d;
    }

    private interface ValueListener {
        void onValue(Object value);
    }

    /** @param area только эта зона (0 — любая) */
    private static CarApi.PropertyListener listener(int area, ValueListener l) {
        return new CarApi.PropertyListener() {
            @Override public void onChange(int id, int a, int status, Object value) {
                if (area == 0 || a == area) l.onValue(value);
            }

            @Override public void onError(int id, int a) {
            }
        };
    }

    private static CarApi.PropertyListener listener(ValueListener l) {
        return listener(0, l);
    }

    private Integer readInt(int id) {
        Object v = read(id, 0);
        return v instanceof Integer ? (Integer) v : null;
    }

    private Object read(int id, int area) {
        try {
            Object[] sv = car.getProperty(id, area);
            return Integer.valueOf(0).equals(sv[0]) ? sv[1] : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Итоги

    private void onSpeed(float value) {
        speed = value;
        if (value >= MOVING_MS && acc != null && acc != 0) {
            if (finished) {
                // Сводка «Итоги» уже была, но поехали дальше: поездка продолжается.
                finished = false;
                Trip.resume(this);
                ui.post(summary::hide);
                Log.d(TAG, "FINISH moving again, trip resumed");
            }
            moved = true;
        }
        checkFinish();
    }

    /** Наступило ли выбранное событие «Итоги»: машина ехала, теперь стоит, условие выполнено. */
    private void checkFinish() {
        if (!running || car == null || acc == null || acc == 0 || !moved || finished || speed > STOPPED_MS) return;
        if (!CarSummary.isEnabled(this, Prefs.SUMMARY_FAREWELL)) return;
        boolean park = gear != null && gear == GEAR_PARK;
        boolean met;
        switch (CarSummary.finishTrigger(this)) {
            case CarSummary.FINISH_PARK_BELT:
                met = park && Boolean.FALSE.equals(belt);
                break;
            case CarSummary.FINISH_PARK_DOOR:
                met = park && door != null && door > 0;
                break;
            case CarSummary.FINISH_BRAKE:
                met = Boolean.TRUE.equals(brake);
                break;
            case CarSummary.FINISH_ACC_OFF:
                met = false;   // срабатывает в accOff
                break;
            default:
                met = park;
        }
        if (!met) return;
        finished = true;
        moved = false;
        Trip.finish(this, CarSummary.read(car));
        CarSummary.Values v = CarSummary.read(this, car);
        boolean shortTrip = v.tripKm == null || v.tripMs == null || v.tripKm < MIN_TRIP_KM || v.tripMs < MIN_TRIP_MS;
        Log.d(TAG, "FINISH " + CarSummary.finishTrigger(this) + (shortTrip ? ", trip too short" : ""));
        if (!shortTrip) showSummary(Prefs.SUMMARY_FAREWELL);
    }

    private void onAcc(int value) {
        Integer prev = acc;
        acc = value;
        if (prev == null) {
            // Первое значение. Зажигание включено, и это новая поездка, если процесс поднялся
            // сразу после загрузки машины (глубокий сон — фактически выключение) или в прошлый
            // раз видели выключение зажигания.
            if (value == 0) return;
            boolean wasOff = Prefs.get(this).getBoolean(Prefs.EVENTS_ACC_OFF, false);
            if (wasOff || SystemClock.elapsedRealtime() < FRESH_BOOT_MS) accOn(wasOff ? "acc was off" : "boot");
            else if (Features.trip()) Trip.startIfMissing(this, CarSummary.read(car));
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
        Prefs.get(this).edit().putBoolean(Prefs.EVENTS_ACC_OFF, true).apply();
        ui.post(summary::hide);
        endTrip(reason);
    }

    /** Конец поездки: итоги, прощание и сводка прощания. */
    private void endTrip(String reason) {
        long now = System.currentTimeMillis();
        if (!running || now - lastFarewell < REPEAT_GUARD_MS) return;
        lastFarewell = now;
        // Итоги поездки — по значениям в этот момент.
        // Если «Итоги» уже был в P, конец поездки зафиксирован тогда — Trip.finish его не тронет.
        postFarewell(() -> {
            if (Features.trip() && car != null && car.isConnected()) Trip.finish(this, CarSummary.read(car));
        });
        if (Farewell.isEnabled(this)) {
            ui.post(() -> {
                boolean played = farewell.play(Farewell.isSoundEnabled(this), Farewell.isPictureEnabled(this));
                Log.d(TAG, "FAREWELL " + reason + (played ? ", played" : ", nothing to play"));
            });
        }
        // Сводка «Итоги» при выключении зажигания — только если так выбрано (пока в настройках нет:
        // экран гаснет сразу). Поверх картинки прощания; голос — после звука прощания.
        if (CarSummary.isEnabled(this, Prefs.SUMMARY_FAREWELL)
                && CarSummary.FINISH_ACC_OFF.equals(CarSummary.finishTrigger(this))) {
            postFarewell(() -> showSummary(Prefs.SUMMARY_FAREWELL), FAREWELL_SUMMARY_DELAY_MS);
        }
    }

    /** Зажигание снова включили — прощание и сводка прощания обрываются. */
    private void cancelFarewell() {
        lastFarewell = 0;
        // Только задачи прощания: ожидание экрана приветствия и сводка «Старт» должны остаться.
        handler.removeCallbacksAndMessages(FAREWELL);
        ui.post(() -> {
            farewell.stop();
            summary.hide();
        });
    }

    /** Метка задач прощания в handler: их снимает {@link #cancelFarewell()}. */
    private static final Object FAREWELL = new Object();

    private void postFarewell(Runnable r) {
        postFarewell(r, 0);
    }

    private void postFarewell(Runnable r, long delayMs) {
        handler.postAtTime(r, FAREWELL, SystemClock.uptimeMillis() + delayMs);
    }

    // ---------------------------------------------------------------- Включение

    private void accOn(String reason) {
        if (!running) return;
        Prefs.get(this).edit().putBoolean(Prefs.EVENTS_ACC_OFF, false).apply();
        moved = false;
        finished = false;
        if (Features.trip()) Trip.start(this, CarSummary.read(car));
        if (!CarSummary.isEnabled(this, Prefs.SUMMARY_WELCOME)) return;
        Log.d(TAG, "SUMMARY " + reason + ", waiting for the welcome screen");
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

    /** @param occasion Prefs.SUMMARY_WELCOME (начало поездки) или Prefs.SUMMARY_FAREWELL (конец) */
    private void showSummary(String occasion) {
        boolean welcome = Prefs.SUMMARY_WELCOME.equals(occasion);
        if (!running || car == null || (welcome && (acc == null || acc == 0))) return;
        CarSummary.Values v = CarSummary.read(this, car);
        ui.post(() -> {
            long speechDelay = welcome ? 0 : farewell.soundMs() + SPEECH_AFTER_SOUND_MS;
            boolean shown = summary.show(occasion, v, speechDelay);
            Log.d(TAG, "SUMMARY " + occasion + (shown ? " shown" : " nothing to show"));
        });
    }
}
