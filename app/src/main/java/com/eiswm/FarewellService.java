package com.eiswm;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

/**
 * Ждёт выключения зажигания и прощается ({@link Farewell#play}). Работает в фоне, пока включён
 * звук или картинка прощания; после загрузки и пробуждения машины запускается из {@link WakeReceiver}.
 * Зажигание: свойство машины SYSTEM_CAN_ACC_STATUS (0 — выключено); запасной признак —
 * переход сервиса машины в режим ожидания. Магнитола после этого работает ещё около 30 с.
 */
public class FarewellService extends Service {
    private static final int ACC_STATUS = 0x21400054;
    private static final int POWER_STANDBY = 0, POWER_RUNNING = 1;
    /** Повторно не прощаться, если оба признака пришли почти одновременно. */
    private static final long REPEAT_GUARD_MS = 10_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;
    private CarApi car;
    private Farewell farewell;
    /** Последнее известное состояние ACC: null — ещё не пришло, первое значение не срабатывает. */
    private Integer acc;
    private long lastFarewell;
    private boolean running;

    static void start(Context c) {
        c.startService(new Intent(c, FarewellService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, FarewellService.class));
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        farewell = new Farewell(this);
        thread = new HandlerThread("eiswm-farewell");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::connectCar);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Farewell.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        ui.post(farewell::stop);
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
            CarDiag.log(this, "FAREWELL car connect failed: " + e);
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
            if (state == POWER_STANDBY) trigger("power standby");
            else if (state == POWER_RUNNING) cancel();
        });
        CarDiag.log(this, "FAREWELL listening: acc " + (l != null) + ", power " + power);
    }

    private void onAcc(int value) {
        Integer prev = acc;
        acc = value;
        if (prev == null) return;
        if (value == 0 && prev != 0) trigger("acc off");
        else if (value != 0 && prev == 0) cancel();
    }

    private void trigger(String reason) {
        long now = System.currentTimeMillis();
        if (!running || !Farewell.isEnabled(this) || now - lastFarewell < REPEAT_GUARD_MS) return;
        lastFarewell = now;
        ui.post(() -> {
            boolean played = farewell.play(Farewell.isSoundEnabled(this), Farewell.isPictureEnabled(this));
            CarDiag.log(this, "FAREWELL " + reason + (played ? ", played" : ", nothing to play"));
        });
    }

    /** Зажигание снова включили — прощание обрывается. */
    private void cancel() {
        lastFarewell = 0;
        ui.post(farewell::stop);
    }
}
