package com.example.reviewreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Rounded flashcard surface that draws default/solid/gradient/photo backgrounds and legibility
 * overlays inside a {@link Canvas#clipPath(Path)} clip — working cleanly even when
 * {@code android:hardwareAccelerated="false"} is enabled.
 */
final class CardSurfaceView extends LinearLayout {
    static final class Palette {
        final int ink, inkSecondary, inkMuted, hairline, chipFill, chipInk, accent, ripple, dotActive, dotInactive;
        final boolean darkSurface;

        Palette(boolean darkSurface, boolean photoBackground) {
            this.darkSurface = darkSurface;
            if (darkSurface) {
                ink = 0xFFF6F5F1;
                inkSecondary = photoBackground ? 0xFFE6E4DE : 0xFFC5CBC7;
                inkMuted = photoBackground ? 0xFFD0D4D1 : 0xFF98A19D;
                hairline = photoBackground ? 0x4DFFFFFF : 0x33F6F5F1;
                chipFill = photoBackground ? 0x45121715 : 0xFF2B3532;
                chipInk = 0xFFE9ECEA;
                accent = 0xFF9FD3BF;
                ripple = 0x26FFFFFF;
                dotActive = 0xFF9FD3BF;
                dotInactive = 0x44FFFFFF;
            } else {
                ink = Theme.INK;
                inkSecondary = Theme.INK_2;
                inkMuted = Theme.INK_3;
                hairline = Theme.HAIRLINE;
                chipFill = Theme.SUNK;
                chipInk = Theme.INK_2;
                accent = Theme.PRIMARY;
                ripple = Theme.RIPPLE_LIGHT;
                dotActive = Theme.PRIMARY;
                dotInactive = Theme.HAIRLINE;
            }
        }
    }

    static final int DARK_PAPER_SURFACE = 0xFF1E2422;
    static final int DARK_PAGE_BG = 0xFF141917;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final RectF bounds = new RectF();
    private final Matrix imageMatrix = new Matrix();
    private float radiusPx;
    private CardStyle style = CardStyle.defaults();
    private boolean darkMode;
    private Bitmap bgBitmap;

    CardSurfaceView(Context context, float radiusDp) {
        super(context);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        radiusPx = Theme.dp(context, radiusDp);
    }

    void applyStyle(CardStyle style, boolean darkMode) {
        this.style = style == null ? CardStyle.defaults() : style;
        this.darkMode = darkMode;
        if (CardStyle.BG_IMAGE.equals(this.style.backgroundMode) && !this.style.backgroundImage.isEmpty()) {
            bgBitmap = CardStyleImages.decodeCached(getContext(), this.style.backgroundImage, true);
        } else {
            bgBitmap = null;
        }
        invalidate();
    }

    Palette palette() {
        boolean isPhoto = CardStyle.BG_IMAGE.equals(style.backgroundMode) && bgBitmap != null;
        boolean dark = isPhoto || style.isDarkSurface(darkMode);
        return new Palette(dark, isPhoto);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(bounds, radiusPx, radiusPx, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int save = canvas.save();
        canvas.clipPath(clipPath);
        drawBackdrop(canvas, getWidth(), getHeight());
        canvas.restoreToCount(save);
        super.onDraw(canvas);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clipPath);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    private void drawBackdrop(Canvas canvas, int w, int h) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);

        if (CardStyle.BG_IMAGE.equals(style.backgroundMode) && bgBitmap != null && !bgBitmap.isRecycled()) {
            paint.setColor(DARK_PAPER_SURFACE);
            canvas.drawRect(0, 0, w, h, paint);
            computeCropMatrix(bgBitmap.getWidth(), bgBitmap.getHeight(), w, h, style.imageCrop, imageMatrix);
            canvas.drawBitmap(bgBitmap, imageMatrix, bitmapPaint);
            // Single legibility scrim control (0–80%) + subtle floor contrast gradient at text center.
            int alpha = Math.round(style.overlayAlpha * 255f / 100f);
            paint.setColor(Color.argb(alpha, 0x12, 0x17, 0x15));
            canvas.drawRect(0, 0, w, h, paint);
            return;
        }

        if (CardStyle.BG_GRADIENT.equals(style.backgroundMode)) {
            CardStyle.GradientPreset g = style.gradientPreset();
            paint.setShader(new LinearGradient(0, 0, 0, h, g.startColor, g.endColor, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            return;
        }

        if (CardStyle.BG_SOLID.equals(style.backgroundMode)) {
            paint.setColor(style.solidPreset().color);
            canvas.drawRect(0, 0, w, h, paint);
            return;
        }

        paint.setColor(darkMode ? DARK_PAPER_SURFACE : Theme.SURFACE);
        canvas.drawRect(0, 0, w, h, paint);
    }

    static void computeCropMatrix(int bw, int bh, int vw, int vh, String cropAnchor, Matrix out) {
        out.reset();
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return;
        float scale = Math.max(vw / (float) bw, vh / (float) bh);
        float dx = (vw - bw * scale) * 0.5f;
        float freeY = vh - bh * scale;
        float dy;
        if (CardStyle.CROP_TOP.equals(cropAnchor)) dy = 0f;
        else if (CardStyle.CROP_BOTTOM.equals(cropAnchor)) dy = freeY;
        else dy = freeY * 0.5f;
        out.setScale(scale, scale);
        out.postTranslate(dx, dy);
    }

    /** Subtle paper-and-ink vector pattern strip for the bottom card slot. */
    static final class PatternStripView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private String patternId = CardStyle.PATTERN_DOTS;
        private int inkColor = Theme.INK_3;

        PatternStripView(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        void configure(String patternId, int baseInkColor) {
            this.patternId = CardStyle.findPattern(patternId).id;
            this.inkColor = baseInkColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            int soft = Theme.alpha(inkColor, 0.24f);
            int softer = Theme.alpha(inkColor, 0.14f);

            if (CardStyle.PATTERN_LINEN.equals(patternId)) {
                stroke.setColor(softer);
                stroke.setStrokeWidth(Math.max(1f, d * 0.8f));
                float step = d * 12f;
                for (float x = step * 0.5f; x < w; x += step) canvas.drawLine(x, 0, x, h, stroke);
                for (float y = step * 0.5f; y < h; y += step) canvas.drawLine(0, y, w, y, stroke);
                return;
            }

            if (CardStyle.PATTERN_WAVES.equals(patternId)) {
                stroke.setStrokeWidth(Math.max(1f, d * 1.1f));
                for (int layer = 0; layer < 2; layer++) {
                    stroke.setColor(layer == 0 ? soft : softer);
                    path.reset();
                    float baseY = h * (layer == 0 ? 0.62f : 0.78f);
                    float amp = h * (layer == 0 ? 0.18f : 0.12f);
                    float phase = layer * 1.4f;
                    int steps = 28;
                    for (int i = 0; i <= steps; i++) {
                        float x = w * i / (float) steps;
                        float y = baseY - (float) Math.sin(i * 0.42f + phase) * amp;
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    canvas.drawPath(path, stroke);
                }
                return;
            }

            if (CardStyle.PATTERN_BOTANICAL.equals(patternId)) {
                stroke.setColor(soft);
                stroke.setStrokeWidth(Math.max(1f, d * 1.15f));
                float cx = w * 0.5f, cy = h * 0.64f;
                float span = Math.min(w * 0.34f, d * 88f);
                path.reset();
                path.moveTo(cx - span, cy + d * 4f);
                path.quadTo(cx, cy - d * 8f, cx + span, cy + d * 2f);
                canvas.drawPath(path, stroke);
                float[] offsets = {-0.65f, -0.30f, 0.05f, 0.38f, 0.68f};
                for (int i = 0; i < offsets.length; i++) {
                    float px = cx + span * offsets[i];
                    float py = cy - d * 2f + Math.abs(offsets[i]) * d * 4f;
                    float dir = (i % 2 == 0) ? -1f : 1f;
                    canvas.drawLine(px, py, px + d * 8f, py + dir * d * 9f, stroke);
                    canvas.drawLine(px, py, px - d * 5f, py + dir * d * 8f, stroke);
                }
                return;
            }

            // Default: subtle ink dots
            fill.setColor(soft);
            float spacing = d * 14f;
            float r = d * 1.35f;
            for (float y = spacing * 0.5f; y < h; y += spacing) {
                for (float x = spacing * 0.5f; x < w; x += spacing) {
                    canvas.drawCircle(x, y, r, fill);
                }
            }
        }
    }

    /** Rounded image strip for the bottom decoration slot (software-rendering safe). */
    static final class BottomPhotoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private final float radius;
        private Bitmap bitmap;

        BottomPhotoView(Context context) {
            super(context);
            radius = Theme.dp(context, 12);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, Theme.dp(context, 0.75f)));
            border.setColor(0x22000000);
        }

        void setBitmap(Bitmap bm, boolean darkSurface) {
            this.bitmap = bm;
            border.setColor(darkSurface ? 0x33FFFFFF : 0x1A1B2220);
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;
            int save = canvas.save();
            canvas.clipPath(clip);
            computeCropMatrix(bitmap.getWidth(), bitmap.getHeight(), getWidth(), getHeight(), CardStyle.CROP_CENTER, matrix);
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restoreToCount(save);
            canvas.drawRoundRect(rect, radius, radius, border);
        }
    }

    /**
     * Appends the configured bottom slot (none / quote / subtle pattern / private image strip)
     * to a card container while adapting to compact height, landscape, and large font scales.
     */
    static void attachBottomSlot(Context c, LinearLayout card, CardStyle style, Palette palette, boolean compact) {
        if (style == null || CardStyle.BOTTOM_NONE.equals(style.bottomMode)) return;
        int dp = Theme.dp(c, 1);

        if (CardStyle.BOTTOM_QUOTE.equals(style.bottomMode)) {
            if (style.quoteText.isEmpty()) return;
            LinearLayout footer = new LinearLayout(c);
            footer.setOrientation(LinearLayout.VERTICAL);
            footer.setGravity(Gravity.CENTER_HORIZONTAL);
            footer.setPadding(Theme.dp(c, 8), Theme.dp(c, compact ? 8 : 14), Theme.dp(c, 8), Theme.dp(c, 2));

            View rule = new View(c);
            rule.setBackgroundColor(palette.hairline);
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(Theme.dp(c, 28), Math.max(1, dp));
            ruleLp.bottomMargin = Theme.dp(c, compact ? 6 : 10);
            footer.addView(rule, ruleLp);

            TextView quote = new TextView(c);
            quote.setText(style.quoteText);
            quote.setGravity(Gravity.CENTER);
            quote.setTypeface(Theme.REGULAR);
            quote.setTextSize(compact ? 12 : 13);
            quote.setTextColor(palette.inkSecondary);
            quote.setLineSpacing(0, 1.3f);
            quote.setMaxLines(compact ? 2 : 3);
            quote.setEllipsize(android.text.TextUtils.TruncateAt.END);
            footer.addView(quote, WordUi.lp());

            if (!style.quoteCredit.isEmpty()) {
                TextView credit = new TextView(c);
                credit.setText("— " + style.quoteCredit);
                credit.setGravity(Gravity.CENTER);
                credit.setTypeface(Theme.REGULAR);
                credit.setTextSize(compact ? 11 : 12);
                credit.setTextColor(palette.inkMuted);
                credit.setSingleLine(true);
                credit.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams clp = WordUi.lp();
                clp.topMargin = Theme.dp(c, 4);
                footer.addView(credit, clp);
            }
            card.addView(footer, WordUi.lp());
            return;
        }

        if (CardStyle.BOTTOM_PATTERN.equals(style.bottomMode)) {
            PatternStripView strip = new PatternStripView(c);
            strip.configure(style.bottomPattern, palette.ink);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Theme.dp(c, compact ? 28 : 42));
            lp.topMargin = Theme.dp(c, compact ? 6 : 10);
            card.addView(strip, lp);
            return;
        }

        if (CardStyle.BOTTOM_IMAGE.equals(style.bottomMode) && !style.bottomImage.isEmpty()) {
            Bitmap bm = CardStyleImages.decodeCached(c, style.bottomImage, false);
            if (bm == null) return;
            BottomPhotoView photo = new BottomPhotoView(c);
            photo.setBitmap(bm, palette.darkSurface);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Theme.dp(c, compact ? 42 : 68));
            lp.topMargin = Theme.dp(c, compact ? 8 : 12);
            card.addView(photo, lp);
        }
    }
}
