package com.example.reviewreminder;

import org.json.JSONObject;

/**
 * Pure-Java global visual style and light/dark strategy model.
 * Separates visual style (paper_ink / soft_eye / slate_mist) from night-mode policy
 * (system / light / dark), providing WCAG 2.2 AA verified palettes across all 6 combinations.
 */
public final class AppTheme {
    public static final String STYLE_PAPER_INK = "paper_ink";
    public static final String STYLE_SOFT_EYE = "soft_eye";
    public static final String STYLE_SLATE_MIST = "slate_mist";

    public static final String NIGHT_SYSTEM = "system";
    public static final String NIGHT_LIGHT = "light";
    public static final String NIGHT_DARK = "dark";

    public static final String PRESET_CLASSIC_PAPER = "classic_paper";
    public static final String PRESET_STACKED_DECK = "stacked_deck";
    public static final String PRESET_QUIET_IMMERSIVE = "quiet_immersive";

    public static final class DesignPreset {
        public final String id, label, summary, styleId, layoutMode, progressStyle;
        DesignPreset(String id, String label, String summary, String styleId, String layoutMode, String progressStyle) {
            this.id = id;
            this.label = label;
            this.summary = summary;
            this.styleId = styleId;
            this.layoutMode = layoutMode;
            this.progressStyle = progressStyle;
        }
    }

    /**
     * Three meaningfully distinct complete in-app design presets.
     * Applying a preset updates visual style, card layout, and progress indicator coherently while
     * preserving the user's orthogonal night-mode policy, custom card backgrounds/quotes/patterns,
     * learning history, and voice settings.
     */
    public static final DesignPreset[] PRESETS = {
        new DesignPreset(
            PRESET_CLASSIC_PAPER,
            "纸墨经典",
            "纸墨清爽 · 经典留边 · 克制细线",
            STYLE_PAPER_INK,
            CardStyle.LAYOUT_CLASSIC,
            CardStyle.PROGRESS_LINE
        ),
        new DesignPreset(
            PRESET_STACKED_DECK,
            "松林叠卡",
            "柔和护眼 · 堆叠卡片（右记/左忘）· 分段刻度",
            STYLE_SOFT_EYE,
            CardStyle.LAYOUT_STACKED,
            CardStyle.PROGRESS_SEGMENTS
        ),
        new DesignPreset(
            PRESET_QUIET_IMMERSIVE,
            "静谧全屏",
            "静谧青灰 · 全屏沉浸 · 紧凑环形",
            STYLE_SLATE_MIST,
            CardStyle.LAYOUT_IMMERSIVE,
            CardStyle.PROGRESS_RING
        )
    };

    public static final class StyleOption {
        public final String id, label, detail;
        public final int lightPreviewBg, lightPreviewAccent, darkPreviewBg, darkPreviewAccent;
        StyleOption(String id, String label, String detail,
                    int lightPreviewBg, int lightPreviewAccent,
                    int darkPreviewBg, int darkPreviewAccent) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.lightPreviewBg = lightPreviewBg;
            this.lightPreviewAccent = lightPreviewAccent;
            this.darkPreviewBg = darkPreviewBg;
            this.darkPreviewAccent = darkPreviewAccent;
        }
    }

    public static final StyleOption[] STYLES = {
        new StyleOption(STYLE_PAPER_INK, "纸墨清爽", "暖白宣纸与松墨绿，默认阅读风格",
            0xFFF6F5F1, 0xFF1F5A4C, 0xFF121614, 0xFF2D7A67),
        new StyleOption(STYLE_SOFT_EYE, "柔和护眼", "温润淡苔纸面，降低长时间复习眩光",
            0xFFF2EFE6, 0xFF355C45, 0xFF151813, 0xFF417D5B),
        new StyleOption(STYLE_SLATE_MIST, "静谧青灰", "清透雾灰纸面与黛青强调色",
            0xFFF1F4F6, 0xFF285A70, 0xFF12161A, 0xFF347490)
    };

    public static final class Palette {
        public final boolean dark;
        public final int bg, surface, sunk, hairline;
        public final int ink, ink2, ink3;
        public final int primary, primaryPressed, primarySoft, primaryTint, onPrimary;
        public final int danger, dangerSoft, warn, warnSoft, scrim;
        public final int rippleLight, rippleDark, rippleAccent;

        Palette(boolean dark,
                int bg, int surface, int sunk, int hairline,
                int ink, int ink2, int ink3,
                int primary, int primaryPressed, int primarySoft, int primaryTint, int onPrimary,
                int danger, int dangerSoft, int warn, int warnSoft, int scrim,
                int rippleLight, int rippleDark, int rippleAccent) {
            this.dark = dark;
            this.bg = bg;
            this.surface = surface;
            this.sunk = sunk;
            this.hairline = hairline;
            this.ink = ink;
            this.ink2 = ink2;
            this.ink3 = ink3;
            this.primary = primary;
            this.primaryPressed = primaryPressed;
            this.primarySoft = primarySoft;
            this.primaryTint = primaryTint;
            this.onPrimary = onPrimary;
            this.danger = danger;
            this.dangerSoft = dangerSoft;
            this.warn = warn;
            this.warnSoft = warnSoft;
            this.scrim = scrim;
            this.rippleLight = rippleLight;
            this.rippleDark = rippleDark;
            this.rippleAccent = rippleAccent;
        }
    }

    public final String style;
    public final String nightMode;

    public AppTheme(String style, String nightMode) {
        this.style = normalizeStyle(style);
        this.nightMode = normalizeNightMode(nightMode);
    }

    public static AppTheme defaults() {
        return new AppTheme(STYLE_PAPER_INK, NIGHT_SYSTEM);
    }

    public boolean isDefault() {
        return STYLE_PAPER_INK.equals(style) && NIGHT_SYSTEM.equals(nightMode);
    }

    public AppTheme withStyle(String nextStyle) {
        return new AppTheme(nextStyle, nightMode);
    }

    public AppTheme withNightMode(String nextNightMode) {
        return new AppTheme(style, nextNightMode);
    }

    public boolean resolveDark(boolean systemNight) {
        if (NIGHT_DARK.equals(nightMode)) return true;
        if (NIGHT_LIGHT.equals(nightMode)) return false;
        return systemNight;
    }

    public Palette palette(boolean systemNight) {
        return paletteFor(style, resolveDark(systemNight));
    }

    public static Palette paletteFor(String styleId, boolean dark) {
        String s = normalizeStyle(styleId);
        if (STYLE_SOFT_EYE.equals(s)) {
            if (dark) {
                return new Palette(true,
                    0xFF151813, 0xFF1E221B, 0xFF282D24, 0xFF343B2F,
                    0xFFEFECE2, 0xFFBEBCAE, 0xFF959484,
                    0xFF417D5B, 0xFF356A4C, 0xFF24342A, 0xFF7CB494, 0xFFFFFFFF,
                    0xFFD47056, 0xFF3B241E, 0xFFD9A84E, 0xFF372E1B, 0x990A0C09,
                    0x20EFECE2, 0x38FFFFFF, 0x307CB494);
            }
            return new Palette(false,
                0xFFF2EFE6, 0xFFFAF7F0, 0xFFE8E3D5, 0xFFDCD6C5,
                0xFF22261E, 0xFF52594B, 0xFF676E60,
                0xFF355C45, 0xFF2A4B37, 0xFFE2ECE3, 0xFF6F9A7E, 0xFFFFFFFF,
                0xFFA34732, 0xFFF4E4DD, 0xFF835B16, 0xFFF5EBCE, 0x66171A15,
                0x1622261E, 0x38FFFFFF, 0x24355C45);
        }
        if (STYLE_SLATE_MIST.equals(s)) {
            if (dark) {
                return new Palette(true,
                    0xFF12161A, 0xFF1B2127, 0xFF242C34, 0xFF303B45,
                    0xFFEDF2F6, 0xFFB2C0CB, 0xFF8A98A4,
                    0xFF347490, 0xFF2A6179, 0xFF1E323D, 0xFF73B2CE, 0xFFFFFFFF,
                    0xFFD46A5E, 0xFF3B2321, 0xFFD6A44C, 0xFF362D1B, 0x99090C0F,
                    0x20EDF2F6, 0x38FFFFFF, 0x3073B2CE);
            }
            return new Palette(false,
                0xFFF1F4F6, 0xFFFFFFFF, 0xFFE6ECEF, 0xFFD9E1E6,
                0xFF182127, 0xFF4D5B65, 0xFF64727C,
                0xFF285A70, 0xFF1F495C, 0xFFE1EEF4, 0xFF6897AE, 0xFFFFFFFF,
                0xFFA8443A, 0xFFF6E6E4, 0xFF855D16, 0xFFF6ECD8, 0x6612161A,
                0x16182127, 0x38FFFFFF, 0x24285A70);
        }
        // Default: paper_ink
        if (dark) {
            return new Palette(true,
                0xFF121614, 0xFF1B211F, 0xFF242C29, 0xFF2F3935,
                0xFFF1F4F2, 0xFFB7C2BD, 0xFF8B9892,
                0xFF2D7A67, 0xFF236454, 0xFF1D332D, 0xFF74B8A4, 0xFFFFFFFF,
                0xFFD46A52, 0xFF3B241E, 0xFFD8A64B, 0xFF372E1B, 0x990A0D0C,
                0x20F1F4F2, 0x38FFFFFF, 0x3074B8A4);
        }
        return new Palette(false,
            0xFFF6F5F1, 0xFFFFFFFF, 0xFFEFEEE9, 0xFFE6E4DE,
            0xFF1B2220, 0xFF525B57, 0xFF6A726E,
            0xFF1F5A4C, 0xFF184A3E, 0xFFE4EEE9, 0xFF6E9C8F, 0xFFFFFFFF,
            0xFFA6442E, 0xFFF6E8E3, 0xFF8A5F17, 0xFFF7EEDB, 0x66141A18,
            0x161B2220, 0x38FFFFFF, 0x241F5A4C);
    }

    public static StyleOption findStyle(String id) {
        String norm = normalizeStyle(id);
        for (StyleOption opt : STYLES) {
            if (opt.id.equals(norm)) return opt;
        }
        return STYLES[0];
    }

    public static DesignPreset findPreset(String id) {
        if (id != null) {
            for (DesignPreset p : PRESETS) {
                if (p.id.equals(id)) return p;
            }
        }
        return PRESETS[0];
    }

    /** Returns the active complete preset ID if theme + card layout/progress match one, or "". */
    public static String matchingPreset(AppTheme theme, CardStyle card) {
        AppTheme t = theme == null ? defaults() : theme;
        CardStyle c = card == null ? CardStyle.defaults() : card;
        for (DesignPreset p : PRESETS) {
            if (p.styleId.equals(t.style) && p.layoutMode.equals(c.layoutMode) && p.progressStyle.equals(c.progressStyle)) {
                return p.id;
            }
        }
        return "";
    }

    /** Applies a complete preset to AppTheme while keeping the user's nightMode untouched. */
    public AppTheme applyPreset(String presetId) {
        DesignPreset p = findPreset(presetId);
        return withStyle(p.styleId);
    }

    /** Applies a complete preset's layout & progress to CardStyle while preserving all custom backgrounds/quotes/patterns/images. */
    public static CardStyle applyPresetToCard(CardStyle current, String presetId) {
        CardStyle base = current == null ? CardStyle.defaults() : current;
        DesignPreset p = findPreset(presetId);
        return base.withLayoutMode(p.layoutMode).withProgressStyle(p.progressStyle);
    }

    public static String nightLabel(String mode) {
        String norm = normalizeNightMode(mode);
        if (NIGHT_LIGHT.equals(norm)) return "始终浅色";
        if (NIGHT_DARK.equals(norm)) return "始终深色";
        return "跟随系统";
    }

    public String summary() {
        return findStyle(style).label + " · " + nightLabel(nightMode);
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
            .put("style", style)
            .put("nightMode", nightMode);
    }

    public static AppTheme fromJson(JSONObject obj) {
        if (obj == null) return defaults();
        return new AppTheme(
            obj.optString("style", STYLE_PAPER_INK),
            obj.optString("nightMode", NIGHT_SYSTEM)
        );
    }

    public static AppTheme fromJsonString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return defaults();
        try {
            return fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return defaults();
        }
    }

    public static String normalizeStyle(String value) {
        if (STYLE_SOFT_EYE.equals(value) || STYLE_SLATE_MIST.equals(value)) return value;
        return STYLE_PAPER_INK;
    }

    public static String normalizeNightMode(String value) {
        if (NIGHT_LIGHT.equals(value) || NIGHT_DARK.equals(value)) return value;
        return NIGHT_SYSTEM;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppTheme)) return false;
        AppTheme other = (AppTheme) o;
        return style.equals(other.style) && nightMode.equals(other.nightMode);
    }

    @Override public int hashCode() {
        return 31 * style.hashCode() + nightMode.hashCode();
    }
}
