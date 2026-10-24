package au.jaso.naglab;

import android.animation.ValueAnimator;
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
import android.widget.ImageView;

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
    private ImageView view;
    private WindowManager.LayoutParams lp;

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
        view = new ImageView(c);
        view.setImageResource(R.drawable.quick);
        view.setAlpha(0.88f);
        view.setContentDescription("Kill all nags");
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

    void hide() {
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
    }

    /** Spin + pulse so the driver sees the tap landed without reading anything. */
    private void confirm() {
        if (view == null) return;
        final View v = view;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(520);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            float s = 1f + 0.18f * (float) Math.sin(Math.PI * t);
            v.setScaleX(s);
            v.setScaleY(s);
            v.setRotation(360f * t);
            v.setAlpha(1f);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                v.setRotation(0f);
                v.setAlpha(0.88f);
            }
        });
        a.start();
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
                        v.setAlpha(0.88f);
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
                    v.setAlpha(0.88f);
                    return true;
                default:
                    return false;
            }
        }
    }
}
