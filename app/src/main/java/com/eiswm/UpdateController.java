package com.eiswm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Обновления на стартовом экране: строка «Проверить обновления» / «Доступна версия N»,
 * проверка при запуске, окно с новой версией, загрузка и установка. Сеть — {@link Updater}.
 */
final class UpdateController {
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Updater updater;
    private final TextView homeUpdate;
    private Updater.Release availableRelease;
    private boolean checkingUpdates = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    UpdateController(Activity activity, SharedPreferences prefs, TextView homeUpdate) {
        this.activity = activity;
        this.prefs = prefs;
        this.homeUpdate = homeUpdate;
        updater = new Updater(activity);
        homeUpdate.setOnClickListener(v -> {
            if (availableRelease != null) showUpdateDialog(availableRelease);
            else checkForUpdates(true);
        });
        // Найденная раньше версия видна сразу, даже если сегодня проверки ещё не было.
        String known = prefs.getString(Prefs.UPDATE_NAME, null);
        if (known != null && prefs.getInt(Prefs.UPDATE_CODE, 0) > updater.currentVersionCode()) {
            homeUpdate.setText(activity.getString(R.string.update_available, known));
        }
    }

    void destroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
    }

    /** Проверка при запуске: не чаще раза в сутки, без сообщений при ошибке. */
    void autoCheckForUpdates() {
        long last = prefs.getLong(Prefs.UPDATE_LAST_CHECK, 0);
        long now = System.currentTimeMillis();
        if (now - last >= Updater.AUTO_CHECK_INTERVAL_MS || now < last) checkForUpdates(false);
    }

    /** @param manual нажата «Проверить обновления»: показать результат и окно с новой версией. */
    private void checkForUpdates(boolean manual) {
        if (checkingUpdates) return;
        checkingUpdates = true;
        if (manual) homeUpdate.setText(R.string.update_checking);
        new Thread(() -> {
            Updater.Release r = null;
            try {
                r = updater.fetchLatest();
            } catch (Exception ignored) {
            }
            final Updater.Release result = r;
            ui.post(() -> onUpdateChecked(result, manual));
        }, "eiswm-update-check").start();
    }

    private void onUpdateChecked(Updater.Release r, boolean manual) {
        checkingUpdates = false;
        if (destroyed) return;
        if (r == null) {
            if (manual) homeUpdate.setText(R.string.update_failed);
            return;
        }
        prefs.edit().putLong(Prefs.UPDATE_LAST_CHECK, System.currentTimeMillis()).apply();
        if (r.versionCode > updater.currentVersionCode()) {
            availableRelease = r;
            prefs.edit().putInt(Prefs.UPDATE_CODE, r.versionCode).putString(Prefs.UPDATE_NAME, r.versionName).apply();
            homeUpdate.setText(activity.getString(R.string.update_available, r.versionName));
            if (manual) showUpdateDialog(r);
        } else {
            availableRelease = null;
            prefs.edit().remove(Prefs.UPDATE_CODE).remove(Prefs.UPDATE_NAME).apply();
            homeUpdate.setText(manual ? R.string.update_latest : R.string.update_check);
        }
    }

    /** Что нового в версии и кнопка «Скачать и установить». */
    private void showUpdateDialog(Updater.Release r) {
        StringBuilder msg = new StringBuilder();
        if (!r.changes.isEmpty()) msg.append(r.changes.trim()).append("\n\n");
        if (!r.date.isEmpty()) msg.append(activity.getString(R.string.update_released, r.date)).append(' ');
        if (r.size > 0) msg.append(activity.getString(R.string.update_size, FileUtils.formatSize(activity, r.size)));
        boolean install = updater.canInstall();
        if (!install) msg.append("\n\n").append(activity.getString(R.string.update_emulator_note));
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_title, r.versionName))
                .setMessage(msg.toString().trim())
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(install ? R.string.update_install : R.string.update_download,
                        (d, w) -> downloadUpdate(r))
                .show();
    }

    /** Загрузка с полоской прогресса; затем установка (на машине) или сообщение (на эмуляторе). */
    private void downloadUpdate(Updater.Release r) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(r.size <= 0);
        bar.setMax(1000);
        TextView text = new TextView(activity);
        text.setTextColor(activity.getColor(R.color.text_secondary));
        text.setTextSize(16);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar);
        box.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled.set(true))
                .show();
        final long[] lastUi = {0};
        new Thread(() -> {
            File apk = null;
            String error = null;
            try {
                apk = updater.download(r, new Updater.Progress() {
                    @Override public void onProgress(long done, long total) {
                        long now = System.currentTimeMillis();
                        if (now - lastUi[0] < 200) return;
                        lastUi[0] = now;
                        ui.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) (done * 1000 / total));
                                text.setText(FileUtils.formatSize(activity, done) + " / "
                                        + FileUtils.formatSize(activity, total));
                            } else {
                                text.setText(FileUtils.formatSize(activity, done));
                            }
                        });
                    }

                    @Override public boolean cancelled() {
                        return cancelled.get() || destroyed;
                    }
                });
                if (apk != null && updater.canInstall()) {
                    prefs.edit().putBoolean(Prefs.UPDATE_REOPEN, true).commit();
                    updater.install(apk);
                }
            } catch (Exception e) {
                prefs.edit().remove(Prefs.UPDATE_REOPEN).apply();
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final File done = apk;
            final String err = error;
            ui.post(() -> {
                if (destroyed) return;
                dialog.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(activity)
                            .setMessage(activity.getString(R.string.update_error, err))
                            .setPositiveButton(R.string.got_it, null)
                            .show();
                } else if (done != null) {
                    Toast.makeText(activity, updater.canInstall() ? activity.getString(R.string.update_installing)
                            : activity.getString(R.string.update_emulator_done, done.getPath()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "eiswm-update-download").start();
    }
}
