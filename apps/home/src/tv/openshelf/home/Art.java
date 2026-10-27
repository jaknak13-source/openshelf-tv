package tv.openshelf.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;

/**
 * Renders every tile face into one bitmap at its final pixel size (including the focus
 * scale), so tiles stay sharp when zoomed and nothing larger than the tile stays in memory.
 */
final class Art {
    static final int NO_TINT = 0;

    /**
     * Card for inputs, system actions and apps without a banner. Opaque on purpose: the focus
     * shadow would otherwise show through it as a dark inner rectangle.
     */
    static final int CARD = 0xFF363B43;

    static final class Face {
        final Bitmap bitmap;
        /** Desaturated average colour for the background tint, or {@link #NO_TINT}. */
        final int tint;

        Face(Bitmap bitmap, int tint) {
            this.bitmap = bitmap;
            this.tint = tint;
        }
    }

    private final LruCache<String, Face> cache = new LruCache<String, Face>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Face f) {
            return f.bitmap.getByteCount() / 1024;
        }
    };

    static String cacheKey(Entry e, int w, int h) {
        return e.key + '|' + e.stamp + '|' + w + 'x' + h + '|' + e.label.hashCode()
                + '|' + (e.detail == null ? 0 : e.detail.hashCode());
    }

    Face cached(Entry e, int w, int h) {
        return cache.get(cacheKey(e, w, h));
    }

    void put(Entry e, int w, int h, Face f) {
        cache.put(cacheKey(e, w, h), f);
    }

    void trim() {
        cache.evictAll();
    }

    /** Worker thread only: loads other apps' resources. */
    Face render(Context c, Entry e, int w, int h) {
        String key = cacheKey(e, w, h);
        Face f = cache.get(key);
        if (f != null) return f;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        PackageManager pm = c.getPackageManager();
        Drawable banner = null;
        Drawable icon = null;
        if (e.activity != null) {
            banner = e.activity.loadBanner(pm);
            if (banner == null) icon = e.activity.loadIcon(pm);
        }
        int tint;
        if (banner != null) {
            canvas.drawColor(0xFF1B1F25);
            banner.setBounds(0, 0, w, h);
            banner.draw(canvas);
            tint = tintOf(bmp);
        } else if (icon != null) {
            canvas.drawColor(CARD);
            drawIconCard(canvas, icon, e.label, w, h);
            tint = tintOf(bmp);
        } else {
            canvas.drawColor(CARD);
            drawTextCard(canvas, e, w, h);
            tint = NO_TINT;
        }
        bmp.prepareToDraw();
        f = new Face(bmp, tint);
        cache.put(key, f);
        return f;
    }

    private static void drawIconCard(Canvas canvas, Drawable icon, String label, int w, int h) {
        int size = Math.round(h * 0.44f);
        int pad = Math.round(h * 0.16f);
        int top = (h - size) / 2;
        icon.setBounds(pad, top, pad + size, top + size);
        icon.draw(canvas);

        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setColor(0xF2FFFFFF);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(h * 0.15f);
        int textLeft = pad + size + Math.round(h * 0.12f);
        int textWidth = Math.max(1, w - textLeft - pad);
        StaticLayout layout = StaticLayout.Builder.obtain(label, 0, label.length(), p, textWidth)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(textLeft, (h - layout.getHeight()) / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    /** Label large at the bottom left, detail small above it: reads like a caption, not a button. */
    private static void drawTextCard(Canvas canvas, Entry e, int w, int h) {
        int pad = Math.round(h * 0.13f);
        int width = w - 2 * pad;

        TextPaint big = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        big.setColor(e.dimmed ? 0x8CFFFFFF : 0xF5FFFFFF);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setTextSize(h * 0.2f);
        StaticLayout main = StaticLayout.Builder.obtain(e.label, 0, e.label.length(), big, width)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.92f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build();
        float mainTop = h - pad - main.getHeight();
        canvas.save();
        canvas.translate(pad, mainTop);
        main.draw(canvas);
        canvas.restore();

        if (e.detail != null) {
            TextPaint small = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            small.setColor(e.dimmed ? 0x80FFFFFF : 0xB3FFFFFF);
            small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            small.setTextSize(h * 0.115f);
            CharSequence d = TextUtils.ellipsize(e.detail, small, width, TextUtils.TruncateAt.END);
            canvas.drawText(d, 0, d.length(), pad, pad - small.ascent(), small);
        }
    }

    /** Average of the opaque pixels, desaturated and kept dark enough to sit behind white text. */
    static int tintOf(Bitmap bmp) {
        Bitmap small = Bitmap.createScaledBitmap(bmp, 16, 9, true);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int px = small.getPixel(x, y);
                if (Color.alpha(px) < 160) continue;
                r += Color.red(px);
                g += Color.green(px);
                b += Color.blue(px);
                n++;
            }
        }
        small.recycle();
        if (n == 0) return NO_TINT;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
        hsv[1] = Math.min(hsv[1] * 0.55f, 0.45f);
        hsv[2] = Math.max(0.38f, Math.min(hsv[2], 0.62f));
        return Color.HSVToColor(hsv);
    }
}
