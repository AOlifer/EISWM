package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.provider.Settings;
import android.net.Uri;
import android.widget.ImageButton;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends Activity {
    private static final String WELCOME_DIR =
            "/data/user_de/0/com.android.launcher3/files/welcome/message";
    private static final String WELCOME_VOICE_SWITCH = "bw_welcome_voice_switch";

    private LinearLayout leftList, rightList;
    private TextView leftPath, rightStatus, playing, welcomeState;
    private ImageButton btnWelcome;
    private File leftDir;
    private File browsingRoot;
    private File selectedLeft, selectedRight;
    private MediaPlayer player;

    private final ContentObserver welcomeObserver = new ContentObserver(new android.os.Handler()) {
        @Override public void onChange(boolean selfChange, Uri uri) {
            refreshWelcomeState();
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
        btnWelcome.setOnClickListener(v -> toggleWelcomeVoice());
        refreshWelcomeState();

        File storage = new File("/storage/emulated/0");
        File notifications = new File(storage, "Notifications");
        browsingRoot = storage;
        leftDir = notifications.isDirectory() ? notifications : storage;
        loadLeft(); loadRight();

        findViewById(R.id.btnUp).setOnClickListener(v -> goUp());
        findViewById(R.id.btnExternal).setOnClickListener(v -> chooseExternalStorage());
        findViewById(R.id.btnInternal).setOnClickListener(v -> chooseInternalStorage());
        findViewById(R.id.btnCopyOne).setOnClickListener(v -> copyOne());
        findViewById(R.id.btnCopyAll).setOnClickListener(v -> copyAll());
        findViewById(R.id.btnBackOne).setOnClickListener(v -> copyBackOne());
        findViewById(R.id.btnBackAll).setOnClickListener(v -> copyBackAll());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> { loadLeft(); loadRight(); });
        findViewById(R.id.btnStop).setOnClickListener(v -> stopPlayback());
        findViewById(R.id.btnExit).setOnClickListener(v -> finishAndRemoveTask());
    }

    @Override protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(WELCOME_VOICE_SWITCH), false, welcomeObserver);
        refreshWelcomeState();
    }

    @Override protected void onStop() {
        try {
            getContentResolver().unregisterContentObserver(welcomeObserver);
        } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshWelcomeState();
    }

    private boolean isWelcomeVoiceEnabled() {
        return Settings.Global.getInt(getContentResolver(), WELCOME_VOICE_SWITCH, 1) != 0;
    }

    private void refreshWelcomeState() {
        if (welcomeState == null || btnWelcome == null) return;
        boolean enabled;
        try {
            enabled = isWelcomeVoiceEnabled();
        } catch (SecurityException e) {
            welcomeState.setText("Приветствие: недоступно");
            btnWelcome.setImageResource(android.R.drawable.ic_lock_lock);
            return;
        }
        welcomeState.setText(enabled ? "Приветствие: ВКЛ" : "Приветствие: ВЫКЛ");
        btnWelcome.setImageResource(enabled ? R.drawable.ic_volume_up : R.drawable.ic_volume_off);
        btnWelcome.setContentDescription(enabled ? "Выключить приветствие" : "Включить приветствие");
    }

    private void toggleWelcomeVoice() {
        try {
            boolean enabled = isWelcomeVoiceEnabled();
            boolean newState = !enabled;
            boolean written = Settings.Global.putInt(
                    getContentResolver(), WELCOME_VOICE_SWITCH, newState ? 1 : 0);
            if (!written) {
                toast("Не удалось изменить состояние приветствия");
                refreshWelcomeState();
                return;
            }
            refreshWelcomeState();
            toast(newState ? "Приветствие включено" : "Приветствие выключено");
        } catch (SecurityException e) {
            toast("Нет прав для управления приветствием");
            refreshWelcomeState();
        } catch (Exception e) {
            toast("Ошибка управления приветствием");
            refreshWelcomeState();
        }
    }

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

    private void goUp() {
        if (leftDir == null || browsingRoot == null) return;
        try {
            String current = leftDir.getCanonicalPath();
            String root = browsingRoot.getCanonicalPath();
            if (current.equals(root)) return;
            File p = leftDir.getParentFile();
            if (p != null && p.exists() && p.isDirectory()) {
                leftDir = p; loadLeft();
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
                    loadLeft();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void loadLeft() {
        leftList.removeAllViews();
        if (leftDir == null) return;
        leftPath.setText(leftDir.getAbsolutePath());
        File[] dirs = leftDir.listFiles(File::isDirectory);
        File[] files = leftDir.listFiles(f -> f.isFile() && isAudio(f));
        if (dirs == null) { addInfo(leftList, "Каталог недоступен"); return; }
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
                selectedLeft = f;
                highlight(v, leftList);
            }
        });
        v.setOnLongClickListener(x -> { selectedLeft = f; highlight(v, leftList); return true; });
        leftList.addView(v, new LinearLayout.LayoutParams(-1, -2));
        divider(leftList);
    }

    private void loadRight() {
        rightList.removeAllViews();
        File dir = new File(WELCOME_DIR);
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".mp3"));
        if (!dir.exists() && !dir.mkdirs()) { rightStatus.setText("Не удалось открыть целевой каталог"); return; }
        if (files == null) { rightStatus.setText("Целевой каталог недоступен"); return; }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        rightStatus.setText("Файлов: " + files.length);
        for (File f : files) addRightRow(f);
    }

    private void addRightRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(8, 5, 4, 5);
        TextView name = new TextView(this);
        name.setText(f.getName() + "\n" + formatSize(f.length()) + "   " + formatDuration(f));
        name.setTextSize(16); name.setPadding(6, 3, 6, 3); name.setSingleLine(false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button play = new Button(this); play.setText("▶"); play.setOnClickListener(v -> play(f));
        row.addView(play, new LinearLayout.LayoutParams(58, -2));
        Button del = new Button(this); del.setText("🗑"); del.setOnClickListener(v -> confirmDelete(f));
        row.addView(del, new LinearLayout.LayoutParams(70, -2));
        row.setOnClickListener(v -> { selectedRight = f; highlight(row, rightList); });
        rightList.addView(row, new LinearLayout.LayoutParams(-1, -2)); divider(rightList);
    }

    private void highlight(View selected, LinearLayout parent) {
        for (int i=0;i<parent.getChildCount();i++) {
            View v=parent.getChildAt(i); if (!(v instanceof android.widget.Space)) v.setBackgroundColor(Color.TRANSPARENT);
        }
        selected.setBackgroundColor(0x553399FF);
    }

    private void copyOne() {
        if (selectedLeft == null || !selectedLeft.isFile()) { toast("Выбери файл слева"); return; }
        if (!isAudio(selectedLeft)) { toast("Можно копировать только MP3"); return; }
        copyFile(selectedLeft, new File(WELCOME_DIR, selectedLeft.getName()));
    }

    private void copyAll() {
        File[] files = leftDir.listFiles(f -> f.isFile() && isAudio(f));
        if (files == null || files.length == 0) { toast("В текущем каталоге нет MP3"); return; }
        for (File f : files) copyFileQuiet(f, new File(WELCOME_DIR, f.getName()));
        loadRight(); toast("Скопировано файлов: " + files.length);
    }

    private void copyBackOne() {
        if (selectedRight == null) { toast("Выбери файл справа"); return; }
        File dst = new File(leftDir, selectedRight.getName());
        copyFile(selectedRight, dst);
    }

    private void copyBackAll() {
        File dir = new File(WELCOME_DIR); File[] files = dir.listFiles(f -> f.isFile() && isAudio(f));
        if (files == null || files.length == 0) { toast("Справа нет MP3"); return; }
        for (File f : files) copyFileQuiet(f, new File(leftDir, f.getName()));
        loadLeft(); toast("Скопировано обратно: " + files.length);
    }

    private void copyFile(File src, File dst) {
        if (dst.exists()) {
            new AlertDialog.Builder(this).setTitle("Файл уже существует")
                    .setMessage(dst.getName()).setNegativeButton("Отмена", null)
                    .setPositiveButton("Заменить", (d,w)-> { copyFileQuiet(src,dst); loadLeft(); loadRight(); }) .show();
            return;
        }
        if (copyFileQuiet(src,dst)) { loadLeft(); loadRight(); toast("Скопировано: " + dst.getName()); }
        else toast("Ошибка копирования");
    }

    private boolean copyFileQuiet(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n); out.flush(); return true;
        } catch (IOException e) { return false; }
    }

    private void play(File f) {
        stopPlayback();
        try {
            player = new MediaPlayer(); player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(mp -> { mp.start(); playing.setText("▶ Воспроизводится: " + f.getName()); });
            player.setOnCompletionListener(mp -> { playing.setText("Готово: " + f.getName()); stopPlaybackOnly(); });
            player.setOnErrorListener((mp, what, extra) -> { playing.setText("Ошибка воспроизведения: " + f.getName()); stopPlaybackOnly(); return true; });
            player.prepareAsync();
        } catch (Exception e) { toast("Не удалось воспроизвести файл"); stopPlayback(); }
    }

    private void stopPlayback() { stopPlaybackOnly(); playing.setText("Воспроизведение остановлено"); }
    private void stopPlaybackOnly() { if (player != null) { try { if (player.isPlaying()) player.stop(); } catch(Exception ignored){} player.release(); player=null; } }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this).setTitle("Удалить файл?").setMessage(f.getName())
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)-> { if(f.delete()){ selectedRight=null; loadRight(); toast("Удалено: "+f.getName()); } else toast("Не удалось удалить"); }).show();
    }

    private boolean isAudio(File f) { return f.getName().toLowerCase().endsWith(".mp3"); }
    private void divider(LinearLayout p){ View v=new View(this); v.setBackgroundColor(0x22000000); p.addView(v,new LinearLayout.LayoutParams(-1,1)); }
    private void addInfo(LinearLayout p,String s){ TextView v=new TextView(this);v.setText(s);v.setPadding(12,20,12,20);p.addView(v); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
    private String formatSize(long b){if(b<1024)return b+" Б";if(b<1048576)return String.format("%.1f КБ",b/1024.0);return String.format("%.1f МБ",b/1048576.0);}

    private String formatDuration(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = value != null ? Long.parseLong(value) : 0;
            long totalSec = Math.max(0, Math.round(ms / 1000.0));
            long min = totalSec / 60;
            long sec = totalSec % 60;
            return String.format("%d:%02d", min, sec);
        } catch (Exception e) {
            return "--:--";
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }
    @Override protected void onDestroy(){ stopPlaybackOnly(); super.onDestroy(); }
}
