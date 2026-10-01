package com.eiswm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Загрузка и пробуждение машины. Глубокий сон машины — фактически выключение: процесс
 * приложения запускается заново, и фоновые службы нужно поднять снова — прощание
 * ({@link FarewellService}), если оно включено, и запись диагностики ({@link CarDiagService}).
 */
public class WakeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Farewell.isEnabled(c)) FarewellService.start(c);
        if (Prefs.get(c).getBoolean(Prefs.DIAG_RECORDING, false)) {
            CarDiagService.start(c, "broadcast " + i.getAction());
        }
    }
}
