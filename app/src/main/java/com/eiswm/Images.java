package com.eiswm;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Картинки для экрана машины: размеры, превью, подгонка под экран и запись PNG. */
final class Images {
    /** Размер экрана машины: картинки подгоняются под него. */
    static final int WIDTH = 1920, HEIGHT = 720;

    private Images() {
    }

    /** Размер картинки без её загрузки: {ширина, высота} или null. */
    static int[] size(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 && o.outHeight > 0 ? new int[]{o.outWidth, o.outHeight} : null;
    }

    /** Уменьшенная копия для превью шириной не меньше minWidth пикселей. */
    static Bitmap thumbnail(File f, int minWidth) {
        int[] size = size(f);
        if (size == null) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), thumbnailOptions(size[0], size[1], minWidth));
    }

    /** То же для картинки размером с экран машины из потока (встроенные стандартные картинки). */
    static Bitmap screenThumbnail(InputStream in, int minWidth) {
        return BitmapFactory.decodeStream(in, null, thumbnailOptions(WIDTH, HEIGHT, minWidth));
    }

    private static BitmapFactory.Options thumbnailOptions(int w, int h, int minWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(w, h, minWidth, 1);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return o;
    }

    /**
     * Подогнать картинку под экран 1920×720: масштабировать так, чтобы она заполнила экран,
     * и обрезать лишнее по центру.
     */
    static Bitmap fitToScreen(File src, Resources res) throws IOException {
        int[] size = size(src);
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

    /** Чёрная картинка во весь экран машины. */
    static Bitmap black() {
        Bitmap b = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565);
        b.eraseColor(Color.BLACK);
        return b;
    }

    /** Наибольшая степень двойки, при которой картинка остаётся не меньше нужного размера. */
    private static int sampleSize(int w, int h, int needW, int needH) {
        int s = 1;
        while (w / (s * 2) >= needW && h / (s * 2) >= needH) s *= 2;
        return s;
    }

    /** Запись PNG через временный файл, чтобы лаунчер не увидел недописанную картинку. */
    static void savePng(Bitmap b, File dst, Resources res) throws IOException {
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
