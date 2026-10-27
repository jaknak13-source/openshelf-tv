package tv.openshelf.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One horizontal row. Owns left/right focus movement (no wrap-around), remembers the last
 * focused tile, and scrolls so the focused tile always stays inside the safe area.
 */
final class TileRow extends ViewGroup {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    final int rowId;
    final int artW;
    final int artH;
    private final int tileH;
    private final int gap;
    private final int inset;
    private final List<TileView> tiles = new ArrayList<>();
    private final TextView empty;
    private int remembered;
    private int targetScroll;
    private ValueAnimator scrollAnim;

    TileRow(Context c, int rowId, int artW, int artH, int labelH, int gap, int inset, String emptyText) {
        super(c);
        this.rowId = rowId;
        this.artW = artW;
        this.artH = artH;
        this.tileH = artH + labelH;
        this.gap = gap;
        this.inset = inset;
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);

        empty = new TextView(c);
        empty.setText(emptyText);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        empty.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        empty.setTextColor(0xB3FFFFFF);
        empty.setVisibility(GONE);
        addView(empty);
    }

    List<TileView> tiles() {
        return tiles;
    }

    boolean isEmpty() {
        return tiles.isEmpty();
    }

    /** Keeps existing TileView instances that are still present so focus survives a rebuild. */
    void setTiles(List<TileView> next) {
        for (TileView old : tiles) {
            if (!next.contains(old)) removeView(old);
        }
        for (TileView t : next) {
            if (t.getParent() != this) addView(t);
        }
        tiles.clear();
        tiles.addAll(next);
        empty.setVisibility(tiles.isEmpty() ? VISIBLE : GONE);
        remembered = Math.min(remembered, Math.max(0, tiles.size() - 1));
        int max = maxScroll(getWidth());
        if (getScrollX() > max) scrollTo(max, 0);
        targetScroll = Math.min(targetScroll, max);
        updateEdgeFade(false);
        requestLayout();
    }

    /**
     * Tiles scrolled past the left safe edge fade out, so the row's left side always lines up
     * with its title instead of showing a cut-off sliver. The right side keeps its peek.
     */
    private void updateEdgeFade(boolean animate) {
        for (int i = 0; i < tiles.size(); i++) {
            int left = inset + i * (artW + gap) - targetScroll;
            float a = left < inset - gap ? 0f : 1f;
            TileView t = tiles.get(i);
            if (t.getAlpha() == a) continue;
            t.animate().cancel();
            if (animate) t.animate().alpha(a).setDuration(160).start();
            else t.setAlpha(a);
        }
    }

    TileView find(String key) {
        for (TileView t : tiles) {
            if (t.entry.key.equals(key)) return t;
        }
        return null;
    }

    TileView rememberedTile() {
        return tiles.isEmpty() ? null : tiles.get(Math.min(remembered, tiles.size() - 1));
    }

    TileView tileAt(int index) {
        if (tiles.isEmpty()) return null;
        return tiles.get(Math.max(0, Math.min(index, tiles.size() - 1)));
    }

    int indexOf(TileView t) {
        return tiles.indexOf(t);
    }

    void resetToStart() {
        remembered = 0;
        if (scrollAnim != null) scrollAnim.cancel();
        targetScroll = 0;
        scrollTo(0, 0);
        updateEdgeFade(false);
    }

    /** Called for every focus change inside this row. */
    void onTileFocused(TileView t, boolean animate) {
        int i = tiles.indexOf(t);
        if (i < 0) return;
        remembered = i;
        scrollToTile(i, animate);
    }

    /** Horizontal centre of tile {@code i} on screen once the current scroll has settled. */
    int settledCenterX(int i) {
        return inset + i * (artW + gap) + artW / 2 - targetScroll;
    }

    private void scrollToTile(int i, boolean animate) {
        int w = getWidth();
        if (w == 0) return;
        // Scroll in whole tile steps, so the leftmost visible tile always sits on the title's edge.
        int pitch = artW + gap;
        int left = inset + i * pitch;
        int right = left + artW;
        int target = targetScroll;
        if (left - target < inset) target = ((left - inset) / pitch) * pitch;
        if (right - target > w - inset) {
            int over = right - (w - inset);
            target = ((over + pitch - 1) / pitch) * pitch;
        }
        target = Math.max(0, Math.min(target, maxScroll(w)));
        if (target == targetScroll && (scrollAnim == null || !scrollAnim.isRunning())
                && getScrollX() == target) {
            return;
        }
        targetScroll = target;
        updateEdgeFade(animate);
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scrollTo(target, 0);
            return;
        }
        scrollAnim = ValueAnimator.ofInt(getScrollX(), target);
        scrollAnim.setDuration(240);
        scrollAnim.setInterpolator(EASE);
        scrollAnim.addUpdateListener(a -> scrollTo((Integer) a.getAnimatedValue(), 0));
        scrollAnim.start();
    }

    /** Far enough that the last tile fits, rounded up to a whole tile step. */
    private int maxScroll(int w) {
        if (tiles.isEmpty() || w == 0) return 0;
        int pitch = artW + gap;
        int contentRight = inset + tiles.size() * pitch - gap;
        int over = Math.max(0, contentRight + inset - w);
        return ((over + pitch - 1) / pitch) * pitch;
    }

    @Override
    public View focusSearch(View focused, int direction) {
        if (direction == FOCUS_LEFT || direction == FOCUS_RIGHT) {
            int i = tiles.indexOf(focused);
            if (i >= 0) {
                int j = i + (direction == FOCUS_LEFT ? -1 : 1);
                // At either end focus stays put: no wrap-around, no jump into another row.
                return j >= 0 && j < tiles.size() ? tiles.get(j) : focused;
            }
        }
        return super.focusSearch(focused, direction);
    }

    /** Children do not overlap, so alpha fades can skip the offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int tileSpecW = MeasureSpec.makeMeasureSpec(artW, MeasureSpec.EXACTLY);
        int tileSpecH = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY);
        for (TileView t : tiles) t.measure(tileSpecW, tileSpecH);
        empty.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w - 2 * inset), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(artH, MeasureSpec.AT_MOST));
        setMeasuredDimension(w, tileH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < tiles.size(); i++) {
            int x = inset + i * (artW + gap);
            tiles.get(i).layout(x, 0, x + artW, tileH);
        }
        int ey = (artH - empty.getMeasuredHeight()) / 2;
        empty.layout(inset, ey, inset + empty.getMeasuredWidth(), ey + empty.getMeasuredHeight());
        if (changed) {
            int max = maxScroll(r - l);
            targetScroll = Math.min(targetScroll, max);
            if (getScrollX() > max) scrollTo(max, 0);
        }
    }
}
