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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating button drawn over every app (TYPE_APPLICATION_OVERLAY) that switches the button
 * set off again. Cost: one static ImageView in the window manager, no timers, no polling; it
 * only does work when tapped. Tap = switch off (never switches one on), card beside it says
 * "Switching off" straight away and then what the car reported.
 * Hold, then drag = move it (position saved). A hold without moving is still a tap.
 * Needs the "display over other apps" app-op: install.sh grants it with
 * appops set au.jaso.naglab SYSTEM_ALERT_WINDOW allow
 */
final class QuickButton {
    static final String KEY_SHOW = "quick_button";
    private static final String KEY_X = "quick_x", KEY_Y = "quick_y";
    private static final int SIZE_DP = 80;
    private static final long HOLD_MS = 400;   // hold this long before a move drags the button

    interface Action {
        void killAll();
    }

    private final Context c;
    private final Action action;
    private final WindowManager wm;
    private FrameLayout view;
    private Mark dot;
    private int badge;
    private WindowManager.LayoutParams lp;
    private LinearLayout bubble;
    private WindowManager.LayoutParams bubbleLp;
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

    /** Show or hide to match the saved choice. Hidden while the app screen is open: its big
     *  button does the same, and the overlay would sit on top of the app's own header. */
    void sync() {
        boolean want = wanted(c) && allowed(c) && !NagService.uiOpen;
        if (want && view == null) show();
        else if (!want && view != null) hide();
    }

    private int dp(float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private void show() {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int size = dp(SIZE_DP);
        view = new FrameLayout(c);
        ImageView img = new ImageView(c);
        img.setImageResource(R.drawable.quick);
        view.addView(img, new FrameLayout.LayoutParams(-1, -1));
        // Attention mark, bottom-right: only when a nag you keep off is on right now.
        // Amber with a "!", so it does not rest on colour.
        dot = new Mark(c);
        dot.setBorder(MainActivity.BG, dp(3));
        dot.set(Mark.ALERT, MainActivity.WARN);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(28), dp(28));
        dlp.gravity = Gravity.BOTTOM | Gravity.END;
        view.addView(dot, dlp);
        applyBadge();
        view.setContentDescription("Switch off the button set");
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
        // Saved from the old 64 dp button: keep the bigger one on screen.
        lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - size));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - size));
        view.setOnTouchListener(new Drag());
        try {
            wm.addView(view, lp);
            Log.w(NagService.TAG, "quick button shown");
        } catch (RuntimeException e) {
            Log.w(NagService.TAG, "quick button: cannot add overlay: " + e);
            view = null;
        }
    }

    /** 1 = a nag in the button set is on right now, 0 = all off, -1 = the button set is empty.
     *  Main thread. */
    void setBadge(int b) {
        badge = b;
        applyBadge();
    }

    private void applyBadge() {
        if (dot != null) dot.setVisibility(badge == 1 ? View.VISIBLE : View.GONE);
        if (view != null) view.setAlpha(rest());
    }

    /** Resting look: dimmed when there is nothing to switch off. */
    private float rest() {
        return badge < 0 ? 0.5f : 0.92f;
    }

    /** Straight after a tap: the card shell, so the driver sees the tap landed. */
    private void showWorking(List<String> names) {
        if (!fillCard(Mark.BUSY, MainActivity.SUB, "Switching off", MainActivity.TEXT)) return;
        for (String n : names) bubble.addView(line(n, "...", MainActivity.SUB));
        present(10_000);   // the result replaces it in ~3 s; this is only a fallback
    }

    /** What each nag in the button set did. Main thread. */
    void showResult(List<String[]> lines, int verdict) {
        int kind, col, headCol;
        switch (verdict) {
            case Report.ALL_OFF:
                kind = Mark.OK;
                col = MainActivity.ACCENT;
                headCol = MainActivity.ACCENT_TEXT;
                break;
            case Report.FAILED:
                kind = Mark.ALERT;
                col = MainActivity.WARN;
                headCol = MainActivity.WARN;
                break;
            case Report.UNKNOWN:
                kind = Mark.UNKNOWN;
                col = MainActivity.SUB;
                headCol = MainActivity.TEXT;
                break;
            default:
                kind = Mark.IDLE;
                col = MainActivity.SUB;
                headCol = MainActivity.TEXT;
                break;
        }
        if (!fillCard(kind, col, Report.header(verdict), headCol)) return;
        if (lines.isEmpty()) bubble.addView(line(Report.NOTHING_HINT, "", MainActivity.SUB));
        for (String[] l : lines) {
            int lc = Report.isOff(l[1]) ? MainActivity.ACCENT_TEXT
                    : Report.STILL_ON.equals(l[1]) ? MainActivity.WARN : MainActivity.SUB;
            bubble.addView(line(l[0], l[1], lc));
        }
        present(verdict == Report.ALL_OFF ? 4000 : 6000);
    }

    /** Empty the card (or make it) and put the header in. False if the button is not shown. */
    private boolean fillCard(int markKind, int markColor, String title, int titleColor) {
        if (view == null) return false;
        if (bubble == null) {
            bubble = new LinearLayout(c);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(20), dp(16), dp(22), dp(16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF51A1C21);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), MainActivity.RAISED);
            bubble.setBackground(bg);
            bubble.setElevation(dp(8));
        }
        bubble.removeAllViews();
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(8));
        Mark m = new Mark(c);
        m.set(markKind, markColor);
        head.addView(m, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(24);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(titleColor);
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        bubble.addView(head);
        return true;
    }

    /** Add the card beside the button, or re-place it after its content changed. */
    private void present(long hideAfterMs) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int bw = bubble.getMeasuredWidth(), bh = bubble.getMeasuredHeight();
        int size = dp(SIZE_DP);
        boolean fresh = bubbleLp == null;
        if (fresh) {
            bubbleLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            bubbleLp.gravity = Gravity.TOP | Gravity.START;
        }
        boolean rightHalf = lp.x + size / 2 > dm.widthPixels / 2;
        bubbleLp.x = rightHalf ? lp.x - bw - dp(12) : lp.x + size + dp(12);
        bubbleLp.y = Math.max(dp(8), Math.min(lp.y + size / 2 - bh / 2, dm.heightPixels - bh - dp(8)));
        try {
            if (fresh) wm.addView(bubble, bubbleLp);
            else wm.updateViewLayout(bubble, bubbleLp);
        } catch (RuntimeException e) {
            Log.w(NagService.TAG, "quick button: cannot show card: " + e);
            bubble = null;
            bubbleLp = null;
            return;
        }
        if (fresh) {
            bubble.setAlpha(0f);
            bubble.animate().alpha(1f).setDuration(120);
        }
        view.removeCallbacks(hideBubble);
        view.postDelayed(hideBubble, hideAfterMs);
    }

    private View line(String name, String what, int color) {
        LinearLayout r = new LinearLayout(c);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView a = new TextView(c);
        a.setText(name);
        a.setTextSize(18);
        a.setTextColor(MainActivity.TEXT);
        r.addView(a);
        TextView b = new TextView(c);
        b.setText(what);
        b.setTextSize(18);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(color);
        b.setPadding(dp(28), 0, 0, 0);
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
        bubbleLp = null;
    }

    void hide() {
        removeBubble();
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
        dot = null;
    }

    /** Press pulse, then the card. */
    private void tapped() {
        if (view == null) return;
        final View v = view;
        v.animate().cancel();
        v.animate().scaleX(0.86f).scaleY(0.86f).alpha(1f).setDuration(90).withEndAction(
                () -> v.animate().scaleX(1f).scaleY(1f).alpha(rest()).setDuration(160));
        List<String> set = NagService.buttonSet(c);
        if (set.isEmpty()) {
            showResult(new ArrayList<String[]>(), Report.NOTHING);
            return;
        }
        List<String> names = new ArrayList<>();
        for (String n : set) names.add(NagService.label(n));
        showWorking(names);
        action.killAll();
    }

    /**
     * Tap = any press that ends without moving past the slop, however long it was held.
     * Drag = held for HOLD_MS, then moved past the slop. A quick swipe that starts on the
     * button (moved before the hold time) does nothing, so brushing past it is harmless.
     */
    private final class Drag implements View.OnTouchListener {
        private final int slop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), dp(12));
        private float downX, downY;
        private int startX, startY;
        private boolean held, dragging, swiped;
        private final Runnable arm = () -> held = true;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    held = dragging = swiped = false;
                    v.postDelayed(arm, HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    boolean moved = Math.abs(e.getRawX() - downX) > slop
                            || Math.abs(e.getRawY() - downY) > slop;
                    if (!dragging && moved) {
                        v.removeCallbacks(arm);
                        if (held && !swiped) {
                            dragging = true;
                            v.setAlpha(0.6f);
                        } else {
                            swiped = true;   // moved before the hold: not a tap, not a drag
                        }
                    }
                    if (dragging) {
                        lp.x = startX + Math.round(e.getRawX() - downX);
                        lp.y = startY + Math.round(e.getRawY() - downY);
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.removeCallbacks(arm);
                    if (dragging) {
                        dragging = false;
                        v.setAlpha(rest());
                        prefs(c).edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
                    } else if (!swiped) {
                        tapped();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(arm);
                    dragging = false;
                    v.setAlpha(rest());
                    return true;
                default:
                    return false;
            }
        }
    }
}
