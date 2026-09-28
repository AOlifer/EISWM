package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EISWM v1.3 — Evolute I-Space Welcome Manager.
 * База — протестированная v1.1; из v1.2 взяты иконка/статус приветствия и ContentObserver.
 */
public class MainActivity extends Activity {
    private static final String WELCOME_DIR =
            "/data/user_de/0/com.android.launcher3/files/welcome/message";
    private static final String WELCOME_SWITCH = "bw_welcome_voice_switch";
    /** Автомобиль поддерживает звуки не длиннее 6 с; 0,5 с — допуск на неточность метаданных MP3. */
    private static final long MAX_DURATION_MS = 6500;
    private static final int COLOR_SELECTED = 0x553399FF;
    private static final int COLOR_TOO_LONG = 0xFFC62828;

    private LinearLayout leftList, rightList;
    private TextView leftPath, rightStatus, playing, welcomeState;
    private ImageButton btnWelcome;
    private Button btnCopyOne, btnCopyAll, btnBackOne, btnBackAll;
    private File leftDir;
    private File browsingRoot;
    private File selectedLeft, selectedRight;
    private View selectedLeftView, selectedRightView;
    private MediaPlayer player;
    private File playingFile;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Один фоновый поток: копирование и чтение длительности не блокируют интерфейс. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int rightGeneration = 0;
    private boolean busy = false;
    private boolean destroyed = false;

    private final ContentObserver welcomeObserver = new ContentObserver(ui) {
        @Override public void onChange(boolean selfChange) {
            updateWelcomeSwitchButton();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        copyBundledWelcomeFilesOnce();
        leftList = findViewById(R.id.leftList);
        rightList = findViewById(R.id.rightList);
        leftPath = findViewById(R.id.leftPath);
        rightStatus = findViewById(R.id.rightStatus);
        playing = findViewById(R.id.playing);
        welcomeState = findViewById(R.id.welcomeState);
        btnWelcome = findViewById(R.id.btnWelcome);
        btnCopyOne = findViewById(R.id.btnCopyOne);
        btnCopyAll = findViewById(R.id.btnCopyAll);
        btnBackOne = findViewById(R.id.btnBackOne);
        btnBackAll = findViewById(R.id.btnBackAll);
        updateWelcomeSwitchButton();

        File storage = new File("/storage/emulated/0");
        File notifications = new File(storage, "Notifications");
        browsingRoot = storage;
        leftDir = notifications.isDirectory() ? notifications : storage;
        loadLeft(); loadRight();

        findViewById(R.id.btnUp).setOnClickListener(v -> goUp());
        findViewById(R.id.btnExternal).setOnClickListener(v -> chooseExternalStorage());
        findViewById(R.id.btnInternal).setOnClickListener(v -> chooseInternalStorage());
        btnCopyOne.setOnClickListener(v -> copyOne());
        btnCopyAll.setOnClickListener(v -> copyAll());
        btnBackOne.setOnClickListener(v -> copyBackOne());
        btnBackAll.setOnClickListener(v -> copyBackAll());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> { loadLeft(); loadRight(); updateWelcomeSwitchButton(); });
        btnWelcome.setOnClickListener(v -> toggleWelcomeSwitch());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopPlayback());
        findViewById(R.id.btnExit).setOnClickListener(v -> finishAndRemoveTask());
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
        if (btnWelcome != null) updateWelcomeSwitchButton();
    }

    @Override protected void onStop() {
        try {
            getContentResolver().unregisterContentObserver(welcomeObserver);
        } catch (Exception ignored) {
        }
        // Приложение ушло с экрана — звук не должен продолжать играть в фоне.
        if (player != null) stopPlayback();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopPlaybackOnly();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Переключатель приветствия

    private void updateWelcomeSwitchButton() {
        if (welcomeState == null || btnWelcome == null) return;
        int state = readWelcomeSwitch();
        if (state == 1) {
            welcomeState.setText("Приветствие: ВКЛ");
            btnWelcome.setImageResource(R.drawable.ic_volume_up);
            btnWelcome.setContentDescription("Выключить приветствие");
        } else if (state == 0) {
            welcomeState.setText("Приветствие: ВЫКЛ");
            btnWelcome.setImageResource(R.drawable.ic_volume_off);
            btnWelcome.setContentDescription("Включить приветствие");
        } else {
            welcomeState.setText("Приветствие: ?");
            btnWelcome.setImageResource(android.R.drawable.ic_menu_help);
            btnWelcome.setContentDescription("Состояние приветствия неизвестно");
        }
    }

    private void toggleWelcomeSwitch() {
        int current = readWelcomeSwitch();
        if (current != 0 && current != 1) {
            toast("Не удалось определить состояние приветствия");
            updateWelcomeSwitchButton();
            return;
        }

        int target = current == 1 ? 0 : 1;
        if (!writeWelcomeSwitch(target)) {
            toast("Не удалось изменить состояние приветствия");
            updateWelcomeSwitchButton();
            return;
        }

        int actual = readWelcomeSwitch();
        updateWelcomeSwitchButton();
        if (actual == target) {
            toast(target == 1 ? "Приветствие включено" : "Приветствие выключено");
        } else {
            toast("Состояние не изменилось");
        }
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
        SharedPreferences prefs = getSharedPreferences("eiswm", MODE_PRIVATE);
        if (prefs.getBoolean("bundled_welcome_files_copied", false)) {
            return;
        }

        File targetDir = new File("/storage/emulated/0/Notifications");
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

    // ---------------------------------------------------------------- Навигация (левая панель)

    private void goUp() {
        if (leftDir == null || browsingRoot == null) return;
        try {
            String current = leftDir.getCanonicalPath();
            String root = browsingRoot.getCanonicalPath();
            if (current.equals(root)) return;
            File p = leftDir.getParentFile();
            if (p != null && p.exists() && p.isDirectory()) {
                leftDir = p;
                selectedLeft = null;
                loadLeft();
            }
        } catch (IOException ignored) {}
    }

    private void chooseInternalStorage() {
        browsingRoot = new File("/storage/emulated/0");
        File notifications = new File(browsingRoot, "Notifications");
        leftDir = notifications.isDirectory() ? notifications : browsingRoot;
        selectedLeft = null;
        loadLeft();
    }

    private void chooseExternalStorage() {
        File storage = new File("/storage");
        File[] roots = storage.listFiles(f -> f.isDirectory() && !f.getName().equals("emulated") && !f.getName().equals("self"));
        if (roots == null || roots.length == 0) {
            toast("Внешние накопители не найдены");
            return;
        }
        Arrays.sort(roots, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        String[] names = new String[roots.length];
        for (int i = 0; i < roots.length; i++) names[i] = roots[i].getName();
        new AlertDialog.Builder(this)
                .setTitle("Внешние накопители")
                .setItems(names, (d, which) -> {
                    browsingRoot = roots[which];
                    leftDir = roots[which];
                    selectedLeft = null;
                    loadLeft();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void loadLeft() {
        leftList.removeAllViews();
        selectedLeftView = null;
        if (leftDir == null) return;
        leftPath.setText(leftDir.getAbsolutePath());
        File[] dirs = leftDir.listFiles(File::isDirectory);
        File[] files = leftDir.listFiles(f -> f.isFile() && isAudio(f));
        if (dirs == null) { addInfo(leftList, "Каталог недоступен"); return; }
        if (selectedLeft != null && !selectedLeft.exists()) selectedLeft = null;
        Arrays.sort(dirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File d : dirs) addLeftRow(d, true);
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : files) addLeftRow(f, false);
        }
    }

    private void addLeftRow(File f, boolean dir) {
        TextView v = new TextView(this);
        v.setText((dir ? "📁 " : "🎵 ") + f.getName());
        v.setTextSize(16); v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(12, 14, 8, 14);
        v.setSingleLine(true);
        v.setOnClickListener(x -> {
            if (dir) {
                leftDir = f;
                selectedLeft = null;
                loadLeft();
            } else {
                selectLeft(v, f);
            }
        });
        if (!dir) {
            v.setOnLongClickListener(x -> { selectLeft(v, f); return true; });
            if (f.equals(selectedLeft)) selectLeft(v, f); // сохранить выделение после «Обновить»
        }
        leftList.addView(v, new LinearLayout.LayoutParams(-1, -2));
        divider(leftList);
    }

    // ---------------------------------------------------------------- Файлы приветствия (правая панель)

    private void loadRight() {
        rightList.removeAllViews();
        selectedRightView = null;
        File dir = new File(WELCOME_DIR);
        // Сначала создаём каталог, потом читаем список (в v1.1 было наоборот).
        if (!dir.isDirectory() && !dir.mkdirs()) { rightStatus.setText("Не удалось открыть целевой каталог"); return; }
        File[] files = dir.listFiles(f -> f.isFile() && isAudio(f));
        if (files == null) { rightStatus.setText("Целевой каталог недоступен"); return; }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (selectedRight != null && !selectedRight.exists()) selectedRight = null;
        if (!busy) rightStatus.setText("Файлов: " + files.length);

        final int generation = ++rightGeneration;
        final List<TextView> metas = new ArrayList<>();
        for (File f : files) metas.add(addRightRow(f));

        // Длительность читается в фоне: MediaMetadataRetriever на каждый файл заметно тормозит UI.
        io.execute(() -> {
            for (int i = 0; i < files.length; i++) {
                final File f = files[i];
                final TextView meta = metas.get(i);
                final long ms = getDurationMs(f);
                ui.post(() -> {
                    if (destroyed || generation != rightGeneration) return;
                    String text = formatSize(f.length()) + "   " + formatDuration(ms);
                    if (ms > MAX_DURATION_MS) {
                        meta.setText(text + "   — длиннее 6 с");
                        meta.setTextColor(COLOR_TOO_LONG);
                    } else {
                        meta.setText(text);
                    }
                });
            }
        });
    }

    /** @return TextView второй строки (размер/длительность), чтобы дописать длительность из фона. */
    private TextView addRightRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(8, 5, 4, 5);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(6, 3, 6, 3);
        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(16);
        TextView meta = new TextView(this);
        meta.setText(formatSize(f.length()) + "   …");
        meta.setTextSize(14);
        texts.addView(name);
        texts.addView(meta);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        Button play = new Button(this); play.setText("▶"); play.setOnClickListener(v -> play(f));
        row.addView(play, new LinearLayout.LayoutParams(dp(58), -2));
        Button del = new Button(this); del.setText("🗑"); del.setOnClickListener(v -> confirmDelete(f));
        row.addView(del, new LinearLayout.LayoutParams(dp(70), -2));
        row.setOnClickListener(v -> selectRight(row, f));
        if (f.equals(selectedRight)) selectRight(row, f);
        rightList.addView(row, new LinearLayout.LayoutParams(-1, -2)); divider(rightList);
        return meta;
    }

    // ---------------------------------------------------------------- Выделение
    // В v1.1 highlight() сбрасывал фон всем детям списка, включая разделители, — они исчезали.

    private void selectLeft(View v, File f) {
        if (selectedLeftView != null) selectedLeftView.setBackgroundColor(Color.TRANSPARENT);
        selectedLeft = f;
        selectedLeftView = v;
        v.setBackgroundColor(COLOR_SELECTED);
    }

    private void selectRight(View v, File f) {
        if (selectedRightView != null) selectedRightView.setBackgroundColor(Color.TRANSPARENT);
        selectedRight = f;
        selectedRightView = v;
        v.setBackgroundColor(COLOR_SELECTED);
    }

    // ---------------------------------------------------------------- Копирование

    private void copyOne() {
        if (busy) return;
        if (selectedLeft == null || !selectedLeft.isFile()) { toast("Выбери файл слева"); return; }
        if (!isAudio(selectedLeft)) { toast("Можно копировать только MP3"); return; }
        final File src = selectedLeft;
        final File dst = new File(WELCOME_DIR, src.getName());
        checkDurationThen(src, () -> confirmReplaceThen(dst, () -> copySingle(src, dst)));
    }

    private void copyBackOne() {
        if (busy) return;
        if (selectedRight == null || !selectedRight.isFile()) { toast("Выбери файл справа"); return; }
        if (leftDir == null) return;
        final File src = selectedRight;
        final File dst = new File(leftDir, src.getName());
        confirmReplaceThen(dst, () -> copySingle(src, dst));
    }

    private void copyAll() {
        if (busy) return;
        if (leftDir == null) return;
        File[] files = leftDir.listFiles(f -> f.isFile() && isAudio(f));
        if (files == null || files.length == 0) { toast("В текущем каталоге нет MP3"); return; }
        prepareBatch(files, new File(WELCOME_DIR), true);
    }

    private void copyBackAll() {
        if (busy) return;
        if (leftDir == null) return;
        File[] files = new File(WELCOME_DIR).listFiles(f -> f.isFile() && isAudio(f));
        if (files == null || files.length == 0) { toast("Справа нет MP3"); return; }
        prepareBatch(files, leftDir, false);
    }

    /** Одиночный файл: предупредить, если он длиннее 6 с (решение за пользователем). */
    private void checkDurationThen(File src, Runnable next) {
        setBusy(true, "Проверка файла…");
        io.execute(() -> {
            long ms = getDurationMs(src);
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                if (ms > MAX_DURATION_MS) {
                    new AlertDialog.Builder(this)
                            .setTitle("Файл слишком длинный")
                            .setMessage(src.getName() + "\nДлительность: " + formatSeconds(ms)
                                    + "\n\nАвтомобиль поддерживает звуки приветствия не длиннее 6 секунд. Всё равно скопировать?")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Копировать", (d, w) -> next.run())
                            .show();
                } else if (ms < 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Не удалось определить длительность")
                            .setMessage(src.getName() + "\n\nВозможно, файл повреждён. Всё равно скопировать?")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Копировать", (d, w) -> next.run())
                            .show();
                } else {
                    next.run();
                }
            });
        });
    }

    private void confirmReplaceThen(File dst, Runnable next) {
        if (!dst.exists()) { next.run(); return; }
        new AlertDialog.Builder(this).setTitle("Файл уже существует")
                .setMessage(dst.getName())
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заменить", (d, w) -> next.run())
                .show();
    }

    private void copySingle(File src, File dst) {
        if (dst.equals(playingFile)) stopPlayback();
        setBusy(true, "Копирование…");
        io.execute(() -> {
            boolean ok = copyFileQuiet(src, dst);
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                loadLeft(); loadRight();
                toast(ok ? "Скопировано: " + dst.getName() : "Ошибка копирования: " + dst.getName());
            });
        });
    }

    /**
     * Пакетное копирование: сначала анализ (конфликты имён, длительность), затем один диалог.
     * При копировании в приветствие файлы длиннее 6 с пропускаются.
     */
    private void prepareBatch(File[] files, File dstDir, boolean checkDuration) {
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        setBusy(true, checkDuration ? "Проверка файлов…" : "Подготовка…");
        io.execute(() -> {
            List<File> toCopy = new ArrayList<>();
            int tooLong = 0, conflicts = 0;
            for (File f : files) {
                if (checkDuration && getDurationMs(f) > MAX_DURATION_MS) { tooLong++; continue; }
                toCopy.add(f);
                if (new File(dstDir, f.getName()).exists()) conflicts++;
            }
            final int fTooLong = tooLong, fConflicts = conflicts;
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                showBatchDialog(toCopy, dstDir, fTooLong, fConflicts);
            });
        });
    }

    private void showBatchDialog(List<File> toCopy, File dstDir, int tooLong, int conflicts) {
        if (toCopy.isEmpty()) {
            toast(tooLong > 0 ? "Все файлы длиннее 6 секунд — копировать нечего" : "Нечего копировать");
            return;
        }
        if (conflicts == 0 && tooLong == 0) {
            runBatch(toCopy, dstDir, false, 0);
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Файлов к копированию: ").append(toCopy.size());
        if (conflicts > 0) msg.append("\nУже существуют в папке назначения: ").append(conflicts);
        if (tooLong > 0) msg.append("\nДлиннее 6 секунд (будут пропущены): ").append(tooLong);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Копирование")
                .setMessage(msg.toString())
                .setNegativeButton("Отмена", null);
        if (conflicts > 0) {
            b.setPositiveButton("Заменить", (d, w) -> runBatch(toCopy, dstDir, true, tooLong));
            b.setNeutralButton("Пропустить существующие", (d, w) -> runBatch(toCopy, dstDir, false, tooLong));
        } else {
            b.setPositiveButton("Копировать", (d, w) -> runBatch(toCopy, dstDir, false, tooLong));
        }
        b.show();
    }

    private void runBatch(List<File> files, File dstDir, boolean replace, int tooLong) {
        if (playingFile != null && dstDir.equals(playingFile.getParentFile())) stopPlayback();
        setBusy(true, "Копирование…");
        io.execute(() -> {
            int copied = 0, skipped = 0, failed = 0;
            for (File f : files) {
                File dst = new File(dstDir, f.getName());
                if (dst.exists() && !replace) { skipped++; continue; }
                if (copyFileQuiet(f, dst)) copied++; else failed++;
            }
            final String report = "Скопировано: " + copied
                    + (skipped > 0 ? ", пропущено существующих: " + skipped : "")
                    + (tooLong > 0 ? ", пропущено длинных: " + tooLong : "")
                    + (failed > 0 ? ", ошибок: " + failed : "");
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false, null);
                loadLeft(); loadRight();
                Toast.makeText(this, report, Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * Копирование через временный файл: при ошибке существующий файл не портится,
     * а лаунчер не увидит недописанный MP3 (временное имя не оканчивается на .mp3).
     */
    private boolean copyFileQuiet(File src, File dst) {
        File tmp = new File(dst.getParentFile(), "." + dst.getName() + ".tmp");
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(dst)) return true;
        // Некоторые ФС (FAT на USB) не заменяют файл при rename.
        if (dst.delete() && tmp.renameTo(dst)) return true;
        tmp.delete();
        return false;
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        btnCopyOne.setEnabled(!value);
        btnCopyAll.setEnabled(!value);
        btnBackOne.setEnabled(!value);
        btnBackAll.setEnabled(!value);
        if (value && status != null) rightStatus.setText(status);
        else if (!value) rightStatus.setText("Файлов: " + countWelcomeFiles());
    }

    private int countWelcomeFiles() {
        File[] files = new File(WELCOME_DIR).listFiles(f -> f.isFile() && isAudio(f));
        return files == null ? 0 : files.length;
    }

    // ---------------------------------------------------------------- Воспроизведение и удаление

    private void play(File f) {
        stopPlayback();
        try {
            player = new MediaPlayer(); player.setDataSource(f.getAbsolutePath());
            playingFile = f;
            player.setOnPreparedListener(mp -> { mp.start(); playing.setText("▶ Воспроизводится: " + f.getName()); });
            player.setOnCompletionListener(mp -> { playing.setText("Готово: " + f.getName()); stopPlaybackOnly(); });
            player.setOnErrorListener((mp, what, extra) -> { playing.setText("Ошибка воспроизведения: " + f.getName()); stopPlaybackOnly(); return true; });
            player.prepareAsync();
        } catch (Exception e) { toast("Не удалось воспроизвести файл"); stopPlayback(); }
    }

    private void stopPlayback() { stopPlaybackOnly(); playing.setText("Воспроизведение остановлено"); }

    private void stopPlaybackOnly() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playingFile = null;
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this).setTitle("Удалить файл?").setMessage(f.getName())
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    if (f.equals(playingFile)) stopPlayback();
                    if (f.delete()) {
                        if (f.equals(selectedRight)) selectedRight = null;
                        loadRight();
                        toast("Удалено: " + f.getName());
                    } else toast("Не удалось удалить");
                }).show();
    }

    // ---------------------------------------------------------------- Утилиты

    private boolean isAudio(File f) { return f.getName().toLowerCase().endsWith(".mp3"); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void divider(LinearLayout p) { View v = new View(this); v.setBackgroundColor(0x22000000); p.addView(v, new LinearLayout.LayoutParams(-1, 1)); }
    private void addInfo(LinearLayout p, String s) { TextView v = new TextView(this); v.setText(s); v.setPadding(12, 20, 12, 20); p.addView(v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private String formatSize(long b) { if (b < 1024) return b + " Б"; if (b < 1048576) return String.format("%.1f КБ", b / 1024.0); return String.format("%.1f МБ", b / 1048576.0); }
    private String formatSeconds(long ms) { return String.format("%.1f с", ms / 1000.0); }

    private String formatDuration(long ms) {
        if (ms < 0) return "--:--";
        long totalSec = Math.round(ms / 1000.0);
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** @return длительность в мс или -1, если прочитать не удалось. */
    private long getDurationMs(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value != null ? Long.parseLong(value) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }
}
