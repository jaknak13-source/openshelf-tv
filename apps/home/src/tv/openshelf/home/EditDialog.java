package tv.openshelf.home;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small action sheet for one tile. Moves keep the sheet open so a tile can be walked several
 * places in a row; favourite and hide close it because the tile leaves this row.
 */
final class EditDialog extends Dialog {
    interface Host {
        boolean isFavorite(Entry e);
        /** {index, count} of the entry in its current row. */
        int[] position(Entry e);
        void toggleFavorite(Entry e);
        void move(Entry e, int delta);
        void hide(Entry e);
    }

    private final Entry entry;
    private final Host host;
    private Button left;
    private Button right;

    EditDialog(Context c, Entry entry, Host host) {
        super(c, R.style.Theme_Home_Dialog);
        this.entry = entry;
        this.host = host;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Context c = getContext();
        float dp = Ui.dp(c);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.rounded(Ui.PANEL, 12 * dp));
        int pad = Math.round(14 * dp);
        panel.setPadding(pad, Math.round(20 * dp), pad, pad);

        TextView title = Ui.text(c, 20, "sans-serif-light", Ui.INK);
        title.setText(entry.label);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), Math.round(14 * dp));
        panel.addView(title);

        boolean fav = host.isFavorite(entry);
        Button favorite = Ui.action(c, c.getString(fav ? R.string.edit_remove_favorite : R.string.edit_add_favorite));
        favorite.setOnClickListener(v -> {
            dismiss();
            host.toggleFavorite(entry);
        });
        left = Ui.action(c, c.getString(R.string.edit_move_left));
        left.setOnClickListener(v -> {
            host.move(entry, -1);
            refresh(left);
        });
        right = Ui.action(c, c.getString(R.string.edit_move_right));
        right.setOnClickListener(v -> {
            host.move(entry, 1);
            refresh(right);
        });
        Button hide = Ui.action(c, c.getString(R.string.edit_hide));
        hide.setOnClickListener(v -> {
            dismiss();
            host.hide(entry);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(2 * dp);
        for (Button b : new Button[] {favorite, left, right, hide}) panel.addView(b, lp);

        setContentView(panel);
        Window w = getWindow();
        if (w != null) {
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.width = Math.round(320 * dp);
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attrs.gravity = Gravity.CENTER;
            w.setAttributes(attrs);
        }
        refresh(null);
        favorite.requestFocus();
    }

    /** Disables moves that would leave the row; keeps focus on a usable button. */
    private void refresh(View pressed) {
        int[] pos = host.position(entry);
        left.setEnabled(pos[0] > 0);
        left.setFocusable(left.isEnabled());
        right.setEnabled(pos[0] >= 0 && pos[0] < pos[1] - 1);
        right.setFocusable(right.isEnabled());
        if (pressed != null && !pressed.isEnabled()) {
            View other = pressed == left ? right : left;
            if (other.isEnabled()) other.requestFocus();
        }
    }
}
