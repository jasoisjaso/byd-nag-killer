package au.jaso.naglab;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The app's own pop-up: a dark rounded panel with the same type and pill buttons as the rest
 * of the app, instead of the system's grey box with teal capitals. Title, a body that scrolls
 * if it has to, and one row of buttons that stays above the keyboard.
 */
final class Sheet {
    private final MainActivity a;
    private final Dialog d;
    private final LinearLayout body, buttons, panel;
    private Runnable onDismiss;

    Sheet(MainActivity a, String title) {
        this.a = a;
        d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(MainActivity.round(0xFF1E2126, a.dp(28)));
        panel.setPadding(a.dp(36), a.dp(30), a.dp(36), a.dp(24));
        panel.setFocusableInTouchMode(true);   // so a number field doesn't grab focus on open

        TextView t = a.text(title, 28, MainActivity.TEXT, true);
        panel.addView(t);

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(a.dp(24));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = a.dp(14);
        panel.addView(sv, sp);

        buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = a.dp(22);
        panel.addView(buttons, bp);
    }

    /** A paragraph. */
    Sheet text(String s) {
        return text(s, MainActivity.TEXT, 20);
    }

    Sheet note(String s) {
        return text(s, MainActivity.SUB, 17);
    }

    private Sheet text(String s, int color, float sp) {
        TextView t = a.text(s, sp, color, false);
        t.setLineSpacing(a.dp(5), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = body.getChildCount() == 0 ? 0 : a.dp(14);
        body.addView(t, lp);
        return this;
    }

    /** Something to read off and type elsewhere (a web address): big, on its own line. */
    Sheet big(String s) {
        TextView t = a.text(s, 26, MainActivity.ACCENT_TEXT, true);
        t.setTextIsSelectable(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(8);
        body.addView(t, lp);
        return this;
    }

    /** Numbered steps: the number stands clear on the left, the words line up on their own edge. */
    Sheet steps(String... items) {
        for (int i = 0; i < items.length; i++) {
            LinearLayout row = new LinearLayout(a);
            TextView n = a.text(String.valueOf(i + 1), 20, MainActivity.ACCENT_TEXT, true);
            row.addView(n, new LinearLayout.LayoutParams(a.dp(40), -2));
            TextView t = a.text(items[i], 20, MainActivity.TEXT, false);
            t.setLineSpacing(a.dp(5), 1f);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = a.dp(14);
            body.addView(row, lp);
        }
        return this;
    }

    Sheet view(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = body.getChildCount() == 0 ? 0 : a.dp(8);
        body.addView(v, lp);
        return this;
    }

    LinearLayout body() {
        return body;
    }

    /** A pill button. primary = the orange one (one per sheet). Closes the sheet, then runs. */
    Sheet button(String label, boolean primary, final Runnable r) {
        TextView b = a.text(label, 20, primary ? 0xFF16110D : MainActivity.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(a.dp(30), 0, a.dp(30), 0);
        b.setMinWidth(a.dp(150));
        b.setBackground(MainActivity.round(primary ? MainActivity.ACCENT : MainActivity.RAISED, a.dp(34)));
        b.setClickable(true);
        b.setOnClickListener(v -> {
            d.dismiss();
            if (r != null) r.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(68));
        if (buttons.getChildCount() > 0) lp.leftMargin = a.dp(16);
        buttons.addView(b, lp);
        return this;
    }

    Sheet onDismiss(Runnable r) {
        onDismiss = r;
        return this;
    }

    void show() {
        d.setContentView(panel);
        d.setOnDismissListener(x -> {
            if (onDismiss != null) onDismiss.run();
        });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int width = Math.min(a.dp(820), dm.widthPixels - a.dp(48));
            // taller than the screen allows (the car's own bars take the top and bottom): cap it,
            // and the body scrolls inside while the buttons stay put
            int max = dm.heightPixels - a.dp(140);
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            w.setLayout(width, panel.getMeasuredHeight() > max ? max : WindowManager.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.72f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);   // keyboard only when a field is tapped   // buttons stay above the keyboard
        }
        // settles in: a small rise and fade, like the tabs
        panel.setAlpha(0f);
        panel.setTranslationY(a.dp(18));
        d.show();
        panel.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
    }
}
