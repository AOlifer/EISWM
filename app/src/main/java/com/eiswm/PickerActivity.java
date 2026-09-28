package com.eiswm;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Выбор файлов или папки в памяти устройства и на внешних накопителях.
 * Три колонки: накопители слева, содержимое папки по центру, выбранное и кнопка действия справа.
 * Экран общий для всех видов файлов: что показывать и как проверять, задаётся через Intent.
 * Сейчас используется для звуков; для картинок приветствия достаточно передать свои расширения.
 */
public class PickerActivity extends BaseActivity implements AudioPreview.Listener {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_TITLE = "title";
    /** Подпись основной кнопки в режиме выбора папки. */
    static final String EXTRA_ACTION = "action";
    /** Режим папки: имя файла, который будет сохранён, — показывается в правой колонке. */
    static final String EXTRA_SUBJECT = "subject";
    /** Расширения без точки, например {"mp3"}. */
    static final String EXTRA_EXTENSIONS = "extensions";
    /** Длительность, после которой машина обрывает звук; 0 — не проверять. */
    static final String EXTRA_MAX_DURATION_MS = "maxDurationMs";
    /** Формы слова для кнопки «Добавить N …»: {"звук", "звука", "звуков"}. */
    static final String EXTRA_ITEM_FORMS = "itemForms";
    static final String EXTRA_START_DIR = "startDir";
    /** Результат режима файлов: ArrayList&lt;String&gt; путей. */
    static final String EXTRA_PATHS = "paths";
    /** Результат режима папки: путь. */
    static final String EXTRA_FOLDER = "folder";

    static final String MODE_FILES = "files";
    static final String MODE_FOLDER = "folder";

    private String mode;
    private String[] extensions;
    private long maxDurationMs;
    private String[] itemForms;
    private boolean audio;
    /** Режим картинок: превью и размер вместо прослушивания и длительности. */
    private boolean images;
    private final Map<File, int[]> imageSizes = new HashMap<>();
    private final Map<File, Bitmap> thumbs = new HashMap<>();

    private List<File> roots;
    private File root, dir;
    /** Выбор сохраняется при переходе между папками и накопителями. */
    private final TreeSet<File> selected = new TreeSet<>();
    /** Длительность уже проверенных файлов; -1 — не удалось прочитать. */
    private final Map<File, Long> durations = new HashMap<>();
    private final Map<File, FileRow> rows = new LinkedHashMap<>();

    private LinearLayout rootsBar, crumbs, list, sideList;
    private HorizontalScrollView crumbsScroll;
    private TextView sideTitle, status;
    private Button btnSelectAll, btnAction;
    private AudioPreview preview;
    private SharedPreferences prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int generation = 0;
    private boolean destroyed = false;

    private static final class FileRow {
        File file;
        View root;
        CheckBox check;
        TextView pill;
        Button play;
        ImageView thumb;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_picker);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        preview = new AudioPreview(this);

        Intent in = getIntent();
        mode = MODE_FOLDER.equals(in.getStringExtra(EXTRA_MODE)) ? MODE_FOLDER : MODE_FILES;
        extensions = in.getStringArrayExtra(EXTRA_EXTENSIONS);
        if (extensions == null) extensions = new String[0];
        maxDurationMs = in.getLongExtra(EXTRA_MAX_DURATION_MS, 0);
        itemForms = in.getStringArrayExtra(EXTRA_ITEM_FORMS);
        if (itemForms == null || itemForms.length != 3) itemForms = new String[]{"файл", "файла", "файлов"};
        audio = Arrays.asList(extensions).contains("mp3");
        images = Arrays.asList(extensions).contains("png");

        ((TextView) findViewById(R.id.pickerTitle)).setText(in.getStringExtra(EXTRA_TITLE));
        rootsBar = findViewById(R.id.rootsBar);
        crumbs = findViewById(R.id.crumbs);
        crumbsScroll = findViewById(R.id.crumbsScroll);
        list = findViewById(R.id.pickerList);
        sideTitle = findViewById(R.id.sideTitle);
        sideList = findViewById(R.id.sideList);
        status = findViewById(R.id.pickerStatus);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnAction = findViewById(R.id.btnAction);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshRoots());
        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        btnAction.setOnClickListener(v -> finishWithResult());
        if (MODE_FOLDER.equals(mode)) {
            btnSelectAll.setVisibility(View.GONE);
            String action = in.getStringExtra(EXTRA_ACTION);
            btnAction.setText(action != null ? action : "Выбрать эту папку");
            sideTitle.setText("Сохранить копию");
            String subject = in.getStringExtra(EXTRA_SUBJECT);
            if (subject != null) sideList.addView(sideText(subject, true));
        }

        roots = FileUtils.storageRoots();
        openStartDir(in.getStringExtra(EXTRA_START_DIR));
    }

    @Override protected void onStop() {
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

    /** Системная кнопка «Назад» сначала поднимается по папкам, потом закрывает экран. */
    @Override public void onBackPressed() {
        if (dir != null && !dir.equals(root) && dir.getParentFile() != null) open(dir.getParentFile());
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- Навигация

    private String lastDirKey() {
        // Звуки и картинки обычно лежат в разных папках — помним их отдельно.
        return "picker_last_dir_" + mode + (images ? "_images" : "");
    }

    private void openStartDir(String requested) {
        String last = prefs.getString(lastDirKey(), null);
        for (String candidate : new String[]{last, requested}) {
            if (candidate == null) continue;
            File d = new File(candidate);
            File r = FileUtils.findRoot(d, roots);
            if (r != null && d.isDirectory()) {
                root = r;
                open(d);
                return;
            }
        }
        root = FileUtils.INTERNAL_ROOT;
        open(root);
    }

    private void refreshRoots() {
        roots = FileUtils.storageRoots();
        File r = dir != null ? FileUtils.findRoot(dir, roots) : null;
        if (r == null || !dir.isDirectory()) {
            if (root != null && !roots.contains(root)) toast("Накопитель " + FileUtils.rootLabel(root) + " отключён");
            root = FileUtils.INTERNAL_ROOT;
            open(root);
        } else {
            root = r;
            open(dir);
        }
    }

    private void open(File d) {
        preview.stop();
        dir = d;
        prefs.edit().putString(lastDirKey(), d.getAbsolutePath()).apply();
        buildRootsBar();
        buildCrumbs();
        loadList();
    }

    private void buildRootsBar() {
        rootsBar.removeAllViews();
        for (File r : roots) {
            boolean internal = r.equals(FileUtils.INTERNAL_ROOT);
            TextView item = Ui.railItem(this, internal ? R.drawable.ic_storage_internal : R.drawable.ic_storage_usb,
                    FileUtils.rootLabel(r), false);
            Ui.setSelected(item, r.equals(root));
            item.setOnClickListener(v -> {
                root = r;
                open(r);
            });
            rootsBar.addView(item);
        }
        if (roots.size() == 1) {
            TextView hint = new TextView(this);
            hint.setText("Флешка не найдена. Вставьте её и нажмите «Обновить».");
            hint.setTextSize(15);
            hint.setTextColor(getColor(R.color.text_secondary));
            hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), 0);
            rootsBar.addView(hint);
        }
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        List<File> chain = new ArrayList<>();
        for (File f = dir; f != null; f = f.getParentFile()) {
            chain.add(0, f);
            if (f.equals(root)) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("›");
                sep.setTextSize(20);
                sep.setTextColor(getColor(R.color.text_disabled));
                crumbs.addView(sep);
            }
            TextView part = new TextView(this);
            part.setText(i == 0 ? FileUtils.rootLabel(root) : f.getName());
            part.setTextSize(18);
            part.setGravity(Gravity.CENTER_VERTICAL);
            part.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            if (last) {
                part.setTextColor(getColor(R.color.text_primary));
                part.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                part.setTextColor(getColor(R.color.accent));
                part.setBackgroundResource(Ui.selectableBackground(this));
                part.setOnClickListener(v -> open(f));
            }
            crumbs.addView(part, new LinearLayout.LayoutParams(-2, -1));
        }
        crumbsScroll.post(() -> crumbsScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void loadList() {
        list.removeAllViews();
        rows.clear();
        final int gen = ++generation;

        File[] dirs = dir.listFiles(f -> f.isDirectory() && !f.isHidden());
        if (dirs == null) {
            Ui.emptyState(list, "Папка недоступна", dir.getAbsolutePath());
            updateSide();
            return;
        }
        FileUtils.sortByName(dirs);
        for (File d : dirs) addFolderRow(d);

        File[] files = new File[0];
        if (MODE_FILES.equals(mode)) {
            File[] found = dir.listFiles(f -> f.isFile() && !f.isHidden() && FileUtils.hasExtension(f, extensions));
            if (found != null) files = found;
            FileUtils.sortByName(files);
            for (File f : files) addFileRow(f);
        }

        if (dirs.length == 0 && files.length == 0) {
            Ui.emptyState(list, MODE_FILES.equals(mode) ? "Здесь нет подходящих файлов" : "Здесь нет вложенных папок",
                    MODE_FILES.equals(mode) ? "Нужны файлы " + extensionsText() + ". Откройте другую папку или накопитель." : null);
        }
        updateSide();
        readDurations(files, gen);
        readImages(files, gen);
    }

    private String extensionsText() {
        StringBuilder sb = new StringBuilder();
        for (String e : extensions) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.toUpperCase());
        }
        return sb.toString();
    }

    private void addFolderRow(File d) {
        LinearLayout row = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 32)));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1);
        nameLp.setMarginStart(Ui.dp(this, 8));
        row.addView(Ui.title(this, d.getName()), nameLp);
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(28);
        chevron.setTextColor(getColor(R.color.text_disabled));
        chevron.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        row.addView(chevron);
        row.setBackgroundResource(Ui.selectableBackground(this));
        row.setOnClickListener(v -> open(d));
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        Ui.divider(list);
    }

    private void addFileRow(File f) {
        FileRow r = new FileRow();
        r.file = f;
        LinearLayout row = Ui.row(this);
        r.root = row;

        r.check = new CheckBox(this);
        r.check.setClickable(false);
        r.check.setFocusable(false);
        r.check.setScaleX(1.4f);
        r.check.setScaleY(1.4f);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(Ui.dp(this, 56), -2);
        checkLp.setMarginStart(Ui.dp(this, 8));
        row.addView(r.check, checkLp);

        if (audio) {
            r.play = Ui.iconButton(this, "▶");
            r.play.setContentDescription("Прослушать " + f.getName());
            r.play.setOnClickListener(v -> preview.toggle(f));
            row.addView(r.play, Ui.iconButtonParams(this));
        } else if (images) {
            // Превью в пропорциях экрана машины 8:3; картинка подгружается в фоне.
            r.thumb = new ImageView(this);
            r.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            r.thumb.setBackgroundColor(0xFF000000);
            Bitmap cached = thumbs.get(f);
            if (cached != null) r.thumb.setImageBitmap(cached);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(Ui.dp(this, 160), Ui.dp(this, 60));
            thumbLp.setMarginStart(Ui.dp(this, 4));
            row.addView(r.thumb, thumbLp);
        }

        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1);
        nameLp.setMarginStart(Ui.dp(this, 8));
        nameLp.setMarginEnd(Ui.dp(this, 16));
        row.addView(Ui.title(this, f.getName()), nameLp);

        r.pill = Ui.pill(this);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.setMarginEnd(Ui.dp(this, 12));
        row.addView(r.pill, pillLp);

        row.setOnClickListener(v -> onFileRowClick(r));
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        Ui.divider(list);
        rows.put(f, r);
        applyRowState(r);
    }

    // ---------------------------------------------------------------- Проверка файлов

    private boolean needsDuration() {
        return audio && maxDurationMs > 0;
    }

    /** Звук ещё проверяется — выбрать его пока нельзя. */
    private boolean pending(File f) {
        return needsDuration() && !durations.containsKey(f);
    }

    /** Машина оборвёт этот звук: он длиннее предела. */
    private boolean tooLong(File f) {
        Long ms = durations.get(f);
        return needsDuration() && ms != null && ms > maxDurationMs;
    }

    private void applyRowState(FileRow r) {
        boolean isSelected = selected.contains(r.file);
        r.check.setChecked(isSelected);
        r.check.setEnabled(!pending(r.file));
        r.root.setBackgroundColor(isSelected ? getColor(R.color.row_active) : Color.TRANSPARENT);

        if (images) {
            int[] size = imageSizes.get(r.file);
            if (size == null) Ui.setPill(r.pill, "…", Ui.PILL_NEUTRAL);
            else if (size[0] <= 0) Ui.setPill(r.pill, getString(R.string.picker_image_bad), Ui.PILL_BAD);
            else if (size[0] * WelcomePictures.HEIGHT == size[1] * WelcomePictures.WIDTH)
                Ui.setPill(r.pill, getString(R.string.picker_image_ok, size[0], size[1]), Ui.PILL_OK);
            else Ui.setPill(r.pill, getString(R.string.picker_image_crop, size[0], size[1]), Ui.PILL_WARN);
            return;
        }
        if (!needsDuration()) {
            Ui.setPill(r.pill, FileUtils.formatSize(r.file.length()), Ui.PILL_NEUTRAL);
            return;
        }
        Long ms = durations.get(r.file);
        if (ms == null) Ui.setPill(r.pill, "проверка…", Ui.PILL_NEUTRAL);
        else Ui.setDurationPill(r.pill, ms, maxDurationMs);
    }

    /** Размер и превью картинок читаются в фоне: большие фото открываются заметное время. */
    private void readImages(File[] files, int gen) {
        if (!images) return;
        final int thumbWidth = Ui.dp(this, 160);
        io.execute(() -> {
            for (File f : files) {
                if (destroyed || gen != generation) return;
                if (imageSizes.containsKey(f) && thumbs.containsKey(f)) continue;
                int[] s = WelcomePictures.imageSize(f);
                final int[] size = s != null ? s : new int[]{0, 0};
                Bitmap b = null;
                try {
                    if (s != null) b = WelcomePictures.thumbnail(f, thumbWidth);
                } catch (OutOfMemoryError ignored) {
                }
                final Bitmap thumb = b;
                ui.post(() -> {
                    imageSizes.put(f, size);
                    if (thumb != null) thumbs.put(f, thumb);
                    if (destroyed || gen != generation) return;
                    FileRow r = rows.get(f);
                    if (r != null) {
                        if (thumb != null) r.thumb.setImageBitmap(thumb);
                        applyRowState(r);
                    }
                });
            }
        });
    }

    private void readDurations(File[] files, int gen) {
        if (!needsDuration()) return;
        io.execute(() -> {
            for (File f : files) {
                if (destroyed || gen != generation) return;
                if (durations.containsKey(f)) continue;
                final long ms = FileUtils.getDurationMs(f);
                ui.post(() -> {
                    durations.put(f, ms);
                    if (destroyed || gen != generation) return;
                    FileRow r = rows.get(f);
                    if (r != null) applyRowState(r);
                    updateSide();
                });
            }
        });
    }

    // ---------------------------------------------------------------- Выбор

    private void onFileRowClick(FileRow r) {
        if (pending(r.file)) { toast("Файл ещё проверяется"); return; }
        if (!selected.remove(r.file)) selected.add(r.file);
        applyRowState(r);
        updateSide();
    }

    /** Файлы текущей папки, которые уже проверены и могут быть выбраны. */
    private List<File> selectableHere() {
        List<File> result = new ArrayList<>();
        for (File f : rows.keySet()) if (!pending(f)) result.add(f);
        return result;
    }

    private void toggleSelectAll() {
        List<File> here = selectableHere();
        if (here.isEmpty()) { toast("В этой папке нечего выбрать"); return; }
        if (selected.containsAll(here)) selected.removeAll(here);
        else selected.addAll(here);
        for (FileRow r : rows.values()) applyRowState(r);
        updateSide();
    }

    private void unselect(File f) {
        selected.remove(f);
        FileRow r = rows.get(f);
        if (r != null) applyRowState(r);
        updateSide();
    }

    /** Правая колонка: список выбранного (в режиме файлов) и кнопки. */
    private void updateSide() {
        if (MODE_FOLDER.equals(mode)) {
            status.setText("Копия будет сохранена в папку «" + (dir.equals(root) ? FileUtils.rootLabel(root) : dir.getName()) + "».");
            return;
        }
        int n = selected.size();
        sideTitle.setText(n > 0 ? "Выбрано: " + n : "Ничего не выбрано");
        sideList.removeAllViews();
        int longCount = 0;
        for (File f : selected) {
            if (tooLong(f)) longCount++;
            sideList.addView(selectedRow(f));
        }
        if (n == 0) {
            sideList.addView(sideText("Отметьте файлы в списке. Выбор сохраняется, даже если перейти в другую папку.", false));
        }

        List<File> here = selectableHere();
        btnSelectAll.setEnabled(!here.isEmpty());
        btnSelectAll.setText(!here.isEmpty() && selected.containsAll(here) ? "Снять выбор в папке" : "Выбрать все в папке");
        btnAction.setEnabled(n > 0);
        btnAction.setText(n > 0 ? "Добавить " + n + " " + FileUtils.plural(n, itemForms[0], itemForms[1], itemForms[2]) : "Добавить");
        status.setText(longCount == 1
                ? "Один звук длиннее 6 секунд: машина оборвёт его на 6-й секунде."
                : "Длиннее 6 секунд: " + longCount + ". Машина оборвёт их на 6-й секунде.");
        status.setVisibility(longCount > 0 ? View.VISIBLE : View.GONE);
    }

    private View selectedRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setTextColor(getColor(tooLong(f) ? R.color.pill_warn_fg : R.color.text_primary));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button remove = Ui.iconButton(this, "✕");
        remove.setTextSize(18);
        remove.setContentDescription("Убрать " + f.getName());
        remove.setOnClickListener(v -> unselect(f));
        row.addView(remove, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 48)));
        return row;
    }

    private TextView sideText(String text, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(bold ? 18 : 16);
        v.setTextColor(getColor(bold ? R.color.text_primary : R.color.text_secondary));
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return v;
    }

    private void finishWithResult() {
        if (MODE_FOLDER.equals(mode)) {
            if (!dir.canWrite()) { toast("В эту папку нельзя записывать, выберите другую"); return; }
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_FOLDER, dir.getAbsolutePath()));
            finish();
            return;
        }
        List<File> longOnes = new ArrayList<>();
        for (File f : selected) if (tooLong(f)) longOnes.add(f);
        if (longOnes.isEmpty()) { returnFiles(false); return; }

        int n = longOnes.size();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(n == 1 ? "Звук длиннее 6 секунд" : "Звуки длиннее 6 секунд")
                .setMessage((n == 1 ? "«" + longOnes.get(0).getName() + "»" : n + " " + FileUtils.plural(n, itemForms[0], itemForms[1], itemForms[2]))
                        + (n == 1 ? " длиннее 6 секунд. Машина начнёт его играть и оборвёт на 6-й секунде."
                                  : " длиннее 6 секунд. Машина начнёт их играть и оборвёт на 6-й секунде."))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Всё равно добавить", (d, w) -> returnFiles(false));
        if (selected.size() > n) {
            b.setNeutralButton(n == 1 ? "Без него" : "Без них", (d, w) -> returnFiles(true));
        }
        b.show();
    }

    private void returnFiles(boolean skipLong) {
        ArrayList<String> paths = new ArrayList<>();
        for (File f : selected) {
            if (skipLong && tooLong(f)) continue;
            if (f.isFile()) paths.add(f.getAbsolutePath());
        }
        if (paths.isEmpty()) return;
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_PATHS, paths));
        finish();
    }

    // ---------------------------------------------------------------- Прослушивание

    @Override public void onPreviewChanged() {
        File current = preview.current();
        for (FileRow r : rows.values()) {
            if (r.play != null) r.play.setText(r.file.equals(current) ? "■" : "▶");
        }
    }

    @Override public void onPreviewError(File file) {
        toast("Не удалось воспроизвести " + file.getName());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
