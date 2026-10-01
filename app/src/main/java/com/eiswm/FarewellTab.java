package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Вкладка «Прощание» в разделе «Звуки» или «Картинки»: свой список файлов, выключатель,
 * «Проверить» и «Добавить». При выключении зажигания {@link FarewellService} играет случайный
 * звук и показывает случайную картинку из этих списков ({@link Farewell}).
 */
final class FarewellTab extends SectionPanel implements AudioPreview.Listener {
    /** Магнитола после выключения зажигания работает около 30 с; длиннее звук может оборваться. */
    static final long MAX_SOUND_DURATION_MS = 20_000;

    /** true — вкладка звуков, false — картинок. */
    private final boolean sounds;
    private final SharedPreferences prefs;
    private final TextView label, status;
    private final Switch toggle;
    private final LinearLayout list;
    private final Button btnTest, btnAdd;
    private final AudioPreview preview;
    private final Farewell farewell;
    private final Map<File, Button> playButtons = new HashMap<>();
    private boolean updatingSwitch = false;
    private int listGeneration = 0;

    static FarewellTab sounds(Activity a, SharedPreferences prefs) {
        return new FarewellTab(a, prefs, true, R.id.farewellSoundLabel, R.id.farewellSoundSwitch,
                R.id.farewellSoundStatus, R.id.farewellSoundList, R.id.btnFarewellSoundTest, R.id.btnFarewellSoundAdd);
    }

    static FarewellTab pictures(Activity a, SharedPreferences prefs) {
        return new FarewellTab(a, prefs, false, R.id.farewellPictureLabel, R.id.farewellPictureSwitch,
                R.id.farewellPictureStatus, R.id.farewellPictureList, R.id.btnFarewellPictureTest, R.id.btnFarewellPictureAdd);
    }

    private FarewellTab(Activity activity, SharedPreferences prefs, boolean sounds,
                        int labelId, int switchId, int statusId, int listId, int testId, int addId) {
        super(activity);
        this.sounds = sounds;
        this.prefs = prefs;
        preview = new AudioPreview(this);
        farewell = new Farewell(activity);
        label = activity.findViewById(labelId);
        toggle = activity.findViewById(switchId);
        status = activity.findViewById(statusId);
        list = activity.findViewById(listId);
        btnTest = activity.findViewById(testId);
        btnAdd = activity.findViewById(addId);

        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingSwitch) setEnabled(checked);
        });
        btnTest.setOnClickListener(v -> test());
        btnAdd.setOnClickListener(v -> openPicker());
        updateSwitch();
        load();
        // Служба могла не запуститься (например, приложение обновили) — поднять её снова.
        if (Farewell.isEnabled(activity)) FarewellService.start(activity);
    }

    /** Вкладка скрыта или приложение ушло с экрана — звук не должен играть в фоне. */
    void stopPreview() {
        preview.stop();
        farewell.stop();
    }

    @Override void destroy() {
        preview.release();
        farewell.stop();
        super.destroy();
    }

    // ---------------------------------------------------------------- Выключатель и проверка

    private void setEnabled(boolean on) {
        prefs.edit().putBoolean(sounds ? Prefs.FAREWELL_SOUND : Prefs.FAREWELL_PICTURE, on).apply();
        if (Farewell.isEnabled(activity)) FarewellService.start(activity);
        else FarewellService.stop(activity);
        updateSwitch();
    }

    private void updateSwitch() {
        boolean on = sounds ? Farewell.isSoundEnabled(activity) : Farewell.isPictureEnabled(activity);
        updatingSwitch = true;
        toggle.setChecked(on);
        updatingSwitch = false;
        if (sounds) label.setText(on ? R.string.farewell_sound_on : R.string.farewell_sound_off);
        else label.setText(on ? R.string.farewell_picture_on : R.string.farewell_picture_off);
    }

    private void test() {
        preview.stop();
        if (!farewell.play(sounds, !sounds)) {
            toast(activity.getString(sounds ? R.string.farewell_no_sound : R.string.farewell_no_picture));
        }
    }

    // ---------------------------------------------------------------- Список

    private File[] files() {
        return sounds ? Farewell.sounds(activity) : Farewell.pictures(activity);
    }

    private void load() {
        list.removeAllViews();
        playButtons.clear();
        File[] files = files();
        status.setText(activity.getResources().getQuantityString(
                sounds ? R.plurals.farewell_sounds_summary : R.plurals.farewell_pictures_summary, files.length, files.length));
        if (files.length == 0) {
            Ui.emptyState(list, activity.getString(sounds ? R.string.farewell_sounds_empty : R.string.farewell_pictures_empty),
                    activity.getString(R.string.farewell_add_details));
        }
        final int generation = ++listGeneration;
        final List<View> slots = new ArrayList<>();
        for (File f : files) slots.add(sounds ? addSoundRow(f) : addPictureRow(f));
        onPreviewChanged();

        // Длительность звуков и превью картинок читаются в фоне, чтобы не тормозить экран.
        io.execute(() -> {
            for (int i = 0; i < files.length; i++) {
                final View slot = slots.get(i);
                if (sounds) {
                    final long ms = FileUtils.getDurationMs(files[i]);
                    ui.post(() -> {
                        if (!destroyed && generation == listGeneration) Ui.setDurationPill((TextView) slot, ms, MAX_SOUND_DURATION_MS);
                    });
                } else {
                    final Bitmap bmp = Images.thumbnail(files[i], Ui.dp(activity, 192));
                    ui.post(() -> {
                        if (!destroyed && generation == listGeneration) ((ImageView) slot).setImageBitmap(bmp);
                    });
                }
            }
        });
    }

    /** @return метка длительности, которую заполнит фоновый поток. */
    private TextView addSoundRow(File f) {
        LinearLayout row = Ui.row(activity);
        Button play = Ui.iconButton(activity, "▶");
        play.setContentDescription(activity.getString(R.string.play_desc, f.getName()));
        play.setOnClickListener(v -> preview.toggle(f));
        row.addView(play, Ui.iconButtonParams(activity));
        playButtons.put(f, play);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        titleLp.setMarginStart(Ui.dp(activity, 8));
        titleLp.setMarginEnd(Ui.dp(activity, 16));
        row.addView(Ui.title(activity, f.getName()), titleLp);
        TextView pill = Ui.pill(activity);
        row.addView(pill);
        addDeleteButton(row, f);
        row.setOnClickListener(v -> preview.toggle(f));
        list.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        Ui.divider(list);
        return pill;
    }

    /** @return превью, которое заполнит фоновый поток. */
    private ImageView addPictureRow(File f) {
        LinearLayout row = Ui.row(activity);
        FrameLayout frame = Ui.screenFrame(activity);
        ImageView image = Ui.addPreviewImage(frame, f.getName());
        row.addView(frame, new LinearLayout.LayoutParams(Ui.dp(activity, 192), WRAP_CONTENT));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        titleLp.setMarginStart(Ui.dp(activity, 16));
        titleLp.setMarginEnd(Ui.dp(activity, 16));
        row.addView(Ui.title(activity, f.getName()), titleLp);
        addDeleteButton(row, f);
        list.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        Ui.divider(list);
        return image;
    }

    private void addDeleteButton(LinearLayout row, File f) {
        Button delete = Ui.iconButton(activity, "✕");
        delete.setContentDescription(activity.getString(R.string.delete_desc, f.getName()));
        delete.setOnClickListener(v -> confirmDelete(f));
        LinearLayout.LayoutParams lp = Ui.iconButtonParams(activity);
        lp.setMarginStart(Ui.dp(activity, 8));
        row.addView(delete, lp);
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(activity)
                .setTitle(sounds ? R.string.farewell_delete_sound : R.string.farewell_delete_picture)
                .setMessage(f.getName())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (f.equals(preview.current())) preview.stop();
                    toast(activity.getString(f.delete() ? R.string.deleted : R.string.delete_failed, f.getName()));
                    load();
                })
                .show();
    }

    // ---------------------------------------------------------------- Прослушивание

    @Override public void onPreviewChanged() {
        File current = preview.current();
        for (Map.Entry<File, Button> e : playButtons.entrySet()) {
            boolean playing = e.getKey().equals(current);
            e.getValue().setText(playing ? "■" : "▶");
            ((View) e.getValue().getParent()).setBackgroundColor(
                    playing ? activity.getColor(R.color.row_active) : Color.TRANSPARENT);
        }
    }

    @Override public void onPreviewError(File file) {
        toast(activity.getString(R.string.cannot_play, file.getName()));
    }

    // ---------------------------------------------------------------- Добавление

    private int requestCode() {
        return sounds ? RequestCodes.FAREWELL_ADD_SOUNDS : RequestCodes.FAREWELL_ADD_PICTURES;
    }

    private void openPicker() {
        if (busy) return;
        Intent i = new Intent(activity, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FILES);
        if (sounds) {
            i.putExtra(PickerActivity.EXTRA_KIND, PickerActivity.KIND_SOUNDS)
                    .putExtra(PickerActivity.EXTRA_TITLE, activity.getString(R.string.sounds_add))
                    .putExtra(PickerActivity.EXTRA_EXTENSIONS, Farewell.SOUND_EXTENSIONS)
                    .putExtra(PickerActivity.EXTRA_MAX_DURATION_MS, MAX_SOUND_DURATION_MS)
                    .putExtra(PickerActivity.EXTRA_ITEM_PLURAL, R.plurals.picker_add_sounds)
                    .putExtra(PickerActivity.EXTRA_START_DIR, FileUtils.NOTIFICATIONS_DIR.getAbsolutePath());
        } else {
            i.putExtra(PickerActivity.EXTRA_KIND, PickerActivity.KIND_IMAGES)
                    .putExtra(PickerActivity.EXTRA_TITLE, activity.getString(R.string.pictures_add))
                    .putExtra(PickerActivity.EXTRA_EXTENSIONS, WelcomePictures.EXTENSIONS)
                    .putExtra(PickerActivity.EXTRA_ITEM_PLURAL, R.plurals.picker_add_pictures)
                    .putExtra(PickerActivity.EXTRA_START_DIR, FileUtils.PICTURES_DIR.getAbsolutePath());
        }
        activity.startActivityForResult(i, requestCode());
    }

    /** @return true, если результат относится к этой вкладке. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != requestCode()) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;
        ArrayList<String> paths = data.getStringArrayListExtra(PickerActivity.EXTRA_PATHS);
        if (paths == null || paths.isEmpty()) return true;
        preview.stop();
        runBusy(() -> {
            int ok = 0, failed = 0;
            File soundDir = Farewell.soundDir(activity);
            //noinspection ResultOfMethodCallIgnored
            soundDir.mkdirs();
            for (String p : paths) {
                File src = new File(p);
                try {
                    if (sounds) {
                        if (!FileUtils.copyFileQuiet(src, new File(soundDir, src.getName()))) throw new Exception();
                    } else {
                        Farewell.addPicture(activity, src);
                    }
                    ok++;
                } catch (Exception | OutOfMemoryError e) {
                    failed++;
                }
            }
            return activity.getString(R.string.add_report, ok)
                    + (failed > 0 ? activity.getString(R.string.add_report_failed, failed) : "");
        }, Toast.LENGTH_LONG);
        return true;
    }

    @Override void setBusy(boolean value) {
        busy = value;
        btnAdd.setEnabled(!value);
        btnTest.setEnabled(!value);
        if (value) status.setText(R.string.copying);
    }

    @Override void afterJob() {
        load();
    }

    @Override String errorMessage(Throwable e) {
        return String.valueOf(e.getMessage());
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
