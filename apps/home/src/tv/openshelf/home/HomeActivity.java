package tv.openshelf.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HomeActivity extends Activity implements EditDialog.Host {
    private static final String TAG = Catalog.TAG;
    static final String WALLPAPER_FILE = "wallpaper.jpg";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Art art = new Art();

    private Prefs prefs;
    private Catalog catalog;
    private HomeModel model;
    private int generation;
    private int appliedVersion = -1;
    /** Stamp of the wallpaper on screen; 0 = bundled graphite (the window background). */
    private volatile long appliedWallpaper = 0L;
    private boolean focusHomeOnNextModel = true;
    private boolean rebuilding;
    private boolean shownOnce;
    private long createdAt;
    private Snapshot snapshot;

    private FrameLayout root;
    private ImageView wallpaper;
    private TintView tint;
    private Column column;
    private TextView clockTime;
    private TextView clockDate;
    private TextView status;
    private EditDialog dialog;
    private float dp;
    private int labelH;

    private final Runnable hideStatus = () -> status.animate().alpha(0f).setDuration(400).start();
    private final Runnable reloadSoon = this::reload;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            scheduleReload();
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String id, int state) { scheduleReload(); }
        @Override public void onInputAdded(String id) { scheduleReload(); }
        @Override public void onInputRemoved(String id) { scheduleReload(); }
        @Override public void onInputUpdated(String id) { scheduleReload(); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        createdAt = SystemClock.uptimeMillis();
        prefs = new Prefs(this); // starts loading the file on a framework thread
        snapshot = new Snapshot(this);
        dp = getResources().getDisplayMetrics().density;
        // The theme's full-size picture serves the starting window. The live window uses a
        // 960x540 copy stretched to the screen: it looks the same (the picture is a soft
        // gradient) but needs a quarter of the texture bandwidth per frame and 6 MB less RAM.
        getWindow().setBackgroundDrawableResource(R.drawable.graphite_half);
        setContentView(buildUi());

        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addDataScheme("package");
        registerReceiver(packageReceiver, pkg);
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, main);

        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        registerReceiver(clockReceiver, f);
        updateClock();
        // The edit screen writes preferences; pick its changes up when we come back.
        if (model != null && prefs.version() != appliedVersion) reload();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(clockReceiver);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            focusHome(hasWindowFocus());
        }
    }

    @Override
    public void onBackPressed() {
        // Home is the bottom of the stack: Back has nowhere to go.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof TileView) {
                openEditor((TileView) f);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) art.trim();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        int inset = px(48);
        labelH = px(30);

        root = new FrameLayout(this);
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setAlpha(0f);
        root.addView(wallpaper, match());
        tint = new TintView(this);
        root.addView(tint, match());

        column = new Column(this, px(56), inset, px(12), px(20));
        column.setAlpha(0f); // faded in with the first rows, so empty titles never flash
        addRow(HomeModel.ROW_FAVORITES, R.string.row_favorites, px(192), px(108), R.string.empty_favorites, inset);
        addRow(HomeModel.ROW_APPS, R.string.row_apps, px(144), px(81), R.string.empty_apps, inset);
        addRow(HomeModel.ROW_INPUTS, R.string.row_inputs, px(144), px(81), R.string.empty_inputs, inset);
        addRow(HomeModel.ROW_SYSTEM, R.string.row_system, px(144), px(81), 0, inset);
        FrameLayout.LayoutParams colLp = match();
        colLp.topMargin = px(64);
        root.addView(column, colLp);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setBaselineAligned(true);
        clockDate = Ui.text(this, 15, "sans-serif", Ui.INK_QUIET);
        clockTime = Ui.text(this, 26, "sans-serif-light", Ui.INK);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = px(14);
        clock.addView(clockDate);
        clock.addView(clockTime, timeLp);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clockLp.topMargin = px(27);
        clockLp.rightMargin = inset;
        root.addView(clock, clockLp);

        status = Ui.text(this, 15, "sans-serif", Ui.INK);
        status.setAlpha(0f);
        status.setMaxLines(2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                px(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        statusLp.topMargin = px(32);
        statusLp.leftMargin = inset;
        root.addView(status, statusLp);

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (!rebuilding && newFocus instanceof TileView) onTileFocused((TileView) newFocus, true);
        });
        return root;
    }

    private void addRow(int id, int title, int artW, int artH, int emptyText, int inset) {
        TextView t = Ui.text(this, 14, "sans-serif-medium", 0x9EFFFFFF);
        t.setText(title);
        t.setLetterSpacing(0.02f);
        String empty = emptyText == 0 ? "" : getString(emptyText);
        column.addRow(t, new TileRow(this, id, artW, artH, labelH, px(id == HomeModel.ROW_FAVORITES ? 20 : 16), inset, empty));
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ---------------------------------------------------------------- data

    private void scheduleReload() {
        main.removeCallbacks(reloadSoon);
        main.postDelayed(reloadSoon, 400);
    }

    /** Re-reads apps, inputs and preferences off the main thread, then rebuilds the rows. */
    private void reload() {
        final int gen = ++generation;
        final boolean cold = model == null;
        worker.execute(() -> {
            if (cold) showSnapshot(gen);
            long t0 = SystemClock.uptimeMillis();
            Catalog cat = Catalog.load(this);
            int version = prefs.version();
            HomeModel m = buildModel(cat);
            long t1 = SystemClock.uptimeMillis();
            Bitmap wp = loadWallpaperIfChanged();
            main.post(() -> {
                if (gen != generation) return;
                catalog = cat;
                appliedVersion = version;
                applyModel(m, null);
                if (column.getAlpha() < 1f) column.animate().alpha(1f).setDuration(180).start();
                if (wp != null) showWallpaper(wp);
                Log.i(TAG, "catalog " + (t1 - t0) + " ms, rows on screen "
                        + (SystemClock.uptimeMillis() - createdAt) + " ms after onCreate");
            });
        });
    }

    /** Worker thread: the last rows, straight from app storage, while the real catalog loads. */
    private void showSnapshot(int gen) {
        long t0 = SystemClock.uptimeMillis();
        List<Snapshot.Item> items = snapshot.load(art);
        if (items == null) return;
        List<List<Entry>> rows = new ArrayList<>();
        for (int r = 0; r <= HomeModel.ROW_SYSTEM; r++) rows.add(new ArrayList<>());
        for (Snapshot.Item it : items) rows.get(it.row).add(it.entry);
        HomeModel m = HomeModel.of(rows.get(0), rows.get(1), rows.get(2), rows.get(3));
        Log.i(TAG, "snapshot " + items.size() + " tiles read in " + (SystemClock.uptimeMillis() - t0) + " ms");
        main.post(() -> {
            if (gen != generation || catalog != null) return;
            applyModel(m, null);
            if (column.getAlpha() < 1f) column.animate().alpha(1f).setDuration(180).start();
            Log.i(TAG, "snapshot on screen " + (SystemClock.uptimeMillis() - createdAt) + " ms after onCreate");
        });
    }

    /** Main thread: what to save, captured while the tiles are known. */
    private List<Snapshot.Item> snapshotItems() {
        List<Snapshot.Item> items = new ArrayList<>();
        for (TileRow row : column.rows()) {
            for (TileView t : row.tiles()) {
                items.add(new Snapshot.Item(row.rowId, t.entry, t.faceWidth(), t.faceHeight(), t.tint()));
            }
        }
        return items;
    }

    private HomeModel buildModel(Catalog cat) {
        return HomeModel.build(cat.apps, cat.inputs, cat.system, prefs.favorites(), prefs.hidden(),
                prefs.order(Prefs.ORDER_APPS), prefs.order(Prefs.ORDER_INPUTS));
    }

    /** Worker thread. Returns null when the wallpaper on screen is already the right one. */
    private Bitmap loadWallpaperIfChanged() {
        final long stamp = prefs.wallpaperName() == null ? 0L : prefs.wallpaperStamp();
        if (stamp == appliedWallpaper) return null;
        if (stamp == 0L) {
            main.post(() -> {
                appliedWallpaper = 0L;
                getWindow().setBackgroundDrawableResource(R.drawable.graphite_half);
                wallpaper.animate().alpha(0f).setDuration(300).withEndAction(() -> wallpaper.setImageDrawable(null)).start();
            });
            return null;
        }
        File f = new File(getFilesDir(), WALLPAPER_FILE);
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) {
            Log.w(TAG, "custom wallpaper missing or unreadable: " + f);
            return null;
        }
        main.post(() -> appliedWallpaper = stamp);
        return b;
    }

    private void showWallpaper(Bitmap b) {
        wallpaper.setImageBitmap(b);
        wallpaper.animate().alpha(1f).setDuration(300).withEndAction(() ->
                // Drop the bundled picture underneath so only one full-screen bitmap stays alive.
                getWindow().setBackgroundDrawable(new ColorDrawable(0xFF0E131A))).start();
    }

    private void applyModel(HomeModel m, String focusKey) {
        View focused = getCurrentFocus();
        String keepKey = focusKey;
        int keepRow = -1;
        int keepIndex = 0;
        if (keepKey == null && focused instanceof TileView) {
            TileView t = (TileView) focused;
            keepKey = t.entry.key;
            TileRow r = column.rowOf(t);
            if (r != null) {
                keepRow = r.rowId;
                keepIndex = r.indexOf(t);
            }
        }

        model = m;
        rebuilding = true;
        List<Runnable> missing = new ArrayList<>();
        for (TileRow row : column.rows()) {
            Map<String, TileView> old = new HashMap<>();
            for (TileView t : row.tiles()) old.put(t.entry.key, t);
            List<TileView> next = new ArrayList<>();
            for (Entry e : m.row(row.rowId)) {
                TileView t = old.get(e.key);
                if (t == null || t.entry.stamp != e.stamp || !t.entry.label.equals(e.label)
                        || !eq(t.entry.detail, e.detail)) {
                    t = newTile(e, row);
                }
                next.add(t);
                if (!t.hasFace()) {
                    final TileView tile = t;
                    Art.Face face = art.cached(e, t.faceWidth(), t.faceHeight());
                    if (face != null) t.setFace(face.bitmap, face.tint);
                    else missing.add(() -> renderFace(tile));
                }
            }
            row.setTiles(next);
        }

        TileView target = null;
        if (focusHomeOnNextModel) {
            focusHomeOnNextModel = false;
            focusHome(false);
            target = (TileView) getCurrentFocus();
        } else {
            if (keepKey != null) target = findTile(keepKey);
            if (target == null && keepRow >= 0) target = column.rows().get(keepRow).tileAt(keepIndex);
            if (target == null && focused == null) target = firstTile();
            if (target != null && target != getCurrentFocus()) target.requestFocus();
        }
        rebuilding = false;
        View now = getCurrentFocus();
        if (now instanceof TileView) onTileFocused((TileView) now, shownOnce);
        shownOnce = true;

        // Only rows built from the live catalog are worth saving for the next cold start.
        final List<Snapshot.Item> save = catalog != null ? snapshotItems() : null;
        worker.execute(() -> {
            if (!missing.isEmpty()) {
                long t0 = SystemClock.uptimeMillis();
                for (Runnable r : missing) r.run();
                Log.i(TAG, missing.size() + " faces in " + (SystemClock.uptimeMillis() - t0) + " ms");
            }
            if (save != null) snapshot.save(save, art);
        });
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private TileView newTile(Entry e, TileRow row) {
        TileView t = new TileView(this, e, row.artW, row.artH, labelH);
        t.setOnClickListener(v -> launch((TileView) v));
        t.setOnLongClickListener(v -> {
            openEditor((TileView) v);
            return true;
        });
        return t;
    }

    /** Worker thread. */
    private void renderFace(TileView t) {
        Art.Face face;
        try {
            face = art.render(this, t.entry, t.faceWidth(), t.faceHeight());
        } catch (RuntimeException ex) {
            // One app with broken resources must not stop the others from getting their banners.
            Log.w(TAG, "no banner for " + t.entry.key, ex);
            return;
        }
        main.post(() -> {
            t.setFace(face.bitmap, face.tint);
            if (t.isFocused()) onTileFocused(t, true);
        });
    }

    // ---------------------------------------------------------------- focus

    private TileView firstTile() {
        for (TileRow r : column.rows()) {
            if (!r.isEmpty()) return r.tileAt(0);
        }
        return null;
    }

    private TileView findTile(String key) {
        for (TileRow r : column.rows()) {
            TileView t = r.find(key);
            if (t != null) return t;
        }
        return null;
    }

    /** First favourite (or the first tile anywhere), rows scrolled back to the start. */
    private void focusHome(boolean animate) {
        for (TileRow r : column.rows()) r.resetToStart();
        TileView first = firstTile();
        if (first == null) return;
        if (first.isFocused()) onTileFocused(first, animate);
        else first.requestFocus();
    }

    private void onTileFocused(TileView t, boolean animate) {
        TileRow row = column.rowOf(t);
        if (row == null) return;
        row.onTileFocused(t, animate);
        int index = column.indexOfRow(row);
        column.scrollToRow(index, animate);
        float cx = row.settledCenterX(row.indexOf(t));
        float cy = column.getTop() + column.settledArtTop(index) + row.artH / 2f;
        tint.setTint(t.tint(), cx, cy);
    }

    // ---------------------------------------------------------------- actions

    private void launch(TileView t) {
        Entry e = t.entry;
        if (Entry.KEY_EDIT.equals(e.key)) {
            startActivity(new Intent(this, EditActivity.class));
            return;
        }
        try {
            startActivity(e.intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Log.w(TAG, "launch failed for " + e.key, ex);
            if (e.kind == Entry.INPUT) {
                showStatus(getString(R.string.status_input_failed, e.label));
            } else {
                showStatus(getString(R.string.status_launch_failed, e.label));
                reload();
            }
        }
    }

    private void openEditor(TileView t) {
        if (t.entry.kind == Entry.SYSTEM) {
            showStatus(getString(R.string.status_system_fixed));
            return;
        }
        if (dialog != null && dialog.isShowing()) return;
        if (catalog == null) return; // still showing the startup snapshot; edits need the live list
        dialog = new EditDialog(this, t.entry, this);
        dialog.show();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.animate().alpha(1f).setDuration(200).start();
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, 6000);
    }

    private void updateClock() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        String time = getString(R.string.clock_time_pattern);
        String date = getString(R.string.clock_date_pattern);
        clockTime.setText(time.isEmpty() ? DateFormat.getTimeFormat(this).format(now)
                : new SimpleDateFormat(time, locale).format(now));
        if (date.isEmpty()) date = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd");
        clockDate.setText(new SimpleDateFormat(date, locale).format(now));
    }

    // ---------------------------------------------------------------- EditDialog.Host

    private int rowIdOf(Entry e) {
        for (int r = HomeModel.ROW_FAVORITES; r <= HomeModel.ROW_SYSTEM; r++) {
            if (HomeModel.indexOf(model.row(r), e.key) >= 0) return r;
        }
        return -1;
    }

    @Override
    public boolean isFavorite(Entry e) {
        return HomeModel.indexOf(model.favorites, e.key) >= 0;
    }

    @Override
    public int[] position(Entry e) {
        int r = rowIdOf(e);
        if (r < 0) return new int[] {-1, 0};
        List<Entry> row = model.row(r);
        return new int[] {HomeModel.indexOf(row, e.key), row.size()};
    }

    @Override
    public void toggleFavorite(Entry e) {
        List<String> favs = prefs.favorites();
        boolean was = favs.remove(e.key);
        if (!was) favs.add(e.key);
        prefs.setFavorites(favs);
        rebuildFromPrefs(e.key);
        showStatus(getString(was ? R.string.status_removed_favorite : R.string.status_added_favorite, e.label));
    }

    @Override
    public void move(Entry e, int delta) {
        int r = rowIdOf(e);
        if (r < 0) return;
        List<Entry> row = model.row(r);
        List<String> keys = HomeModel.moved(row, HomeModel.indexOf(row, e.key), delta);
        if (keys == null) return;
        if (r == HomeModel.ROW_FAVORITES) prefs.setFavorites(HomeModel.mergeFavorites(keys, prefs.favorites()));
        else if (r == HomeModel.ROW_APPS) prefs.setOrder(Prefs.ORDER_APPS, keys);
        else if (r == HomeModel.ROW_INPUTS) prefs.setOrder(Prefs.ORDER_INPUTS, keys);
        else return;
        rebuildFromPrefs(e.key);
    }

    @Override
    public void hide(Entry e) {
        List<String> hidden = prefs.hiddenOrdered();
        if (!hidden.contains(e.key)) hidden.add(e.key);
        prefs.setHidden(hidden);
        List<String> favs = prefs.favorites();
        if (favs.remove(e.key)) prefs.setFavorites(favs);
        int[] pos = position(e);
        int r = rowIdOf(e);
        rebuildFromPrefs(null);
        if (r >= 0) {
            TileView next = column.rows().get(r).tileAt(Math.max(0, pos[0]));
            if (next == null) next = firstTile();
            if (next != null) next.requestFocus();
        }
        showStatus(getString(R.string.status_hidden, e.label));
    }

    /** Preferences are in memory once loaded, so this is list shuffling, not I/O. */
    private void rebuildFromPrefs(String focusKey) {
        if (catalog == null) return;
        appliedVersion = prefs.version();
        applyModel(buildModel(catalog), focusKey);
    }
}
