package tv.openshelf.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A soft pool of light in the focused banner's colour, crossfaded on focus change.
 *
 * Measured on the target TV (frame p50 while scrolling): a full-screen gradient shader 24 ms,
 * two stacked bitmaps during the crossfade 23 ms, one 1/10-size bitmap repainted per frame
 * 17 ms, one 1/40-size bitmap 10 ms. So the crossfade happens inside a 48x27 bitmap (about
 * 1,300 pixels repainted per animation frame) that the GPU stretches over the screen with
 * bilinear filtering; the pool is soft enough that this is invisible. The ValueAnimator
 * honours the system animator duration scale.
 */
final class TintView extends View {
    private static final int STRENGTH = 0x44; // alpha at the centre of the pool
    private static final int SCALE = 40;

    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fromPaint = new Paint();
    private final Paint toPaint = new Paint();
    private final Rect dst = new Rect();
    private Bitmap pool;
    private Canvas poolCanvas;
    private int toColor = Art.NO_TINT;
    private float toX;
    private float toY;
    private float mix = 1f;
    private ValueAnimator anim;

    TintView(Context c) {
        super(c);
    }

    /**
     * Starts the crossfade only once focus has rested for SETTLE_MS. While the D-pad is held,
     * every crossfade frame forces a full-window redraw on top of the tile zoom and the first
     * texture uploads of newly visible banners; skipping those intermediate fades keeps
     * scrolling smooth, and the colour catches up as soon as focus stops.
     */
    void setTint(int color, float cx, float cy) {
        pendingColor = color;
        pendingX = cx;
        pendingY = cy;
        removeCallbacks(applyPending);
        postDelayed(applyPending, SETTLE_MS);
    }

    private static final long SETTLE_MS = 150;
    private int pendingColor = Art.NO_TINT;
    private float pendingX;
    private float pendingY;
    private final Runnable applyPending = () -> applyTint(pendingColor, pendingX, pendingY);

    private void applyTint(int color, float cx, float cy) {
        if (color == toColor && Math.abs(cx - toX) < 1f && Math.abs(cy - toY) < 1f) return;
        ensurePool();
        // What is on screen now becomes the layer that fades out.
        fromPaint.setShader(toPaint.getShader());
        final int startAlpha = Math.round(255 * mix);
        toColor = color;
        toX = cx;
        toY = cy;
        toPaint.setShader(color == Art.NO_TINT ? null : shader(color, cx, cy));
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            mix = (Float) a.getAnimatedValue();
            fromPaint.setAlpha(Math.round(startAlpha * (1f - mix)));
            toPaint.setAlpha(Math.round(255 * mix));
            repaint();
        });
        mix = 0f;
        anim.start();
    }

    private void ensurePool() {
        if (pool != null) return;
        // Screen size, not view size: the first tint can arrive before the first layout.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        pool = Bitmap.createBitmap(Math.max(1, dm.widthPixels / SCALE), Math.max(1, dm.heightPixels / SCALE),
                Bitmap.Config.ARGB_8888);
        poolCanvas = new Canvas(pool);
    }

    private Shader shader(int color, float cx, float cy) {
        float radius = getResources().getDisplayMetrics().widthPixels * 0.62f / SCALE;
        int center = Color.argb(STRENGTH, Color.red(color), Color.green(color), Color.blue(color));
        int mid = Color.argb(STRENGTH / 3, Color.red(color), Color.green(color), Color.blue(color));
        return new RadialGradient(cx / SCALE, cy / SCALE, Math.max(1f, radius),
                new int[] {center, mid, Color.TRANSPARENT}, new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
    }

    private void repaint() {
        poolCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (fromPaint.getShader() != null && fromPaint.getAlpha() > 0) poolCanvas.drawPaint(fromPaint);
        if (toPaint.getShader() != null && toPaint.getAlpha() > 0) poolCanvas.drawPaint(toPaint);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pool == null) return;
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(pool, null, dst, blit);
    }
}
