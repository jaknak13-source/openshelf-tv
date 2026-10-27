package tv.openshelf.home;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "Edit home screen": wallpaper choice and the list of hidden tiles. */
public final class EditActivity extends Activity {
    private static final String TAG = Catalog.TAG;
    private static final int REQUEST_STORAGE = 1;
    private static final File WALLPAPER_DIR =
            new File(Environment.getExternalStorageDirectory(), "Pictures/Wallpaper");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private float dp;
    private TextView status;
    private LinearLayout wallpaperBox;
    private LinearLayout hiddenBox;
    private boolean permissionDenied;
    private boolean couldRead;
    private boolean applying;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        dp = Ui.dp(this);
        setContentView(buildUi());
        couldRead = canRead();
        scanWallpapers();
        loadHidden();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Access may have been granted meanwhile (system settings or adb): show the pictures then.
        if (canRead() != couldRead) {
            couldRead = canRead();
            scanWallpapers();
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(px(48), px(48), px(48), 0);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, 28, "sans-serif-light", Ui.INK);
        title.setText(R.string.editor_title);
        side.addView(title);
        TextView intro = Ui.text(this, 15, "sans-serif", Ui.INK_QUIET);
        intro.setText(R.string.editor_intro);
        intro.setLineSpacing(0f, 1.25f);
        side.addView(intro, marginTop(px(16)));
        status = Ui.text(this, 15, "sans-serif-medium", Ui.INK);
        status.setLineSpacing(0f, 1.25f);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        side.addView(status, marginTop(px(28)));
        root.addView(side, new LinearLayout.LayoutParams(px(300), ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, px(48));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(sectionTitle(R.string.editor_section_wallpaper));
        wallpaperBox = new LinearLayout(this);
        wallpaperBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(wallpaperBox);
        TextView hiddenTitle = sectionTitle(R.string.editor_section_hidden);
        list.addView(hiddenTitle, marginTop(px(32)));
        hiddenBox = new LinearLayout(this);
        hiddenBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(hiddenBox);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        scrollLp.leftMargin = px(64);
        root.addView(scroll, scrollLp);
        return root;
    }

    private LinearLayout.LayoutParams marginTop(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = m;
        return lp;
    }

    private TextView sectionTitle(int text) {
        TextView t = Ui.text(this, 14, "sans-serif-medium", 0x9EFFFFFF);
        t.setText(text);
        t.setPadding(px(16), 0, 0, px(10));
        return t;
    }

    private TextView note(String text) {
        TextView t = Ui.text(this, 15, "sans-serif", Ui.INK_QUIET);
        t.setText(text);
        t.setLineSpacing(0f, 1.25f);
        t.setPadding(px(16), px(8), px(16), px(8));
        return t;
    }

    /** A list row: name on the left, state or verb on the right; both invert on focus. */
    private LinearLayout row(String name, String state) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setMinimumHeight(px(44));
        r.setPadding(px(16), 0, px(16), 0);
        r.setBackground(Ui.plate(this));
        ColorStateList ink = new ColorStateList(
                new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {android.R.attr.state_hovered}, {}},
                new int[] {0x52FFFFFF, 0xFF111418, 0xFF111418, 0xE6FFFFFF});
        ColorStateList quiet = new ColorStateList(
                new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {android.R.attr.state_hovered}, {}},
                new int[] {0x40FFFFFF, 0xB3111418, 0xB3111418, 0x99FFFFFF});
        TextView a = Ui.text(this, 16, "sans-serif", 0);
        a.setTextColor(ink);
        a.setText(name);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        a.setDuplicateParentStateEnabled(true);
        r.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView b = Ui.text(this, 14, "sans-serif-medium", 0);
        b.setTextColor(quiet);
        b.setText(state == null ? "" : state);
        b.setDuplicateParentStateEnabled(true);
        r.addView(b);
        r.setContentDescription(state == null ? name : name + ", " + state);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(2);
        r.setLayoutParams(lp);
        return r;
    }

    private static void setState(LinearLayout row, String state) {
        ((TextView) row.getChildAt(1)).setText(state);
    }

    private void say(String text) {
        status.setText(text);
    }

    // ------------------------------------------------------------ wallpaper

    private boolean canRead() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanWallpapers() {
        wallpaperBox.removeAllViews();
        wallpaperBox.addView(defaultRow());
        if (!canRead()) {
            wallpaperBox.addView(note(getString(permissionDenied
                    ? R.string.editor_permission_denied : R.string.editor_permission_needed)));
            LinearLayout grant = row(getString(R.string.editor_grant), null);
            grant.setOnClickListener(v -> requestPermissions(
                    new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE));
            wallpaperBox.addView(grant);
            focusFirst(wallpaperBox);
            return;
        }
        final TextView scanning = note(getString(R.string.editor_scanning));
        wallpaperBox.addView(scanning);
        worker.execute(() -> {
            File[] files = WALLPAPER_DIR.listFiles((dir, name) -> {
                String n = name.toLowerCase(Locale.ROOT);
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
            });
            List<File> found = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
            found.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            main.post(() -> showWallpapers(scanning, found));
        });
    }

    private void showWallpapers(TextView scanning, List<File> files) {
        wallpaperBox.removeView(scanning);
        if (files.isEmpty()) {
            wallpaperBox.addView(note(getString(R.string.editor_no_images)));
        }
        String active = prefs.wallpaperName();
        for (File f : files) {
            LinearLayout r = row(f.getName(), f.getName().equals(active)
                    ? getString(R.string.editor_active) : getString(R.string.editor_apply));
            r.setOnClickListener(v -> applyWallpaper(f, r));
            wallpaperBox.addView(r);
        }
        focusFirst(wallpaperBox);
    }

    private LinearLayout defaultRow() {
        LinearLayout r = row(getString(R.string.editor_wallpaper_default),
                prefs.wallpaperName() == null ? getString(R.string.editor_active) : getString(R.string.editor_apply));
        r.setOnClickListener(v -> {
            if (applying) return;
            prefs.setWallpaper(null);
            worker.execute(() -> new File(getFilesDir(), HomeActivity.WALLPAPER_FILE).delete());
            say(getString(R.string.editor_wallpaper_default_done));
            refreshWallpaperStates();
        });
        return r;
    }

    private void refreshWallpaperStates() {
        String active = prefs.wallpaperName();
        for (int i = 0; i < wallpaperBox.getChildCount(); i++) {
            View v = wallpaperBox.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) v;
            String name = ((TextView) r.getChildAt(0)).getText().toString();
            boolean isDefault = i == 0;
            boolean on = isDefault ? active == null : name.equals(active);
            if (r.getChildAt(1) != null && !getString(R.string.editor_grant).equals(name)) {
                setState(r, getString(on ? R.string.editor_active : R.string.editor_apply));
            }
            r.setEnabled(!applying);
        }
    }

    /** Scales the picture to cover 1920x1080 and stores it privately, so home never needs storage access. */
    private void applyWallpaper(File source, LinearLayout row) {
        if (applying) return;
        applying = true;
        refreshWallpaperStates();
        setState(row, getString(R.string.editor_applying));
        say("");
        worker.execute(() -> {
            boolean ok = copyScaled(source, new File(getFilesDir(), HomeActivity.WALLPAPER_FILE));
            main.post(() -> {
                applying = false;
                if (ok) {
                    prefs.setWallpaper(source.getName());
                    say(getString(R.string.editor_wallpaper_done, source.getName()));
                } else {
                    say(getString(R.string.editor_wallpaper_failed, source.getName()));
                }
                refreshWallpaperStates();
                row.requestFocus();
            });
        });
    }

    private boolean copyScaled(File source, File target) {
        final int w = 1920, h = 1080;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) {
            opts.inSampleSize *= 2;
        }
        Bitmap src;
        try {
            src = BitmapFactory.decodeFile(source.getPath(), opts);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "wallpaper too large: " + source, e);
            return false;
        }
        if (src == null) return false;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!out.compress(Bitmap.CompressFormat.JPEG, 92, fos)) return false;
        } catch (IOException e) {
            Log.w(TAG, "could not store wallpaper", e);
            return false;
        } finally {
            out.recycle();
        }
        return tmp.renameTo(target);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_STORAGE) return;
        permissionDenied = results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED;
        scanWallpapers();
    }

    // ------------------------------------------------------------ hidden tiles

    private void loadHidden() {
        final List<String> keys = prefs.hiddenOrdered();
        worker.execute(() -> {
            List<String> labels = new ArrayList<>();
            for (String k : keys) labels.add(labelFor(this, k));
            main.post(() -> showHidden(keys, labels));
        });
    }

    private void showHidden(List<String> keys, List<String> labels) {
        hiddenBox.removeAllViews();
        if (keys.isEmpty()) {
            hiddenBox.addView(note(getString(R.string.editor_no_hidden)));
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            final String label = labels.get(i);
            LinearLayout r = row(label, getString(R.string.editor_unhide));
            r.setOnClickListener(v -> unhide(key, label, r));
            hiddenBox.addView(r);
        }
    }

    private void unhide(String key, String label, LinearLayout r) {
        List<String> hidden = prefs.hiddenOrdered();
        hidden.remove(key);
        prefs.setHidden(hidden);
        int i = hiddenBox.indexOfChild(r);
        hiddenBox.removeView(r);
        if (hiddenBox.getChildCount() == 0) {
            hiddenBox.addView(note(getString(R.string.editor_no_hidden)));
            focusFirst(wallpaperBox);
        } else {
            hiddenBox.getChildAt(Math.min(i, hiddenBox.getChildCount() - 1)).requestFocus();
        }
        say(getString(R.string.editor_unhidden, label));
    }

    private static String labelFor(Context c, String key) {
        if (key.startsWith("app:")) {
            String pkg = key.substring(4);
            PackageManager pm = c.getPackageManager();
            try {
                return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
            } catch (PackageManager.NameNotFoundException e) {
                return c.getString(R.string.editor_not_installed, pkg);
            }
        }
        if (key.startsWith("input:")) {
            String id = key.substring(6);
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            TvInputInfo info = tim == null ? null : tim.getTvInputInfo(id);
            if (info == null) return c.getString(R.string.editor_not_installed, id);
            CharSequence custom = info.loadCustomLabel(c);
            return String.valueOf(TextUtils.isEmpty(custom) ? info.loadLabel(c) : custom);
        }
        return key;
    }

    private static void focusFirst(ViewGroup box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v.isFocusable() && v.isEnabled()) {
                if (box.getRootView().findFocus() == null) v.requestFocus();
                return;
            }
        }
    }
}
