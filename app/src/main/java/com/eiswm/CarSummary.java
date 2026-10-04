package com.eiswm;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Сводка при старте: предупреждения, температура за бортом, запас хода, заряд, бак и пробег
 * до ТО; сводка при прощании: итоги поездки ({@link Trip}) и остаток запаса хода. Пункты — в
 * порядке, который выбрал пользователь. Показывается плашкой внизу экрана поверх всего
 * ({@link Overlay}) и, если включено, озвучивается ({@link Speech}). Единицы — из настроек
 * машины: расстояние в км или милях, температура в °C или °F. Срабатывание при включении
 * и выключении зажигания — в {@link CarEventsService}.
 */
final class CarSummary {
    /** Пункты сводки: имя — часть ключа настройки. */
    static final String WARNINGS = "warnings", TEMP = "temp", TEMP_INSIDE = "temp_inside", RANGE = "range", CHARGE = "charge",
            FUEL = "fuel", SERVICE = "service";
    /** Итоги поездки ({@link Trip}) — для сводки прощания. */
    static final String TRIP_DISTANCE = "trip_distance", TRIP_TIME = "trip_time", TRIP_SPEED = "trip_speed",
            CHARGE_USED = "charge_used", FUEL_USED = "fuel_used";
    /** Пункты при включении зажигания; порядок по умолчанию — как здесь. */
    static final String[] WELCOME_ITEMS = {WARNINGS, TEMP, TEMP_INSIDE, RANGE, CHARGE, FUEL, SERVICE};
    /** Пункты при выключении зажигания: итоги поездки и остаток запаса хода. */
    static final String[] FAREWELL_ITEMS = {TRIP_DISTANCE, TRIP_TIME, TRIP_SPEED, CHARGE_USED, FUEL_USED, RANGE};

    // Свойства машины (проверены диагностикой на Evolute i-Space).
    private static final int ENV_OUTSIDE_TEMPERATURE = 0x11600703;     // °C
    /** Температура в салоне (ZONED_TEMP_ACTUAL у лаунчера); на Evolute i-Space всегда 0 — датчика, видимо, нет. */
    private static final int HVAC_TEMPERATURE_CURRENT = 0x15600502, HVAC_AREA_ALL = 0x75;
    private static final int RANGE_REMAINING = 0x11600308;              // метры
    private static final int VEHICLE_EV_RANGE_REMAINING = 0x21605858;   // км
    private static final int EV_BATTERY_PERCENT = 0x1160030d;
    private static final int VEHICLE_FUEL_REMAINING_PERCENT = 0x21405978;
    private static final int VEHICLE_MAINTENANCE_MILEAGE_REMAINING = 0x21405842; // км
    private static final int VEHICLE_BATTERY_VOLTAGE = 0x2160584b;
    private static final int PERF_ODOMETER = 0x11600204;                // км, шаг 0,1
    private static final int INFO_FUEL_CAPACITY = 0x11600104;           // мл
    /** Единица температуры на экранах машины (VehicleUnit: 0x30 — °C, 0x31 — °F). */
    private static final int HVAC_TEMPERATURE_DISPLAY_UNITS = 0x1140050e;
    private static final int FAHRENHEIT = 0x31;
    /** Единица пробега из настроек машины (Общие → единица пробега: 0 — км, 1 — мили). */
    private static final String MILEAGE_UNIT = "bw_ip_mileage_unit";
    static final float KM_PER_MILE = 1.609344f;
    private static final float LITRES_PER_GALLON = 3.785412f;

    private static final long SHOW_MS = 10_000;
    private static final int WARNING_COLOR = 0xFFFFC107;

    /** Значения из машины (км и °C); null — машина не отдала значение. */
    static final class Values {
        /** Температура в салоне, °C; 0 — нет данных. */
        Float tempInside;
        Float temp, rangeKm, evRangeKm, charge, fuel, serviceKm, voltage, odometer;
        /** Объём бака, мл. */
        Float fuelCapacity;
        boolean fahrenheit;
        /** Итоги поездки ({@link Trip#fill}): пробег в км, время, расход заряда и бака в процентах. */
        Float tripKm, chargeUsed, fuelUsed;
        Long tripMs;
    }

    /** Один пункт сводки: как он выглядит на экране и как звучит. */
    static final class Part {
        final String item;
        final CharSequence screen;
        final String speech;

        Part(String item, CharSequence screen, String speech) {
            this.item = item;
            this.screen = screen;
            this.speech = speech;
        }
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Speech speech;
    private FrameLayout banner;
    private final Runnable hideTask = this::hide;
    private Runnable speechTask;

    CarSummary(Context context) {
        this.context = context.getApplicationContext();
        speech = new Speech(context);
    }

    // ---------------------------------------------------------------- Настройки

    // Настройки хранятся отдельно для приветствия и прощания: occasion — Prefs.SUMMARY_WELCOME
    // или Prefs.SUMMARY_FAREWELL, префикс ключей.

    static boolean isEnabled(Context c, String occasion) {
        if (Prefs.SUMMARY_FAREWELL.equals(occasion) && !Features.FAREWELL) return false;
        return Prefs.get(c).getBoolean(occasion + Prefs.SUMMARY_ENABLED, false);
    }

    /** Включена ли сводка хоть где-то: тогда нужна служба {@link CarEventsService}. */
    static boolean isAnyEnabled(Context c) {
        return isEnabled(c, Prefs.SUMMARY_WELCOME) || isEnabled(c, Prefs.SUMMARY_FAREWELL);
    }

    static boolean isSpeakEnabled(Context c, String occasion) {
        if (!Features.SPEECH) return false;
        return Prefs.get(c).getBoolean(occasion + Prefs.SUMMARY_SPEAK, false);
    }

    static boolean isItemOn(Context c, String occasion, String item) {
        return Prefs.get(c).getBoolean(occasion + Prefs.SUMMARY_ITEM + item, true);
    }

    static void setItemOn(SharedPreferences prefs, String occasion, String item, boolean on) {
        prefs.edit().putBoolean(occasion + Prefs.SUMMARY_ITEM + item, on).apply();
    }

    /** Порядок пунктов: сохранённый, без неизвестных; новые пункты — в конце. */
    static List<String> order(Context c, String occasion) {
        List<String> out = new ArrayList<>();
        String saved = Prefs.get(c).getString(occasion + Prefs.SUMMARY_ORDER, "");
        String[] items = items(occasion);
        // Порядок прощания из тестовых сборок, где там были пункты приветствия, не годится.
        boolean stale = Prefs.SUMMARY_FAREWELL.equals(occasion) && !saved.contains(TRIP_DISTANCE);
        if (stale) saved = "";
        for (String s : TextUtils.split(saved, ",")) {
            if (Arrays.asList(items).contains(s) && !out.contains(s)) out.add(s);
        }
        for (String s : items) if (!out.contains(s)) out.add(s);
        return out;
    }

    static String[] items(String occasion) {
        return Prefs.SUMMARY_FAREWELL.equals(occasion) ? FAREWELL_ITEMS : WELCOME_ITEMS;
    }

    /** Пункт — итог поездки: без поездки у него нет значения. */
    static boolean isTripItem(String item) {
        return Arrays.asList(TRIP_DISTANCE, TRIP_TIME, TRIP_SPEED, CHARGE_USED, FUEL_USED).contains(item);
    }

    static void setOrder(SharedPreferences prefs, String occasion, List<String> order) {
        prefs.edit().putString(occasion + Prefs.SUMMARY_ORDER, TextUtils.join(",", order)).apply();
    }

    /** Расстояние в милях по настройке машины. */
    static boolean useMiles(Context c) {
        return Settings.System.getInt(c.getContentResolver(), MILEAGE_UNIT, 0) == 1;
    }

    // ---------------------------------------------------------------- Данные

    /**
     * Прочитать значения из подключённого сервиса машины (несколько запросов, не в главном потоке)
     * и добавить итоги последней или текущей поездки.
     */
    static Values read(Context c, CarApi car) {
        Values v = read(car);
        Trip.fill(c, v);
        return v;
    }

    /** Только значения из машины, без итогов поездки. */
    static Values read(CarApi car) {
        Values v = new Values();
        v.temp = get(car, ENV_OUTSIDE_TEMPERATURE);
        v.tempInside = get(car, HVAC_TEMPERATURE_CURRENT, HVAC_AREA_ALL);
        Float meters = get(car, RANGE_REMAINING);
        v.rangeKm = meters != null ? meters / 1000f : null;
        v.evRangeKm = get(car, VEHICLE_EV_RANGE_REMAINING);
        v.charge = get(car, EV_BATTERY_PERCENT);
        v.fuel = get(car, VEHICLE_FUEL_REMAINING_PERCENT);
        v.serviceKm = get(car, VEHICLE_MAINTENANCE_MILEAGE_REMAINING);
        v.voltage = get(car, VEHICLE_BATTERY_VOLTAGE);
        v.odometer = get(car, PERF_ODOMETER);
        v.fuelCapacity = get(car, INFO_FUEL_CAPACITY);
        Float unit = get(car, HVAC_TEMPERATURE_DISPLAY_UNITS);
        v.fahrenheit = unit != null && Math.round(unit) == FAHRENHEIT;
        return v;
    }

    private static Float get(CarApi car, int id) {
        return get(car, id, 0);
    }

    private static Float get(CarApi car, int id, int area) {
        try {
            Object[] sv = car.getProperty(id, area);
            if (!Integer.valueOf(0).equals(sv[0]) || !(sv[1] instanceof Number)) return null;
            return ((Number) sv[1]).floatValue();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Запас хода, пробег до ТО и т.п.: 0 означает «нет данных», а не ноль километров. */
    private static boolean known(Float v) {
        return v != null && v > 0;
    }


    // ---------------------------------------------------------------- Тексты

    private static String number(float v) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(Math.round(v));
    }

    /** Расстояние на экране: «376 км» или «234 mi» по настройке машины. */
    private static String distance(Context c, float km, boolean miles) {
        float d = miles ? km / KM_PER_MILE : km;
        return c.getString(miles ? R.string.unit_mi : R.string.unit_km, number(d));
    }

    /** Расстояние голосом: «376 километров» или «234 мили». */
    private static String spokenDistance(Context c, float km, boolean miles) {
        int n = Math.round(miles ? km / KM_PER_MILE : km);
        return c.getResources().getQuantityString(miles ? R.plurals.speech_mi : R.plurals.speech_km, n, n);
    }

    /** Число с одним знаком после запятой: «0,9», «1,2». */
    private static String decimal(float v) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.getDefault());
        f.setMinimumFractionDigits(1);
        f.setMaximumFractionDigits(1);
        return f.format(v);
    }

    /** Время голосом: «1 час 5 минут», «38 минут». */
    private static String spokenDuration(Context c, long minutes) {
        int h = (int) (minutes / 60), m = (int) (minutes % 60);
        String hours = c.getResources().getQuantityString(R.plurals.speech_hours, h, h);
        String mins = c.getResources().getQuantityString(R.plurals.speech_minutes, m, m);
        if (h == 0) return mins;
        return m == 0 ? hours : hours + " " + mins;
    }

    // ---------------------------------------------------------------- Температура

    /** @return температура пункта в °C или null, если машина её не отдала */
    private static Float temperature(String item, Values v) {
        if (TEMP.equals(item)) return v.temp;
        // В салоне 0 — нет данных: на Evolute i-Space свойство всегда 0.
        return v.tempInside != null && v.tempInside != 0f ? v.tempInside : null;
    }

    private static int tempLabel(String item) {
        return TEMP.equals(item) ? R.string.summary_temp_outside : R.string.summary_temp_inside;
    }

    private static int degrees(Values v, float celsius) {
        return Math.round(v.fahrenheit ? celsius * 9f / 5f + 32f : celsius);
    }

    /** На экране: «+2 °C» или без единицы — «+2». */
    private static String screenTemp(Values v, float celsius, boolean unit) {
        int t = degrees(v, celsius);
        String n = String.format(Locale.getDefault(), t == 0 ? "%d" : "%+d", t);
        return unit ? n + " " + (v.fahrenheit ? "°F" : "°C") : n;
    }

    /** Голосом: «плюс 2 градуса» или без единицы — «плюс 2». */
    private static String spokenTemp(Context c, Values v, float celsius, boolean unit) {
        int t = degrees(v, celsius), a = Math.abs(t);
        String sign = t > 0 ? c.getString(R.string.speech_plus) + " " : t < 0 ? c.getString(R.string.speech_minus) + " " : "";
        if (!unit) return sign + a;
        String deg = c.getResources().getQuantityString(R.plurals.speech_degrees, a, a);
        if (v.fahrenheit) deg = c.getString(R.string.speech_fahrenheit, deg);
        return sign + deg;
    }

    /**
     * Обе температуры одним пунктом: «Температура за бортом +2, в салоне +18 °C».
     * Порядок — как у пунктов в списке; единица — только у второй.
     */
    private static Part bothTemps(Context c, Values v, String first, String second) {
        float a = temperature(first, v), b = temperature(second, v);
        String la = c.getString(tempLabel(first)), lb = c.getString(tempLabel(second));
        return new Part(first,
                c.getString(R.string.summary_temp_two, la, screenTemp(v, a, false), lb, screenTemp(v, b, true)),
                c.getString(R.string.speech_temp_two, la, spokenTemp(c, v, a, false), lb, spokenTemp(c, v, b, true)));
    }

    // ---------------------------------------------------------------- Топливо

    /** Топливо в литрах (галлонах при милях): так выбрано и машина отдала объём бака. */
    static boolean useVolume(Context c, Values v) {
        return isFuelVolume(c) && known(v.fuelCapacity);
    }

    static boolean isFuelVolume(Context c) {
        return Prefs.get(c).getBoolean(Prefs.SUMMARY_FUEL_VOLUME, true);
    }

    static void setFuelVolume(SharedPreferences prefs, boolean volume) {
        prefs.edit().putBoolean(Prefs.SUMMARY_FUEL_VOLUME, volume).apply();
    }

    /** Процент бака в литрах (галлонах), с одним знаком после запятой. */
    private static String volume(Values v, float percent, boolean miles) {
        float litres = percent * v.fuelCapacity / 100f / 1000f;
        return decimal(miles ? litres / LITRES_PER_GALLON : litres);
    }

    private static String percent(Context c, float v) {
        int n = Math.round(v);
        return c.getResources().getQuantityString(R.plurals.speech_percent, n, n);
    }

    /**
     * Пункты сводки в выбранном порядке, для экрана и для голоса.
     * @param onlyEnabled только включённые галочками (для показа); false — все (для настроек)
     * @param miles расстояние в милях
     */
    static List<Part> parts(Context c, String occasion, Values v, boolean onlyEnabled, boolean miles) {
        List<Part> out = new ArrayList<>();
        String firstTemp = null;
        for (String item : order(c, occasion)) {
            if (onlyEnabled && !isItemOn(c, occasion, item)) continue;
            Part p = part(c, item, v, miles);
            if (p == null) continue;
            boolean temp = TEMP.equals(item) || TEMP_INSIDE.equals(item);
            if (temp && firstTemp != null) {
                // Вторая температура — в один пункт с первой, на её месте.
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).item.equals(firstTemp)) out.set(i, bothTemps(c, v, firstTemp, item));
                }
                continue;
            }
            if (temp) firstTemp = item;
            out.add(p);
        }
        return out;
    }

    /** @return пункт или null, если машина не отдала значение (или предупреждений нет). */
    static Part part(Context c, String item, Values v, boolean miles) {
        switch (item) {
            case WARNINGS: {
                List<String> w = Warnings.active(c, v);
                if (w.isEmpty()) return null;
                SpannableStringBuilder sb = new SpannableStringBuilder();
                for (String s : w) {
                    if (sb.length() > 0) sb.append("   ");
                    int start = sb.length();
                    // Каждое предупреждение не разрывается; перенос — только между ними.
                    sb.append("⚠" + NBSP).append(s.replace(' ', ' '));
                    sb.setSpan(new ForegroundColorSpan(WARNING_COLOR), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                return new Part(item, sb, TextUtils.join(". ", w) + ".");
            }
            case TEMP:
            case TEMP_INSIDE: {
                Float t = temperature(item, v);
                if (t == null) return null;
                String label = c.getString(tempLabel(item));
                return new Part(item,
                        c.getString(R.string.summary_temp_one, label, screenTemp(v, t, true)),
                        c.getString(R.string.speech_temp_one, label, spokenTemp(c, v, t, true)));
            }
            case RANGE: {
                if (!known(v.rangeKm)) return null;
                if (known(v.evRangeKm)) {
                    return new Part(item,
                            c.getString(R.string.summary_range_ev, distance(c, v.rangeKm, miles), distance(c, v.evRangeKm, miles)),
                            c.getString(R.string.speech_range_ev, spokenDistance(c, v.rangeKm, miles), spokenDistance(c, v.evRangeKm, miles)));
                }
                return new Part(item, c.getString(R.string.summary_range, distance(c, v.rangeKm, miles)),
                        c.getString(R.string.speech_range, spokenDistance(c, v.rangeKm, miles)));
            }
            case CHARGE:
                if (!known(v.charge)) return null;
                return new Part(item, c.getString(R.string.summary_charge, number(v.charge)),
                        c.getString(R.string.speech_charge, percent(c, v.charge)));
            case FUEL:
                if (v.fuel == null) return null;
                if (useVolume(c, v)) {
                    String n = volume(v, v.fuel, miles);
                    return new Part(item, c.getString(R.string.summary_fuel_volume,
                            c.getString(miles ? R.string.unit_gal : R.string.unit_l, n)),
                            c.getString(R.string.speech_fuel,
                                    c.getString(miles ? R.string.speech_gallons : R.string.speech_litres, n)));
                }
                return new Part(item, c.getString(R.string.summary_fuel, number(v.fuel)),
                        c.getString(R.string.speech_fuel, percent(c, v.fuel)));
            case SERVICE:
                if (!known(v.serviceKm)) return null;
                return new Part(item, c.getString(R.string.summary_service, distance(c, v.serviceKm, miles)),
                        c.getString(R.string.speech_service, spokenDistance(c, v.serviceKm, miles)));
            case TRIP_DISTANCE: {
                if (v.tripKm == null) return null;
                float d = miles ? v.tripKm / KM_PER_MILE : v.tripKm;
                // Короткие поездки — с десятыми: «3,4 км».
                String screen = d < 10 ? String.format(Locale.getDefault(), "%.1f", d) : number(d);
                String spoken = Math.round(d) >= 1 ? c.getString(R.string.speech_trip_distance, spokenDistance(c, v.tripKm, miles))
                        : c.getString(miles ? R.string.speech_trip_distance_short_mi : R.string.speech_trip_distance_short_km);
                return new Part(item, c.getString(R.string.summary_trip_distance,
                        c.getString(miles ? R.string.unit_mi : R.string.unit_km, screen)), spoken);
            }
            case TRIP_TIME: {
                if (v.tripMs == null) return null;
                long min = v.tripMs / 60_000;
                String screen = min >= 60 ? c.getString(R.string.summary_hours_minutes, min / 60, min % 60)
                        : c.getString(R.string.summary_minutes, min);
                return new Part(item, c.getString(R.string.summary_trip_time, screen),
                        min >= 1 ? c.getString(R.string.speech_trip_time, spokenDuration(c, min))
                                : c.getString(R.string.speech_trip_time_short));
            }
            case TRIP_SPEED: {
                // Средняя скорость имеет смысл, если проехали хоть километр и хоть минуту.
                if (v.tripKm == null || v.tripMs == null || v.tripKm < 1 || v.tripMs < 60_000) return null;
                float kmh = v.tripKm / (v.tripMs / 3600_000f);
                float s = miles ? kmh / KM_PER_MILE : kmh;
                return new Part(item, c.getString(R.string.summary_trip_speed,
                        c.getString(miles ? R.string.unit_mph : R.string.unit_kmh, number(s))),
                        c.getString(R.string.speech_trip_speed, spokenDistance(c, kmh, miles)));
            }
            case CHARGE_USED:
                // Заряд вырос (рекуперация, зарядка) — расхода нет.
                if (v.chargeUsed == null || Math.round(v.chargeUsed) < 1) return null;
                return new Part(item, c.getString(R.string.summary_charge_used, number(v.chargeUsed)),
                        c.getString(R.string.speech_charge_used, percent(c, v.chargeUsed)));
            case FUEL_USED:
                if (v.fuelUsed == null || Math.round(v.fuelUsed) < 1) return null;
                if (useVolume(c, v)) {
                    String n = volume(v, v.fuelUsed, miles);
                    return new Part(item, c.getString(R.string.summary_fuel_used_volume,
                            c.getString(miles ? R.string.unit_gal : R.string.unit_l, n)),
                            c.getString(R.string.speech_fuel_used_volume,
                                    c.getString(miles ? R.string.speech_gallons : R.string.speech_litres, n)));
                }
                return new Part(item, c.getString(R.string.summary_fuel_used, number(v.fuelUsed)),
                        c.getString(R.string.speech_fuel_used, percent(c, v.fuelUsed)));
            default:
                return null;
        }
    }

    /**
     * Текст плашки: пункты через «·»; вокруг предупреждений — просто пробелы. Пункт не
     * переносится по словам: пробелы внутри него неразрывные, перенос — только между пунктами.
     * Точка-разделитель остаётся в конце строки.
     */
    static CharSequence screenText(List<Part> parts) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String prevItem = null;
        for (Part p : parts) {
            if (sb.length() > 0) {
                sb.append(WARNINGS.equals(prevItem) || WARNINGS.equals(p.item) ? "     " : NBSP + NBSP + "·  ");
            }
            // Предупреждения уже собраны с неразрывными пробелами внутри каждого.
            sb.append(WARNINGS.equals(p.item) ? p.screen : unbreakable(p.screen));
            prevItem = p.item;
        }
        return sb.length() == 0 ? null : sb;
    }

    private static final String NBSP = " ";

    /** Копия текста (со стилями), где все пробелы неразрывные. */
    private static CharSequence unbreakable(CharSequence s) {
        SpannableStringBuilder b = new SpannableStringBuilder(s);
        for (int i = 0; i < b.length(); i++) {
            if (b.charAt(i) == ' ') b.replace(i, i + 1, NBSP);
        }
        return b;
    }

    static String speechText(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) sb.append(p.speech).append(' ');
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- Показ

    /**
     * Показать плашку и, если включено, озвучить. Вызывать из главного потока.
     * @param occasion Prefs.SUMMARY_WELCOME или Prefs.SUMMARY_FAREWELL
     * @param speechDelayMs задержка голоса (на прощании — пока играет звук прощания)
     * @return false, если показывать нечего (машина не отдала значений)
     */
    boolean show(String occasion, Values v, long speechDelayMs) {
        hide();
        if (speechTask != null) ui.removeCallbacks(speechTask);
        List<Part> parts = parts(context, occasion, v, true, useMiles(context));
        CharSequence text = screenText(parts);
        if (text == null) return false;
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(26);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        int pad = Ui.dp(context, 20);
        t.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2101418);
        bg.setCornerRadius(Ui.dp(context, 16));
        t.setBackground(bg);
        t.setOnClickListener(x -> hide());
        // Отступы от краёв экрана: у окна поверх экрана своих полей нет.
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (Overlay.show(context, frame, Gravity.BOTTOM, ViewGroup.LayoutParams.WRAP_CONTENT)) {
            banner = frame;
            ui.postDelayed(hideTask, SHOW_MS);
        }
        if (isSpeakEnabled(context, occasion)) {
            String s = speechText(parts);
            if (!s.isEmpty()) {
                speechTask = () -> speech.speak(s);
                ui.postDelayed(speechTask, speechDelayMs);
            }
        }
        return true;
    }

    /** Убрать плашку; голос, если уже назначен, прозвучит. */
    void hide() {
        ui.removeCallbacks(hideTask);
        Overlay.hide(context, banner);
        banner = null;
    }

    void release() {
        hide();
        if (speechTask != null) ui.removeCallbacks(speechTask);
        speech.shutdown();
    }
}
