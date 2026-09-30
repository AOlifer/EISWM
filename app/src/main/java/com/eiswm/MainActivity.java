package com.eiswm;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * EISWM — Evolute I-Space Welcome Manager.
 * Главный экран: колонка разделов слева, содержимое выбранного раздела справа.
 * Разделы: «Звуки» ({@link SoundsPanel}) и «Картинки» ({@link PicturesPanel}).
 * Новый раздел = панель в activity_main.xml + запись в {@link #setupSections()}.
 * Файлы добавляются через {@link PickerActivity}.
 */
public class MainActivity extends BaseActivity {
    private static final String STATE_SECTION = "section";
    private static final String PREF_DISCLAIMER_SHOWN = "disclaimer_shown";

    /** Раздел приложения: пункт в колонке слева, панель и своя справка. */
    private static final class Section {
        final int icon;
        final String title, helpText;
        final View panel;
        TextView railItem;

        Section(int icon, String title, View panel, String helpText) {
            this.icon = icon;
            this.title = title;
            this.panel = panel;
            this.helpText = helpText;
        }
    }

    private final List<Section> sections = new ArrayList<>();
    private int currentSection = -1;
    private View homePanel;

    private SharedPreferences prefs;
    private SoundsPanel soundsPanel;
    private PicturesPanel picturesPanel;
    private UpdateController updates;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        soundsPanel = new SoundsPanel(this, prefs);

        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnExit).setOnClickListener(v -> exitApp());
        findViewById(R.id.logo).setClipToOutline(true);
        setupSections();
        setupHome();
        picturesPanel = new PicturesPanel(this);
        // Запуск — со стартового экрана; после смены темы остаёмся в том же разделе.
        showSection(b != null ? b.getInt(STATE_SECTION, -1) : -1);
        if (b == null && !prefs.getBoolean(PREF_DISCLAIMER_SHOWN, false)) showDisclaimer(true);
        if (b == null) updates.autoCheckForUpdates();
    }

    @Override protected void onStart() {
        super.onStart();
        soundsPanel.onStart();
    }

    @Override protected void onResume() {
        super.onResume();
        soundsPanel.onResume();
    }

    @Override protected void onStop() {
        soundsPanel.onStop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (soundsPanel != null) soundsPanel.destroy();
        if (picturesPanel != null) picturesPanel.destroy();
        if (updates != null) updates.destroy();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (picturesPanel != null && picturesPanel.onActivityResult(requestCode, resultCode, data)) return;
        soundsPanel.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- Выход

    /** Системная «Назад» на главном экране работает так же, как кнопка «Выход». */
    @Override public void onBackPressed() {
        exitApp();
    }

    /**
     * Закрыть приложение и убрать его из недавних. Во время копирования сначала спросить:
     * копирование при выходе доделается в фоне, но итог «Добавлено: N» никто не увидит.
     */
    private void exitApp() {
        if (!soundsPanel.isBusy() && (picturesPanel == null || !picturesPanel.isBusy())) {
            finishAndRemoveTask();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_busy_title)
                .setMessage(R.string.exit_busy_message)
                .setPositiveButton(R.string.exit_wait, null)
                .setNegativeButton(R.string.exit_now, (d, w) -> finishAndRemoveTask())
                .show();
    }
    // ---------------------------------------------------------------- Разделы, помощь, тема

    private void setupSections() {
        sections.add(new Section(R.drawable.ic_volume_up, getString(R.string.sounds_section),
                findViewById(R.id.soundsPanel), getString(R.string.sounds_help)));
        sections.add(new Section(R.drawable.ic_section_pictures, getString(R.string.pictures_section),
                findViewById(R.id.picturesPanel), getString(R.string.pictures_help)));

        LinearLayout rail = findViewById(R.id.sectionRail);
        for (int i = 0; i < sections.size(); i++) {
            final int index = i;
            Section s = sections.get(i);
            s.railItem = Ui.railItem(this, s.icon, s.title, true);
            s.railItem.setOnClickListener(v -> showSection(index));
            rail.addView(s.railItem);
        }

        // Тема — внизу колонки, под разделами.
        rail.addView(new Space(this), new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        String themeName = themeName(themeMode(this));
        TextView theme = Ui.railItem(this, R.drawable.ic_theme, getString(R.string.theme_rail, themeName), true);
        Ui.setSelected(theme, false);
        theme.setContentDescription(getString(R.string.theme_desc, themeName));
        theme.setOnClickListener(v -> switchTheme());
        ((LinearLayout.LayoutParams) theme.getLayoutParams()).bottomMargin = 0;
        rail.addView(theme);
    }

    /** @param index номер раздела; -1 — стартовый экран, где ни один раздел не выбран. */
    private void showSection(int index) {
        if (index >= sections.size()) index = -1;
        currentSection = index;
        homePanel.setVisibility(index < 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            Ui.setSelected(s.railItem, i == index);
            s.panel.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (index != 0) soundsPanel.stopPreview();
    }

    /** Состояние раздела переживает пересоздание экрана (например, смену темы). */
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_SECTION, currentSection);
    }

    /** Стартовый экран: картинка, описание, версия, разработчик и быстрый переход в разделы. */
    private void setupHome() {
        homePanel = findViewById(R.id.homePanel);
        findViewById(R.id.homeImage).setClipToOutline(true);
        homePanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::fitHomeImage);
        });
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.homeVersion)).setText(getString(R.string.home_version, version));
        findViewById(R.id.homeSounds).setOnClickListener(v -> showSection(0));
        findViewById(R.id.homePictures).setOnClickListener(v -> showSection(1));
        findViewById(R.id.logo).setOnClickListener(v -> showSection(-1));
        findViewById(R.id.appTitle).setOnClickListener(v -> showSection(-1));

        updates = new UpdateController(this, prefs, findViewById(R.id.homeUpdate));
    }

    /**
     * Картинка стартового экрана во всю высоту панели, но не шире 40% её ширины: приложение
     * бывает открыто не во весь экран (например, рядом с панелью лаунчера), и тексту нужно место.
     */
    private void fitHomeImage() {
        View image = findViewById(R.id.homeImage);
        android.graphics.drawable.Drawable d = ((android.widget.ImageView) image).getDrawable();
        int availH = homePanel.getHeight() - homePanel.getPaddingTop() - homePanel.getPaddingBottom();
        int availW = homePanel.getWidth() - homePanel.getPaddingLeft() - homePanel.getPaddingRight();
        if (d == null || availH <= 0 || availW <= 0 || d.getIntrinsicHeight() <= 0) return;
        float aspect = (float) d.getIntrinsicWidth() / d.getIntrinsicHeight();
        int w = Math.min(Math.round(availH * aspect), Math.round(availW * 0.4f));
        int h = Math.round(w / aspect);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) image.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        image.setLayoutParams(lp);
    }

    /**
     * Отказ от ответственности (полный текст — DISCLAIMER.md в репозитории).
     * @param firstRun показывается сам при первом запуске; после «Понятно» больше не появляется.
     */
    private void showDisclaimer(boolean firstRun) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_text)
                .setCancelable(!firstRun)
                .setPositiveButton(R.string.got_it, (d, w) ->
                        prefs.edit().putBoolean(PREF_DISCLAIMER_SHOWN, true).apply())
                .show();
    }

    /** Помощь по тому разделу, который сейчас открыт (или общая — на стартовом экране). */
    private void showHelp() {
        if (currentSection < 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(getString(R.string.home_help) + "\n\n" + getString(R.string.home_disclaimer_short))
                    .setPositiveButton(R.string.got_it, null)
                    .setNeutralButton(R.string.disclaimer_title, (d, w) -> showDisclaimer(false))
                    .show();
            return;
        }
        Section s = sections.get(currentSection);
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setMessage(s.helpText)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    /** Авто → светлая → тёмная → авто; экран пересоздаётся с новой темой. */
    private void switchTheme() {
        int next = (themeMode(this) + 1) % THEME_COUNT;
        prefs.edit().putInt(PREF_THEME, next).apply();
        toast(getString(R.string.theme_toast, themeName(next)));
        recreate();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
