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
            boolean first = to == 0 && t > 0;
            from = f;
            to = t;
            this.charging = charging;
            if (first) replay();
            else invalidate();
        }

        private float grow = 1f;   // 0..1 while the fill runs up to its level
        private android.animation.ValueAnimator anim;

        /** Fill from empty up to the level: 0.7 s, fast then settling. */
        void replay() {
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(700);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
            anim.addUpdateListener(v -> {
                grow = (Float) v.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float nub = 0;   // one clean rounded bar, no terminal
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
            float to = this.to * grow;
            float a = Math.min(from < 0 ? 0 : from, to);
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
                p.setColor(from < 0 ? MainActivity.TEXT : MainActivity.ACCENT);
                float x0 = pad + iw * a, x1 = pad + iw * to;
                c.drawRect(x0, pad, x1, pad + ih, p);
                if (charging) {
                    // energy going in: a soft bright band travels along what this charge added,
                    // slower as the battery nears full
                    long period = to > 0.9f ? 3600 : 1800;
                    float ph = (SystemClock.uptimeMillis() % period) / (float) period;
                    float band = Math.max(18 * d, (x1 - x0) * 0.6f), cx = x0 - band + (x1 - x0 + 2 * band) * ph;
                    c.save();
                    c.clipRect(x0, pad, x1, pad + ih);
                    p.setShader(new android.graphics.LinearGradient(cx - band / 2f, 0, cx + band / 2f, 0,
                            new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRect(cx - band / 2f, pad, cx + band / 2f, pad + ih, p);
                    p.setShader(null);
                    c.restore();
                }
            }
            c.restore();
            if (charging && isShown()) postInvalidateDelayed(66);   // about 15 frames a second
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

        private float sweep = 1f;
        private android.animation.ValueAnimator anim;

        /** The arc runs round to its figure and the number counts up with it. */
        void replay() {
            if (anim != null) anim.cancel();
            if (Float.isNaN(pct)) return;
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(900);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
            anim.addUpdateListener(v -> {
                sweep = (Float) v.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void set(float pct, boolean early) {
            boolean first = Float.isNaN(this.pct) && !Float.isNaN(pct);
            this.pct = pct;
            this.early = early;
            if (first) replay();
            setContentDescription(Float.isNaN(pct) ? "Battery health: not measured yet"
                    : "Battery health: about " + Math.round(Math.min(100, pct)) + " percent of new");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), stroke = Math.max(14 * d, Math.min(w, h) * 0.07f);
            float size = Math.min(w, h) - stroke;
            float cx = w / 2f, cy = h / 2f + size * 0.07f;   // the open bottom makes it look high
            r.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            p.setStrokeWidth(stroke);
            p.setColor(MainActivity.RAISED);
            c.drawArc(r, START, SWEEP, false, p);
            boolean has = !Float.isNaN(pct);
            float shown = has ? Math.max(0, Math.min(100, pct)) * sweep : 0;
            if (has) {
                p.setColor(MainActivity.TEXT);
                c.drawArc(r, START, SWEEP * shown / 100f, false, p);
            }
            if (has) {   // with no figure yet the mark would mean nothing
                // warranty notch: a short orange tick across the ring at 70%
                double ang = Math.toRadians(START + SWEEP * Trips.WARRANTY_PCT / 100f);
                float r0 = size / 2f - stroke * 0.5f, r1 = size / 2f + stroke * 0.5f;
                p.setStrokeWidth(4 * d);
                p.setColor(MainActivity.ACCENT);
                c.drawLine(cx + (float) Math.cos(ang) * r0, cy + (float) Math.sin(ang) * r0,
                        cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1, p);
            }
            t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            t.setColor(MainActivity.TEXT);
            t.setTextSize(size * 0.30f);
            c.drawText(has ? Math.round(shown) + "%" : "–", cx, cy + t.getTextSize() * 0.2f, t);
            t.setTypeface(Typeface.DEFAULT);
            t.setColor(MainActivity.SUB);
            t.setTextSize(Math.min(22 * sd, size * 0.085f));
            c.drawText(has ? (early ? "of new, early" : "of new") : "measuring", cx, cy + size * 0.24f, t);
        }
    }

    /** One dot per reading against the odometer, between the "new" line and the warranty line. */
    static final class Trend extends View {
        private List<Trips.HealthReading> rd;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private static final float TOP = 112, BOTTOM = 64;   // percent of new at the edges

        Trend(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            t.setTextSize(15 * c.getResources().getDisplayMetrics().scaledDensity);
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
            p.setColor(0xFFB4541A);   // the warranty line: orange, a touch heavier than the "new" line
            p.setStrokeWidth(2 * d);
            c.drawLine(0, yWar, w, yWar, p);
            p.setStrokeWidth(Math.max(1, d));
            t.setTextAlign(Paint.Align.RIGHT);
            c.drawText("new", w, yNew - 8 * d, t);
            c.drawText("warranty line 70%", w, yWar - 8 * d, t);
            if (rd == null || rd.isEmpty()) {
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText("Your battery health over time: one point for each day you drive", w / 2f, (yNew + yWar) / 2f + 6 * d, t);
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
