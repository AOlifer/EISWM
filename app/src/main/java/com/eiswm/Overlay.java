package com.eiswm;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

/**
 * Окно поверх всего экрана, включая экран ожидания машины: картинка прощания, плашка сводки.
 * Окно системного уровня доступно system uid; без него (эмулятор) — обычное окно поверх
 * приложений, если оно разрешено.
 */
final class Overlay {
    private static final String TAG = "EISWM";
    private Overlay() {
    }

    /**
     * @param gravity расположение, например Gravity.BOTTOM
     * @param height высота в пикселях или WindowManager.LayoutParams.MATCH_PARENT
     * @return true, если окно показано
     */
    @SuppressWarnings("deprecation")
    static boolean show(Context c, View v, int gravity, int height) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        int[] types = Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(c)
                ? new int[]{WindowManager.LayoutParams.TYPE_SYSTEM_ERROR, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}
                : new int[]{WindowManager.LayoutParams.TYPE_SYSTEM_ERROR};
        for (int type : types) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, height, type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | (height == WindowManager.LayoutParams.MATCH_PARENT ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0),
                    PixelFormat.TRANSLUCENT);
            lp.gravity = gravity;
            try {
                wm.addView(v, lp);
                return true;
            } catch (Exception e) {
                // Этот тип окна недоступен — пробуем следующий.
                Log.d(TAG, "OVERLAY type " + type + " failed: " + e);
                // Неудачное окно остаётся зарегистрированным — иначе следующая попытка не пройдёт.
                try {
                    wm.removeViewImmediate(v);
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    static void hide(Context c, View v) {
        if (v == null) return;
        try {
            c.getSystemService(WindowManager.class).removeView(v);
        } catch (Exception ignored) {
        }
    }
}
