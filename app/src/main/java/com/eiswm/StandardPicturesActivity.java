package com.eiswm;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Стандартные картинки приветствия (штатные картинки лаунчера с русским текстом, assets/standard):
 * можно добавить выбранные на круглый год или включить все по временам года.
 * Результат — RESULT_OK, после которого раздел «Картинки» перечитывает список.
 */
public class StandardPicturesActivity extends BaseActivity {
    private WelcomePictures pictures;
    private final TreeSet<String> selected = new TreeSet<>();
    private final Map<String, CheckBox> checks = new HashMap<>();
    private final Map<String, View> cards = new HashMap<>();
    private List<String> names = new ArrayList<>();
    private Set<String> alreadyAdded;

    private TextView selectedTitle, hint, seasonalState;
    private Button btnSelectAll, btnClear, btnAdd, btnSeasonal;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy = false;
    private boolean destroyed = false;
    private boolean changed = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_standard);
        pictures = WelcomePictures.get(this);
        selectedTitle = findViewById(R.id.standardSelected);
        hint = findViewById(R.id.standardHint);
        seasonalState = findViewById(R.id.seasonalState);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnAdd = findViewById(R.id.btnAddSelected);
        btnSeasonal = findViewById(R.id.btnSeasonal);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnClear = findViewById(R.id.btnClear);
        btnSelectAll.setOnClickListener(v -> setAll(true));
        btnClear.setOnClickListener(v -> setAll(false));
        btnAdd.setOnClickListener(v -> addSelected());
        btnSeasonal.setOnClickListener(v -> toggleSeasonal());

        names = pictures.standardNames();
        alreadyAdded = pictures.standardAdded();
        buildGrid();
        updateSide();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        // Начатое добавление картинок доделывается: прерывать запись в базу лаунчера нельзя.
        io.shutdown();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void finish() {
        if (changed) setResult(RESULT_OK);
        super.finish();
    }

    // ---------------------------------------------------------------- Сетка по сезонам

    private void buildGrid() {
        LinearLayout grid = findViewById(R.id.standardGrid);
        List<ImageView> images = new ArrayList<>();
        int lastSeason = -1;
        LinearLayout row = null;
        for (String name : names) {
            int season = WelcomePictures.seasonIndex(name);
            if (season != lastSeason) {
                lastSeason = season;
                TextView header = new TextView(this);
                header.setText(season < Ui.SEASON_TITLES.length ? getString(Ui.SEASON_TITLES[season]) : "");
                header.setTextSize(19);
                header.setTextColor(getColor(R.color.text_primary));
                header.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), 0, Ui.dp(this, 2));
                grid.addView(header);
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            }
            images.add(addCard(row, name));
        }
        loadThumbnails(images);
    }

    private ImageView addCard(LinearLayout row, String name) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));

        FrameLayout frame = Ui.screenFrame(this);
        ImageView image = Ui.addPreviewImage(frame, name);

        CheckBox check = Ui.addPreviewCheck(frame, 6);
        check.setClickable(false);
        check.setFocusable(false);
        checks.put(name, check);

        if (alreadyAdded.contains(name)) {
            TextView mark = Ui.pill(this);
            Ui.setPill(mark, getString(R.string.standard_added_mark), Ui.PILL_OK);
            FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            markLp.setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 6));
            frame.addView(mark, markLp);
        }
        card.addView(frame, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        card.setOnClickListener(v -> toggle(name));
        frame.setOnClickListener(v -> toggle(name));
        cards.put(name, card);
        row.addView(card, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        return image;
    }

    private void loadThumbnails(List<ImageView> images) {
        final List<String> list = new ArrayList<>(names);
        final int width = Ui.dp(this, 220);
        io.execute(() -> {
            for (int i = 0; i < list.size(); i++) {
                if (destroyed) return;
                final Bitmap bmp = pictures.standardThumbnail(list.get(i), width);
                final ImageView view = images.get(i);
                ui.post(() -> {
                    if (!destroyed && bmp != null) view.setImageBitmap(bmp);
                });
            }
        });
    }

    // ---------------------------------------------------------------- Выбор

    private void toggle(String name) {
        if (busy) return;
        if (!selected.remove(name)) selected.add(name);
        applySelection();
    }

    private void setAll(boolean on) {
        if (busy) return;
        if (on) selected.addAll(names);
        else selected.clear();
        applySelection();
    }

    private void applySelection() {
        for (String n : names) {
            boolean on = selected.contains(n);
            checks.get(n).setChecked(on);
            cards.get(n).setBackgroundColor(on ? getColor(R.color.row_active) : Color.TRANSPARENT);
        }
        updateSide();
    }

    private void updateSide() {
        int n = selected.size();
        selectedTitle.setText(getString(R.string.standard_selected, n));
        hint.setVisibility(View.VISIBLE);
        btnAdd.setEnabled(n > 0 && !busy);
        btnAdd.setText(n > 0
                ? getResources().getQuantityString(R.plurals.picker_add_pictures, n, n)
                : getString(R.string.standard_add));
        boolean seasonal = pictures.isSeasonal();
        seasonalState.setVisibility(seasonal ? View.VISIBLE : View.GONE);
        btnSeasonal.setText(seasonal ? R.string.standard_seasonal_off : R.string.standard_seasonal_on);
        btnSeasonal.setEnabled(!busy);
        btnSelectAll.setEnabled(!busy && n < names.size());
        btnClear.setEnabled(!busy && n > 0);
    }

    // ---------------------------------------------------------------- Действия

    private void addSelected() {
        final List<String> list = new ArrayList<>(selected);
        run(() -> {
            pictures.addStandard(list);
            return getString(R.string.standard_done_added, list.size());
        }, true);
    }

    private void toggleSeasonal() {
        final boolean on = !pictures.isSeasonal();
        run(() -> {
            pictures.setSeasonal(on);
            return getString(on ? R.string.standard_done_seasonal_on : R.string.standard_done_seasonal_off);
        }, true);
    }

    private interface Job {
        String run() throws Exception;
    }

    private void run(Job job, boolean finishAfter) {
        busy = true;
        updateSide();
        io.execute(() -> {
            String msg;
            boolean ok = true;
            try {
                msg = job.run();
            } catch (Exception | OutOfMemoryError e) {
                msg = getString(R.string.pictures_error, String.valueOf(e.getMessage()));
                ok = false;
            }
            final String message = msg;
            final boolean success = ok;
            ui.post(() -> {
                if (destroyed) return;
                busy = false;
                changed = true;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                if (success && finishAfter) finish();
                else updateSide();
            });
        });
    }
}
