package com.example.reviewreminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Restrained batch progress indicator supporting three clear visual variants:
 *  - {@link CardStyle#PROGRESS_LINE} (default): thin rounded track with M3 end stop dot
 *  - {@link CardStyle#PROGRESS_SEGMENTS}: discrete rounded ticks (capped at 12 segments for clarity)
 *  - {@link CardStyle#PROGRESS_RING}: compact 20dp circular stroke indicator beside numeric counter
 *
 * Always honours reduced motion ({@link Motion#enabled(Context)}).
 */
final class ProgressLine extends View {
    private static final int MAX_SEGMENTS = 12;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private String mode = CardStyle.PROGRESS_LINE;
    private float value;
    private int stepTotal = 1;
    private ValueAnimator anim;

    ProgressLine(Context c) {
        super(c);
        track.setColor(Theme.SUNK);
        fill.setColor(Theme.PRIMARY);
        ringTrack.setStyle(Paint.Style.STROKE);
        ringTrack.setStrokeCap(Paint.Cap.ROUND);
        ringTrack.setColor(Theme.SUNK);
        ringFill.setStyle(Paint.Style.STROKE);
        ringFill.setStrokeCap(Paint.Cap.ROUND);
        ringFill.setColor(Theme.PRIMARY);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    ProgressLine style(String nextMode) {
        String normalized = CardStyle.normalizeProgressStyle(nextMode);
        if (!normalized.equals(mode)) {
            mode = normalized;
            requestLayout();
        }
        invalidate();
        return this;
    }

    ProgressLine color(int c) {
        fill.setColor(c);
        ringFill.setColor(c);
        invalidate();
        return this;
    }

    ProgressLine trackColor(int c) {
        track.setColor(c);
        ringTrack.setColor(c);
        invalidate();
        return this;
    }

    ProgressLine colors(int fillColor, int trackColor) {
        fill.setColor(fillColor);
        ringFill.setColor(fillColor);
        track.setColor(trackColor);
        ringTrack.setColor(trackColor);
        invalidate();
        return this;
    }

    void setStep(int completed, int total, boolean animate) {
        stepTotal = Math.max(1, total);
        float fraction = Math.max(0f, Math.min(1f, completed / (float) stepTotal));
        set(fraction, animate);
    }

    void set(float target, boolean animate) {
        target = Math.max(0f, Math.min(1f, target));
        if (anim != null) anim.cancel();
        if (!animate || !Motion.enabled(getContext())) {
            value = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(value, target);
        anim.setDuration(Theme.ENTER_MS + 120);
        anim.setInterpolator(Motion.EASE);
        anim.addUpdateListener(a -> {
            value = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override protected void onMeasure(int w, int h) {
        if (CardStyle.PROGRESS_RING.equals(mode)) {
            int size = Theme.dp(getContext(), 20);
            setMeasuredDimension(resolveSize(size, w), resolveSize(size, h));
            return;
        }
        int heightDp = CardStyle.PROGRESS_SEGMENTS.equals(mode) ? 4 : 3;
        setMeasuredDimension(getDefaultSize(0, w), resolveSize(Theme.dp(getContext(), heightDp), h));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (CardStyle.PROGRESS_RING.equals(mode)) {
            float stroke = Math.max(2f, Theme.dp(getContext(), 2.2f));
            ringTrack.setStrokeWidth(stroke);
            ringFill.setStrokeWidth(stroke);
            float inset = stroke * 0.5f + 1f;
            float size = Math.min(w, h);
            float left = (w - size) * 0.5f + inset;
            float top = (h - size) * 0.5f + inset;
            rect.set(left, top, left + size - inset * 2f, top + size - inset * 2f);
            c.drawArc(rect, 0f, 360f, false, ringTrack);
            if (value > 0f) {
                c.drawArc(rect, -90f, Math.max(12f, 360f * value), false, ringFill);
            }
            return;
        }

        if (CardStyle.PROGRESS_SEGMENTS.equals(mode)) {
            int segCount = Math.max(2, Math.min(MAX_SEGMENTS, stepTotal > 1 ? stepTotal : 8));
            float gap = Math.max(2f, Theme.dp(getContext(), 3));
            float segW = (w - gap * (segCount - 1)) / (float) segCount;
            if (segW < 2f) {
                drawLine(c, w, h);
                return;
            }
            float r = h * 0.5f;
            float exactFilled = value * segCount;
            for (int i = 0; i < segCount; i++) {
                float left = i * (segW + gap);
                rect.set(left, 0f, left + segW, h);
                c.drawRoundRect(rect, r, r, track);
                float segFill = Math.max(0f, Math.min(1f, exactFilled - i));
                if (segFill > 0.01f) {
                    rect.set(left, 0f, left + Math.max(h * 0.8f, segW * segFill), h);
                    c.drawRoundRect(rect, r, r, fill);
                }
            }
            return;
        }

        drawLine(c, w, h);
    }

    private void drawLine(Canvas c, int w, int h) {
        float r = h * 0.5f;
        rect.set(0f, 0f, w, h);
        c.drawRoundRect(rect, r, r, track);
        if (value > 0f) {
            rect.set(0f, 0f, Math.max(h, w * value), h);
            c.drawRoundRect(rect, r, r, fill);
        }
        // Material 3 stop indicator dot at track end so total length stays legible even on low-contrast tracks.
        if (value < 0.98f && w > h * 3f) {
            c.drawCircle(w - r, r, r, fill);
        }
    }
}
