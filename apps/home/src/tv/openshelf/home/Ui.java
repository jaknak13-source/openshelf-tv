package tv.openshelf.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Shared type and control styles. Ink is white at fixed opacities; the only accent is light itself. */
final class Ui {
    static final int INK = 0xF5FFFFFF;
    static final int INK_QUIET = 0xA6FFFFFF;
    static final int PANEL = 0xF2161A20;

    private Ui() {}

    static float dp(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    static TextView text(Context c, float sp, String family, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return t;
    }

    /** List-style action: quiet at rest, a light plate when focused or hovered, dim when disabled. */
    static Button action(Context c, String label) {
        float dp = dp(c);
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);
        b.setMinHeight(Math.round(44 * dp));
        b.setMinimumHeight(Math.round(44 * dp));
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setBackground(plate(c));
        b.setTextColor(new ColorStateList(
                new int[][] {
                        {-android.R.attr.state_enabled},
                        {android.R.attr.state_focused},
                        {android.R.attr.state_hovered},
                        {}},
                new int[] {0x52FFFFFF, 0xFF111418, 0xFF111418, 0xE6FFFFFF}));
        return b;
    }

    static Drawable plate(Context c) {
        float r = 6 * dp(c);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_focused}, rounded(0xF0EEF0F3, r));
        s.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_hovered}, rounded(0xF0EEF0F3, r));
        s.addState(new int[] {android.R.attr.state_pressed}, rounded(0xFFD5D8DD, r));
        s.addState(new int[] {}, new ColorDrawable(0x00000000));
        return s;
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }
}
