package au.jaso.naglab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Seven bars (km per day, today on the right) with one-letter day labels. Drawn directly:
 *  no chart library, nothing to load. */
final class WeekBars extends View {
    private float[] km = new float[7];
    private float grow = 1f;
    private android.animation.ValueAnimator anim;

    /** The bars rise from the baseline: 0.5 s, fast then settling. */
    void replay() {
        if (anim != null) anim.cancel();
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(500);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
        anim.addUpdateListener(v -> {
            grow = (Float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private int hi = -1;   // the bar that is "now"; -1 = the last one
    private String[] labels = {"", "", "", "", "", "", ""};
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float density;

    WeekBars(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        label.setTextSize(18 * c.getResources().getDisplayMetrics().scaledDensity);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void set(float[] km, String[] labels) {
        this.km = km;
        this.labels = labels;
        set(km, labels, "Kilometres per day this week:");
    }

    void set(float[] km, String[] labels, String what, int now) {
        hi = now;
        set(km, labels, what);
    }

    /** Any number of bars; the last one is "now" (orange) unless set(.., now) said otherwise. */
    void set(float[] km, String[] labels, String what) {
        this.km = km;
        this.labels = labels;
        StringBuilder d = new StringBuilder(what);
        for (int i = 0; i < km.length; i++) d.append(' ').append(labels[i]).append(' ').append(Trips.km(km[i]));
        setContentDescription(d);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float labelH = 28 * density;
        int n = km.length;
        float slot = w / n, gap = Math.min(14 * density, slot * 0.35f);
        float bw = Math.min(slot - gap, 40 * density);
        float max = 0;
        for (float v : km) max = Math.max(max, v);
        float top = 4 * density, bottom = h - labelH;
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2f;
            float bh = max > 0 ? (bottom - top) * km[i] / max : 0;
            if (km[i] > 0) bh = Math.max(bh, 6 * density);
            if (labels[i].isEmpty() && km[i] <= 0 && n > 12) {   // month view: a dot per empty day
                bar.setColor(MainActivity.LINE);
                c.drawCircle(cx, bottom - 2 * density, 2 * density, bar);
                continue;
            }   // a short drive still shows
            boolean today = i == (hi >= 0 ? hi : n - 1);
            // empty days: a faint stub so the week still reads as seven slots
            bar.setColor(km[i] > 0 ? (today ? MainActivity.ACCENT : MainActivity.SUB) : MainActivity.RAISED);
            float hh = km[i] > 0 ? Math.max(4 * density, bh * grow) : 4 * density;
            r.set(cx - bw / 2f, bottom - hh, cx + bw / 2f, bottom);
            c.drawRoundRect(r, 6 * density, 6 * density, bar);
            label.setColor(today ? MainActivity.TEXT : MainActivity.SUB);
            c.drawText(labels[i], cx, h - 6 * density, label);
        }
    }
}
