package com.example.reviewreminder;

import org.json.JSONObject;
import java.util.Locale;

/**
 * Pure-Java configuration model for review flashcard layout, progress indicator, and visual customization.
 * Stores only compact, validated values and private file names — never image Base64 data.
 */
public final class CardStyle {
    public static final String LAYOUT_CLASSIC = "classic";
    public static final String LAYOUT_IMMERSIVE = "immersive";

    public static final String PROGRESS_LINE = "line";
    public static final String PROGRESS_SEGMENTS = "segments";
    public static final String PROGRESS_RING = "ring";

    public static final String BG_DEFAULT = "default";
    public static final String BG_SOLID = "solid";
    public static final String BG_GRADIENT = "gradient";
    public static final String BG_IMAGE = "image";

    public static final String CROP_CENTER = "center";
    public static final String CROP_TOP = "top";
    public static final String CROP_BOTTOM = "bottom";

    public static final int OVERLAY_MIN = 0;
    public static final int OVERLAY_MAX = 80;
    public static final int OVERLAY_DEFAULT = 45;

    public static final String BOTTOM_NONE = "none";
    public static final String BOTTOM_QUOTE = "quote";
    public static final String BOTTOM_PATTERN = "pattern";
    public static final String BOTTOM_IMAGE = "image";

    public static final String PATTERN_DOTS = "dots";
    public static final String PATTERN_LINEN = "linen";
    public static final String PATTERN_WAVES = "waves";
    public static final String PATTERN_BOTANICAL = "botanical";

    public static final int MAX_QUOTE_CHARS = 120;
    public static final int MAX_CREDIT_CHARS = 36;

    public static final class SolidPreset {
        public final String id, label;
        public final int color;
        public final boolean dark;
        SolidPreset(String id, String label, int color, boolean dark) {
            this.id = id; this.label = label; this.color = color; this.dark = dark;
        }
    }

    public static final class GradientPreset {
        public final String id, label;
        public final int startColor, endColor;
        public final boolean dark;
        GradientPreset(String id, String label, int startColor, int endColor, boolean dark) {
            this.id = id; this.label = label; this.startColor = startColor; this.endColor = endColor; this.dark = dark;
        }
    }

    public static final class PatternPreset {
        public final String id, label;
        PatternPreset(String id, String label) { this.id = id; this.label = label; }
    }

    public static final class SampleQuote {
        public final String text, credit;
        SampleQuote(String text, String credit) { this.text = text; this.credit = credit; }
    }

    public static final SolidPreset[] SOLIDS = {
        new SolidPreset("warm_linen", "暖麻纸", 0xFFF8F4EC, false),
        new SolidPreset("mist_sage", "浅松雾", 0xFFEDF3F0, false),
        new SolidPreset("pale_stone", "素石灰", 0xFFF1F0EC, false),
        new SolidPreset("dusk_slate", "暮青石", 0xFF242B2D, true),
        new SolidPreset("night_ink", "夜墨纸", 0xFF1C2220, true)
    };

    public static final GradientPreset[] GRADIENTS = {
        new GradientPreset("morning_paper", "晨纸", 0xFFFAF8F2, 0xFFEFECE2, false),
        new GradientPreset("pine_mist", "松岚", 0xFFF2F6F4, 0xFFE2ECE7, false),
        new GradientPreset("warm_sand", "暖砂", 0xFFF8F4EE, 0xFFECE3D5, false),
        new GradientPreset("deep_forest", "深林夜", 0xFF212E2A, 0xFF18211E, true),
        new GradientPreset("twilight_ink", "暮色墨", 0xFF262B31, 0xFF1B1F24, true)
    };

    public static final PatternPreset[] PATTERNS = {
        new PatternPreset(PATTERN_DOTS, "墨点"),
        new PatternPreset(PATTERN_LINEN, "纸格"),
        new PatternPreset(PATTERN_WAVES, "远山"),
        new PatternPreset(PATTERN_BOTANICAL, "松枝")
    };

    /** Original study reflections created for this app; no invented attributions. */
    public static final SampleQuote[] SAMPLE_QUOTES = {
        new SampleQuote("慢一点记住，比快一点忘掉更省力。", ""),
        new SampleQuote("今天多看一眼，明天少费三分力。", ""),
        new SampleQuote("把生词留在日常里，它们自然会开口。", ""),
        new SampleQuote("Small reviews, kept steady, outlast long nights.", ""),
        new SampleQuote("Return once more before the trace fades.", "")
    };

    public final String layoutMode;
    public final String progressStyle;

    public final String backgroundMode;
    public final String backgroundPreset;
    public final String backgroundImage;
    public final String imageCrop;
    public final int overlayAlpha;

    public final String bottomMode;
    public final String quoteText;
    public final String quoteCredit;
    public final String bottomPattern;
    public final String bottomImage;

    public CardStyle(
        String backgroundMode,
        String backgroundPreset,
        String backgroundImage,
        String imageCrop,
        int overlayAlpha,
        String bottomMode,
        String quoteText,
        String quoteCredit,
        String bottomPattern,
        String bottomImage
    ) {
        this(LAYOUT_CLASSIC, PROGRESS_LINE, backgroundMode, backgroundPreset, backgroundImage,
            imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle(
        String layoutMode,
        String progressStyle,
        String backgroundMode,
        String backgroundPreset,
        String backgroundImage,
        String imageCrop,
        int overlayAlpha,
        String bottomMode,
        String quoteText,
        String quoteCredit,
        String bottomPattern,
        String bottomImage
    ) {
        String normLayout = normalizeLayoutMode(layoutMode);
        String normProgress = normalizeProgressStyle(progressStyle);
        String normBg = normalizeBgMode(backgroundMode);
        String cleanBgImage = sanitizeImageName(backgroundImage);
        String cleanPreset = "";
        if (BG_SOLID.equals(normBg)) {
            cleanPreset = findSolid(backgroundPreset).id;
        } else if (BG_GRADIENT.equals(normBg)) {
            cleanPreset = findGradient(backgroundPreset).id;
        } else if (BG_IMAGE.equals(normBg) && cleanBgImage.isEmpty()) {
            normBg = BG_DEFAULT;
        }

        String normBottom = normalizeBottomMode(bottomMode);
        String cleanBottomImage = sanitizeImageName(bottomImage);
        String cleanQuote = sanitizeCopy(quoteText, MAX_QUOTE_CHARS);
        String cleanCredit = sanitizeCopy(quoteCredit, MAX_CREDIT_CHARS);
        String cleanPattern = findPattern(bottomPattern).id;
        if (BOTTOM_IMAGE.equals(normBottom) && cleanBottomImage.isEmpty()) {
            normBottom = BOTTOM_NONE;
        }

        this.layoutMode = normLayout;
        this.progressStyle = normProgress;
        this.backgroundMode = normBg;
        this.backgroundPreset = cleanPreset;
        this.backgroundImage = BG_IMAGE.equals(normBg) ? cleanBgImage : "";
        this.imageCrop = normalizeCrop(imageCrop);
        this.overlayAlpha = clampOverlay(overlayAlpha);
        this.bottomMode = normBottom;
        this.quoteText = cleanQuote;
        this.quoteCredit = cleanCredit;
        this.bottomPattern = cleanPattern;
        this.bottomImage = BOTTOM_IMAGE.equals(normBottom) ? cleanBottomImage : "";
    }

    public static CardStyle defaults() {
        return new CardStyle(LAYOUT_CLASSIC, PROGRESS_LINE, BG_DEFAULT, "", "", CROP_CENTER, OVERLAY_DEFAULT, BOTTOM_NONE, "", "", PATTERN_DOTS, "");
    }

    public boolean isDefault() {
        return LAYOUT_CLASSIC.equals(layoutMode)
            && PROGRESS_LINE.equals(progressStyle)
            && BG_DEFAULT.equals(backgroundMode)
            && BOTTOM_NONE.equals(bottomMode);
    }

    public boolean isImmersiveLayout() {
        return LAYOUT_IMMERSIVE.equals(layoutMode);
    }

    /**
     * Explicit priority rule for background appearance under global theme switching:
     * 1) User photo (always dark surface with legibility scrim)
     * 2) User gradient preset (follows preset luminance)
     * 3) User solid preset (follows preset luminance)
     * 4) Default card surface (follows active global AppTheme light/dark mode)
     */
    public boolean isDarkSurface(boolean appDark) {
        if (BG_IMAGE.equals(backgroundMode)) return true;
        if (BG_SOLID.equals(backgroundMode)) return findSolid(backgroundPreset).dark;
        if (BG_GRADIENT.equals(backgroundMode)) return findGradient(backgroundPreset).dark;
        return appDark;
    }

    /** Returns true when the user chose an explicit background that overrides the default paper. */
    public boolean hasCustomBackground() {
        return !BG_DEFAULT.equals(backgroundMode);
    }

    public SolidPreset solidPreset() {
        return findSolid(backgroundPreset);
    }

    public GradientPreset gradientPreset() {
        return findGradient(backgroundPreset);
    }

    public PatternPreset patternPreset() {
        return findPattern(bottomPattern);
    }

    public static String layoutLabel(String mode) {
        return LAYOUT_IMMERSIVE.equals(normalizeLayoutMode(mode)) ? "全屏沉浸" : "经典留边";
    }

    public static String progressLabel(String style) {
        String norm = normalizeProgressStyle(style);
        if (PROGRESS_SEGMENTS.equals(norm)) return "分段刻度";
        if (PROGRESS_RING.equals(norm)) return "紧凑环形";
        return "克制细线";
    }

    public String summary() {
        if (isDefault()) return "经典留边 · 默认纸面";
        StringBuilder sb = new StringBuilder();
        sb.append(layoutLabel(layoutMode));
        if (!PROGRESS_LINE.equals(progressStyle)) {
            sb.append(" · ").append(progressLabel(progressStyle));
        }
        String bg;
        if (BG_SOLID.equals(backgroundMode)) bg = "纯色 " + solidPreset().label;
        else if (BG_GRADIENT.equals(backgroundMode)) bg = "渐变 " + gradientPreset().label;
        else if (BG_IMAGE.equals(backgroundMode)) bg = "本地图片";
        else bg = "默认纸面";
        sb.append(" · ").append(bg);

        if (BOTTOM_QUOTE.equals(bottomMode)) sb.append(" · ").append(quoteText.isEmpty() ? "底部短句（未填）" : "底部短句");
        else if (BOTTOM_PATTERN.equals(bottomMode)) sb.append(" · 纹样 ").append(patternPreset().label);
        else if (BOTTOM_IMAGE.equals(bottomMode)) sb.append(" · 底部图片");

        return sb.toString();
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
            .put("layoutMode", layoutMode)
            .put("progressStyle", progressStyle)
            .put("backgroundMode", backgroundMode)
            .put("backgroundPreset", backgroundPreset)
            .put("backgroundImage", backgroundImage)
            .put("imageCrop", imageCrop)
            .put("overlayAlpha", overlayAlpha)
            .put("bottomMode", bottomMode)
            .put("quoteText", quoteText)
            .put("quoteCredit", quoteCredit)
            .put("bottomPattern", bottomPattern)
            .put("bottomImage", bottomImage);
    }

    public static CardStyle fromJson(JSONObject obj) {
        if (obj == null) return defaults();
        return new CardStyle(
            obj.optString("layoutMode", LAYOUT_CLASSIC),
            obj.optString("progressStyle", PROGRESS_LINE),
            obj.optString("backgroundMode", BG_DEFAULT),
            obj.optString("backgroundPreset", ""),
            obj.optString("backgroundImage", ""),
            obj.optString("imageCrop", CROP_CENTER),
            obj.optInt("overlayAlpha", OVERLAY_DEFAULT),
            obj.optString("bottomMode", BOTTOM_NONE),
            obj.optString("quoteText", ""),
            obj.optString("quoteCredit", ""),
            obj.optString("bottomPattern", PATTERN_DOTS),
            obj.optString("bottomImage", "")
        );
    }

    public static CardStyle fromJsonString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return defaults();
        try {
            return fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return defaults();
        }
    }

    public CardStyle withLayoutMode(String nextLayout) {
        return new CardStyle(nextLayout, progressStyle, backgroundMode, backgroundPreset, backgroundImage, imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withProgressStyle(String nextProgress) {
        return new CardStyle(layoutMode, nextProgress, backgroundMode, backgroundPreset, backgroundImage, imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withBackgroundDefault() {
        return new CardStyle(layoutMode, progressStyle, BG_DEFAULT, "", "", imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withSolid(String presetId) {
        return new CardStyle(layoutMode, progressStyle, BG_SOLID, presetId, "", imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withGradient(String presetId) {
        return new CardStyle(layoutMode, progressStyle, BG_GRADIENT, presetId, "", imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withBackgroundImage(String fileName) {
        return new CardStyle(layoutMode, progressStyle, BG_IMAGE, "", fileName, imageCrop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withImageCrop(String crop) {
        return new CardStyle(layoutMode, progressStyle, backgroundMode, backgroundPreset, backgroundImage, crop, overlayAlpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withOverlayAlpha(int alpha) {
        return new CardStyle(layoutMode, progressStyle, backgroundMode, backgroundPreset, backgroundImage, imageCrop, alpha, bottomMode, quoteText, quoteCredit, bottomPattern, bottomImage);
    }

    public CardStyle withBottomNone() {
        return new CardStyle(layoutMode, progressStyle, backgroundMode, backgroundPreset, backgroundImage, imageCrop, overlayAlpha, BOTTOM_NONE, quoteText, quoteCredit, bottomPattern, "");
    }

    public CardStyle withBottomQuote(String quote, String credit) {
        return new CardStyle(layoutMode, progressStyle, backgroundMode, backgroundPreset, backgroundImage, imageCrop, overlayAlpha, BOTTOM_QUOTE, quote, credit, bottomPattern, "");
    }

    public CardStyle withBottomPattern(String patternId) {
        return new CardStyle(layoutMode, progressStyle, backgroundMode, backgroundPreset, backgroundImage, imageCrop, overlayAlpha, BOTTOM_PATTERN, quoteText, quoteCredit, patternId, "");
    }

    public CardStyle withBottomImage(String fileName) {
        return new CardStyle(layoutMode, progressStyle, backgroundMode, backgroundPreset, backgroundImage, imageCrop, overlayAlpha, BOTTOM_IMAGE, quoteText, quoteCredit, bottomPattern, fileName);
    }

    public static String normalizeLayoutMode(String mode) {
        return LAYOUT_IMMERSIVE.equals(mode) ? LAYOUT_IMMERSIVE : LAYOUT_CLASSIC;
    }

    public static String normalizeProgressStyle(String style) {
        if (PROGRESS_SEGMENTS.equals(style) || PROGRESS_RING.equals(style)) return style;
        return PROGRESS_LINE;
    }

    public static String normalizeBgMode(String mode) {
        if (BG_SOLID.equals(mode) || BG_GRADIENT.equals(mode) || BG_IMAGE.equals(mode)) return mode;
        return BG_DEFAULT;
    }

    public static String normalizeBottomMode(String mode) {
        if (BOTTOM_QUOTE.equals(mode) || BOTTOM_PATTERN.equals(mode) || BOTTOM_IMAGE.equals(mode)) return mode;
        return BOTTOM_NONE;
    }

    public static String normalizeCrop(String crop) {
        if (CROP_TOP.equals(crop) || CROP_BOTTOM.equals(crop)) return crop;
        return CROP_CENTER;
    }

    public static int clampOverlay(int value) {
        return Math.max(OVERLAY_MIN, Math.min(OVERLAY_MAX, value));
    }

    public static SolidPreset findSolid(String id) {
        if (id != null) {
            for (SolidPreset p : SOLIDS) if (p.id.equals(id)) return p;
        }
        return SOLIDS[0];
    }

    public static GradientPreset findGradient(String id) {
        if (id != null) {
            for (GradientPreset p : GRADIENTS) if (p.id.equals(id)) return p;
        }
        return GRADIENTS[0];
    }

    public static PatternPreset findPattern(String id) {
        if (id != null) {
            for (PatternPreset p : PATTERNS) if (p.id.equals(id)) return p;
        }
        return PATTERNS[0];
    }

    /** Rejects Base64 blobs, URIs, and path traversal; allows only safe local filenames. */
    public static String sanitizeImageName(String name) {
        if (name == null) return "";
        String value = name.trim();
        if (value.isEmpty() || value.length() > 80) return "";
        if (value.contains("/") || value.contains("\\") || value.contains("..") || value.startsWith(".")) return "";
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("data:") || lower.contains("base64") || lower.contains(":")) return "";
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.';
            if (!ok) return "";
        }
        return value;
    }

    public static String sanitizeCopy(String text, int maxChars) {
        if (text == null) return "";
        String clean = text.replace('\r', ' ').replace('\n', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        return clean.length() > maxChars ? clean.substring(0, maxChars).trim() : clean;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CardStyle)) return false;
        CardStyle other = (CardStyle) o;
        return overlayAlpha == other.overlayAlpha
            && layoutMode.equals(other.layoutMode)
            && progressStyle.equals(other.progressStyle)
            && backgroundMode.equals(other.backgroundMode)
            && backgroundPreset.equals(other.backgroundPreset)
            && backgroundImage.equals(other.backgroundImage)
            && imageCrop.equals(other.imageCrop)
            && bottomMode.equals(other.bottomMode)
            && quoteText.equals(other.quoteText)
            && quoteCredit.equals(other.quoteCredit)
            && bottomPattern.equals(other.bottomPattern)
            && bottomImage.equals(other.bottomImage);
    }

    @Override public int hashCode() {
        int h = layoutMode.hashCode();
        h = 31 * h + progressStyle.hashCode();
        h = 31 * h + backgroundMode.hashCode();
        h = 31 * h + backgroundPreset.hashCode();
        h = 31 * h + backgroundImage.hashCode();
        h = 31 * h + imageCrop.hashCode();
        h = 31 * h + overlayAlpha;
        h = 31 * h + bottomMode.hashCode();
        h = 31 * h + quoteText.hashCode();
        h = 31 * h + quoteCredit.hashCode();
        h = 31 * h + bottomPattern.hashCode();
        h = 31 * h + bottomImage.hashCode();
        return h;
    }
}
