package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Раздел «Звуки» главного экрана: список звуков приветствия с прослушиванием, выключатель
 * звукового приветствия, добавление через {@link PickerActivity}, сохранение копии и удаление.
 */
final class SoundsPanel extends SectionPanel implements AudioPreview.Listener {
    /**
     * Машина играет звук приветствия не дольше 6 с и обрывает более длинный;
     * 0,5 с — допуск на неточность метаданных MP3.
     */
    static final long MAX_SOUND_DURATION_MS = 6500;
    static final String[] SOUND_EXTENSIONS = {"mp3"};

    private final SharedPreferences prefs;
    private final File soundDir;
    private final TextView welcomeLabel, welcomeWarning, soundStatus;
    private final Switch welcomeSwitch;
    private final LinearLayout soundList;
    private final Button btnAddSounds;

    private final AudioPreview preview;
    private final Map<File, SoundRow> rows = new HashMap<>();
    private File pendingSave;

    private int listGeneration = 0;
    private final WelcomeSwitch welcome;
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

    SoundsPanel(Activity activity, SharedPreferences prefs) {
        super(activity);
        this.prefs = prefs;
        welcome = new WelcomeSwitch(activity.getContentResolver());
        soundDir = new File(activity.getString(R.string.welcome_sound_dir));
        preview = new AudioPreview(this);
        copyBundledWelcomeFilesOnce();
        io.execute(this::deleteLeftoverTempFiles);

        welcomeLabel = activity.findViewById(R.id.welcomeLabel);
        welcomeSwitch = activity.findViewById(R.id.welcomeSwitch);
        soundList = activity.findViewById(R.id.soundList);
        soundStatus = activity.findViewById(R.id.soundStatus);
        btnAddSounds = activity.findViewById(R.id.btnAddSounds);
        welcomeWarning = activity.findViewById(R.id.welcomeWarning);

        welcomeSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingSwitch) setWelcomeSwitch(checked ? 1 : 0);
        });
        btnAddSounds.setOnClickListener(v -> openSoundPicker());
        updateWelcomeSwitch();
        loadSounds();
    }

    void onStart() {
        try {
            activity.getContentResolver().registerContentObserver(
                    WelcomeSwitch.uri(), false, welcomeObserver);
        } catch (Exception ignored) {
        }
    }

    void onResume() {
        updateWelcomeSwitch();
    }

    void onStop() {
        try {
            activity.getContentResolver().unregisterContentObserver(welcomeObserver);
        } catch (Exception ignored) {
        }
        // Приложение ушло с экрана — звук не должен продолжать играть в фоне.
        preview.stop();
    }

    /** Раздел скрыт — прослушивание останавливается. */
    void stopPreview() {
        preview.stop();
    }

    @Override void destroy() {
        preview.release();
        super.destroy();
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

    // ---------------------------------------------------------------- Переключатель приветствия

    private void updateWelcomeSwitch() {
        int state = welcome.read();
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
        if (!welcome.write(target)) {
            toast(activity.getString(R.string.sounds_switch_failed));
        } else if (welcome.read() != target) {
            toast(activity.getString(R.string.sounds_switch_unchanged));
        }
        updateWelcomeSwitch();
    }

    // ---------------------------------------------------------------- Встроенные MP3

    /** Первый запуск: встроенные MP3 — в Notifications. В фоне: запись с fsync тормозила бы экран. */
    private void copyBundledWelcomeFilesOnce() {
        if (prefs.getBoolean(Prefs.BUNDLED_COPIED, false)) return;
        io.execute(() -> {
            String message = copyBundledWelcomeFiles();
            if (message != null) {
                ui.post(() -> {
                    if (!destroyed) toast(message);
                });
            }
        });
    }

    /** @return сообщение для пользователя или null. */
    private String copyBundledWelcomeFiles() {
        File targetDir = FileUtils.NOTIFICATIONS_DIR;
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return activity.getString(R.string.sounds_notifications_failed);
        }
        int copied = 0;
        try {
            String[] names = activity.getAssets().list("welcome");
            if (names != null) {
                for (String name : names) {
                    File dst = new File(targetDir, name);
                    if (!FileUtils.hasExtension(dst, SOUND_EXTENSIONS) || dst.exists()) continue;
                    try (InputStream in = activity.getAssets().open("welcome/" + name)) {
                        if (!FileUtils.copyStreamQuiet(in, dst)) throw new IOException(dst.getPath());
                    }
                    copied++;
                }
            }
        } catch (IOException e) {
            return activity.getString(R.string.sounds_bundled_failed);
        }
        prefs.edit().putBoolean(Prefs.BUNDLED_COPIED, true).apply();
        return copied > 0 ? activity.getString(R.string.sounds_bundled_added, copied) : null;
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
            Ui.emptyState(soundList, activity.getString(R.string.sounds_no_access), soundDir.getAbsolutePath());
            setStatus("");
            return;
        }
        FileUtils.sortByName(files);
        if (files.length == 0) {
            Ui.emptyState(soundList, activity.getString(R.string.sounds_empty),
                    activity.getString(R.string.sounds_empty_details));
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
        if (count == 0) return activity.getString(R.string.sounds_summary_none);
        if (count == 1) return activity.getString(R.string.sounds_summary_one);
        return activity.getResources().getQuantityString(R.plurals.sounds_summary_many, count, count);
    }

    /** @return метка длительности, которую заполнит фоновый поток. */
    private TextView addSoundRow(File f) {
        LinearLayout row = Ui.row(activity);

        SoundRow r = new SoundRow();
        r.root = row;
        r.play = Ui.iconButton(activity, "▶");
        r.play.setContentDescription(activity.getString(R.string.play_desc, f.getName()));
        r.play.setOnClickListener(v -> preview.toggle(f));
        row.addView(r.play, Ui.iconButtonParams(activity));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.title(activity, f.getName()));
        r.progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        r.progress.setMax(1000);
        r.progress.setVisibility(View.GONE);
        texts.addView(r.progress, new LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(activity, 8)));
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        textsLp.setMarginStart(Ui.dp(activity, 8));
        textsLp.setMarginEnd(Ui.dp(activity, 16));
        row.addView(texts, textsLp);

        TextView pill = Ui.pill(activity);
        row.addView(pill);

        Button more = Ui.iconButton(activity, "⋮");
        more.setContentDescription(activity.getString(R.string.actions_desc, f.getName()));
        more.setOnClickListener(v -> showSoundMenu(v, f));
        LinearLayout.LayoutParams moreLp = Ui.iconButtonParams(activity);
        moreLp.setMarginStart(Ui.dp(activity, 8));
        row.addView(more, moreLp);

        row.setOnClickListener(v -> preview.toggle(f));
        soundList.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        Ui.divider(soundList);
        rows.put(f, r);
        return pill;
    }

    private void showSoundMenu(View anchor, File f) {
        PopupMenu menu = new PopupMenu(activity, anchor);
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
            r.root.setBackgroundColor(playing ? activity.getColor(R.color.row_active) : Color.TRANSPARENT);
        }
        ui.removeCallbacks(progressTick);
        if (current != null) ui.post(progressTick);
    }

    @Override public void onPreviewError(File file) {
        toast(activity.getString(R.string.cannot_play, file.getName()));
    }

    // ---------------------------------------------------------------- Добавление и сохранение

    private void openSoundPicker() {
        if (busy) return;
        Intent i = new Intent(activity, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FILES)
                .putExtra(PickerActivity.EXTRA_KIND, PickerActivity.KIND_SOUNDS)
                .putExtra(PickerActivity.EXTRA_TITLE, activity.getString(R.string.sounds_add_title))
                .putExtra(PickerActivity.EXTRA_EXTENSIONS, SOUND_EXTENSIONS)
                .putExtra(PickerActivity.EXTRA_MAX_DURATION_MS, MAX_SOUND_DURATION_MS)
                .putExtra(PickerActivity.EXTRA_ITEM_PLURAL, R.plurals.picker_add_sounds)
                .putExtra(PickerActivity.EXTRA_START_DIR,
                        FileUtils.NOTIFICATIONS_DIR.getAbsolutePath());
        activity.startActivityForResult(i, RequestCodes.SOUNDS_ADD);
    }

    private void openSaveFolderPicker(File f) {
        if (busy) return;
        pendingSave = f;
        Intent i = new Intent(activity, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FOLDER)
                .putExtra(PickerActivity.EXTRA_TITLE, activity.getString(R.string.save_where_title))
                .putExtra(PickerActivity.EXTRA_SUBJECT, f.getName())
                .putExtra(PickerActivity.EXTRA_ACTION, activity.getString(R.string.save_here));
        activity.startActivityForResult(i, RequestCodes.SOUNDS_SAVE);
    }

    /** @return true, если результат относится к этому разделу. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != RequestCodes.SOUNDS_ADD && requestCode != RequestCodes.SOUNDS_SAVE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;
        if (requestCode == RequestCodes.SOUNDS_ADD) {
            ArrayList<String> paths = data.getStringArrayListExtra(PickerActivity.EXTRA_PATHS);
            if (paths == null || paths.isEmpty()) return true;
            List<File> files = new ArrayList<>();
            for (String p : paths) files.add(new File(p));
            prepareAdd(files);
        } else if (pendingSave != null) {
            String folder = data.getStringExtra(PickerActivity.EXTRA_FOLDER);
            if (folder == null) return true;
            final File src = pendingSave;
            final File dst = new File(folder, src.getName());
            pendingSave = null;
            if (dst.equals(src)) { toast(activity.getString(R.string.file_already_here)); return true; }
            confirmReplaceThen(dst, () -> copySingle(src, dst));
        }
        return true;
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
                ? activity.getString(R.string.sounds_conflict_one, files.get(0).getName())
                : activity.getString(R.string.sounds_conflict_many, conflicts, files.size());
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
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
        runBusy(() -> {
            int copied = 0, skipped = 0, failed = 0;
            for (File f : files) {
                File dst = new File(soundDir, f.getName());
                if (dst.exists() && !replace) { skipped++; continue; }
                if (FileUtils.copyFileQuiet(f, dst)) copied++; else failed++;
            }
            return activity.getString(R.string.add_report, copied)
                    + (skipped > 0 ? activity.getString(R.string.add_report_skipped, skipped) : "")
                    + (failed > 0 ? activity.getString(R.string.add_report_failed, failed) : "");
        }, Toast.LENGTH_LONG);
    }

    private void confirmReplaceThen(File dst, Runnable next) {
        if (!dst.exists()) { next.run(); return; }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.file_exists_title)
                .setMessage(activity.getString(R.string.file_exists_message, dst.getAbsolutePath()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.replace, (d, w) -> next.run())
                .show();
    }

    private void copySingle(File src, File dst) {
        if (dst.equals(preview.current())) preview.stop();
        runBusy(() -> FileUtils.copyFileQuiet(src, dst)
                ? activity.getString(R.string.saved, dst.getAbsolutePath())
                : activity.getString(R.string.save_failed, dst.getName()), Toast.LENGTH_SHORT);
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.sound_delete_title)
                .setMessage(activity.getString(R.string.sound_delete_message, f.getName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (f.equals(preview.current())) preview.stop();
                    if (f.delete()) {
                        loadSounds();
                        toast(activity.getString(R.string.deleted, f.getName()));
                    } else {
                        toast(activity.getString(R.string.delete_failed, f.getName()));
                    }
                })
                .show();
    }

    /** На время копирования: кнопка «Добавить» недоступна, в строке состояния «Копирование…». */
    @Override void setBusy(boolean value) {
        busy = value;
        btnAddSounds.setEnabled(!value);
        if (value) setStatus(activity.getString(R.string.copying));
        else {
            File[] files = listSounds();
            setStatus(summary(files == null ? 0 : files.length));
        }
    }

    @Override void afterJob() {
        loadSounds();
    }

    @Override String errorMessage(Throwable e) {
        return String.valueOf(e.getMessage());
    }

    private void setStatus(String text) {
        soundStatus.setText(text);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
