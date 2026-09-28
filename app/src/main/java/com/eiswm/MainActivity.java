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
 * Главный экран: вкладка «Звуки» (файлы приветствия в машине) и вкладка «Картинки» (в разработке).
 * Файлы добавляются через {@link PickerActivity}.
 */
public class MainActivity extends Activity implements AudioPreview.Listener {
    private static final String WELCOME_SWITCH = "bw_welcome_voice_switch";
    /** Автомобиль поддерживает звуки не длиннее 6 с; 0,5 с — допуск на неточность метаданных MP3. */
    static final long MAX_SOUND_DURATION_MS = 6500;
    static final String[] SOUND_EXTENSIONS = {"mp3"};
    private static final int REQ_ADD_SOUNDS = 1;
    private static final int REQ_SAVE_FOLDER = 2;
    private static final String PREF_TAB = "tab";

    private File soundDir;
    private TextView tabSounds, tabPictures, welcomeLabel, soundStatus;
    private View soundsPanel, picturesPanel;
    private Switch welcomeSwitch;
    private LinearLayout soundList;
    private Button btnAddSounds;

    private AudioPreview preview;
    private final Map<File, SoundRow> rows = new HashMap<>();
    private File pendingSave;
    private SharedPreferences prefs;

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
        prefs = getSharedPreferences("eiswm", MODE_PRIVATE);
        soundDir = new File(getString(R.string.welcome_sound_dir));
        preview = new AudioPreview(this);
        copyBundledWelcomeFilesOnce();

        tabSounds = findViewById(R.id.tabSounds);
        tabPictures = findViewById(R.id.tabPictures);
        soundsPanel = findViewById(R.id.soundsPanel);
        picturesPanel = findViewById(R.id.picturesPanel);
        welcomeLabel = findViewById(R.id.welcomeLabel);
        welcomeSwitch = findViewById(R.id.welcomeSwitch);
        soundList = findViewById(R.id.soundList);
        soundStatus = findViewById(R.id.soundStatus);
        btnAddSounds = findViewById(R.id.btnAddSounds);

        tabSounds.setOnClickListener(v -> showTab(0));
        tabPictures.setOnClickListener(v -> showTab(1));
        welcomeSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingSwitch) setWelcomeSwitch(checked ? 1 : 0);
        });
        btnAddSounds.setOnClickListener(v -> openSoundPicker());
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnExit).setOnClickListener(v -> finishAndRemoveTask());

        showTab(prefs.getInt(PREF_TAB, 0));
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
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Вкладки и справка

    private void showTab(int tab) {
        boolean sounds = tab != 1;
        Ui.setTabSelected(tabSounds, sounds);
        Ui.setTabSelected(tabPictures, !sounds);
        soundsPanel.setVisibility(sounds ? View.VISIBLE : View.GONE);
        picturesPanel.setVisibility(sounds ? View.GONE : View.VISIBLE);
        if (!sounds) preview.stop();
        prefs.edit().putInt(PREF_TAB, sounds ? 0 : 1).apply();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Звуки приветствия")
                .setMessage("Это MP3-файлы, которые машина проигрывает при приветствии. "
                        + "Каждый раз она выбирает один из них случайно.\n\n"
                        + "▶  прослушать звук, повторное нажатие останавливает его.\n"
                        + "⋮  удалить звук или сохранить его копию в память или на флешку.\n"
                        + "«Добавить звуки»  выбрать MP3 в памяти устройства или на USB-флешке.\n\n"
                        + "Машина проигрывает только MP3 длительностью не больше 6 секунд, "
                        + "более длинные файлы добавить нельзя.\n\n"
                        + "Переключатель вверху включает и выключает звуковое приветствие.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    // ---------------------------------------------------------------- Переключатель приветствия

    private void updateWelcomeSwitch() {
        if (welcomeSwitch == null) return;
        int state = readWelcomeSwitch();
        updatingSwitch = true;
        if (state == 0 || state == 1) {
            welcomeSwitch.setEnabled(true);
            welcomeSwitch.setChecked(state == 1);
            welcomeLabel.setText(state == 1 ? "Звуковое приветствие включено" : "Звуковое приветствие выключено");
        } else {
            welcomeSwitch.setChecked(false);
            welcomeSwitch.setEnabled(false);
            welcomeLabel.setText("Звуковое приветствие: не удалось узнать, включено ли оно");
        }
        updatingSwitch = false;
    }

    private void setWelcomeSwitch(int target) {
        if (!writeWelcomeSwitch(target)) {
            toast("Не удалось изменить состояние приветствия");
        } else if (readWelcomeSwitch() != target) {
            toast("Состояние не изменилось");
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
            toast("Не удалось создать папку Notifications");
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
                toast("Добавлено встроенных звуков: " + copied);
            }
        } catch (IOException e) {
            toast("Ошибка копирования встроенных звуков");
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
            Ui.emptyState(soundList, "Нет доступа к папке звуков приветствия", soundDir.getAbsolutePath());
            setStatus("");
            return;
        }
        FileUtils.sortByName(files);
        if (files.length == 0) {
            Ui.emptyState(soundList, "Звуков пока нет",
                    "Нажмите «Добавить звуки», чтобы выбрать MP3 в памяти или на флешке.");
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
                    if (ms < 0) Ui.setPill(pill, "длительность неизвестна", Ui.PILL_WARN);
                    else if (ms > MAX_SOUND_DURATION_MS) Ui.setPill(pill, FileUtils.formatDuration(ms) + ", не прозвучит", Ui.PILL_BAD);
                    else Ui.setPill(pill, FileUtils.formatDuration(ms), Ui.PILL_OK);
                });
            }
        });
    }

    private String summary(int count) {
        if (count == 0) return "";
        return count + " " + FileUtils.plural(count, "звук", "звука", "звуков")
                + (count == 1 ? ". Машина проигрывает его при приветствии." : ". Машина выбирает один из них случайно.");
    }

    /** @return метка длительности, которую заполнит фоновый поток. */
    private TextView addSoundRow(File f) {
        LinearLayout row = Ui.row(this);

        SoundRow r = new SoundRow();
        r.root = row;
        r.play = Ui.iconButton(this, "▶");
        r.play.setContentDescription("Прослушать " + f.getName());
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
        more.setContentDescription("Действия с " + f.getName());
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
        menu.getMenu().add(0, 1, 0, "Сохранить копию в…");
        menu.getMenu().add(0, 2, 1, "Удалить");
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
            r.root.setBackgroundColor(playing ? Ui.ROW_PLAYING : Color.TRANSPARENT);
        }
        ui.removeCallbacks(progressTick);
        if (current != null) ui.post(progressTick);
    }

    @Override public void onPreviewError(File file) {
        toast("Не удалось воспроизвести " + file.getName());
    }

    // ---------------------------------------------------------------- Добавление и сохранение

    private void openSoundPicker() {
        if (busy) return;
        Intent i = new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FILES)
                .putExtra(PickerActivity.EXTRA_TITLE, "Добавить звуки")
                .putExtra(PickerActivity.EXTRA_EXTENSIONS, SOUND_EXTENSIONS)
                .putExtra(PickerActivity.EXTRA_MAX_DURATION_MS, MAX_SOUND_DURATION_MS)
                .putExtra(PickerActivity.EXTRA_ITEM_FORMS, new String[]{"звук", "звука", "звуков"})
                .putExtra(PickerActivity.EXTRA_START_DIR,
                        new File(FileUtils.INTERNAL_ROOT, "Notifications").getAbsolutePath());
        startActivityForResult(i, REQ_ADD_SOUNDS);
    }

    private void openSaveFolderPicker(File f) {
        if (busy) return;
        pendingSave = f;
        Intent i = new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FOLDER)
                .putExtra(PickerActivity.EXTRA_TITLE, "Куда сохранить " + f.getName())
                .putExtra(PickerActivity.EXTRA_ACTION, "Сохранить сюда");
        startActivityForResult(i, REQ_SAVE_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            if (dst.equals(src)) { toast("Файл уже лежит в этой папке"); return; }
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
                ? "Звук «" + files.get(0).getName() + "» уже есть в машине. Заменить его?"
                : "Уже есть в машине: " + conflicts + " из " + files.size() + ". Что сделать с ними?";
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Такие звуки уже есть")
                .setMessage(message)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заменить", (d, w) -> runAdd(files, true));
        if (files.size() > conflicts) {
            b.setNeutralButton("Пропустить их", (d, w) -> runAdd(files, false));
        }
        b.show();
    }

    private void runAdd(List<File> files, boolean replace) {
        preview.stop();
        setBusy(true, "Копирование…");
        io.execute(() -> {
            int copied = 0, skipped = 0, failed = 0;
            for (File f : files) {
                File dst = new File(soundDir, f.getName());
                if (dst.exists() && !replace) { skipped++; continue; }
                if (FileUtils.copyFileQuiet(f, dst)) copied++; else failed++;
            }
            final String report = "Добавлено: " + copied
                    + (skipped > 0 ? ", пропущено: " + skipped : "")
                    + (failed > 0 ? ", ошибок: " + failed : "");
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
                .setTitle("Файл уже существует")
                .setMessage(dst.getAbsolutePath() + "\n\nЗаменить его?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заменить", (d, w) -> next.run())
                .show();
    }

    private void copySingle(File src, File dst) {
        if (dst.equals(preview.current())) preview.stop();
        setBusy(true, "Копирование…");
        io.execute(() -> {
            boolean ok = FileUtils.copyFileQuiet(src, dst);
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                loadSounds();
                toast(ok ? "Сохранено: " + dst.getAbsolutePath() : "Не удалось сохранить " + dst.getName());
            });
        });
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить звук?")
                .setMessage(f.getName() + "\n\nМашина больше не будет его проигрывать.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    if (f.equals(preview.current())) preview.stop();
                    if (f.delete()) {
                        loadSounds();
                        toast("Удалено: " + f.getName());
                    } else {
                        toast("Не удалось удалить " + f.getName());
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
}
