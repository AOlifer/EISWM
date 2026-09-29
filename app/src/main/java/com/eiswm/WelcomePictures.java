package com.eiswm;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Картинки приветствия лаунчера Evolute.
 *
 * Лаунчер показывает при приветствии случайную картинку из папки files/welcome/advice/, но только
 * если она записана в его базе welcome_db (таблица welcome_advice) и сейчас идёт её срок показа
 * (start_time &lt; сейчас &lt; end_time). Без подходящих записей показывается встроенная картинка.
 * Лаунчер работает от того же system uid, что и EISWM, поэтому папку и базу можно менять напрямую.
 *
 * Служба загрузки лаунчера при успешном ответе сервера удаляет записи, которых нет на сервере,
 * поэтому EISWM держит копии своих картинок и восстанавливает их ({@link #restore()}).
 *
 * «Выключить картинки» = отложить все записи в настройки EISWM и оставить одну чёрную картинку:
 * экран приветствия станет тёмным, а при включении записи вернутся.
 *
 * Все методы работают с диском и базой — вызывать из фонового потока.
 */
final class WelcomePictures {
    /** Размер экрана машины: картинки подгоняются под него. */
    static final int WIDTH = 1920, HEIGHT = 720;
    static final String[] EXTENSIONS = {"png", "jpg", "jpeg", "webp", "bmp"};

    private static final String TABLE = "welcome_advice";
    private static final String OWN_PREFIX = "eiswm_";
    private static final String BLACK_ID = "eiswm_off";
    /** Срок показа наших картинок: до 2100 года. */
    private static final long FOREVER = 4102444800000L;
    private static final String PREF_DISABLED = "pictures_disabled";
    private static final String PREF_PARKED = "pictures_parked";
    /** Стандартные картинки (assets/standard): добавленные навсегда и включённые по сезонам. */
    private static final String STANDARD_DIR = "standard";
    private static final String STD_PREFIX = "eiswm_std_";
    private static final String SEASON_PREFIX = "eiswm_season_";
    private static final String PREF_STD = "pictures_std";
    private static final String PREF_SEASONAL = "pictures_seasonal";
    private static final String PREF_FIRST_RUN_DONE = "pictures_first_run_done";
    /** Картинка по умолчанию при первой установке — та же, что лаунчер показывает, когда своих нет. */
    private static final String DEFAULT_PICTURE = "bw_welcome_summer2.png";
    static final String[] SEASONS = {"winter", "spring", "summer", "autumn"};
    private static final int[] SEASON_START_MONTH = {Calendar.DECEMBER, Calendar.MARCH, Calendar.JUNE, Calendar.SEPTEMBER};

    /** Запись из welcome_advice и её файл. */
    static final class Picture {
        String id, title, url, fileName;
        int type, sort;
        long start, end, created;
        File file;
        /** Откуда восстановить файл: наша копия или встроенная стандартная картинка. */
        File backup;
        String asset;

        boolean activeNow() {
            long now = System.currentTimeMillis();
            return start < now && end > now;
        }

        boolean expired() {
            return end <= System.currentTimeMillis();
        }
    }

    private final File adviceDir;
    private final File dbFile;
    private final File backupDir;
    private final SharedPreferences prefs;
    private final AssetManager assets;
    /** Строки ошибок на языке системы. */
    private final Resources res;

    WelcomePictures(Context c) {
        assets = c.getAssets();
        res = c.getResources();
        adviceDir = new File(c.getString(R.string.welcome_picture_dir));
        dbFile = resolveDbFile(c);
        backupDir = new File(c.getFilesDir(), "pictures");
        prefs = c.getSharedPreferences(BaseActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** Путь к базе: из настроек варианта сборки; пустой — своя база (для эмулятора). */
    private static File resolveDbFile(Context c) {
        String[] candidates = c.getResources().getStringArray(R.array.welcome_db_paths);
        if (candidates.length == 0) return c.getDatabasePath("welcome_db");
        for (String p : candidates) {
            if (new File(p).isFile()) return new File(p);
        }
        return new File(candidates[0]);
    }

    File adviceDir() {
        return adviceDir;
    }

    boolean isDisabled() {
        return prefs.getBoolean(PREF_DISABLED, false);
    }

    // ---------------------------------------------------------------- Список

    /**
     * Картинки, которыми управляет пользователь: записи базы (или отложенные, если картинки
     * выключены), у которых есть файл. Чёрная «картинка выключения» в список не входит.
     */
    List<Picture> list() throws IOException {
        List<Picture> all = isDisabled() ? readParked() : readRows();
        List<Picture> result = new ArrayList<>();
        for (Picture p : all) {
            if (BLACK_ID.equals(p.id)) continue;
            p.file = new File(adviceDir, p.fileName);
            if (p.file.isFile()) result.add(p);
        }
        // Сначала свои (новые выше), затем стандартные по сезонам.
        Collections.sort(result, (a, b) -> {
            if (a.created != b.created) return Long.compare(b.created, a.created);
            int sa = seasonIndex(a.id), sb = seasonIndex(b.id);
            return sa != sb ? Integer.compare(sa, sb) : a.id.compareTo(b.id);
        });
        return result;
    }

    // ---------------------------------------------------------------- Добавление и удаление

    /** Подогнать картинку под экран, положить в папку лаунчера, сделать копию и записать в базу. */
    void add(File src) throws IOException {
        Bitmap fitted = fitToScreen(src);
        long now = System.currentTimeMillis();
        String id = OWN_PREFIX + now;
        String name = id + ".png";
        try {
            savePng(fitted, new File(adviceDir, name));
            savePng(fitted, new File(backupDir, name));
        } finally {
            fitted.recycle();
        }

        Picture p = new Picture();
        p.id = id;
        p.title = src.getName();
        p.url = "";
        p.fileName = name;
        p.start = 0;
        p.end = FOREVER;
        p.created = now;
        if (isDisabled()) {
            List<Picture> parked = readParked();
            parked.add(p);
            writeParked(parked);
        } else {
            try (SQLiteDatabase db = openDb()) {
                db.insertWithOnConflict(TABLE, null, values(p), SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
    }

    /** Удалить запись, файл и нашу копию. */
    void delete(Picture p) throws IOException {
        if (isDisabled()) {
            List<Picture> parked = readParked();
            for (int i = parked.size() - 1; i >= 0; i--) {
                if (parked.get(i).id.equals(p.id)) parked.remove(i);
            }
            writeParked(parked);
        } else {
            try (SQLiteDatabase db = openDb()) {
                db.delete(TABLE, "id = ?", new String[]{p.id});
            }
        }
        new File(adviceDir, p.fileName).delete();
        new File(backupDir, p.fileName).delete();
        // Стандартную картинку убираем и из выбора, иначе restore() вернёт её обратно.
        String name = p.id.startsWith(STD_PREFIX) ? p.id.substring(STD_PREFIX.length())
                : p.id.startsWith(SEASON_PREFIX) ? p.id.substring(SEASON_PREFIX.length()) : null;
        if (name != null) {
            String key = p.id.startsWith(STD_PREFIX) ? PREF_STD : PREF_SEASONAL;
            Set<String> set = new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
            set.remove(name + ".png");
            prefs.edit().putStringSet(key, set).commit();
        }
    }

    // ---------------------------------------------------------------- Выключение

    /**
     * Выключить: все записи уходят в настройки EISWM, в базе остаётся одна чёрная картинка.
     * Включить: чёрная картинка удаляется, отложенные записи возвращаются.
     */
    void setDisabled(boolean disabled) throws IOException {
        if (disabled == isDisabled()) return;
        if (disabled) {
            List<Picture> rows = readRows();
            List<Picture> parked = new ArrayList<>();
            for (Picture p : rows) if (!BLACK_ID.equals(p.id)) parked.add(p);
            writeParked(parked);
            prefs.edit().putBoolean(PREF_DISABLED, true).commit();
            try (SQLiteDatabase db = openDb()) {
                db.delete(TABLE, null, null);
            }
            ensureBlack();
        } else {
            List<Picture> parked = readParked();
            try (SQLiteDatabase db = openDb()) {
                db.delete(TABLE, "id = ?", new String[]{BLACK_ID});
                for (Picture p : parked) {
                    if (new File(adviceDir, p.fileName).isFile()) {
                        db.insertWithOnConflict(TABLE, null, values(p), SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }
            }
            new File(adviceDir, BLACK_ID + ".png").delete();
            prefs.edit().putBoolean(PREF_DISABLED, false).remove(PREF_PARKED).commit();
        }
    }

    private void ensureBlack() throws IOException {
        File f = new File(adviceDir, BLACK_ID + ".png");
        if (!f.isFile()) {
            Bitmap black = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565);
            black.eraseColor(Color.BLACK);
            try {
                savePng(black, f);
            } finally {
                black.recycle();
            }
        }
        Picture p = new Picture();
        p.id = BLACK_ID;
        p.title = "EISWM: pictures off";
        p.url = "";
        p.fileName = f.getName();
        p.start = 0;
        p.end = FOREVER;
        p.created = System.currentTimeMillis();
        try (SQLiteDatabase db = openDb()) {
            db.insertWithOnConflict(TABLE, null, values(p), SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    // ---------------------------------------------------------------- Восстановление

    /**
     * Вернуть то, что стёрла служба загрузки лаунчера: наши файлы и записи из копий,
     * а если картинки выключены — чёрную картинку и отложить всё, что появилось в базе заново.
     * Вызывается при запуске EISWM и при старте машины.
     * @return сколько наших картинок пришлось восстановить.
     */
    int restore() throws IOException {
        List<Picture> desired = desiredRecords();
        Set<String> restored = new HashSet<>();
        for (Picture d : desired) {
            if (restoreFile(d)) restored.add(d.id);
        }
        if (isDisabled()) {
            List<Picture> parked = readParked();
            List<Picture> rows = readRows();
            for (Picture r : rows) {
                if (!BLACK_ID.equals(r.id) && !containsId(parked, r.id)) parked.add(r);
            }
            for (Picture d : desired) {
                if (!containsId(parked, d.id)) {
                    parked.add(d);
                } else if (d.id.startsWith(SEASON_PREFIX)) {
                    replaceById(parked, d); // сроки сезона сдвигаются на следующий год
                }
            }
            writeParked(parked);
            if (rows.size() != 1 || !BLACK_ID.equals(rows.get(0).id)) {
                try (SQLiteDatabase db = openDb()) {
                    db.delete(TABLE, "id != ?", new String[]{BLACK_ID});
                }
            }
            ensureBlack();
            return restored.size();
        }
        if (desired.isEmpty()) return 0;
        List<Picture> rows = readRows();
        try (SQLiteDatabase db = openDb()) {
            for (Picture d : desired) {
                boolean missing = !containsId(rows, d.id);
                if (missing) restored.add(d.id);
                if (missing || d.id.startsWith(SEASON_PREFIX)) {
                    db.insertWithOnConflict(TABLE, null, values(d), SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
        }
        return restored.size();
    }

    /**
     * Записи, которые EISWM поддерживает сам: добавленные пользователем (по копиям),
     * стандартные «всегда» и стандартные «по временам года» (сроки — на текущий или ближайший сезон).
     */
    private List<Picture> desiredRecords() {
        List<Picture> result = new ArrayList<>();
        File[] backups = backupDir.listFiles(f -> f.isFile() && f.getName().startsWith(OWN_PREFIX));
        if (backups != null) for (File b : backups) result.add(ownPicture(b));
        for (String name : prefs.getStringSet(PREF_STD, Collections.emptySet())) {
            result.add(standardPicture(name, false));
        }
        for (String name : prefs.getStringSet(PREF_SEASONAL, Collections.emptySet())) {
            result.add(standardPicture(name, true));
        }
        return result;
    }

    /** Вернуть файл картинки в папку лаунчера из нашей копии или из встроенных картинок. */
    private boolean restoreFile(Picture p) {
        File target = new File(adviceDir, p.fileName);
        if (target.isFile()) return false;
        adviceDir.mkdirs();
        if (p.backup != null) return FileUtils.copyFileQuiet(p.backup, target);
        if (p.asset != null) {
            try (InputStream in = assets.open(STANDARD_DIR + "/" + p.asset)) {
                return FileUtils.copyStreamQuiet(in, target);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private static void replaceById(List<Picture> list, Picture p) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(p.id)) {
                list.set(i, p);
                return;
            }
        }
        list.add(p);
    }

    // ---------------------------------------------------------------- Стандартные картинки

    /** Имена встроенных стандартных картинок (bw_welcome_winter1.png …), по сезонам. */
    List<String> standardNames() {
        List<String> result = new ArrayList<>();
        try {
            String[] names = assets.list(STANDARD_DIR);
            if (names != null) {
                for (String n : names) if (n.endsWith(".png")) result.add(n);
            }
        } catch (IOException ignored) {
        }
        Collections.sort(result, (a, b) -> {
            int sa = seasonIndex(a), sb = seasonIndex(b);
            return sa != sb ? Integer.compare(sa, sb) : a.compareTo(b);
        });
        return result;
    }

    Bitmap standardThumbnail(String name, int minWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(WIDTH, HEIGHT, minWidth, 1);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = assets.open(STANDARD_DIR + "/" + name)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    /** Имена стандартных картинок, добавленных навсегда. */
    Set<String> standardAdded() {
        return new HashSet<>(prefs.getStringSet(PREF_STD, Collections.emptySet()));
    }

    /**
     * Первый запуск после установки: если в базе лаунчера нет ни одной записи и в папке картинок
     * нет ни одного файла, ставим стандартную картинку по умолчанию на круглый год, чтобы она
     * была видна и управлялась в приложении. Проверка выполняется один раз.
     * @return true, если картинка по умолчанию добавлена.
     */
    boolean installDefaultOnFirstRun() throws IOException {
        if (prefs.getBoolean(PREF_FIRST_RUN_DONE, false)) return false;
        File[] files = adviceDir.listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        boolean empty = readRows().isEmpty() && (files == null || files.length == 0);
        if (empty && standardNames().contains(DEFAULT_PICTURE)) {
            addStandard(Collections.singletonList(DEFAULT_PICTURE));
        }
        // Флаг ставим только после успешной проверки: если база была недоступна, повторим в следующий раз.
        prefs.edit().putBoolean(PREF_FIRST_RUN_DONE, true).commit();
        return empty;
    }

    boolean isSeasonal() {
        return !prefs.getStringSet(PREF_SEASONAL, Collections.emptySet()).isEmpty();
    }

    /** Добавить стандартные картинки: показываются круглый год. */
    void addStandard(List<String> names) throws IOException {
        Set<String> set = standardAdded();
        set.addAll(names);
        if (!prefs.edit().putStringSet(PREF_STD, set).commit()) throw new IOException(res.getString(R.string.pictures_prefs_failed));
        restore();
    }

    /**
     * Все стандартные картинки по временам года: зимой машина выбирает из четырёх зимних,
     * весной — из весенних и так далее. Сроки обновляются при запуске и при старте машины.
     */
    void setSeasonal(boolean on) throws IOException {
        if (on) {
            Set<String> all = new HashSet<>(standardNames());
            if (!prefs.edit().putStringSet(PREF_SEASONAL, all).commit()) throw new IOException(res.getString(R.string.pictures_prefs_failed));
            restore();
            return;
        }
        Set<String> names = prefs.getStringSet(PREF_SEASONAL, Collections.emptySet());
        List<String> ids = new ArrayList<>();
        for (String n : names) ids.add(SEASON_PREFIX + baseName(n));
        prefs.edit().remove(PREF_SEASONAL).commit();
        if (isDisabled()) {
            List<Picture> parked = readParked();
            for (int i = parked.size() - 1; i >= 0; i--) {
                if (parked.get(i).id.startsWith(SEASON_PREFIX)) parked.remove(i);
            }
            writeParked(parked);
        } else {
            try (SQLiteDatabase db = openDb()) {
                for (String id : ids) db.delete(TABLE, "id = ?", new String[]{id});
            }
        }
        for (String id : ids) new File(adviceDir, id + ".png").delete();
    }

    private Picture standardPicture(String name, boolean seasonal) {
        Picture p = new Picture();
        p.id = (seasonal ? SEASON_PREFIX : STD_PREFIX) + baseName(name);
        p.fileName = p.id + ".png";
        p.title = name;
        p.url = "";
        p.asset = name;
        p.created = 0;
        if (seasonal) {
            long[] w = seasonWindow(seasonIndex(name), System.currentTimeMillis());
            p.start = w[0];
            p.end = w[1];
        } else {
            p.start = 0;
            p.end = FOREVER;
        }
        return p;
    }

    private static String baseName(String name) {
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    /** 0 — зима, 1 — весна, 2 — лето, 3 — осень, 4 — не понятно. */
    static int seasonIndex(String name) {
        for (int i = 0; i < SEASONS.length; i++) if (name.contains(SEASONS[i])) return i;
        return SEASONS.length;
    }

    /** Стандартная картинка из assets (навсегда или по сезонам). */
    static boolean isStandard(Picture p) {
        return p.id.startsWith(STD_PREFIX) || p.id.startsWith(SEASON_PREFIX);
    }

    /** Сезон записи «по временам года» (индекс как у {@link #seasonIndex}) или -1. */
    static int seasonOf(Picture p) {
        return p.id.startsWith(SEASON_PREFIX) ? seasonIndex(p.id) : -1;
    }

    /**
     * Срок сезона, который идёт сейчас или наступит следующим: зима — декабрь…февраль,
     * весна — март…май, лето — июнь…август, осень — сентябрь…ноябрь (по местному времени).
     */
    static long[] seasonWindow(int season, long now) {
        int startMonth = SEASON_START_MONTH[Math.min(season, SEASON_START_MONTH.length - 1)];
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int year = c.get(Calendar.YEAR);
        for (int y = year - 1; y <= year + 1; y++) {
            c.clear();
            c.set(y, startMonth, 1, 0, 0, 0);
            long start = c.getTimeInMillis();
            c.add(Calendar.MONTH, 3);
            long end = c.getTimeInMillis();
            if (end > now) return new long[]{start, end};
        }
        return new long[]{0, FOREVER};
    }

    private static Picture ownPicture(File backup) {
        Picture p = new Picture();
        p.fileName = backup.getName();
        p.id = p.fileName.substring(0, p.fileName.length() - 4);
        p.title = "EISWM";
        p.url = "";
        p.start = 0;
        p.end = FOREVER;
        p.backup = backup;
        try {
            p.created = Long.parseLong(p.id.substring(OWN_PREFIX.length()));
        } catch (NumberFormatException e) {
            p.created = backup.lastModified();
        }
        return p;
    }

    private static boolean containsId(List<Picture> list, String id) {
        for (Picture p : list) if (p.id.equals(id)) return true;
        return false;
    }

    // ---------------------------------------------------------------- База лаунчера

    private SQLiteDatabase openDb() throws IOException {
        File dir = dbFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException(res.getString(R.string.pictures_no_dir_access, dir));
        }
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
            // Та же схема, что создаёт лаунчер (BwDbHelper), — на случай, если базы ещё нет.
            db.execSQL("create table if not exists welcome_advice(id text primary key,title text,url text,"
                    + "type integer,sort integer,start_time integer,end_time integer,create_time integer,file_name text)");
            return db;
        } catch (RuntimeException e) {
            throw new IOException(res.getString(R.string.pictures_db_failed, dbFile + ": " + e.getMessage()), e);
        }
    }

    private List<Picture> readRows() throws IOException {
        List<Picture> result = new ArrayList<>();
        try (SQLiteDatabase db = openDb();
             Cursor c = db.query(TABLE, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                Picture p = new Picture();
                p.id = str(c, "id");
                p.title = str(c, "title");
                p.url = str(c, "url");
                p.type = c.getInt(c.getColumnIndexOrThrow("type"));
                p.sort = c.getInt(c.getColumnIndexOrThrow("sort"));
                p.start = c.getLong(c.getColumnIndexOrThrow("start_time"));
                p.end = c.getLong(c.getColumnIndexOrThrow("end_time"));
                p.created = c.getLong(c.getColumnIndexOrThrow("create_time"));
                p.fileName = str(c, "file_name");
                if (p.id != null && p.fileName != null) result.add(p);
            }
        }
        return result;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndexOrThrow(column);
        return c.isNull(i) ? null : c.getString(i);
    }

    private static ContentValues values(Picture p) {
        ContentValues v = new ContentValues();
        v.put("id", p.id);
        v.put("title", p.title);
        v.put("url", p.url);
        v.put("type", p.type);
        v.put("sort", p.sort);
        v.put("start_time", p.start);
        v.put("end_time", p.end);
        v.put("create_time", p.created);
        v.put("file_name", p.fileName);
        return v;
    }

    // ---------------------------------------------------------------- Отложенные записи

    private List<Picture> readParked() {
        List<Picture> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_PARKED, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Picture p = new Picture();
                p.id = o.getString("id");
                p.title = o.optString("title", "");
                p.url = o.optString("url", "");
                p.type = o.optInt("type");
                p.sort = o.optInt("sort");
                p.start = o.optLong("start");
                p.end = o.optLong("end");
                p.created = o.optLong("created");
                p.fileName = o.getString("file");
                result.add(p);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void writeParked(List<Picture> list) throws IOException {
        JSONArray arr = new JSONArray();
        try {
            for (Picture p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("title", p.title);
                o.put("url", p.url);
                o.put("type", p.type);
                o.put("sort", p.sort);
                o.put("start", p.start);
                o.put("end", p.end);
                o.put("created", p.created);
                o.put("file", p.fileName);
                arr.put(o);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (!prefs.edit().putString(PREF_PARKED, arr.toString()).commit()) {
            throw new IOException(res.getString(R.string.pictures_prefs_failed));
        }
    }

    // ---------------------------------------------------------------- Изображения

    /** Размер картинки без её загрузки: {ширина, высота} или null. */
    static int[] imageSize(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 && o.outHeight > 0 ? new int[]{o.outWidth, o.outHeight} : null;
    }

    /** Уменьшенная копия для превью шириной не меньше minWidth пикселей. */
    static Bitmap thumbnail(File f, int minWidth) {
        int[] size = imageSize(f);
        if (size == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(size[0], size[1], minWidth, 1);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    /**
     * Подогнать картинку под экран 1920×720: масштабировать так, чтобы она заполнила экран,
     * и обрезать лишнее по центру.
     */
    Bitmap fitToScreen(File src) throws IOException {
        int[] size = imageSize(src);
        if (size == null) throw new IOException(res.getString(R.string.pictures_read_failed, src.getName()));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(size[0], size[1], WIDTH, HEIGHT);
        Bitmap in = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (in == null) throw new IOException(res.getString(R.string.pictures_read_failed, src.getName()));
        try {
            float scale = Math.max(WIDTH / (float) in.getWidth(), HEIGHT / (float) in.getHeight());
            int cropW = Math.round(WIDTH / scale), cropH = Math.round(HEIGHT / scale);
            int left = (in.getWidth() - cropW) / 2, top = (in.getHeight() - cropH) / 2;
            Bitmap out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(in, new Rect(left, top, left + cropW, top + cropH),
                    new Rect(0, 0, WIDTH, HEIGHT), new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        } finally {
            in.recycle();
        }
    }

    /** Наибольшая степень двойки, при которой картинка остаётся не меньше нужного размера. */
    private static int sampleSize(int w, int h, int needW, int needH) {
        int s = 1;
        while (w / (s * 2) >= needW && h / (s * 2) >= needH) s *= 2;
        return s;
    }

    /** Запись PNG через временный файл, чтобы лаунчер не увидел недописанную картинку. */
    private void savePng(Bitmap b, File dst) throws IOException {
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException(res.getString(R.string.pictures_no_dir_access, dir));
        File tmp = new File(dir, "." + dst.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException(res.getString(R.string.pictures_write_failed, dst.getName()));
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(dst) && !(dst.delete() && tmp.renameTo(dst))) {
            tmp.delete();
            throw new IOException(res.getString(R.string.pictures_write_failed, dst));
        }
    }
}
