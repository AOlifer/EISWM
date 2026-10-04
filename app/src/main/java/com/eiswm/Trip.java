package com.eiswm;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Итоги поездки для сводки прощания: одометр, время, заряд и бак запоминаются при включении
 * зажигания и при выключении; разница — пробег, время в пути и расход за поездку.
 * Штатные свойства поездки машины (VEHICLE_CURRENT_TRIP и т.п.) ненадёжны — на машине они
 * отдавали нули, поэтому считаем сами. Значения хранятся в настройках: процесс приложения
 * может перезапуститься посреди поездки.
 */
final class Trip {
    /** Поездка длиннее — это не поездка, а сбой часов или пропущенное выключение. */
    private static final long MAX_MS = 24 * 3600_000L;
    private static final float MAX_KM = 2000f;

    private Trip() {
    }

    /** Зажигание включили: начать новую поездку. */
    static void start(Context c, CarSummary.Values v) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        save(e, Prefs.TRIP_START, System.currentTimeMillis(), v);
        clear(e, Prefs.TRIP_END);
        e.apply();
    }

    /**
     * Зажигание включено, но начала поездки не видели (приложение запустилось посреди поездки):
     * продолжить начатую поездку или начать новую, если прошлая уже закончилась.
     */
    static void startIfMissing(Context c, CarSummary.Values v) {
        SharedPreferences p = Prefs.get(c);
        if (!p.contains(Prefs.TRIP_START + Prefs.TRIP_TIME) || p.contains(Prefs.TRIP_END + Prefs.TRIP_TIME)) start(c, v);
    }

    /** Зажигание выключили: запомнить конец поездки, если она начата и ещё не закончена. */
    static void finish(Context c, CarSummary.Values v) {
        SharedPreferences p = Prefs.get(c);
        if (!p.contains(Prefs.TRIP_START + Prefs.TRIP_TIME) || p.contains(Prefs.TRIP_END + Prefs.TRIP_TIME)) return;
        SharedPreferences.Editor e = p.edit();
        save(e, Prefs.TRIP_END, System.currentTimeMillis(), v);
        e.apply();
    }

    /** После конца поездки снова поехали (зажигание не выключали): поездка продолжается. */
    static void resume(Context c) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        clear(e, Prefs.TRIP_END);
        e.apply();
    }

    /**
     * Заполнить в v итоги поездки: последней законченной или, если она ещё идёт, — на сейчас.
     * Без начала поездки поля остаются null.
     */
    static void fill(Context c, CarSummary.Values v) {
        SharedPreferences p = Prefs.get(c);
        if (!p.contains(Prefs.TRIP_START + Prefs.TRIP_TIME)) return;
        boolean ended = p.contains(Prefs.TRIP_END + Prefs.TRIP_TIME);
        long end = ended ? p.getLong(Prefs.TRIP_END + Prefs.TRIP_TIME, 0) : System.currentTimeMillis();
        long ms = end - p.getLong(Prefs.TRIP_START + Prefs.TRIP_TIME, 0);
        if (ms < 0 || ms > MAX_MS) return;
        v.tripMs = ms;
        Float odo = ended ? load(p, Prefs.TRIP_END, Prefs.TRIP_ODOMETER) : v.odometer;
        Float km = diff(load(p, Prefs.TRIP_START, Prefs.TRIP_ODOMETER), odo);
        if (km != null && km >= 0 && km < MAX_KM) v.tripKm = km;
        v.chargeUsed = diff(ended ? load(p, Prefs.TRIP_END, Prefs.TRIP_CHARGE) : v.charge,
                load(p, Prefs.TRIP_START, Prefs.TRIP_CHARGE));
        v.fuelUsed = diff(ended ? load(p, Prefs.TRIP_END, Prefs.TRIP_FUEL) : v.fuel,
                load(p, Prefs.TRIP_START, Prefs.TRIP_FUEL));
    }

    /** @return b − a или null, если одного из значений нет */
    private static Float diff(Float a, Float b) {
        return a != null && b != null ? b - a : null;
    }

    private static void save(SharedPreferences.Editor e, String prefix, long time, CarSummary.Values v) {
        e.putLong(prefix + Prefs.TRIP_TIME, time);
        put(e, prefix + Prefs.TRIP_ODOMETER, v.odometer);
        put(e, prefix + Prefs.TRIP_CHARGE, v.charge);
        put(e, prefix + Prefs.TRIP_FUEL, v.fuel);
    }

    private static void put(SharedPreferences.Editor e, String key, Float value) {
        if (value != null) e.putFloat(key, value);
        else e.remove(key);
    }

    private static void clear(SharedPreferences.Editor e, String prefix) {
        e.remove(prefix + Prefs.TRIP_TIME).remove(prefix + Prefs.TRIP_ODOMETER)
                .remove(prefix + Prefs.TRIP_CHARGE).remove(prefix + Prefs.TRIP_FUEL);
    }

    private static Float load(SharedPreferences p, String prefix, String key) {
        return p.contains(prefix + key) ? p.getFloat(prefix + key, 0) : null;
    }
}
