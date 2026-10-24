package au.jaso.naglab;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Floating "kill all nags" button drawn over every app (TYPE_APPLICATION_OVERLAY).
 * Cost: one static ImageView in the window manager, no timers, no polling; it only does work
 * when tapped. Tap = kill every nag now. Long-press then drag = move it (position saved).
 * Needs the "display over other apps" app-op: install.sh grants it with
 * appops set au.jaso.naglab SYSTEM_ALERT_WINDOW allow
 */
final class QuickButton {
    static final String KEY_SHOW = "quick_button";
    private static final String KEY_X = "quick_x", KEY_Y = "quick_y";

    interface Action {
        void killAll();
    }

    private final Context c;
    private final Action action;
    private final WindowManager wm;
    private FrameLayout view;
    private ImageView img;
    private View dot;
    private int badge = -1;
    private WindowManager.LayoutParams lp;
    private LinearLayout bubble;
    private final Runnable hideBubble = this::removeBubble;

    QuickButton(Context c, Action action) {
        this.c = c;
        this.action = action;
        this.wm = c.getSystemService(WindowManager.class);
    }

    static boolean wanted(Context c) {
        return prefs(c).getBoolean(KEY_SHOW, false);
    }

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE);
    }

    /** Show or hide to match the saved choice. Main thread only. */
    void sync() {
        boolean want = wanted(c) && allowed(c);
        if (want && view == null) show();
        else if (!want && view != null) hide();
    }

    private int dp(float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private void show() {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int size = dp(64);
        view = new FrameLayout(c);
        img = new ImageView(c);
        img.setImageResource(R.drawable.quick);
        view.addView(img, new FrameLayout.LayoutParams(-1, -1));
        // Status dot, bottom-right: green = everything this button covers is off,
        // orange = something it covers is on, hidden = not read yet.
        dot = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2.5f), 0xFF0F1013);
        dot.setBackground(g);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(20), dp(20));
        dlp.gravity = Gravity.BOTTOM | Gravity.END;
        view.addView(dot, dlp);
        applyBadge();
        view.setAlpha(0.92f);
        view.setContentDescription("Kill nags");
        view.setElevation(dp(6));

        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Default: right edge, a bit below the middle (clear of the BYD status bar and dock).
        lp.x = prefs(c).getInt(KEY_X, dm.widthPixels - size - dp(16));
        lp.y = prefs(c).getInt(KEY_Y, (int) (dm.heightPixels * 0.55f));
        view.setOnTouchListener(new Drag());
        try {
            wm.addView(view, lp);
            Log.w(NagService.TAG, "quick button shown");
        } catch (RuntimeException e) {
            Log.w(NagService.TAG, "quick button: cannot add overlay: " + e);
            view = null;
        }
    }

    /** 1 = something in the set is on, 0 = all off, -1 = unknown. Main thread. */
    void setBadge(int b) {
        badge = b;
        applyBadge();
    }

    private void applyBadge() {
        if (dot == null) return;
        dot.setVisibility(badge < 0 ? View.GONE : View.VISIBLE);
        ((GradientDrawable) dot.getBackground()).setColor(badge == 1 ? 0xFFFF7A45 : 0xFF7FD1A8);
    }

    /** Small card beside the button for 3.5 s: what each nag in the set did. Main thread. */
    void showResult(List<String[]> lines, boolean allOff) {
        if (view == null) return;
        removeBubble();
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        bubble = new LinearLayout(c);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01A1C21);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF2F333A);
        bubble.setBackground(bg);
        bubble.setElevation(dp(8));

        TextView head = new TextView(c);
        head.setText(lines.isEmpty() ? "Nothing selected" : allOff ? "All quiet" : "Not everything went off");
        head.setTextSize(19);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setTextColor(allOff && !lines.isEmpty() ? 0xFF7FD1A8 : 0xFFF2B65A);
        head.setPadding(0, 0, 0, dp(6));
        bubble.addView(head);
        if (lines.isEmpty()) {
            bubble.addView(line("Pick nags for the button in the Nag Killer app.", "", 0xFF9BA0A8));
        }
        for (String[] l : lines) {
            int col = l[1].equals("switched off") ? 0xFF7FD1A8
                    : l[1].equals("already off") ? 0xFF9BA0A8 : 0xFFFF7A45;
            bubble.addView(line(l[0], l[1], col));
        }

        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int bw = bubble.getMeasuredWidth(), bh = bubble.getMeasuredHeight();
        int size = dp(64);
        WindowManager.LayoutParams bl = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        bl.gravity = Gravity.TOP | Gravity.START;
        boolean rightHalf = lp.x + size / 2 > dm.widthPixels / 2;
        bl.x = rightHalf ? lp.x - bw - dp(12) : lp.x + size + dp(12);
        bl.y = Math.max(dp(8), Math.min(lp.y + size / 2 - bh / 2, dm.heightPixels - bh - dp(8)));
        try {
            wm.addView(bubble, bl);
        } catch (RuntimeException e) {
            Log.w(NagService.TAG, "quick button: cannot show result: " + e);
            bubble = null;
            return;
        }
        bubble.setAlpha(0f);
        bubble.animate().alpha(1f).setDuration(150);
        view.postDelayed(hideBubble, 3500);
    }

    private View line(String name, String what, int color) {
        LinearLayout r = new LinearLayout(c);
        r.setPadding(0, dp(3), 0, dp(3));
        TextView a = new TextView(c);
        a.setText(name);
        a.setTextSize(16);
        a.setTextColor(0xFFF4F5F7);
        r.addView(a);
        TextView b = new TextView(c);
        b.setText(what);
        b.setTextSize(16);
        b.setTextColor(color);
        b.setPadding(dp(24), 0, 0, 0);
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(0, -2, 1f);
        b.setGravity(Gravity.END);
        r.addView(b, lpb);
        return r;
    }

    private void removeBubble() {
        if (view != null) view.removeCallbacks(hideBubble);
        if (bubble == null) return;
        try {
            wm.removeView(bubble);
        } catch (RuntimeException ignored) {
        }
        bubble = null;
    }

    void hide() {
        removeBubble();
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
    }

    /** Quick press pulse; the result card that follows is the real answer. */
    private void confirm() {
        if (view == null) return;
        final View v = view;
        v.animate().cancel();
        v.animate().scaleX(0.86f).scaleY(0.86f).alpha(1f).setDuration(90).withEndAction(
                () -> v.animate().scaleX(1f).scaleY(1f).alpha(0.92f).setDuration(160));
    }

    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;
        private long downAt;
        private final Runnable arm = () -> {
            dragging = true;
            if (view != null) view.setAlpha(0.6f);
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    downAt = e.getEventTime();
                    v.postDelayed(arm, 450);   // long-press arms dragging
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        lp.x = startX + Math.round(e.getRawX() - downX);
                        lp.y = startY + Math.round(e.getRawY() - downY);
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (RuntimeException ignored) {
                        }
                    } else if (Math.abs(e.getRawX() - downX) > dp(12)
                            || Math.abs(e.getRawY() - downY) > dp(12)) {
                        v.removeCallbacks(arm);   // a swipe that started on us: ignore it
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.removeCallbacks(arm);
                    if (dragging) {
                        dragging = false;
                        v.setAlpha(0.92f);
                        prefs(c).edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
                    } else if (e.getEventTime() - downAt < 450
                            && Math.abs(e.getRawX() - downX) <= dp(12)
                            && Math.abs(e.getRawY() - downY) <= dp(12)) {
                        confirm();
                        action.killAll();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(arm);
                    dragging = false;
                    v.setAlpha(0.92f);
                    return true;
                default:
                    return false;
            }
        }
    }
}
