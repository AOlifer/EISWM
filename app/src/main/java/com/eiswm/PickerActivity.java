package com.eiswm;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
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
 * Экран общий для всех видов файлов: что показывать и как проверять, задаётся через Intent.
 * Сейчас используется для звуков; для картинок приветствия достаточно передать свои расширения.
 */
public class PickerActivity extends Activity implements AudioPreview.Listener {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_TITLE = "title";
    /** Подпись основной кнопки в режиме выбора папки. */
    static final String EXTRA_ACTION = "action";
    /** Расширения без точки, например {"mp3"}. */
    static final String EXTRA_EXTENSIONS = "extensions";
    /** Максимальная длительность звука; 0 — не проверять. */
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

    private List<File> roots;
    private File root, dir;
    /** Выбор сохраняется при переходе между папками. */
    private final TreeSet<File> selected = new TreeSet<>();
    /** Длительность уже проверенных файлов; -1 — не удалось прочитать. */
    private final Map<File, Long> durations = new HashMap<>();
    private final Map<File, FileRow> rows = new LinkedHashMap<>();

    private LinearLayout rootsBar, crumbs, list;
    private HorizontalScrollView crumbsScroll;
    private TextView status;
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
        TextView name;
        TextView pill;
        Button play;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_picker);
        prefs = getSharedPreferences("eiswm", MODE_PRIVATE);
        preview = new AudioPreview(this);

        Intent in = getIntent();
        mode = MODE_FOLDER.equals(in.getStringExtra(EXTRA_MODE)) ? MODE_FOLDER : MODE_FILES;
        extensions = in.getStringArrayExtra(EXTRA_EXTENSIONS);
        if (extensions == null) extensions = new String[0];
        maxDurationMs = in.getLongExtra(EXTRA_MAX_DURATION_MS, 0);
        itemForms = in.getStringArrayExtra(EXTRA_ITEM_FORMS);
        if (itemForms == null || itemForms.length != 3) itemForms = new String[]{"файл", "файла", "файлов"};
        audio = Arrays.asList(extensions).contains("mp3");

        ((TextView) findViewById(R.id.pickerTitle)).setText(in.getStringExtra(EXTRA_TITLE));
        rootsBar = findViewById(R.id.rootsBar);
        crumbs = findViewById(R.id.crumbs);
        crumbsScroll = findViewById(R.id.crumbsScroll);
        list = findViewById(R.id.pickerList);
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
        return "picker_last_dir_" + mode;
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
            TextView chip = new TextView(this, null, 0, R.style.EISWM_Tab);
            chip.setText(FileUtils.rootLabel(r));
            Ui.setTabSelected(chip, r.equals(root));
            chip.setOnClickListener(v -> {
                root = r;
                open(r);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
            lp.setMarginEnd(Ui.dp(this, 8));
            rootsBar.addView(chip, lp);
        }
        if (roots.size() == 1) {
            TextView hint = new TextView(this);
            hint.setText("Флешка не найдена");
            hint.setTextSize(16);
            hint.setTextColor(getColor(R.color.text_disabled));
            hint.setPadding(Ui.dp(this, 8), 0, 0, 0);
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
            part.setGravity(android.view.Gravity.CENTER_VERTICAL);
            part.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            if (last) {
                part.setTextColor(getColor(R.color.text_primary));
                part.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                part.setTextColor(getColor(R.color.accent));
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
            updateBottom();
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
        updateBottom();
        readDurations(files, gen);
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
        TextView icon = new TextView(this);
        icon.setText("📁");
        icon.setTextSize(24);
        icon.setGravity(android.view.Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), -2));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1);
        nameLp.setMarginStart(Ui.dp(this, 8));
        row.addView(Ui.title(this, d.getName()), nameLp);
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(28);
        chevron.setTextColor(getColor(R.color.text_disabled));
        chevron.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        row.addView(chevron);
        row.setBackgroundResource(selectableBackground());
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
        }

        r.name = Ui.title(this, f.getName());
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1);
        nameLp.setMarginStart(Ui.dp(this, 8));
        nameLp.setMarginEnd(Ui.dp(this, 16));
        row.addView(r.name, nameLp);

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

    private int selectableBackground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ---------------------------------------------------------------- Проверка файлов

    private boolean needsDuration() {
        return audio && maxDurationMs > 0;
    }

    /** null — файл ещё проверяется, true/false — можно ли его выбрать. */
    private Boolean selectable(File f) {
        if (!needsDuration()) return true;
        Long ms = durations.get(f);
        if (ms == null) return null;
        return ms <= maxDurationMs; // неизвестная длительность (-1) допускается с предупреждением
    }

    private void applyRowState(FileRow r) {
        Boolean ok = selectable(r.file);
        if (ok != null && !ok) selected.remove(r.file);
        boolean isSelected = selected.contains(r.file);
        r.check.setChecked(isSelected);
        r.check.setEnabled(ok != null && ok);
        r.name.setTextColor(getColor(ok != null && !ok ? R.color.text_disabled : R.color.text_primary));
        r.root.setBackgroundColor(isSelected ? Ui.ROW_PLAYING : Color.TRANSPARENT);

        if (!needsDuration()) {
            Ui.setPill(r.pill, FileUtils.formatSize(r.file.length()), Ui.PILL_NEUTRAL);
            return;
        }
        Long ms = durations.get(r.file);
        if (ms == null) Ui.setPill(r.pill, "проверка…", Ui.PILL_NEUTRAL);
        else if (ms < 0) Ui.setPill(r.pill, "длительность неизвестна", Ui.PILL_WARN);
        else if (ms > maxDurationMs) Ui.setPill(r.pill, FileUtils.formatDuration(ms) + ", слишком длинный", Ui.PILL_BAD);
        else Ui.setPill(r.pill, FileUtils.formatDuration(ms), Ui.PILL_OK);
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
                    updateBottom();
                });
            }
        });
    }

    // ---------------------------------------------------------------- Выбор

    private void onFileRowClick(FileRow r) {
        Boolean ok = selectable(r.file);
        if (ok == null) { toast("Файл ещё проверяется"); return; }
        if (!ok) {
            toast("Файл длиннее " + (maxDurationMs / 1000) + " секунд, машина его не проиграет");
            return;
        }
        if (!selected.remove(r.file)) selected.add(r.file);
        applyRowState(r);
        updateBottom();
    }

    /** Файлы текущей папки, которые можно выбрать. */
    private List<File> selectableHere() {
        List<File> result = new ArrayList<>();
        for (File f : rows.keySet()) {
            Boolean ok = selectable(f);
            if (ok != null && ok) result.add(f);
        }
        return result;
    }

    private void toggleSelectAll() {
        List<File> here = selectableHere();
        if (here.isEmpty()) { toast("В этой папке нечего выбрать"); return; }
        if (selected.containsAll(here)) selected.removeAll(here);
        else selected.addAll(here);
        for (FileRow r : rows.values()) applyRowState(r);
        updateBottom();
    }

    private void updateBottom() {
        if (MODE_FOLDER.equals(mode)) {
            status.setText("Файл будет сохранён в открытую папку.");
            return;
        }
        int n = selected.size();
        List<File> here = selectableHere();
        btnSelectAll.setEnabled(!here.isEmpty());
        btnSelectAll.setText(!here.isEmpty() && selected.containsAll(here) ? "Снять выбор" : "Выбрать все подходящие");
        btnAction.setEnabled(n > 0);
        btnAction.setText(n > 0 ? "Добавить " + n + " " + FileUtils.plural(n, itemForms[0], itemForms[1], itemForms[2]) : "Добавить");
        if (n > 0) status.setText("Выбрано: " + n);
        else if (needsDuration()) status.setText("Отметьте файлы. Подходят MP3 не длиннее " + (maxDurationMs / 1000) + " секунд.");
        else status.setText("Отметьте нужные файлы.");
    }

    private void finishWithResult() {
        Intent data = new Intent();
        if (MODE_FOLDER.equals(mode)) {
            if (!dir.canWrite()) { toast("В эту папку нельзя записывать, выберите другую"); return; }
            data.putExtra(EXTRA_FOLDER, dir.getAbsolutePath());
        } else {
            ArrayList<String> paths = new ArrayList<>();
            for (File f : selected) if (f.isFile()) paths.add(f.getAbsolutePath());
            if (paths.isEmpty()) return;
            data.putStringArrayListExtra(EXTRA_PATHS, paths);
        }
        setResult(RESULT_OK, data);
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
