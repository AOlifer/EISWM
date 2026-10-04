package com.eiswm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Загрузка и пробуждение машины. Глубокий сон машины — фактически выключение: процесс
 * приложения запускается заново, и службу событий машины для сводки
 * ({@link CarEventsService}) нужно поднять снова.
 */
public class WakeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        CarEventsService.update(c);
    }
}
