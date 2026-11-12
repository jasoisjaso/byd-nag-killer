package au.jaso.naglab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

/** The Battery tab's three drawings. Plain Canvas, nothing to load, nothing running unless the
 *  car is charging and the tab is on screen. */
final class BatteryViews {
    private BatteryViews() {
    }

    private static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** A battery lying on its side: white up to where the charge started, orange for what this
     *  charge added. While charging the orange part breathes (8 frames a second, no more). */
    static final class Bar extends View {
        private float from, to;
        private boolean charging;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        Bar(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        /** Battery % (0-100) before and after; from < 0 = unknown (draw it all as one colour). */
        void set(int fromPct, int toPct, boolean charging) {
            float f = fromPct < 0 ? -1 : Math.max(0, Math.min(100, fromPct)) / 100f;
            float t = Math.max(0, Math.min(100, toPct)) / 100f;
            if (f == from && t == to && charging == this.charging) return;
            from = f;
            to = t;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float nub = h >= 28 * d ? 8 * d : 0;   // the big one gets a terminal, the row one doesn't
            float bw = w - nub, rad = Math.min(h / 2f, 10 * d);
            p.setColor(MainActivity.RAISED);
            r.set(0, 0, bw, h);
            c.drawRoundRect(r, rad, rad, p);
            if (nub > 0) {
                p.setColor(0xFF555A63);   // RAISED alone disappears into the card
                r.set(bw + 2 * d, h * 0.3f, w, h * 0.7f);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
            }
            float pad = h >= 28 * d ? 4 * d : 0;
            float iw = bw - 2 * pad, ih = h - 2 * pad, ir = Math.max(2 * d, rad - pad);
            float a = from < 0 ? 0 : from;
            c.save();
            // one rounded shape for the whole fill, cut into its two colours
            r.set(pad, pad, pad + iw * to, pad + ih);
            if (r.width() < 2 * ir) r.right = r.left + Math.min(iw, 2 * ir) * (to > 0 ? 1 : 0);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, ir, ir, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            if (a > 0) {
                p.setColor(MainActivity.TEXT);
                c.drawRect(pad, pad, pad + iw * Math.min(a, to), pad + ih, p);
            }
            if (to > a) {
                int alpha = 255;
                if (charging) {
                    double ph = (SystemClock.uptimeMillis() % 2400) / 2400.0 * 2 * Math.PI;
                    alpha = (int) (255 * (0.82 + 0.18 * Math.sin(ph)));
                }
                p.setColor(from < 0 ? MainActivity.TEXT : MainActivity.ACCENT);
                p.setAlpha(alpha);
                c.drawRect(pad + iw * a, pad, pad + iw * to, pad + ih, p);
                p.setAlpha(255);
            }
            c.restore();
            if (charging && isShown()) postInvalidateDelayed(125);
        }
    }

    /** Three quarters of a circle: how much of the new battery is left, with a notch at the
     *  warranty line. Empty (just the track) until there is a figure. */
    static final class Ring extends View {
        private float pct = Float.NaN;
        private boolean early;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d, sd;
        private static final float START = 135, SWEEP = 270;

        Ring(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            sd = c.getResources().getDisplayMetrics().scaledDensity;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            t.setTextAlign(Paint.Align.CENTER);
        }

        void set(float pct, boolean early) {
            this.pct = pct;
            this.early = early;
            setContentDescription(Float.isNaN(pct) ? "Battery health: not measured yet"
                    : "Battery health: about " + Math.round(Math.min(100, pct)) + " percent of new");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), stroke = 16 * d;
            float size = Math.min(w, h) - stroke;
            float cx = w / 2f, cy = h / 2f + size * 0.07f;   // the open bottom makes it look high
            r.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            p.setStrokeWidth(stroke);
            p.setColor(MainActivity.RAISED);
            c.drawArc(r, START, SWEEP, false, p);
            boolean has = !Float.isNaN(pct);
            float shown = has ? Math.max(0, Math.min(100, pct)) : 0;
            if (has) {
                p.setColor(MainActivity.TEXT);
                c.drawArc(r, START, SWEEP * shown / 100f, false, p);
            }
            // warranty notch: a short orange tick across the ring at 70%
            double ang = Math.toRadians(START + SWEEP * Trips.WARRANTY_PCT / 100f);
            float r0 = size / 2f - stroke * 0.5f, r1 = size / 2f + stroke * 0.5f;
            p.setStrokeWidth(4 * d);
            p.setColor(MainActivity.ACCENT);
            c.drawLine(cx + (float) Math.cos(ang) * r0, cy + (float) Math.sin(ang) * r0,
                    cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1, p);

            t.setTypeface(medium());
            t.setColor(MainActivity.TEXT);
            t.setTextSize(Math.min(56 * sd, size * 0.30f));
            c.drawText(has ? Math.round(shown) + "%" : "–", cx, cy + t.getTextSize() * 0.2f, t);
            t.setTypeface(Typeface.DEFAULT);
            t.setColor(MainActivity.SUB);
            t.setTextSize(Math.min(18 * sd, size * 0.10f));
            c.drawText(has ? (early ? "of new, early" : "of new") : "measuring", cx, cy + size * 0.24f, t);
        }
    }

    /** One dot per reading against the odometer, between the "new" line and the warranty line. */
    static final class Trend extends View {
        private List<Trips.HealthReading> rd;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private static final float TOP = 106, BOTTOM = 64;   // percent of new at the edges

        Trend(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            t.setTextSize(14 * c.getResources().getDisplayMetrics().scaledDensity);
            t.setColor(MainActivity.SUB);
        }

        void set(List<Trips.HealthReading> readings) {
            rd = readings;
            setContentDescription("Battery health readings so far: " + (readings == null ? 0 : readings.size()));
            invalidate();
        }

        private float y(float pct, float h) {
            return h * (TOP - Math.max(BOTTOM, Math.min(TOP, pct))) / (TOP - BOTTOM);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(Math.max(1, d));
            p.setColor(0xFF555A63);
            float yNew = y(100, h), yWar = y(Trips.WARRANTY_PCT, h);
            c.drawLine(0, yNew, w, yNew, p);
            p.setColor(0xFF8A4A1C);   // dim orange, readable on the card
            c.drawLine(0, yWar, w, yWar, p);
            t.setTextAlign(Paint.Align.RIGHT);
            c.drawText("new", w, yNew + 16 * d, t);
            c.drawText("warranty line 70%", w, yWar - 6 * d, t);
            if (rd == null || rd.isEmpty()) {
                t.setTextAlign(Paint.Align.LEFT);
                c.drawText("Readings show up here, one per big charge", 0, (yNew + yWar) / 2f, t);
                return;
            }
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (Trips.HealthReading x : rd) {
                if (Float.isNaN(x.odo)) continue;
                lo = Math.min(lo, x.odo);
                hi = Math.max(hi, x.odo);
            }
            float inset = 8 * d, usable = w - 2 * inset - 130 * d;   // keep clear of the labels
            for (int i = 0; i < rd.size(); i++) {
                Trips.HealthReading x = rd.get(i);
                float fx = hi > lo && !Float.isNaN(x.odo) ? (x.odo - lo) / (hi - lo)
                        : rd.size() > 1 ? i / (float) (rd.size() - 1) : 0;
                boolean last = i == rd.size() - 1;
                p.setColor(last ? MainActivity.ACCENT : MainActivity.TEXT);
                c.drawCircle(inset + usable * fx, y(x.pct(), h), (last ? 6 : 4) * d, p);
            }
        }
    }
}
