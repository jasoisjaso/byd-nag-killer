package au.jaso.naglab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Big, finger-friendly on/off switch drawn by hand (the stock Switch is tiny on the car).
 *  On = brand orange, knob right; off = grey, knob left. The row next to it says the state in
 *  words, so neither colour nor knob position has to carry it alone. */
final class Toggle extends View {
    interface OnChange {
        void changed(boolean on);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean on;
    private float pos;          // 0 = off, 1 = on (animated)
    private OnChange listener;
    private String label = "Switch";
    private android.animation.ValueAnimator anim;

    Toggle(Context c) {
        super(c);
        knob.setColor(MainActivity.TEXT);
        setClickable(true);
        describe();
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    /** What TalkBack reads, e.g. "Lane assist, Keep off" (", on" / ", off" is added). */
    void setLabel(String l) {
        label = l;
        describe();
    }

    private void describe() {
        setContentDescription(label + ", " + (on ? "on" : "off"));
    }

    void setOn(boolean v, boolean animate) {
        if (v == on && (pos == (v ? 1f : 0f))) return;
        on = v;
        describe();
        if (!animate) {
            if (anim != null) anim.cancel();
            pos = v ? 1f : 0f;
            invalidate();
            return;
        }
        if (anim != null) anim.cancel();   // rapid taps: one animator owns pos
        final float from = pos, to = v ? 1f : 0f;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
        anim = a;
        a.setDuration(160);
        a.addUpdateListener(an -> {
            pos = (float) an.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    boolean isOn() {
        return on;
    }

    /** A tap on the switch or anywhere on its row. */
    void flip() {
        if (!isEnabled()) return;
        setOn(!on, true);
        if (listener != null) listener.changed(on);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        // Only a release over the switch counts (sliding off it cancels, like a stock button).
        if (e.getActionMasked() == MotionEvent.ACTION_UP
                && e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
            flip();
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        // Off track 0xFF6B7078 = 3.4:1 on the card; on = icon orange 4.9:1 on the card.
        int offC = isEnabled() ? MainActivity.DIM : MainActivity.RAISED;
        int onC = isEnabled() ? MainActivity.ACCENT : MainActivity.ACCENT_DEEP;
        track.setColor(blend(offC, onC, pos));
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, h / 2f, h / 2f, track);
        float pad = h * 0.12f, kr = h / 2f - pad;
        float cx = pad + kr + (w - 2 * (pad + kr)) * pos;
        knob.setAlpha(isEnabled() ? 255 : 110);
        cv.drawCircle(cx, h / 2f, kr, knob);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
