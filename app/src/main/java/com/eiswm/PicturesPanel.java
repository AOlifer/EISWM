package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Раздел «Картинки» главного экрана: сетка превью картинок приветствия, выключатель показа,
 * добавление через {@link PickerActivity} и сохранение копии. Данные — {@link WelcomePictures}.
 */
final class PicturesPanel {
    static final int REQ_ADD = 11;
    static final int REQ_SAVE = 12;
    static final int REQ_STANDARD = 13;
    private static final int COLUMNS = 2;

    private final Activity activity;
    private final WelcomePictures pictures;
    private final LinearLayout grid;
    private final TextView label, warning, status;
    private final Switch toggle;
    private final Button btnAdd;
    private final Button btnStandard;
    private final TextView header;
    private final Button btnSelectAll, btnClear, btnDelete;
    /** Выделенные картинки (по id записи) и то, что сейчас показано в сетке. */
    private final Set<String> selectedIds = new HashSet<>();
    private final List<WelcomePictures.Picture> current = new ArrayList<>();
    private final Map<String, CheckBox> checks = new HashMap<>();
    private static final int[] SEASON_TITLES = {
            R.string.season_winter, R.string.season_spring, R.string.season_summer, R.string.season_autumn};

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Отдельный поток: обработка больших картинок не мешает звукам. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int generation = 0;
    private boolean busy = false;
    private boolean destroyed = false;
    private boolean updatingSwitch = false;
    private WelcomePictures.Picture pendingSave;

    PicturesPanel(Activity activity) {
        this.activity = activity;
        pictures = new WelcomePictures(activity);
        grid = activity.findViewById(R.id.pictureGrid);
        label = activity.findViewById(R.id.picturesLabel);
        warning = activity.findViewById(R.id.picturesWarning);
        status = activity.findViewById(R.id.picturesStatus);
        toggle = activity.findViewById(R.id.picturesSwitch);
        btnAdd = activity.findViewById(R.id.btnAddPictures);

        btnAdd.setOnClickListener(v -> openPicker());
        btnStandard = activity.findViewById(R.id.btnStandardPictures);
        btnStandard.setOnClickListener(v -> {
            if (!busy) activity.startActivityForResult(new Intent(activity, StandardPicturesActivity.class), REQ_STANDARD);
        });
        header = activity.findViewById(R.id.picturesHeader);
        btnSelectAll = activity.findViewById(R.id.btnPicturesSelectAll);
        btnClear = activity.findViewById(R.id.btnPicturesClear);
        btnDelete = activity.findViewById(R.id.btnPicturesDelete);
        btnSelectAll.setOnClickListener(v -> setAllSelected(true));
        btnClear.setOnClickListener(v -> setAllSelected(false));
        btnDelete.setOnClickListener(v -> confirmDeleteSelected());
        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingSwitch) setShown(checked);
        });
        updateSwitch();
        restoreThenLoad();
    }

    boolean isBusy() {
        return busy;
    }

    void destroy() {
        destroyed = true;
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- Список

    /** При открытии вернуть то, что мог стереть лаунчер, и показать список. */
    private void restoreThenLoad() {
        io.execute(() -> {
            int restored;
            try {
                restored = pictures.restore();
            } catch (Exception e) {
                restored = 0;
            }
            final int count = restored;
            ui.post(() -> {
                if (destroyed) return;
                if (count > 0) toast(activity.getString(R.string.pictures_restored, count));
                load();
            });
        });
    }

    void load() {
        final int gen = ++generation;
        io.execute(() -> {
            List<WelcomePictures.Picture> list;
            String error = null;
            try {
                list = pictures.list();
            } catch (Exception e) {
                list = null;
                error = e.getMessage();
            }
            final List<WelcomePictures.Picture> result = list;
            final String err = error;
            ui.post(() -> {
                if (destroyed || gen != generation) return;
                show(result, err, gen);
            });
        });
    }

    private void show(List<WelcomePictures.Picture> list, String error, int gen) {
        grid.removeAllViews();
        checks.clear();
        current.clear();
        if (list == null) {
            selectedIds.clear();
            updateSelectionUi();
            Ui.emptyState(grid, activity.getString(R.string.pictures_no_access), error);
            status.setText("");
            return;
        }
        current.addAll(list);
        // Выделение переживает обновление списка, но только для оставшихся картинок.
        Set<String> ids = new HashSet<>();
        for (WelcomePictures.Picture p : list) ids.add(p.id);
        selectedIds.retainAll(ids);
        if (list.isEmpty()) {
            Ui.emptyState(grid, activity.getString(R.string.pictures_empty),
                    activity.getString(R.string.pictures_empty_details));
        }
        int active = 0;
        for (WelcomePictures.Picture p : list) if (p.activeNow()) active++;
        if (!busy) status.setText(summary(list.size(), active));

        boolean dimmed = pictures.isDisabled();
        List<ImageView> images = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            images.add(addCard(row, list.get(i), dimmed));
        }
        // Пустая ячейка в неполной строке, чтобы карточки были одной ширины.
        if (row != null && list.size() % COLUMNS != 0) {
            row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        }
        loadThumbnails(list, images, gen);
        updateSelectionUi();
    }

    // ---------------------------------------------------------------- Выделение

    private void setAllSelected(boolean on) {
        if (busy) return;
        // Сначала меняем набор, потом галочки: их обработчики вызовут updateSelectionUi().
        if (on) for (WelcomePictures.Picture p : current) selectedIds.add(p.id);
        else selectedIds.clear();
        for (CheckBox c : checks.values()) c.setChecked(on);
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        int n = selectedIds.size();
        header.setText(n > 0
                ? activity.getString(R.string.pictures_selected_header, n, current.size())
                : activity.getString(R.string.pictures_header));
        btnSelectAll.setEnabled(!busy && !current.isEmpty() && n < current.size());
        btnClear.setEnabled(!busy && n > 0);
        btnDelete.setVisibility(n > 0 ? View.VISIBLE : View.INVISIBLE);
        btnDelete.setEnabled(!busy);
        btnDelete.setText(activity.getString(R.string.pictures_delete_selected, n));
    }

    private void confirmDeleteSelected() {
        final List<WelcomePictures.Picture> victims = new ArrayList<>();
        for (WelcomePictures.Picture p : current) if (selectedIds.contains(p.id)) victims.add(p);
        if (victims.isEmpty()) return;
        int n = victims.size();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.pictures_delete_many_title)
                .setMessage(activity.getResources().getQuantityString(R.plurals.pictures_delete_many_message, n, n))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pictures_delete_ok, (d, w) -> run(() -> {
                    int deleted = 0;
                    for (WelcomePictures.Picture p : victims) {
                        pictures.delete(p);
                        deleted++;
                    }
                    // Выделение удалённых само уйдёт при обновлении списка (show()).
                    return activity.getString(R.string.pictures_deleted_many, deleted);
                }))
                .show();
    }

    /** @param active сколько из них показывается сейчас (у сезонных и просроченных срок не сегодня). */
    private String summary(int n, int active) {
        Resources r = activity.getResources();
        if (n == 0) return activity.getString(R.string.pictures_summary_none);
        if (pictures.isDisabled()) return r.getQuantityString(R.plurals.pictures_summary_off, n, n);
        if (active != n) return r.getQuantityString(R.plurals.pictures_summary_partial, n, n, active);
        if (n == 1) return activity.getString(R.string.pictures_summary_one);
        return r.getQuantityString(R.plurals.pictures_summary_many, n, n);
    }

    /** Карточка: превью 8:3 (как экран машины), подпись и меню ⋮. */
    private ImageView addCard(LinearLayout row, WelcomePictures.Picture p, boolean dimmed) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 4));
        card.setAlpha(dimmed ? 0.45f : 1f);

        FrameLayout frame = new FrameLayout(activity) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                int height = width * WelcomePictures.HEIGHT / WelcomePictures.WIDTH;
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        frame.setBackgroundColor(0xFF000000);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(p.title);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        frame.setOnClickListener(v -> showLarge(p));

        // Галочка выделения в углу превью; нажатие на само превью открывает картинку крупно.
        CheckBox check = new CheckBox(activity);
        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setColor(0x99000000);
        checkBg.setCornerRadius(Ui.dp(activity, 6));
        check.setBackground(checkBg);
        check.setScaleX(1.3f);
        check.setScaleY(1.3f);
        check.setChecked(selectedIds.contains(p.id));
        check.setContentDescription(activity.getString(R.string.select_all));
        check.setOnCheckedChangeListener((v, on) -> {
            if (on) selectedIds.add(p.id); else selectedIds.remove(p.id);
            card.setBackgroundColor(on ? activity.getColor(R.color.row_active) : Color.TRANSPARENT);
            updateSelectionUi();
        });
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        checkLp.setMargins(Ui.dp(activity, 8), Ui.dp(activity, 8), 0, 0);
        frame.addView(check, checkLp);
        checks.put(p.id, check);
        if (check.isChecked()) card.setBackgroundColor(activity.getColor(R.color.row_active));
        card.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout info = new LinearLayout(activity);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView date = new TextView(activity);
        date.setTextSize(15);
        date.setTextColor(activity.getColor(R.color.text_secondary));
        date.setSingleLine(true);
        date.setText(describe(p));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(0, -2, 1);
        dateLp.setMarginStart(Ui.dp(activity, 4));
        info.addView(date, dateLp);
        int season = WelcomePictures.seasonOf(p);
        if (season >= 0 && season < SEASON_TITLES.length) {
            // Картинка «по временам года»: подпись сезона, зелёная — если он идёт сейчас.
            TextView pill = Ui.pill(activity);
            boolean now = p.activeNow() && !pictures.isDisabled();
            Ui.setPill(pill, activity.getString(SEASON_TITLES[season]) + (now ? " · " + activity.getString(R.string.season_now) : ""),
                    now ? Ui.PILL_OK : Ui.PILL_NEUTRAL);
            info.addView(pill);
        } else if (!p.activeNow() && !pictures.isDisabled()) {
            TextView pill = Ui.pill(activity);
            Ui.setPill(pill, activity.getString(p.expired() ? R.string.pictures_expired : R.string.pictures_not_started),
                    Ui.PILL_WARN);
            info.addView(pill);
        }
        Button more = Ui.iconButton(activity, "⋮");
        more.setContentDescription(activity.getString(R.string.pictures_menu_delete));
        more.setOnClickListener(v -> showMenu(v, p));
        info.addView(more, new LinearLayout.LayoutParams(Ui.dp(activity, 56), Ui.dp(activity, 52)));
        card.addView(info, new LinearLayout.LayoutParams(-1, -2));

        row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        return image;
    }

    private String describe(WelcomePictures.Picture p) {
        if (WelcomePictures.isStandard(p)) {
            return activity.getString(WelcomePictures.seasonOf(p) >= 0
                    ? R.string.pictures_desc_std_season : R.string.pictures_desc_std_year);
        }
        if (p.created <= 0) return "";
        // Формат даты — по языку системы (28.09.2026 / 9/28/2026).
        String date = android.text.format.DateFormat.getDateFormat(activity).format(new java.util.Date(p.created));
        return activity.getString(R.string.pictures_desc_added, date);
    }

    private void loadThumbnails(List<WelcomePictures.Picture> list, List<ImageView> images, int gen) {
        final int width = Ui.dp(activity, 400);
        io.execute(() -> {
            for (int i = 0; i < list.size(); i++) {
                if (destroyed || gen != generation) return;
                final Bitmap bmp = WelcomePictures.thumbnail(list.get(i).file, width);
                final ImageView view = images.get(i);
                ui.post(() -> {
                    if (destroyed || gen != generation) return;
                    if (bmp != null) view.setImageBitmap(bmp);
                });
            }
        });
    }

    /** Картинка крупно — примерно так, как её покажет машина. */
    private void showLarge(WelcomePictures.Picture p) {
        ImageView big = new ImageView(activity);
        big.setAdjustViewBounds(true);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setBackgroundColor(0xFF000000);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(big)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.show();
        io.execute(() -> {
            final Bitmap bmp = WelcomePictures.thumbnail(p.file, WelcomePictures.WIDTH / 2);
            ui.post(() -> {
                if (!destroyed && bmp != null) big.setImageBitmap(bmp);
            });
        });
    }

    private void showMenu(View anchor, WelcomePictures.Picture p) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.pictures_menu_save);
        menu.getMenu().add(0, 2, 1, R.string.pictures_menu_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openSaveFolderPicker(p);
            else confirmDelete(p);
            return true;
        });
        menu.show();
    }

    private void confirmDelete(WelcomePictures.Picture p) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.pictures_delete_title)
                .setMessage(R.string.pictures_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pictures_delete_ok, (d, w) -> run(() -> {
                    pictures.delete(p);
                    return activity.getString(R.string.pictures_deleted);
                }))
                .show();
    }

    // ---------------------------------------------------------------- Выключатель

    private void updateSwitch() {
        boolean shown = !pictures.isDisabled();
        updatingSwitch = true;
        toggle.setChecked(shown);
        updatingSwitch = false;
        label.setText(shown ? R.string.pictures_on : R.string.pictures_off);
        warning.setVisibility(shown ? View.GONE : View.VISIBLE);
    }

    private void setShown(boolean shown) {
        run(() -> {
            pictures.setDisabled(!shown);
            return null;
        });
    }

    // ---------------------------------------------------------------- Добавление и копии

    private void openPicker() {
        if (busy) return;
        Intent i = new Intent(activity, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FILES)
                .putExtra(PickerActivity.EXTRA_TITLE, activity.getString(R.string.pictures_add_title))
                .putExtra(PickerActivity.EXTRA_EXTENSIONS, WelcomePictures.EXTENSIONS)
                .putExtra(PickerActivity.EXTRA_ITEM_PLURAL, R.plurals.picker_add_pictures)
                .putExtra(PickerActivity.EXTRA_START_DIR,
                        new File(FileUtils.INTERNAL_ROOT, "Pictures").getAbsolutePath());
        activity.startActivityForResult(i, REQ_ADD);
    }

    private void openSaveFolderPicker(WelcomePictures.Picture p) {
        if (busy) return;
        pendingSave = p;
        Intent i = new Intent(activity, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FOLDER)
                .putExtra(PickerActivity.EXTRA_TITLE, activity.getString(R.string.save_where_title))
                .putExtra(PickerActivity.EXTRA_SUBJECT, copyName(p))
                .putExtra(PickerActivity.EXTRA_ACTION, activity.getString(R.string.save_here));
        activity.startActivityForResult(i, REQ_SAVE);
    }

    /** @return true, если результат относится к этому разделу. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_STANDARD) {
            load();
            return true;
        }
        if (requestCode != REQ_ADD && requestCode != REQ_SAVE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;
        if (requestCode == REQ_ADD) {
            ArrayList<String> paths = data.getStringArrayListExtra(PickerActivity.EXTRA_PATHS);
            if (paths != null && !paths.isEmpty()) addAll(paths);
        } else if (pendingSave != null) {
            String folder = data.getStringExtra(PickerActivity.EXTRA_FOLDER);
            final WelcomePictures.Picture p = pendingSave;
            pendingSave = null;
            if (folder != null) saveCopy(p, new File(folder, copyName(p)));
        }
        return true;
    }

    /** Имя копии: исходное имя картинки (файл всегда PNG), иначе имя файла в машине. */
    private static String copyName(WelcomePictures.Picture p) {
        String t = p.title;
        if (t == null || t.isEmpty() || t.contains("/") || !t.contains(".")) return p.file.getName();
        return t.substring(0, t.lastIndexOf('.')) + ".png";
    }

    private void addAll(List<String> paths) {
        run(() -> {
            int ok = 0, failed = 0;
            for (String path : paths) {
                try {
                    pictures.add(new File(path));
                    ok++;
                } catch (Exception | OutOfMemoryError e) {
                    failed++;
                }
            }
            return activity.getString(R.string.pictures_added, ok)
                    + (failed > 0 ? activity.getString(R.string.pictures_add_failed, failed) : "");
        });
    }

    private void saveCopy(WelcomePictures.Picture p, File dst) {
        run(() -> FileUtils.copyFileQuiet(p.file, dst)
                ? activity.getString(R.string.pictures_saved, dst.getAbsolutePath())
                : activity.getString(R.string.pictures_save_failed));
    }

    // ---------------------------------------------------------------- Фоновые операции

    private interface Job {
        /** @return сообщение для пользователя или null. */
        String run() throws Exception;
    }

    /** Выполнить в фоне с блокировкой кнопок, затем обновить выключатель и список. */
    private void run(Job job) {
        setBusy(true);
        io.execute(() -> {
            String message;
            try {
                message = job.run();
            } catch (Exception | OutOfMemoryError e) {
                message = activity.getString(R.string.pictures_error, String.valueOf(e.getMessage()));
            }
            final String msg = message;
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false);
                updateSwitch();
                load();
                if (msg != null) Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        btnAdd.setEnabled(!value);
        btnStandard.setEnabled(!value);
        toggle.setEnabled(!value);
        updateSelectionUi();
        if (value) status.setText(R.string.pictures_busy);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }
}
