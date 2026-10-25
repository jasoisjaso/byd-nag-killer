package au.jaso.naglab;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Small drawn status mark, so a state never rests on colour alone:
 * filled + tick = off as asked, filled + "!" = needs attention, ring + dash = no answer,
 * plain ring = nothing to show, ring with a turning arc = working.
 */
final class Mark extends View {
    static final int IDLE = 0, OK = 1, ALERT = 2, UNKNOWN = 3, BUSY = 4;
    private static final int INK = 0xFF0F1013;   // glyph on a filled mark (= app background)

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private int kind = IDLE, color = 0xFF9BA0A8, border;
    private float borderW, spin;
    private ValueAnimator spinner;

    Mark(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setColor(INK);
    }

    /** A ring in the background colour around the mark (to lift it off an icon). */
    void setBorder(int c, float px) {
        border = c;
        borderW = px;
        invalidate();
    }

    void set(int kind, int color) {
        if (kind == this.kind && color == this.color) return;
        this.kind = kind;
        this.color = color;
        if (kind == BUSY) startSpin();
        else stopSpin();
        invalidate();
    }

    private void startSpin() {
        if (spinner != null || !isAttachedToWindow()) return;
        spinner = ValueAnimator.ofFloat(0f, 360f);
        spinner.setDuration(900);
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        spinner.start();
    }

    private void stopSpin() {
        if (spinner != null) spinner.cancel();
        spinner = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (kind == BUSY) startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float s = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float rad = s / 2f;
        if (borderW > 0) {
            fill.setColor(border);
            cv.drawCircle(cx, cy, rad, fill);
            rad -= borderW;
        }
        float sw = Math.max(2f, s * 0.12f);
        stroke.setStrokeWidth(sw);
        stroke.setColor(color);
        ink.setStrokeWidth(Math.max(2f, s * 0.13f));
        switch (kind) {
            case OK:
                fill.setColor(color);
                cv.drawCircle(cx, cy, rad, fill);
                path.reset();
                path.moveTo(cx - rad * 0.42f, cy + rad * 0.02f);
                path.lineTo(cx - rad * 0.10f, cy + rad * 0.34f);
                path.lineTo(cx + rad * 0.45f, cy - rad * 0.30f);
                cv.drawPath(path, ink);
                break;
            case ALERT:
                fill.setColor(color);
                cv.drawCircle(cx, cy, rad, fill);
                cv.drawLine(cx, cy - rad * 0.48f, cx, cy + rad * 0.10f, ink);
                cv.drawPoint(cx, cy + rad * 0.45f, ink);
                break;
            case UNKNOWN:
                cv.drawCircle(cx, cy, rad - sw / 2f, stroke);
                cv.drawLine(cx - rad * 0.36f, cy, cx + rad * 0.36f, cy, stroke);
                break;
            case BUSY: {
                float in = rad - sw / 2f;
                r.set(cx - in, cy - in, cx + in, cy + in);
                stroke.setAlpha(70);
                cv.drawCircle(cx, cy, in, stroke);
                stroke.setAlpha(255);
                cv.drawArc(r, spin - 90f, 100f, false, stroke);
                break;
            }
            default:
                cv.drawCircle(cx, cy, rad - sw / 2f, stroke);
                break;
        }
    }
}
