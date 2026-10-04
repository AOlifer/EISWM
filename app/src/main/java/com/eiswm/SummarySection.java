package com.eiswm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Раздел «Сводка»: вкладки «Приветствие» (при включении зажигания) и «Прощание» (при
 * выключении) со своими настройками ({@link CarSummary}). У каждой вкладки выключатели
 * «Показывать сводку» и «Озвучивать сводку», пункты с галочками, образцом плашки по текущим
 * значениям из машины и кнопкой ▶ (как пункт звучит), и «Показать сейчас».
 * Порядок пунктов меняется перетаскиванием за ручку ≡.
 */
final class SummarySection extends SectionPanel {
    /** Значения обновляются, пока раздел на экране. */
    private static final long REFRESH_MS = 5000;

    private static final Map<String, Integer> TITLES = new HashMap<>();

    static {
        TITLES.put(CarSummary.WARNINGS, R.string.summary_item_warnings);
        TITLES.put(CarSummary.TEMP, R.string.summary_item_temp);
        TITLES.put(CarSummary.TEMP_INSIDE, R.string.summary_item_temp_inside);
        TITLES.put(CarSummary.RANGE, R.string.summary_item_range);
        TITLES.put(CarSummary.CHARGE, R.string.summary_item_charge);
        TITLES.put(CarSummary.FUEL, R.string.summary_item_fuel);
        TITLES.put(CarSummary.SERVICE, R.string.summary_item_service);
        TITLES.put(CarSummary.TRIP_DISTANCE, R.string.summary_item_trip_distance);
        TITLES.put(CarSummary.TRIP_TIME, R.string.summary_item_trip_time);
        TITLES.put(CarSummary.TRIP_SPEED, R.string.summary_item_trip_speed);
        TITLES.put(CarSummary.CHARGE_USED, R.string.summary_item_charge_used);
        TITLES.put(CarSummary.FUEL_USED, R.string.summary_item_fuel_used);
    }

    private final SharedPreferences prefs;
    private final View panel;
    private final Tab welcome, farewell;
    private final CarSummary summary;
    /** Голос для кнопок ▶ у пунктов. */
    private final Speech voice;
    private final HandlerThread carThread = new HandlerThread("eiswm-summary");
    private final Handler carHandler;
    private CarApi car;
    /** Последние значения из машины; null — ещё не прочитаны. */
    private CarSummary.Values values;
    /** Есть синтезатор речи: без него ▶ и «Озвучивать сводку» недоступны. */
    private boolean voiceAvailable = true;
    /** Кнопки «л» / «%» у пунктов о топливе. */
    private final List<Button> fuelUnitButtons = new ArrayList<>();

    private final Runnable refreshTask = this::refresh;

    SummarySection(Activity activity, SharedPreferences prefs) {
        super(activity);
        this.prefs = prefs;
        summary = new CarSummary(activity);
        voice = new Speech(activity);
        panel = activity.findViewById(R.id.summaryPanel);
        welcome = new Tab(Prefs.SUMMARY_WELCOME, R.id.summaryWelcomeList, R.id.summaryWelcomeSwitch,
                R.id.summaryWelcomeSpeakSwitch, R.id.summaryWelcomeEngine, R.id.btnSummaryWelcomeShow);
        farewell = new Tab(Prefs.SUMMARY_FAREWELL, R.id.summaryFarewellList, R.id.summaryFarewellSwitch,
                R.id.summaryFarewellSpeakSwitch, R.id.summaryFarewellEngine, R.id.btnSummaryFarewellShow);

        checkEngine();

        carThread.start();
        carHandler = new Handler(carThread.getLooper());
        if (CarApi.isAvailable()) {
            car = new CarApi(activity);
            carHandler.post(() -> {
                try {
                    car.connect(carHandler, () -> ui.post(this::refresh), () -> {
                    });
                } catch (Throwable ignored) {
                }
            });
        } else {
            // Вне машины (эмулятор) образцы строятся по примеру, чтобы было видно, как выглядит сводка.
            values = demo();
            updatePreviews();
        }
        CarEventsService.update(activity);
    }

    @Override void destroy() {
        summary.release();
        voice.shutdown();
        carHandler.post(() -> {
            if (car != null) car.disconnect();
            carThread.quitSafely();
        });
        super.destroy();
    }

    /** Раздел или вкладка показаны: обновлять значения, пока они на экране. */
    void onShown() {
        refresh();
        checkEngine();
    }

    /**
     * Есть ли чем озвучивать. Без синтезатора выключатель «Озвучивать сводку» недоступен.
     * Проверяется при каждом показе раздела: синтезатор могли поставить, пока приложение открыто.
     */
    private void checkEngine() {
        if (!Features.SPEECH) return;
        io.execute(() -> {
            String name = Speech.engineName(activity);
            String text = name != null ? activity.getString(R.string.summary_engine, name)
                    : activity.getString(R.string.summary_no_engine);
            ui.post(() -> {
                if (destroyed) return;
                voiceAvailable = name != null;
                welcome.setEngine(text, voiceAvailable);
                farewell.setEngine(text, voiceAvailable);
                updatePreviews();
            });
        });
    }

    /** Раздел скрыт: убрать плашку, показанную кнопкой «Показать сейчас». */
    void stopPreview() {
        summary.hide();
    }

    // ---------------------------------------------------------------- Вкладка

    /** Вкладка «Приветствие» или «Прощание»: свои настройки и список пунктов. */
    private final class Tab {
        final String occasion;
        final LinearLayout list;
        final Switch toggle, speakToggle;
        final TextView engine;
        /** Пункт → образец плашки, пояснение без значения, кнопка ▶. */
        final Map<String, View[]> previews = new HashMap<>();
        private View dragged;
        private boolean updating;

        Tab(String occasion, int listId, int switchId, int speakId, int engineId, int showId) {
            this.occasion = occasion;
            list = activity.findViewById(listId);
            toggle = activity.findViewById(switchId);
            speakToggle = activity.findViewById(speakId);
            engine = activity.findViewById(engineId);
            toggle.setOnCheckedChangeListener((v, checked) -> {
                if (updating) return;
                prefs.edit().putBoolean(occasion + Prefs.SUMMARY_ENABLED, checked).apply();
                CarEventsService.update(activity);
            });
            speakToggle.setOnCheckedChangeListener((v, checked) -> {
                if (!updating) prefs.edit().putBoolean(occasion + Prefs.SUMMARY_SPEAK, checked).apply();
            });
            activity.findViewById(showId).setOnClickListener(v -> showNow(occasion));
            list.setOnDragListener(this::onDrag);
            updating = true;
            toggle.setChecked(CarSummary.isEnabled(activity, occasion));
            speakToggle.setChecked(CarSummary.isSpeakEnabled(activity, occasion));
            updating = false;
            if (!Features.SPEECH) {
                // Озвучка выключена: строка выключателя и подпись про синтезатор скрыты.
                ((View) speakToggle.getParent()).setVisibility(View.GONE);
                engine.setVisibility(View.GONE);
            }
            buildList();
        }

        /** Без синтезатора выключатель озвучки недоступен и показан выключенным; настройка сохраняется. */
        void setEngine(String text, boolean available) {
            engine.setText(text);
            updating = true;
            speakToggle.setChecked(available && CarSummary.isSpeakEnabled(activity, occasion));
            updating = false;
            speakToggle.setEnabled(available);
            ((View) speakToggle.getParent()).setAlpha(available ? 1f : 0.45f);
        }

        private void buildList() {
            list.removeAllViews();
            previews.clear();
            for (String item : CarSummary.order(activity, occasion)) addRow(item);
        }

        /** Строка пункта; вместе с разделителем лежит в обёртке, которую перетаскивают. */
        @SuppressLint("ClickableViewAccessibility")
        private void addRow(String item) {
            LinearLayout wrapper = new LinearLayout(activity);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setTag(item);
            wrapper.setBackgroundColor(activity.getColor(R.color.surface));

            LinearLayout row = Ui.row(activity);
            CheckBox check = new CheckBox(activity);
            check.setChecked(CarSummary.isItemOn(activity, occasion, item));
            check.setOnCheckedChangeListener((v, checked) -> {
                CarSummary.setItemOn(prefs, occasion, item, checked);
                SummarySection.this.updatePreviews();
            });
            String title = activity.getString(TITLES.get(item));
            check.setContentDescription(title);
            row.addView(check, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
            texts.addView(Ui.title(activity, title));
            // Образец плашки — на тёмном фоне, как на экране машины.
            TextView screen = new TextView(activity);
            screen.setTextSize(17);
            screen.setTextColor(Color.WHITE);
            int pad = Ui.dp(activity, 8);
            screen.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF2101418);
            bg.setCornerRadius(Ui.dp(activity, 8));
            screen.setBackground(bg);
            LinearLayout.LayoutParams screenLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            screenLp.topMargin = Ui.dp(activity, 6);
            texts.addView(screen, screenLp);
            // Вместо образца, если значения нет: почему его нет.
            TextView status = new TextView(activity);
            status.setTextSize(16);
            status.setTextColor(activity.getColor(R.color.text_secondary));
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            statusLp.topMargin = Ui.dp(activity, 4);
            texts.addView(status, statusLp);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            textsLp.setMarginStart(Ui.dp(activity, 2));
            row.addView(texts, textsLp);

            // «л» / «%» у топлива — в чём показывать бак и расход; выбор общий для обоих пунктов.
            if (CarSummary.FUEL.equals(item) || CarSummary.FUEL_USED.equals(item)) {
                Button unit = Ui.iconButton(activity, "");
                unit.setTextSize(20);
                unit.setAllCaps(false);
                unit.setContentDescription(activity.getString(R.string.summary_fuel_unit_desc));
                unit.setOnClickListener(v -> {
                    CarSummary.setFuelVolume(prefs, !CarSummary.isFuelVolume(activity));
                    SummarySection.this.updatePreviews();
                });
                row.addView(unit, Ui.iconButtonParams(activity));
                fuelUnitButtons.add(unit);
            }

            // ⚙ у предупреждений — какие показывать и пороги.
            if (CarSummary.WARNINGS.equals(item)) {
                Button settings = Ui.iconButton(activity, "⚙");
                settings.setContentDescription(activity.getString(R.string.warn_settings_desc));
                settings.setOnClickListener(v -> showWarningSettings());
                row.addView(settings, Ui.iconButtonParams(activity));
            }

            // ▶ — как пункт прозвучит голосом.
            Button play = Ui.iconButton(activity, "▶");
            play.setContentDescription(activity.getString(R.string.summary_play_desc, title));
            play.setOnClickListener(v -> {
                CarSummary.Part p = values != null ? CarSummary.part(activity, item, values, CarSummary.useMiles(activity)) : null;
                if (p != null) voice.speak(p.speech);
            });
            row.addView(play, Ui.iconButtonParams(activity));
            if (!Features.SPEECH) play.setVisibility(View.GONE);

            // Ручка: нажать и тянуть вверх или вниз.
            TextView handle = new TextView(activity);
            handle.setText("≡");
            handle.setTextSize(32);
            handle.setGravity(Gravity.CENTER);
            handle.setTextColor(activity.getColor(R.color.text_secondary));
            handle.setContentDescription(activity.getString(R.string.summary_drag_desc, title));
            handle.setOnTouchListener((v, e) -> {
                if (e.getAction() != MotionEvent.ACTION_DOWN) return false;
                startDrag(wrapper);
                return true;
            });
            row.addView(handle, Ui.iconButtonParams(activity));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> check.toggle());
            // Долгое нажатие на строку — тоже перетаскивание.
            row.setOnLongClickListener(v -> {
                startDrag(wrapper);
                return true;
            });

            wrapper.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            Ui.divider(wrapper);
            list.addView(wrapper, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            previews.put(item, new View[]{screen, status, play});
        }

        @SuppressWarnings("deprecation")
        private void startDrag(View wrapper) {
            dragged = wrapper;
            ClipData data = ClipData.newPlainText("item", String.valueOf(wrapper.getTag()));
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(wrapper);
            if (Build.VERSION.SDK_INT >= 24) wrapper.startDragAndDrop(data, shadow, wrapper, 0);
            else wrapper.startDrag(data, shadow, wrapper, 0);
            wrapper.setAlpha(0.3f);
        }

        /** Пока пункт тянут, он сразу встаёт на место под пальцем; порядок сохраняется в конце. */
        private boolean onDrag(View v, DragEvent e) {
            if (dragged == null) return e.getAction() == DragEvent.ACTION_DRAG_STARTED;
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_LOCATION:
                    moveTo(e.getY());
                    autoScroll(e.getY());
                    return true;
                case DragEvent.ACTION_DROP:
                case DragEvent.ACTION_DRAG_ENDED:
                    dragged.setAlpha(1f);
                    dragged = null;
                    saveOrder();
                    return true;
                default:
                    return true;
            }
        }

        private void moveTo(float y) {
            // Новое место = сколько других пунктов выше пальца (по их середине).
            int to = 0;
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                if (c != dragged && y > c.getTop() + c.getHeight() / 2f) to++;
            }
            if (to == list.indexOfChild(dragged)) return;
            list.removeView(dragged);
            list.addView(dragged, to);
        }

        /** У краёв видимой области список прокручивается сам. */
        private void autoScroll(float y) {
            if (!(list.getParent() instanceof ScrollView)) return;
            ScrollView scroll = (ScrollView) list.getParent();
            float visible = y - scroll.getScrollY();
            int edge = Ui.dp(activity, 64), step = Ui.dp(activity, 16);
            if (visible < edge) scroll.smoothScrollBy(0, -step);
            else if (visible > scroll.getHeight() - edge) scroll.smoothScrollBy(0, step);
        }

        private void saveOrder() {
            List<String> order = new ArrayList<>();
            for (int i = 0; i < list.getChildCount(); i++) order.add(String.valueOf(list.getChildAt(i).getTag()));
            CarSummary.setOrder(prefs, occasion, order);
        }

        /** Образцы плашки по последним значениям; ▶ доступна, если у пункта есть значение. */
        void updatePreviews(boolean miles) {
            for (Map.Entry<String, View[]> e : previews.entrySet()) {
                String item = e.getKey();
                TextView screen = (TextView) e.getValue()[0], status = (TextView) e.getValue()[1];
                View play = e.getValue()[2];
                CarSummary.Part p = values != null ? CarSummary.part(activity, item, values, miles) : null;
                if (p != null) {
                    screen.setText(p.screen);
                    screen.setVisibility(View.VISIBLE);
                    status.setVisibility(View.GONE);
                } else {
                    screen.setVisibility(View.GONE);
                    int empty = item.equals(CarSummary.WARNINGS) && values != null ? R.string.summary_no_warnings
                            : values == null ? R.string.summary_no_car
                            : CarSummary.isTripItem(item) ? R.string.summary_no_trip : R.string.summary_no_value;
                    status.setText(activity.getString(empty));
                    status.setVisibility(View.VISIBLE);
                }
                play.setEnabled(p != null && voiceAvailable);
                play.setAlpha(p != null && voiceAvailable ? 1f : 0.3f);
                screen.setAlpha(CarSummary.isItemOn(activity, occasion, item) ? 1f : 0.45f);
            }
        }
    }

    // ---------------------------------------------------------------- Предупреждения

    /** Окно настройки предупреждений: галочка и порог «−  значение  +» у каждого. */
    private void showWarningSettings() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        box.setPadding(pad, Ui.dp(activity, 8), pad, 0);
        TextView hint = new TextView(activity);
        hint.setText(R.string.warn_settings_hint);
        hint.setTextSize(16);
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        box.addView(hint);
        for (Warnings.Def d : Warnings.ALL) box.addView(warningRow(d));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.warn_settings)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setOnDismissListener(d -> updatePreviews())
                .show();
    }

    private View warningRow(Warnings.Def d) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, 0, 0, 0);
        String title = activity.getString(d.title);
        CheckBox check = new CheckBox(activity);
        check.setText(title);
        check.setTextSize(18);
        check.setTextColor(activity.getColor(R.color.text_primary));
        check.setChecked(Warnings.isOn(activity, d));
        row.addView(check, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        Button minus = Ui.iconButton(activity, "−");
        TextView value = new TextView(activity);
        value.setTextSize(19);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(activity.getColor(R.color.text_primary));
        Button plus = Ui.iconButton(activity, "+");
        minus.setContentDescription(activity.getString(R.string.warn_decrease, title));
        plus.setContentDescription(activity.getString(R.string.warn_increase, title));
        value.setText(limitText(d, Warnings.limit(activity, d)));
        minus.setOnClickListener(v -> value.setText(limitText(d, Warnings.step(activity, d, -1))));
        plus.setOnClickListener(v -> value.setText(limitText(d, Warnings.step(activity, d, 1))));
        check.setOnCheckedChangeListener((v, on) -> {
            Warnings.setOn(activity, d, on);
            minus.setEnabled(on);
            plus.setEnabled(on);
            value.setAlpha(on ? 1f : 0.45f);
        });
        boolean on = check.isChecked();
        minus.setEnabled(on);
        plus.setEnabled(on);
        value.setAlpha(on ? 1f : 0.45f);
        row.addView(minus, Ui.iconButtonParams(activity));
        row.addView(value, new LinearLayout.LayoutParams(Ui.dp(activity, 110), WRAP_CONTENT));
        row.addView(plus, Ui.iconButtonParams(activity));
        return row;
    }

    /** Порог в единицах машины: км или мили, °C или °F. */
    private String limitText(Warnings.Def d, float v) {
        switch (d.unit) {
            case TEMP: {
                boolean f = values != null && values.fahrenheit;
                int t = Math.round(f ? v * 9f / 5f + 32f : v);
                return activity.getString(f ? R.string.unit_f : R.string.unit_c,
                        String.format(Locale.getDefault(), t > 0 ? "+%d" : "%d", t));
            }
            case DISTANCE: {
                boolean miles = CarSummary.useMiles(activity);
                return activity.getString(miles ? R.string.unit_mi : R.string.unit_km,
                        String.valueOf(Math.round(miles ? v / CarSummary.KM_PER_MILE : v)));
            }
            case PERCENT:
                return activity.getString(R.string.unit_percent, String.valueOf(Math.round(v)));
            default:
                return activity.getString(R.string.unit_volt,
                        String.format(Locale.getDefault(), "%.1f", v));
        }
    }

    // ---------------------------------------------------------------- Значения

    private void updatePreviews() {
        boolean miles = CarSummary.useMiles(activity);
        // На кнопке — текущая единица; без объёма бака из машины выбирать не из чего.
        boolean volumeKnown = values != null && values.fuelCapacity != null && values.fuelCapacity > 0;
        String label = CarSummary.isFuelVolume(activity)
                ? activity.getString(miles ? R.string.unit_gal : R.string.unit_l, "").trim() : "%";
        for (Button b : fuelUnitButtons) {
            b.setText(label);
            b.setVisibility(volumeKnown ? View.VISIBLE : View.GONE);
        }
        welcome.updatePreviews(miles);
        farewell.updatePreviews(miles);
    }

    /** Прочитать значения из машины; повторять, пока раздел на экране. */
    private void refresh() {
        ui.removeCallbacks(refreshTask);
        if (destroyed || !panel.isShown()) return;
        if (car == null || !car.isConnected()) {
            updatePreviews();
            return;
        }
        carHandler.post(() -> {
            CarSummary.Values v = CarSummary.read(activity, car);
            ui.post(() -> {
                if (destroyed) return;
                values = v;
                updatePreviews();
                ui.postDelayed(refreshTask, REFRESH_MS);
            });
        });
    }

    private void showNow(String occasion) {
        if (car != null && car.isConnected()) {
            carHandler.post(() -> {
                CarSummary.Values v = CarSummary.read(activity, car);
                ui.post(() -> {
                    if (!destroyed && !summary.show(occasion, v, 0)) toast(activity.getString(R.string.summary_nothing));
                });
            });
            return;
        }
        toast(activity.getString(R.string.summary_demo));
        summary.show(occasion, demo(), 0);
    }

    /** Пример значений вне машины. */
    private static CarSummary.Values demo() {
        CarSummary.Values v = new CarSummary.Values();
        v.temp = 2f;
        v.tempInside = 18f;
        v.rangeKm = 376f;
        v.evRangeKm = 70f;
        v.charge = 78f;
        v.fuel = 38f;
        v.serviceKm = 1834f;
        v.voltage = 12.4f;
        v.fuelCapacity = 15000f;
        v.tripKm = 123.4f;
        v.tripMs = 98 * 60_000L;
        v.chargeUsed = 21f;
        v.fuelUsed = 6f;
        return v;
    }

    @Override void setBusy(boolean value) {
        busy = value;
    }

    @Override void afterJob() {
    }

    @Override String errorMessage(Throwable e) {
        return String.valueOf(e.getMessage());
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
