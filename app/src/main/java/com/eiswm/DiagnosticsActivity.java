package com.eiswm;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Диагностика машины (скрытый экран: долгое нажатие на версию на стартовом экране).
 * Снимок свойств машины, запись событий в фоне ({@link CarDiagService}), отметки в журнале
 * и сохранение файлов на флешку. В машину ничего не пишет.
 */
public class DiagnosticsActivity extends BaseActivity {
    private static final int REQUEST_SAVE = 1;
    private static final long REFRESH_MS = 2000;
    private static final int TAIL_BYTES = 48 * 1024;

    private SharedPreferences prefs;
    private TextView status, log, files;
    private ScrollView scroll;
    private Button btnRecord, btnSnapshot, btnSave, btnClear;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler io;
    private CarApi car;
    private boolean busy = false;
    private boolean destroyed = false;
    private int marks = 0;
    /** Одна ссылка на метод: removeCallbacks находит задачу только по тому же объекту. */
    private final Runnable refreshTask = this::refresh;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_diagnostics);
        prefs = Prefs.get(this);
        status = findViewById(R.id.diagStatus);
        log = findViewById(R.id.diagLog);
        files = findViewById(R.id.diagFiles);
        scroll = findViewById(R.id.diagScroll);
        btnRecord = findViewById(R.id.btnRecord);
        btnSnapshot = findViewById(R.id.btnSnapshot);
        btnSave = findViewById(R.id.btnSave);
        btnClear = findViewById(R.id.btnClearLog);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.btnMark).setOnClickListener(v -> mark());
        btnSnapshot.setOnClickListener(v -> snapshot());
        btnSave.setOnClickListener(v -> openSaveFolderPicker());
        btnClear.setOnClickListener(v -> clearLog());

        thread = new HandlerThread("eiswm-diag-ui");
        thread.start();
        io = new Handler(thread.getLooper());
        io.post(this::connectCar);
        updateUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        io.post(() -> {
            if (car != null) car.disconnect();
            thread.quitSafely();
        });
        super.onDestroy();
    }

    private void connectCar() {
        if (!CarApi.isAvailable()) {
            ui.post(this::updateUi);
            return;
        }
        car = new CarApi(this);
        try {
            car.connect(io, () -> ui.post(this::updateUi), () -> ui.post(this::updateUi));
        } catch (Throwable e) {
            car = null;
        }
    }

    private boolean recording() {
        return prefs.getBoolean(Prefs.DIAG_RECORDING, false);
    }

    private void toggleRecording() {
        boolean on = !recording();
        prefs.edit().putBoolean(Prefs.DIAG_RECORDING, on).apply();
        if (on) CarDiagService.start(this, "button");
        else CarDiagService.stop(this);
        updateUi();
        // Снимок при включении записи: без него в журнале нет полного списка свойств.
        if (on && !busy) snapshot();
        else ui.postDelayed(refreshTask, 500);
    }

    /** Отметка в журнале: пользователь нажимает её перед действием с машиной. */
    private void mark() {
        marks++;
        CarDiag.log(this, "MARK " + marks + " ------------------------------");
        toast(getString(R.string.diag_marked, marks));
        refresh();
    }

    private void snapshot() {
        setBusy(true);
        status.setText(R.string.diag_snapshot_busy);
        io.post(() -> {
            String msg;
            try {
                File f = CarDiag.snapshot(this, car);
                CarDiag.log(this, "SNAPSHOT " + f.getName());
                msg = getString(R.string.diag_snapshot_done, f.getName());
            } catch (Throwable e) {
                msg = getString(R.string.diag_error, String.valueOf(e.getMessage()));
            }
            final String m = msg;
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false);
                toast(m);
                refresh();
            });
        });
    }

    private void openSaveFolderPicker() {
        if (busy) return;
        Intent i = new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FOLDER)
                .putExtra(PickerActivity.EXTRA_TITLE, getString(R.string.save_where_title))
                .putExtra(PickerActivity.EXTRA_SUBJECT, folderName())
                .putExtra(PickerActivity.EXTRA_ACTION, getString(R.string.save_here));
        startActivityForResult(i, REQUEST_SAVE);
    }

    private static String folderName() {
        return "EISWM-diag-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE || resultCode != Activity.RESULT_OK || data == null) return;
        String folder = data.getStringExtra(PickerActivity.EXTRA_FOLDER);
        if (folder == null) return;
        File dst = new File(folder, folderName());
        setBusy(true);
        io.post(() -> {
            int ok = 0, failed = 0;
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            for (File f : CarDiag.files(this)) {
                if (FileUtils.copyFileQuiet(f, new File(dst, f.getName()))) ok++;
                else failed++;
            }
            final String msg = failed == 0 && ok > 0
                    ? getString(R.string.saved, dst.getAbsolutePath())
                    : getString(R.string.save_failed, dst.getAbsolutePath());
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false);
                toast(msg);
            });
        });
    }

    private void clearLog() {
        CarDiag.clearLog(this);
        if (recording()) CarDiag.log(this, "=== log cleared");
        refresh();
    }

    // ---------------------------------------------------------------- Экран

    private void refresh() {
        ui.removeCallbacks(refreshTask);
        if (destroyed) return;
        boolean atBottom = !scroll.canScrollVertically(1);
        String text = CarDiag.tail(this, TAIL_BYTES);
        log.setText(text.isEmpty() ? getString(R.string.diag_log_empty) : text);
        if (atBottom) scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        updateUi();
        ui.postDelayed(refreshTask, REFRESH_MS);
    }

    private void updateUi() {
        if (destroyed) return;
        if (!busy) {
            String carState = !CarApi.isAvailable() ? getString(R.string.diag_car_missing)
                    : car != null && car.isConnected() ? getString(R.string.diag_car_connected)
                    : getString(R.string.diag_car_connecting);
            status.setText(getString(recording() ? R.string.diag_status_recording : R.string.diag_status_idle)
                    + "\n" + carState);
        }
        btnRecord.setText(recording() ? R.string.diag_record_stop : R.string.diag_record_start);
        long total = 0;
        File[] all = CarDiag.files(this);
        for (File f : all) total += f.length();
        files.setText(getString(R.string.diag_files, all.length, FileUtils.formatSize(this, total)));
        btnSave.setEnabled(!busy && all.length > 0);
        btnClear.setEnabled(!busy);
        btnSnapshot.setEnabled(!busy);
    }

    private void setBusy(boolean value) {
        busy = value;
        updateUi();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
