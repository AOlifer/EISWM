package com.eiswm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/**
 * Прощание при выключении зажигания: случайный звук из своего набора и картинка во весь экран.
 * Файлы лежат в files/farewell/ приложения. Звук играет так же, как приветствие лаунчера —
 * в канале машины WARNING, который слышен и после выключения зажигания (режим AVOFF).
 * Срабатывание отслеживает {@link CarEventsService}; кнопка «Проверить» вызывает {@link #play}.
 */
final class Farewell {
    static final String[] SOUND_EXTENSIONS = {"mp3"};
    /** Картинка видна не меньше этого времени и не дольше {@link #MAX_SHOW_MS}. */
    private static final long MIN_SHOW_MS = 5000, MAX_SHOW_MS = 15000;
    /** Атрибуты звука приветствия лаунчера: usage 15 и канал машины WARNING. */
    private static final int CAR_WARNING_USAGE = 15;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private View overlay;
    /** Длительность звука последнего прощания, мс (0 — без звука). */
    private long soundMs;

    Farewell(Context context) {
        this.context = context.getApplicationContext();
    }

    static File soundDir(Context c) {
        return new File(c.getFilesDir(), "farewell/sounds");
    }

    static File pictureDir(Context c) {
        return new File(c.getFilesDir(), "farewell/pictures");
    }

    static boolean isSoundEnabled(Context c) {
        return Prefs.get(c).getBoolean(Prefs.FAREWELL_SOUND, false);
    }

    static boolean isPictureEnabled(Context c) {
        return Prefs.get(c).getBoolean(Prefs.FAREWELL_PICTURE, false);
    }

    /** Включено ли хоть что-то: тогда нужна служба {@link CarEventsService}. */
    static boolean isEnabled(Context c) {
        return Features.FAREWELL && (isSoundEnabled(c) || isPictureEnabled(c));
    }

    static File[] sounds(Context c) {
        return list(soundDir(c), SOUND_EXTENSIONS);
    }

    static File[] pictures(Context c) {
        return list(pictureDir(c), new String[]{"png"});
    }

    private static File[] list(File dir, String[] extensions) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] f = dir.listFiles(x -> x.isFile() && !x.getName().startsWith(".")
                && FileUtils.hasExtension(x, extensions));
        if (f == null) return new File[0];
        FileUtils.sortByName(f);
        return f;
    }

    /** Добавить картинку: подгоняется под экран машины и сохраняется в PNG. */
    static File addPicture(Context c, File src) throws IOException {
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        File dst = new File(pictureDir(c), (dot > 0 ? name.substring(0, dot) : name) + ".png");
        Bitmap fitted = Images.fitToScreen(src, c.getResources());
        try {
            Images.savePng(fitted, dst, c.getResources());
        } finally {
            fitted.recycle();
        }
        return dst;
    }

    /**
     * Попрощаться: случайный звук и случайная картинка из наборов (чего нет — пропускается).
     * Вызывать из главного потока.
     * @param withSound играть звук
     * @param withPicture показывать картинку
     * @return false, если играть и показывать нечего
     */
    boolean play(boolean withSound, boolean withPicture) {
        stop();
        File[] sounds = withSound ? sounds(context) : new File[0];
        File[] pictures = withPicture ? pictures(context) : new File[0];
        if (sounds.length == 0 && pictures.length == 0) return false;
        long showMs = MIN_SHOW_MS;
        soundMs = 0;
        if (sounds.length > 0) {
            File s = sounds[random.nextInt(sounds.length)];
            soundMs = Math.max(0, FileUtils.getDurationMs(s));
            showMs = Math.max(showMs, soundMs);
            playSound(s);
        }
        if (pictures.length > 0) showPicture(pictures[random.nextInt(pictures.length)], Math.min(showMs, MAX_SHOW_MS));
        return true;
    }

    /** Сколько длится звук последнего прощания, мс: после него можно говорить. */
    long soundMs() {
        return soundMs;
    }

    /** Прервать прощание (например, зажигание снова включили). */
    void stop() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        AudioManager am = context.getSystemService(AudioManager.class);
        if (focus != null && am != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(focus);
        focus = null;
        hidePicture();
    }

    // ---------------------------------------------------------------- Звук

    private void playSound(File f) {
        AudioAttributes attrs = carWarningAttributes();
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am != null && Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs).build();
            am.requestAudioFocus(focus);
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> releaseSound());
            player.setOnErrorListener((mp, what, extra) -> {
                releaseSound();
                return true;
            });
            player.setOnPreparedListener(MediaPlayer::start);
            player.prepareAsync();
        } catch (Exception e) {
            releaseSound();
        }
    }

    private void releaseSound() {
        if (player != null) {
            player.release();
            player = null;
        }
        AudioManager am = context.getSystemService(AudioManager.class);
        if (focus != null && am != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(focus);
        focus = null;
    }

    /**
     * Атрибуты звука приветствия лаунчера: usage 15 (скрытый USAGE_VIRTUAL_SOURCE) и канал
     * машины в пакете «bw.audio.source.NAME» через скрытый AudioAttributes.Builder.addBundle.
     * Вне машины (нет bw.car.proxy, эмулятор) звук играет как обычное уведомление.
     */
    @SuppressLint("WrongConstant")
    static AudioAttributes carWarningAttributes() {
        AudioAttributes.Builder b = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION);
        if (!CarApi.isAvailable()) return b.build();
        try {
            Bundle bundle = new Bundle();
            bundle.putString("bw.audio.source.NAME", "WARNING");
            AudioAttributes.Builder.class.getMethod("addBundle", Bundle.class).invoke(b, bundle);
            b.setUsage(CAR_WARNING_USAGE);
        } catch (Throwable ignored) {
            // Без пакета канала остаётся обычное уведомление.
        }
        return b.build();
    }

    // ---------------------------------------------------------------- Картинка

    /** Картинка во весь экран поверх всего ({@link Overlay}); нажатие закрывает её раньше. */
    private void showPicture(File f, long showMs) {
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) return;
        ImageView v = new ImageView(context);
        v.setImageBitmap(bmp);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundColor(Color.BLACK);
        v.setOnClickListener(x -> hidePicture());
        if (Overlay.show(context, v, Gravity.CENTER, WindowManager.LayoutParams.MATCH_PARENT)) {
            overlay = v;
            ui.postDelayed(this::hidePicture, showMs);
        }
    }

    private void hidePicture() {
        Overlay.hide(context, overlay);
        overlay = null;
    }
}
