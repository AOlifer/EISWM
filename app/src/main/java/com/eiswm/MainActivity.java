package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EISWM — Evolute I-Space Welcome Manager.
 * Главный экран: колонка разделов слева, содержимое выбранного раздела справа.
 * Разделы: «Звуки» (этот класс) и «Картинки» ({@link PicturesPanel}).
 * Новый раздел = панель в activity_main.xml + запись в {@link #setupSections()}.
 * Файлы добавляются через {@link PickerActivity}.
 */
public class MainActivity extends BaseActivity implements AudioPreview.Listener {
    private static final String WELCOME_SWITCH = "bw_welcome_voice_switch";
    /**
     * Машина играет звук приветствия не дольше 6 с и обрывает более длинный;
     * 0,5 с — допуск на неточность метаданных MP3.
     */
    static final long MAX_SOUND_DURATION_MS = 6500;
    static final String[] SOUND_EXTENSIONS = {"mp3"};
    private static final int REQ_ADD_SOUNDS = 1;
    private static final int REQ_SAVE_FOLDER = 2;
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

    private File soundDir;
    private TextView welcomeLabel, welcomeWarning, soundStatus;
    private Switch welcomeSwitch;
    private LinearLayout soundList;
    private Button btnAddSounds;

    private AudioPreview preview;
    private final Map<File, SoundRow> rows = new HashMap<>();
    private File pendingSave;
    private SharedPreferences prefs;
    private PicturesPanel picturesPanel;
    private Updater updater;
    private TextView homeUpdate;
    private Updater.Release availableRelease;
    private boolean checkingUpdates = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Один фоновый поток: копирование и чтение длительности не блокируют интерфейс. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int listGeneration = 0;
    private boolean busy = false;
    private boolean destroyed = false;
    private boolean updatingSwitch = false;

    private static final class SoundRow {
        View root;
        Button play;
        ProgressBar progress;
    }

    private final ContentObserver welcomeObserver = new ContentObserver(ui) {
        @Override public void onChange(boolean selfChange) {
            updateWelcomeSwitch();
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            File current = preview.current();
            if (current == null) return;
            SoundRow r = rows.get(current);
            if (r != null) r.progress.setProgress(Math.round(preview.progress() * 1000));
            ui.postDelayed(this, 100);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        soundDir = new File(getString(R.string.welcome_sound_dir));
        preview = new AudioPreview(this);
        copyBundledWelcomeFilesOnce();
        io.execute(this::deleteLeftoverTempFiles);

        welcomeLabel = findViewById(R.id.welcomeLabel);
        welcomeSwitch = findViewById(R.id.welcomeSwitch);
        soundList = findViewById(R.id.soundList);
        soundStatus = findViewById(R.id.soundStatus);
        btnAddSounds = findViewById(R.id.btnAddSounds);

        welcomeSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingSwitch) setWelcomeSwitch(checked ? 1 : 0);
        });
        btnAddSounds.setOnClickListener(v -> openSoundPicker());
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnExit).setOnClickListener(v -> exitApp());
        welcomeWarning = findViewById(R.id.welcomeWarning);
        findViewById(R.id.logo).setClipToOutline(true);
        setupSections();
        setupHome();
        picturesPanel = new PicturesPanel(this);
        // Запуск — со стартового экрана; после смены темы остаёмся в том же разделе.
        showSection(b != null ? b.getInt(STATE_SECTION, -1) : -1);
        if (b == null && !prefs.getBoolean(PREF_DISCLAIMER_SHOWN, false)) showDisclaimer(true);
        if (b == null) autoCheckForUpdates();
        updateWelcomeSwitch();
        loadSounds();
    }

    @Override protected void onStart() {
        super.onStart();
        try {
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(WELCOME_SWITCH), false, welcomeObserver);
        } catch (Exception ignored) {
        }
    }

    @Override protected void onResume() {
        super.onResume();
        updateWelcomeSwitch();
    }

    @Override protected void onStop() {
        try {
            getContentResolver().unregisterContentObserver(welcomeObserver);
        } catch (Exception ignored) {
        }
        // Приложение ушло с экрана — звук не должен продолжать играть в фоне.
        preview.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        preview.release();
        if (picturesPanel != null) picturesPanel.destroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
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
        if (!busy && (picturesPanel == null || !picturesPanel.isBusy())) {
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

    /**
     * Удалить временные файлы «.имя.tmp», оставшиеся от копирования, которое прервалось
     * (например, машину выключили). Лаунчер их не видит, но место они занимают.
     */
    private void deleteLeftoverTempFiles() {
        File[] leftovers = soundDir.listFiles(f -> f.isFile()
                && f.getName().startsWith(".") && f.getName().endsWith(".tmp"));
        if (leftovers == null) return;
        for (File f : leftovers) f.delete();
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
        rail.addView(new Space(this), new LinearLayout.LayoutParams(-1, 0, 1));
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
        if (index != 0) preview.stop();
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
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.homeVersion)).setText(getString(R.string.home_version, version));
        findViewById(R.id.homeSounds).setOnClickListener(v -> showSection(0));
        findViewById(R.id.homePictures).setOnClickListener(v -> showSection(1));
        findViewById(R.id.homeDisclaimer).setOnClickListener(v -> showDisclaimer(false));
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
                    .setMessage(R.string.home_help)
                    .setPositiveButton(R.string.got_it, null)
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

    // ---------------------------------------------------------------- Переключатель приветствия

    private void updateWelcomeSwitch() {
        if (welcomeSwitch == null) return;
        int state = readWelcomeSwitch();
        updatingSwitch = true;
        if (state == 0 || state == 1) {
            welcomeSwitch.setEnabled(true);
            welcomeSwitch.setChecked(state == 1);
            welcomeLabel.setText(state == 1 ? R.string.sounds_on : R.string.sounds_off);
        } else {
            welcomeSwitch.setChecked(false);
            welcomeSwitch.setEnabled(false);
            welcomeLabel.setText(R.string.sounds_unknown);
        }
        welcomeWarning.setVisibility(state == 0 ? View.VISIBLE : View.GONE);
        updatingSwitch = false;
    }

    private void setWelcomeSwitch(int target) {
        if (!writeWelcomeSwitch(target)) {
            toast(getString(R.string.sounds_switch_failed));
        } else if (readWelcomeSwitch() != target) {
            toast(getString(R.string.sounds_switch_unchanged));
        }
        updateWelcomeSwitch();
    }

    private int readWelcomeSwitch() {
        try {
            return Settings.Global.getInt(getContentResolver(), WELCOME_SWITCH);
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            String value = runCommand("settings", "get", "global", WELCOME_SWITCH);
            try {
                return Integer.parseInt(value.trim());
            } catch (Exception ignored) {
                return -1;
            }
        }
    }

    private boolean writeWelcomeSwitch(int value) {
        try {
            if (Settings.Global.putInt(getContentResolver(), WELCOME_SWITCH, value)) {
                if (readWelcomeSwitch() == value) return true;
            }
        } catch (SecurityException ignored) {
        }

        String result = runRootCommand("settings", "put", "global", WELCOME_SWITCH, String.valueOf(value));
        return result != null && readWelcomeSwitch() == value;
    }

    private String runCommand(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String runRootCommand(String... command) {
        String[] rootCommand = new String[command.length + 2];
        rootCommand[0] = "su";
        rootCommand[1] = "root";
        System.arraycopy(command, 0, rootCommand, 2, command.length);
        return runCommand(rootCommand);
    }

    // ---------------------------------------------------------------- Встроенные MP3

    private void copyBundledWelcomeFilesOnce() {
        if (prefs.getBoolean("bundled_welcome_files_copied", false)) {
            return;
        }

        File targetDir = new File(FileUtils.INTERNAL_ROOT, "Notifications");
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            toast(getString(R.string.sounds_notifications_failed));
            return;
        }

        int copied = 0;
        try {
            String[] names = getAssets().list("welcome");
            if (names != null) {
                for (String name : names) {
                    if (!name.toLowerCase().endsWith(".mp3")) {
                        continue;
                    }
                    File dst = new File(targetDir, name);
                    if (dst.exists()) {
                        continue;
                    }
                    try (InputStream in = getAssets().open("welcome/" + name);
                         FileOutputStream out = new FileOutputStream(dst)) {
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                        out.flush();
                    }
                    copied++;
                }
            }
            prefs.edit().putBoolean("bundled_welcome_files_copied", true).apply();
            if (copied > 0) {
                toast(getString(R.string.sounds_bundled_added, copied));
            }
        } catch (IOException e) {
            toast(getString(R.string.sounds_bundled_failed));
        }
    }

    // ---------------------------------------------------------------- Список звуков

    private File[] listSounds() {
        return soundDir.listFiles(f -> f.isFile() && FileUtils.hasExtension(f, SOUND_EXTENSIONS));
    }

    private void loadSounds() {
        soundList.removeAllViews();
        rows.clear();
        // Сначала создаём каталог, потом читаем список.
        File[] files = soundDir.isDirectory() || soundDir.mkdirs() ? listSounds() : null;
        if (files == null) {
            Ui.emptyState(soundList, getString(R.string.sounds_no_access), soundDir.getAbsolutePath());
            setStatus("");
            return;
        }
        FileUtils.sortByName(files);
        if (files.length == 0) {
            Ui.emptyState(soundList, getString(R.string.sounds_empty),
                    getString(R.string.sounds_empty_details));
        }
        if (!busy) setStatus(summary(files.length));

        final int generation = ++listGeneration;
        final List<TextView> pills = new ArrayList<>();
        for (File f : files) pills.add(addSoundRow(f));
        onPreviewChanged();

        // Длительность читается в фоне: MediaMetadataRetriever на каждый файл заметно тормозит UI.
        io.execute(() -> {
            for (int i = 0; i < files.length; i++) {
                final long ms = FileUtils.getDurationMs(files[i]);
                final TextView pill = pills.get(i);
                ui.post(() -> {
                    if (destroyed || generation != listGeneration) return;
                    Ui.setDurationPill(pill, ms, MAX_SOUND_DURATION_MS);
                });
            }
        });
    }

    private String summary(int count) {
        if (count == 0) return getString(R.string.sounds_summary_none);
        if (count == 1) return getString(R.string.sounds_summary_one);
        return getResources().getQuantityString(R.plurals.sounds_summary_many, count, count);
    }

    /** @return метка длительности, которую заполнит фоновый поток. */
    private TextView addSoundRow(File f) {
        LinearLayout row = Ui.row(this);

        SoundRow r = new SoundRow();
        r.root = row;
        r.play = Ui.iconButton(this, "▶");
        r.play.setContentDescription(getString(R.string.play_desc, f.getName()));
        r.play.setOnClickListener(v -> preview.toggle(f));
        row.addView(r.play, Ui.iconButtonParams(this));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.title(this, f.getName()));
        r.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        r.progress.setMax(1000);
        r.progress.setVisibility(View.GONE);
        texts.addView(r.progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 8)));
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, -2, 1);
        textsLp.setMarginStart(Ui.dp(this, 8));
        textsLp.setMarginEnd(Ui.dp(this, 16));
        row.addView(texts, textsLp);

        TextView pill = Ui.pill(this);
        row.addView(pill);

        Button more = Ui.iconButton(this, "⋮");
        more.setContentDescription(getString(R.string.actions_desc, f.getName()));
        more.setOnClickListener(v -> showSoundMenu(v, f));
        LinearLayout.LayoutParams moreLp = Ui.iconButtonParams(this);
        moreLp.setMarginStart(Ui.dp(this, 8));
        row.addView(more, moreLp);

        row.setOnClickListener(v -> preview.toggle(f));
        soundList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        Ui.divider(soundList);
        rows.put(f, r);
        return pill;
    }

    private void showSoundMenu(View anchor, File f) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_save_copy);
        menu.getMenu().add(0, 2, 1, R.string.delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openSaveFolderPicker(f);
            else confirmDelete(f);
            return true;
        });
        menu.show();
    }

    // ---------------------------------------------------------------- Прослушивание

    @Override public void onPreviewChanged() {
        File current = preview.current();
        for (Map.Entry<File, SoundRow> e : rows.entrySet()) {
            boolean playing = e.getKey().equals(current);
            SoundRow r = e.getValue();
            r.play.setText(playing ? "■" : "▶");
            r.progress.setVisibility(playing ? View.VISIBLE : View.GONE);
            if (!playing) r.progress.setProgress(0);
            r.root.setBackgroundColor(playing ? getColor(R.color.row_active) : Color.TRANSPARENT);
        }
        ui.removeCallbacks(progressTick);
        if (current != null) ui.post(progressTick);
    }

    @Override public void onPreviewError(File file) {
        toast(getString(R.string.cannot_play, file.getName()));
    }

    // ---------------------------------------------------------------- Добавление и сохранение

    private void openSoundPicker() {
        if (busy) return;
        Intent i = new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FILES)
                .putExtra(PickerActivity.EXTRA_TITLE, getString(R.string.sounds_add_title))
                .putExtra(PickerActivity.EXTRA_EXTENSIONS, SOUND_EXTENSIONS)
                .putExtra(PickerActivity.EXTRA_MAX_DURATION_MS, MAX_SOUND_DURATION_MS)
                .putExtra(PickerActivity.EXTRA_ITEM_PLURAL, R.plurals.picker_add_sounds)
                .putExtra(PickerActivity.EXTRA_START_DIR,
                        new File(FileUtils.INTERNAL_ROOT, "Notifications").getAbsolutePath());
        startActivityForResult(i, REQ_ADD_SOUNDS);
    }

    private void openSaveFolderPicker(File f) {
        if (busy) return;
        pendingSave = f;
        Intent i = new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FOLDER)
                .putExtra(PickerActivity.EXTRA_TITLE, getString(R.string.save_where_title))
                .putExtra(PickerActivity.EXTRA_SUBJECT, f.getName())
                .putExtra(PickerActivity.EXTRA_ACTION, getString(R.string.save_here));
        startActivityForResult(i, REQ_SAVE_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (picturesPanel != null && picturesPanel.onActivityResult(requestCode, resultCode, data)) return;
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_ADD_SOUNDS) {
            ArrayList<String> paths = data.getStringArrayListExtra(PickerActivity.EXTRA_PATHS);
            if (paths == null || paths.isEmpty()) return;
            List<File> files = new ArrayList<>();
            for (String p : paths) files.add(new File(p));
            prepareAdd(files);
        } else if (requestCode == REQ_SAVE_FOLDER && pendingSave != null) {
            String folder = data.getStringExtra(PickerActivity.EXTRA_FOLDER);
            if (folder == null) return;
            final File src = pendingSave;
            final File dst = new File(folder, src.getName());
            pendingSave = null;
            if (dst.equals(src)) { toast(getString(R.string.file_already_here)); return; }
            confirmReplaceThen(dst, () -> copySingle(src, dst));
        }
    }

    /** Если в машине уже есть файлы с такими именами — один вопрос на все сразу. */
    private void prepareAdd(List<File> files) {
        int conflicts = 0;
        for (File f : files) if (new File(soundDir, f.getName()).exists()) conflicts++;
        if (conflicts == 0) {
            runAdd(files, false);
            return;
        }
        String message = files.size() == 1
                ? getString(R.string.sounds_conflict_one, files.get(0).getName())
                : getString(R.string.sounds_conflict_many, conflicts, files.size());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.sounds_conflict_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.replace, (d, w) -> runAdd(files, true));
        if (files.size() > conflicts) {
            b.setNeutralButton(R.string.skip_them, (d, w) -> runAdd(files, false));
        }
        b.show();
    }

    private void runAdd(List<File> files, boolean replace) {
        preview.stop();
        setBusy(true, getString(R.string.copying));
        io.execute(() -> {
            int copied = 0, skipped = 0, failed = 0;
            for (File f : files) {
                File dst = new File(soundDir, f.getName());
                if (dst.exists() && !replace) { skipped++; continue; }
                if (FileUtils.copyFileQuiet(f, dst)) copied++; else failed++;
            }
            final String report = getString(R.string.add_report, copied)
                    + (skipped > 0 ? getString(R.string.add_report_skipped, skipped) : "")
                    + (failed > 0 ? getString(R.string.add_report_failed, failed) : "");
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                loadSounds();
                Toast.makeText(this, report, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void confirmReplaceThen(File dst, Runnable next) {
        if (!dst.exists()) { next.run(); return; }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_exists_title)
                .setMessage(getString(R.string.file_exists_message, dst.getAbsolutePath()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.replace, (d, w) -> next.run())
                .show();
    }

    private void copySingle(File src, File dst) {
        if (dst.equals(preview.current())) preview.stop();
        setBusy(true, getString(R.string.copying));
        io.execute(() -> {
            boolean ok = FileUtils.copyFileQuiet(src, dst);
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                loadSounds();
                toast(ok ? getString(R.string.saved, dst.getAbsolutePath()) : getString(R.string.save_failed, dst.getName()));
            });
        });
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sound_delete_title)
                .setMessage(getString(R.string.sound_delete_message, f.getName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (f.equals(preview.current())) preview.stop();
                    if (f.delete()) {
                        loadSounds();
                        toast(getString(R.string.deleted, f.getName()));
                    } else {
                        toast(getString(R.string.delete_failed, f.getName()));
                    }
                })
                .show();
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        btnAddSounds.setEnabled(!value);
        if (value) setStatus(status);
        else {
            File[] files = listSounds();
            setStatus(summary(files == null ? 0 : files.length));
        }
    }

    private void setStatus(String text) {
        soundStatus.setText(text);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
