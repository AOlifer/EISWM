package com.eiswm;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Предупреждения сводки: какие показывать и их пороги. Настройки общие для приветствия и
 * прощания; пороги хранятся в км, °C, процентах и вольтах независимо от единиц на экране.
 */
final class Warnings {
    /** Во что переводить порог для экрана. */
    enum Unit { TEMP, DISTANCE, PERCENT, VOLT }

    /** Одно предупреждение: срабатывает, когда значение ниже порога (лёд — не выше). */
    static final class Def {
        final String key;
        final int title, text;
        final Unit unit;
        final float limit, step, min, max;
        final boolean onByDefault;

        Def(String key, int title, int text, Unit unit, float limit, float step, float min, float max, boolean on) {
            this.key = key;
            this.title = title;
            this.text = text;
            this.unit = unit;
            this.limit = limit;
            this.step = step;
            this.min = min;
            this.max = max;
            this.onByDefault = on;
        }
    }

    static final Def ICE = new Def("ice", R.string.warn_title_ice, R.string.summary_warn_ice,
            Unit.TEMP, 3, 1, -10, 10, true);
    static final Def RANGE = new Def("range", R.string.warn_title_range, R.string.summary_warn_range,
            Unit.DISTANCE, 50, 10, 10, 300, true);
    static final Def CHARGE = new Def("charge", R.string.warn_title_charge, R.string.summary_warn_charge,
            Unit.PERCENT, 20, 5, 5, 80, false);
    static final Def FUEL = new Def("fuel", R.string.warn_title_fuel, R.string.summary_warn_fuel,
            Unit.PERCENT, 15, 5, 5, 80, false);
    static final Def SERVICE = new Def("service", R.string.warn_title_service, R.string.summary_warn_service,
            Unit.DISTANCE, 500, 100, 100, 5000, true);
    static final Def BATTERY = new Def("battery", R.string.warn_title_battery, R.string.summary_warn_battery,
            Unit.VOLT, 11.8f, 0.1f, 10.5f, 13f, true);
    static final Def[] ALL = {ICE, RANGE, CHARGE, FUEL, SERVICE, BATTERY};

    private Warnings() {
    }

    static boolean isOn(Context c, Def d) {
        return Prefs.get(c).getBoolean(Prefs.WARN + d.key + Prefs.WARN_ON, d.onByDefault);
    }

    static void setOn(Context c, Def d, boolean on) {
        Prefs.get(c).edit().putBoolean(Prefs.WARN + d.key + Prefs.WARN_ON, on).apply();
    }

    static float limit(Context c, Def d) {
        return Prefs.get(c).getFloat(Prefs.WARN + d.key + Prefs.WARN_LIMIT, d.limit);
    }

    /** Новый порог с шагом step в пределах min…max; округление убирает накопленную ошибку float. */
    static float step(Context c, Def d, int direction) {
        float v = limit(c, d) + direction * d.step;
        v = Math.round(Math.max(d.min, Math.min(d.max, v)) * 10f) / 10f;
        SharedPreferences.Editor e = Prefs.get(c).edit();
        e.putFloat(Prefs.WARN + d.key + Prefs.WARN_LIMIT, v).apply();
        return v;
    }

    /** Сработавшие предупреждения — тексты для плашки и голоса. */
    static List<String> active(Context c, CarSummary.Values v) {
        List<String> w = new ArrayList<>();
        for (Def d : ALL) {
            if (isOn(c, d) && fires(d, v, limit(c, d))) w.add(c.getString(d.text));
        }
        return w;
    }

    private static boolean fires(Def d, CarSummary.Values v, float limit) {
        if (d == ICE) return v.temp != null && v.temp <= limit;
        // 0 у этих значений — «нет данных», а не ноль.
        if (d == RANGE) return known(v.rangeKm) && v.rangeKm < limit;
        if (d == CHARGE) return known(v.charge) && v.charge < limit;
        if (d == FUEL) return v.fuel != null && v.fuel < limit;
        if (d == SERVICE) return known(v.serviceKm) && v.serviceKm < limit;
        if (d == BATTERY) return known(v.voltage) && v.voltage < limit;
        return false;
    }

    private static boolean known(Float v) {
        return v != null && v > 0;
    }
}
