package tv.openshelf.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** Banner plus the name that appears under it while focused. The whole tile takes focus. */
final class TileView extends ViewGroup {
    static final float FOCUS_SCALE = 1.08f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    final Entry entry;
    final int artW;
    final int artH;
    private final int labelGap;
    private final ImageView art;
    private final TextView label;
    private final GradientDrawable ring;
    private final float lift;
    private ValueAnimator ringAnim;
    private int tint = Art.NO_TINT;
    private boolean hasArt;
    private boolean longPressed;

    TileView(Context c, Entry entry, int artW, int artH, int labelHeight) {
        super(c);
        this.entry = entry;
        this.artW = artW;
        this.artH = artH;
        float dp = c.getResources().getDisplayMetrics().density;
        final float radius = 6 * dp;
        lift = 10 * dp;
        labelGap = Math.round(artH * (FOCUS_SCALE - 1f) / 2f + 7 * dp);

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setClipChildren(false);
        setWillNotDraw(true);
        setContentDescription(entry.detail == null ? entry.label : entry.label + ", " + entry.detail);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        art.setBackgroundColor(Art.CARD);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        art.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Math.max(2, Math.round(1.5f * dp)), 0xF2FFFFFF);
        ring.setAlpha(0);
        art.setForeground(ring);
        addView(art);

        label = new TextView(c);
        label.setText(entry.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(0xF0FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setIncludeFontPadding(false);
        label.setAlpha(0f);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label);
    }

    void setFace(Bitmap bitmap, int tint) {
        art.setImageBitmap(bitmap);
        art.setBackground(null);
        this.tint = tint;
        hasArt = true;
    }

    boolean hasFace() {
        return hasArt;
    }

    int tint() {
        return tint;
    }

    /** Pixel size of the bitmap: the focused size, so the zoom never upsamples. */
    int faceWidth() {
        return Math.round(artW * FOCUS_SCALE);
    }

    int faceHeight() {
        return Math.round(artH * FOCUS_SCALE);
    }

    /** Children do not overlap, so alpha fades can skip the offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int labelH = MeasureSpec.getSize(heightSpec) - artH;
        art.measure(MeasureSpec.makeMeasureSpec(artW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(artH, MeasureSpec.EXACTLY));
        int labelW = Math.round(artW * 1.3f);
        label.measure(MeasureSpec.makeMeasureSpec(labelW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, labelH - labelGap), MeasureSpec.AT_MOST));
        setMeasuredDimension(artW, MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        art.layout(0, 0, artW, artH);
        int lw = label.getMeasuredWidth();
        int left = (artW - lw) / 2;
        label.layout(left, artH + labelGap, left + lw, artH + labelGap + label.getMeasuredHeight());
    }

    private static boolean isConfirm(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * Holding OK opens the editor as soon as the remote reports a long press (key repeat or
     * FLAG_LONG_PRESS), and the release that follows is swallowed so it never launches the app.
     * The framework's own long-click timer alone misses fast long presses.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode)) {
            if (event.getRepeatCount() == 0 && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) == 0) {
                longPressed = false;
            } else {
                if (!longPressed) {
                    longPressed = true;
                    setPressed(false);
                    performLongClick();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode) && longPressed) {
            longPressed = false;
            setPressed(false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean performLongClick() {
        longPressed = true; // also when the framework's timer fires first: the release must not launch
        return super.performLongClick();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? FOCUS_SCALE : 1f;
        long ms = gained ? 200 : 150;
        // Raise the whole tile above its neighbours; only the banner casts a shadow.
        setTranslationZ(gained ? 1f : 0f);
        art.animate().scaleX(s).scaleY(s).translationZ(gained ? lift : 0f)
                .setDuration(ms).setInterpolator(EASE).start();
        label.animate().alpha(gained ? 1f : 0f).setDuration(ms).setInterpolator(EASE).start();

        if (ringAnim != null) ringAnim.cancel();
        ringAnim = ValueAnimator.ofInt(ring.getAlpha(), gained ? 255 : 0);
        ringAnim.setDuration(ms);
        ringAnim.setInterpolator(EASE);
        ringAnim.addUpdateListener(a -> ring.setAlpha((Integer) a.getAnimatedValue()));
        ringAnim.start();
    }
}
