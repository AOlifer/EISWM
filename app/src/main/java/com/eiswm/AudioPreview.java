package com.eiswm;

import android.media.MediaPlayer;

import java.io.File;

/** Прослушивание одного файла: повторное нажатие на тот же файл останавливает звук. */
final class AudioPreview {
    interface Listener {
        /** Звук начался, закончился или остановлен. */
        void onPreviewChanged();
        void onPreviewError(File file);
    }

    private final Listener listener;
    private MediaPlayer player;
    private File current;
    private boolean prepared;

    AudioPreview(Listener listener) {
        this.listener = listener;
    }

    /** @return файл, который сейчас играет (или готовится), либо null. */
    File current() {
        return current;
    }

    void toggle(File f) {
        if (f.equals(current)) stop();
        else play(f);
    }

    void play(File f) {
        release();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            current = f;
            player.setOnPreparedListener(mp -> {
                prepared = true;
                mp.start();
                listener.onPreviewChanged();
            });
            player.setOnCompletionListener(mp -> stop());
            player.setOnErrorListener((mp, what, extra) -> {
                stop();
                listener.onPreviewError(f);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            release();
            listener.onPreviewError(f);
        }
        listener.onPreviewChanged();
    }

    void stop() {
        boolean wasPlaying = current != null;
        release();
        if (wasPlaying) listener.onPreviewChanged();
    }

    /** @return доля проигранного от 0 до 1. */
    float progress() {
        if (player == null || !prepared) return 0;
        try {
            int duration = player.getDuration();
            return duration > 0 ? Math.min(1f, player.getCurrentPosition() / (float) duration) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Остановить без уведомления (для onDestroy). */
    void release() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        current = null;
        prepared = false;
    }
}
