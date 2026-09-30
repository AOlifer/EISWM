package com.eiswm;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
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
    /** Последняя найденная на сервере версия: строка «Доступна версия N» видна до установки. */
    private static final String PREF_UPDATE_CODE = "update_available_code";
    private static final String PREF_UPDATE_NAME = "update_available_name";

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
    private Updater updater;
    private TextView homeUpdate;
    private Updater.Release availableRelease;
    private boolean checkingUpdates = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

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
        if (b == null) autoCheckForUpdates();
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
        destroyed = true;
        if (soundsPanel != null) soundsPanel.destroy();
        if (picturesPanel != null) picturesPanel.destroy();
        ui.removeCallbacksAndMessages(null);
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

        updater = new Updater(this);
        homeUpdate = findViewById(R.id.homeUpdate);
        homeUpdate.setOnClickListener(v -> {
            if (availableRelease != null) showUpdateDialog(availableRelease);
            else checkForUpdates(true);
        });
        // Найденная раньше версия видна сразу, даже если сегодня проверки ещё не было.
        String known = prefs.getString(PREF_UPDATE_NAME, null);
        if (known != null && prefs.getInt(PREF_UPDATE_CODE, 0) > updater.currentVersionCode()) {
            homeUpdate.setText(getString(R.string.update_available, known));
        }
    }

    // ---------------------------------------------------------------- Обновления

    /** Проверка при запуске: не чаще раза в сутки, без сообщений при ошибке. */
    private void autoCheckForUpdates() {
        long last = prefs.getLong(Updater.PREF_LAST_CHECK, 0);
        long now = System.currentTimeMillis();
        if (now - last >= Updater.AUTO_CHECK_INTERVAL_MS || now < last) checkForUpdates(false);
    }

    /** @param manual нажата «Проверить обновления»: показать результат и окно с новой версией. */
    private void checkForUpdates(boolean manual) {
        if (checkingUpdates) return;
        checkingUpdates = true;
        if (manual) homeUpdate.setText(R.string.update_checking);
        new Thread(() -> {
            Updater.Release r = null;
            try {
                r = updater.fetchLatest();
            } catch (Exception ignored) {
            }
            final Updater.Release result = r;
            ui.post(() -> onUpdateChecked(result, manual));
        }, "eiswm-update-check").start();
    }

    private void onUpdateChecked(Updater.Release r, boolean manual) {
        checkingUpdates = false;
        if (destroyed) return;
        if (r == null) {
            if (manual) homeUpdate.setText(R.string.update_failed);
            return;
        }
        prefs.edit().putLong(Updater.PREF_LAST_CHECK, System.currentTimeMillis()).apply();
        if (r.versionCode > updater.currentVersionCode()) {
            availableRelease = r;
            prefs.edit().putInt(PREF_UPDATE_CODE, r.versionCode).putString(PREF_UPDATE_NAME, r.versionName).apply();
            homeUpdate.setText(getString(R.string.update_available, r.versionName));
            if (manual) showUpdateDialog(r);
        } else {
            availableRelease = null;
            prefs.edit().remove(PREF_UPDATE_CODE).remove(PREF_UPDATE_NAME).apply();
            homeUpdate.setText(manual ? R.string.update_latest : R.string.update_check);
        }
    }

    /** Что нового в версии и кнопка «Скачать и установить». */
    private void showUpdateDialog(Updater.Release r) {
        StringBuilder msg = new StringBuilder();
        if (!r.changes.isEmpty()) msg.append(r.changes.trim()).append("\n\n");
        if (!r.date.isEmpty()) msg.append(getString(R.string.update_released, r.date)).append(' ');
        if (r.size > 0) msg.append(getString(R.string.update_size, FileUtils.formatSize(this, r.size)));
        boolean install = updater.canInstall();
        if (!install) msg.append("\n\n").append(getString(R.string.update_emulator_note));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_title, r.versionName))
                .setMessage(msg.toString().trim())
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(install ? R.string.update_install : R.string.update_download,
                        (d, w) -> downloadUpdate(r))
                .show();
    }

    /** Загрузка с полоской прогресса; затем установка (на машине) или сообщение (на эмуляторе). */
    private void downloadUpdate(Updater.Release r) {
        final boolean[] cancelled = {false};
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(r.size <= 0);
        bar.setMax(1000);
        TextView text = new TextView(this);
        text.setTextColor(getColor(R.color.text_secondary));
        text.setTextSize(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar);
        box.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.update_downloading)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled[0] = true)
                .show();
        final long[] lastUi = {0};
        new Thread(() -> {
            File apk = null;
            String error = null;
            try {
                apk = updater.download(r, new Updater.Progress() {
                    @Override public void onProgress(long done, long total) {
                        long now = System.currentTimeMillis();
                        if (now - lastUi[0] < 200) return;
                        lastUi[0] = now;
                        ui.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) (done * 1000 / total));
                                text.setText(FileUtils.formatSize(MainActivity.this, done) + " / "
                                        + FileUtils.formatSize(MainActivity.this, total));
                            } else {
                                text.setText(FileUtils.formatSize(MainActivity.this, done));
                            }
                        });
                    }

                    @Override public boolean cancelled() {
                        return cancelled[0] || destroyed;
                    }
                });
                if (apk != null && updater.canInstall()) {
                    prefs.edit().putBoolean(Updater.PREF_REOPEN, true).commit();
                    updater.install(apk);
                }
            } catch (Exception e) {
                prefs.edit().remove(Updater.PREF_REOPEN).apply();
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final File done = apk;
            final String err = error;
            ui.post(() -> {
                if (destroyed) return;
                dialog.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.update_error, err))
                            .setPositiveButton(R.string.got_it, null)
                            .show();
                } else if (done != null) {
                    toastLong(updater.canInstall() ? getString(R.string.update_installing)
                            : getString(R.string.update_emulator_done, done.getPath()));
                }
            });
        }, "eiswm-update-download").start();
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

    private void toastLong(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
