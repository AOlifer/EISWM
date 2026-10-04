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
 * Разделы: «Звуки» ({@link SoundsPanel}) и «Картинки» ({@link PicturesPanel}); в каждом вкладки
 * «Приветствие» и «Прощание» ({@link SectionTabs}, {@link FarewellTab}). Раздел «Сводка»
 * ({@link SummarySection}) — тоже с вкладками «Приветствие» и «Прощание».
 * Новый раздел = панель в activity_main.xml + запись в {@link #setupSections()}.
 * Файлы добавляются через {@link PickerActivity}.
 */
public class MainActivity extends BaseActivity {
    private static final String STATE_SECTION = "section";
    private static final String STATE_SOUNDS_TAB = "soundsTab", STATE_PICTURES_TAB = "picturesTab",
            STATE_SUMMARY_TAB = "summaryTab";

    /** Раздел приложения: пункт в колонке слева, панель и своя справка. */
    private static final class Section {
        final int icon;
        final String title, helpText;
        /** Справка вкладки «Прощание»; null, если вкладок нет. */
        String farewellHelp;
        SectionTabs tabs;
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
    private FarewellTab farewellSounds, farewellPictures;
    private SummarySection summarySection;
    private UpdateController updates;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);
        soundsPanel = new SoundsPanel(this, prefs);

        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnExit).setOnClickListener(v -> exitApp());
        findViewById(R.id.logo).setClipToOutline(true);
        setupSections();
        setupHome();
        picturesPanel = new PicturesPanel(this);
        summarySection = new SummarySection(this, prefs);
        setupFarewellTabs(b);
        // Запуск — со стартового экрана; после смены темы остаёмся в том же разделе.
        showSection(b != null ? b.getInt(STATE_SECTION, -1) : -1);
        if (b == null && !prefs.getBoolean(Prefs.DISCLAIMER_SHOWN, false)) showDisclaimer(true);
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
        stopFarewellPreviews();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (soundsPanel != null) soundsPanel.destroy();
        if (picturesPanel != null) picturesPanel.destroy();
        if (farewellSounds != null) farewellSounds.destroy();
        if (farewellPictures != null) farewellPictures.destroy();
        if (summarySection != null) summarySection.destroy();
        if (updates != null) updates.destroy();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (picturesPanel != null && picturesPanel.onActivityResult(requestCode, resultCode, data)) return;
        if (farewellSounds != null && farewellSounds.onActivityResult(requestCode, resultCode, data)) return;
        if (farewellPictures != null && farewellPictures.onActivityResult(requestCode, resultCode, data)) return;
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
        if (!soundsPanel.isBusy() && (picturesPanel == null || !picturesPanel.isBusy())
                && (farewellSounds == null || !farewellSounds.isBusy())
                && (farewellPictures == null || !farewellPictures.isBusy())) {
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
        sections.add(new Section(R.drawable.ic_section_summary, getString(R.string.summary_section),
                findViewById(R.id.summaryPanel), getString(R.string.summary_help)));

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

    /** Вкладки «Приветствие | Прощание» в разделах «Звуки», «Картинки» и «Сводка». */
    private void setupFarewellTabs(Bundle b) {
        farewellSounds = FarewellTab.sounds(this, prefs);
        farewellPictures = FarewellTab.pictures(this, prefs);
        SectionTabs.Listener stopAll = tab -> {
            soundsPanel.stopPreview();
            stopFarewellPreviews();
        };
        Section sounds = sections.get(0), pictures = sections.get(1);
        sounds.tabs = new SectionTabs(this, R.id.soundsTabs,
                new int[]{R.id.soundsWelcomeContent, R.id.soundsWelcomeSide},
                new int[]{R.id.soundsFarewellContent, R.id.soundsFarewellSide}, stopAll);
        sounds.farewellHelp = getString(R.string.farewell_sounds_help);
        pictures.tabs = new SectionTabs(this, R.id.picturesTabs,
                new int[]{R.id.picturesWelcomeContent, R.id.picturesWelcomeSide},
                new int[]{R.id.picturesFarewellContent, R.id.picturesFarewellSide}, stopAll);
        pictures.farewellHelp = getString(R.string.farewell_pictures_help);
        Section summaryTabs = sections.get(2);
        summaryTabs.tabs = new SectionTabs(this, R.id.summaryTabs,
                new int[]{R.id.summaryWelcomeContent, R.id.summaryWelcomeSide},
                new int[]{R.id.summaryFarewellContent, R.id.summaryFarewellSide}, tab -> {
                    summarySection.stopPreview();
                    summarySection.onShown();
                });
        summaryTabs.farewellHelp = getString(R.string.summary_farewell_help);
        if (b != null) {
            sounds.tabs.select(b.getInt(STATE_SOUNDS_TAB, SectionTabs.WELCOME));
            pictures.tabs.select(b.getInt(STATE_PICTURES_TAB, SectionTabs.WELCOME));
            summaryTabs.tabs.select(b.getInt(STATE_SUMMARY_TAB, SectionTabs.WELCOME));
        }
    }

    private void stopFarewellPreviews() {
        if (farewellSounds != null) farewellSounds.stopPreview();
        if (farewellPictures != null) farewellPictures.stopPreview();
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
        stopFarewellPreviews();
        if (summarySection != null) {
            if (index == 2) summarySection.onShown();
            else summarySection.stopPreview();
        }
    }

    /** Состояние раздела переживает пересоздание экрана (например, смену темы). */
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_SECTION, currentSection);
        if (sections.get(0).tabs != null) out.putInt(STATE_SOUNDS_TAB, sections.get(0).tabs.current());
        if (sections.get(1).tabs != null) out.putInt(STATE_PICTURES_TAB, sections.get(1).tabs.current());
        if (sections.get(2).tabs != null) out.putInt(STATE_SUMMARY_TAB, sections.get(2).tabs.current());
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
        findViewById(R.id.homeSummary).setOnClickListener(v -> showSection(2));
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
                        prefs.edit().putBoolean(Prefs.DISCLAIMER_SHOWN, true).apply())
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
        boolean farewell = s.tabs != null && s.tabs.current() == SectionTabs.FAREWELL;
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setMessage(farewell ? s.farewellHelp : s.helpText)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    /** Авто → светлая → тёмная → авто; экран пересоздаётся с новой темой. */
    private void switchTheme() {
        int next = (themeMode(this) + 1) % THEME_COUNT;
        prefs.edit().putInt(Prefs.THEME, next).apply();
        toast(getString(R.string.theme_toast, themeName(next)));
        recreate();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
