package tv.openshelf.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The vertical stack of rows. Up/down always lands on a row's remembered tile, and the focused
 * row slides to the same anchor line as the first row, so the eye never has to hunt for focus.
 */
final class Column extends ViewGroup {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    private final List<TextView> titles = new ArrayList<>();
    private final List<TileRow> rows = new ArrayList<>();
    private final int padTop;
    private final int inset;
    private final int titleGap;
    private final int sectionGap;
    private int targetScroll;
    private ValueAnimator scrollAnim;

    Column(Context c, int padTop, int inset, int titleGap, int sectionGap) {
        super(c);
        this.padTop = padTop;
        this.inset = inset;
        this.titleGap = titleGap;
        this.sectionGap = sectionGap;
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);
    }

    void addRow(TextView title, TileRow row) {
        titles.add(title);
        rows.add(row);
        addView(title);
        addView(row);
    }

    List<TileRow> rows() {
        return rows;
    }

    int indexOfRow(TileRow r) {
        return rows.indexOf(r);
    }

    TileRow rowOf(View v) {
        View child = v;
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == this) return child instanceof TileRow ? (TileRow) child : null;
            child = (View) p;
            p = p.getParent();
        }
        return null;
    }

    /** Top edge of row {@code i}'s banners on screen (relative to this view) after scrolling settles. */
    int settledArtTop(int i) {
        return rows.get(i).getTop() - targetScroll;
    }

    void scrollToRow(int i, boolean animate) {
        // Only the focused row and the one below it are shown: nothing half-cut under the clock
        // or at the bottom edge, and no fading-edge layer to pay for on every frame.
        for (int j = 0; j < rows.size(); j++) {
            float a = j < i || j > i + 1 ? 0f : 1f;
            for (View v : new View[] {titles.get(j), rows.get(j)}) {
                if (v.getAlpha() == a && !animate) continue;
                v.animate().cancel();
                if (animate) v.animate().alpha(a).setDuration(200).start();
                else v.setAlpha(a);
            }
        }
        int target = Math.max(0, titles.get(i).getTop() - padTop);
        if (target == targetScroll && getScrollY() == target) return;
        targetScroll = target;
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scrollTo(0, target);
            return;
        }
        scrollAnim = ValueAnimator.ofInt(getScrollY(), target);
        scrollAnim.setDuration(280);
        scrollAnim.setInterpolator(EASE);
        scrollAnim.addUpdateListener(a -> scrollTo(0, (Integer) a.getAnimatedValue()));
        scrollAnim.start();
    }

    @Override
    public View focusSearch(View focused, int direction) {
        if (direction == FOCUS_UP || direction == FOCUS_DOWN) {
            TileRow from = rowOf(focused);
            if (from != null) {
                int step = direction == FOCUS_UP ? -1 : 1;
                for (int i = rows.indexOf(from) + step; i >= 0 && i < rows.size(); i += step) {
                    if (!rows.get(i).isEmpty()) return rows.get(i).rememberedTile();
                }
                return focused; // top or bottom row: stay
            }
        }
        return super.focusSearch(focused, direction);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < rows.size(); i++) {
            titles.get(i).measure(MeasureSpec.makeMeasureSpec(w - 2 * inset, MeasureSpec.AT_MOST), unbounded);
            rows.get(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), unbounded);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int y = padTop;
        for (int i = 0; i < rows.size(); i++) {
            TextView title = titles.get(i);
            title.layout(inset, y, inset + title.getMeasuredWidth(), y + title.getMeasuredHeight());
            y += title.getMeasuredHeight() + titleGap;
            TileRow row = rows.get(i);
            row.layout(0, y, r - l, y + row.getMeasuredHeight());
            y += row.getMeasuredHeight() + sectionGap;
        }
    }
}
