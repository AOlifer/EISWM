package com.eiswm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * При старте машины возвращает картинки приветствия, которые могла стереть служба загрузки
 * лаунчера, и поддерживает выключенный показ картинок ({@link WelcomePictures#restore()}).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                WelcomePictures.get(app).restore();
            } catch (Exception ignored) {
            } finally {
                result.finish();
            }
        }, "eiswm-restore").start();
    }
}
