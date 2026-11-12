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

    /** Any number of bars; the last one is "now" (orange). */
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
        float labelH = 28 * density, gap = 14 * density;
        int n = km.length;
        float slot = w / n, bw = Math.min(slot - gap, 40 * density);
        float max = 0;
        for (float v : km) max = Math.max(max, v);
        float top = 4 * density, bottom = h - labelH;
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2f;
            float bh = max > 0 ? (bottom - top) * km[i] / max : 0;
            if (km[i] > 0) bh = Math.max(bh, 6 * density);   // a short drive still shows
            boolean today = i == n - 1;
            // empty days: a faint stub so the week still reads as seven slots
            bar.setColor(km[i] > 0 ? (today ? MainActivity.ACCENT : MainActivity.SUB) : MainActivity.LINE);
            float hh = km[i] > 0 ? bh : 4 * density;
            r.set(cx - bw / 2f, bottom - hh, cx + bw / 2f, bottom);
            c.drawRoundRect(r, 6 * density, 6 * density, bar);
            label.setColor(today ? MainActivity.TEXT : MainActivity.SUB);
            c.drawText(labels[i], cx, h - 6 * density, label);
        }
    }
}
