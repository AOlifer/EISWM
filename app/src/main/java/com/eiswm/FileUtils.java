package com.eiswm;

import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Файловые операции и форматирование, общие для главного экрана и экрана выбора файлов. */
final class FileUtils {
    static final File INTERNAL_ROOT = new File("/storage/emulated/0");

    private FileUtils() {}

    static boolean hasExtension(File f, String[] extensions) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (name.endsWith("." + ext)) return true;
        }
        return false;
    }

    static void sortByName(File[] files) {
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
    }

    /** Память устройства и подключённые внешние накопители (USB, SD). */
    static List<File> storageRoots() {
        List<File> roots = new ArrayList<>();
        roots.add(INTERNAL_ROOT);
        File[] external = new File("/storage").listFiles(f -> f.isDirectory()
                && !f.getName().equals("emulated") && !f.getName().equals("self") && f.canRead());
        if (external != null) {
            sortByName(external);
            roots.addAll(Arrays.asList(external));
        }
        return roots;
    }

    static String rootLabel(File root) {
        return root.equals(INTERNAL_ROOT) ? "Память" : "USB " + root.getName();
    }

    /** @return накопитель, внутри которого лежит dir, или null. */
    static File findRoot(File dir, List<File> roots) {
        String path = dir.getAbsolutePath();
        for (File root : roots) {
            String rootPath = root.getAbsolutePath();
            if (path.equals(rootPath) || path.startsWith(rootPath + "/")) return root;
        }
        return null;
    }

    /**
     * Копирование через временный файл: при ошибке существующий файл не портится,
     * а лаунчер не увидит недописанный MP3 (временное имя не оканчивается на .mp3).
     */
    static boolean copyFileQuiet(File src, File dst) {
        File tmp = new File(dst.getParentFile(), "." + dst.getName() + ".tmp");
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
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

    /** @return длительность в мс или -1, если прочитать не удалось. */
    static long getDurationMs(File f) {
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

    static String formatSize(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1048576) return String.format("%.1f КБ", b / 1024.0);
        return String.format("%.1f МБ", b / 1048576.0);
    }

    /** Короткие звуки — в секундах с десятыми («4,8 с»), длинные — в минутах («3:45»). */
    static String formatDuration(long ms) {
        if (ms < 0) return "?";
        if (ms < 60000) return String.format("%.1f с", ms / 1000.0);
        long totalSec = Math.round(ms / 1000.0);
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** Русское множественное число: plural(2, "звук", "звука", "звуков") → «звука». */
    static String plural(int n, String one, String few, String many) {
        int mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }
}
