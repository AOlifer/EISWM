package com.eiswm;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Общая основа разделов главного экрана ({@link SoundsPanel}, {@link PicturesPanel}):
 * свой фоновый поток, блокировка кнопок на время долгой операции и сообщение по её итогу.
 */
abstract class SectionPanel {
    /** Долгая операция в фоновом потоке. */
    interface Job {
        /** @return сообщение для пользователя или null. */
        String run() throws Exception;
    }

    final Activity activity;
    final Handler ui = new Handler(Looper.getMainLooper());
    /** У каждого раздела свой поток: работа с картинками не задерживает звуки и наоборот. */
    final ExecutorService io = Executors.newSingleThreadExecutor();
    boolean busy = false;
    boolean destroyed = false;

    SectionPanel(Activity activity) {
        this.activity = activity;
    }

    boolean isBusy() {
        return busy;
    }

    /**
     * Экран закрывается: новых операций не принимать, отложенные обновления интерфейса убрать.
     * Начатое копирование доделывается в фоне (об этом предупреждает выход во время работы):
     * прерванный поток оставил бы недописанные файлы и записи.
     */
    void destroy() {
        destroyed = true;
        io.shutdown();
        ui.removeCallbacksAndMessages(null);
    }

    /** Заблокировать кнопки раздела на время операции (true) и разблокировать (false). */
    abstract void setBusy(boolean value);

    /** Обновить раздел после операции: список, выключатель. */
    abstract void afterJob();

    /** Сообщение, если операция упала с ошибкой. */
    abstract String errorMessage(Throwable e);

    /**
     * Выполнить в фоне с блокировкой кнопок, затем обновить раздел и показать сообщение.
     * @param toastLength Toast.LENGTH_SHORT или Toast.LENGTH_LONG
     */
    void runBusy(Job job, int toastLength) {
        setBusy(true);
        io.execute(() -> {
            String message;
            try {
                message = job.run();
            } catch (Exception | OutOfMemoryError e) {
                message = errorMessage(e);
            }
            final String msg = message;
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false);
                afterJob();
                if (msg != null) Toast.makeText(activity, msg, toastLength).show();
            });
        });
    }
}
