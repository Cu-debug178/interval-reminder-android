import com.example.reviewreminder.AppTheme;
import com.example.reviewreminder.CardStyle;

public final class AppThemeTest {
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static double relLum(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double contrast(int c1, int c2) {
        double l1 = relLum(c1), l2 = relLum(c2);
        if (l1 < l2) { double t = l1; l1 = l2; l2 = t; }
        return (l1 + 0.05) / (l2 + 0.05);
    }

    public static void main(String[] args) throws Exception {
        // 1. Defaults & normalization
        AppTheme def = AppTheme.defaults();
        check(def.isDefault(), "defaults() is default");
        check(AppTheme.STYLE_PAPER_INK.equals(def.style), "default style is paper_ink");
        check(AppTheme.NIGHT_SYSTEM.equals(def.nightMode), "default nightMode is system");
        check(!def.resolveDark(false) && def.resolveDark(true), "system mode follows system night flag");

        AppTheme lightForced = def.withNightMode(AppTheme.NIGHT_LIGHT);
        check(!lightForced.resolveDark(true) && !lightForced.resolveDark(false), "light mode stays light regardless of system");

        AppTheme darkForced = def.withNightMode(AppTheme.NIGHT_DARK);
        check(darkForced.resolveDark(false) && darkForced.resolveDark(true), "dark mode stays dark regardless of system");

        AppTheme invalid = new AppTheme("neon_rainbow", "midnight");
        check(invalid.isDefault(), "invalid style and nightMode fall back to defaults");

        // 2. JSON round-trip & corrupt recovery
        AppTheme eyeDark = def.withStyle(AppTheme.STYLE_SOFT_EYE).withNightMode(AppTheme.NIGHT_DARK);
        String json = eyeDark.toJson().toString();
        AppTheme restored = AppTheme.fromJsonString(json);
        check(eyeDark.equals(restored), "AppTheme JSON round-trip preserves style and nightMode");
        check(AppTheme.fromJsonString("{bad").isDefault(), "corrupt AppTheme JSON recovers to defaults");
        check(AppTheme.fromJsonString("").isDefault(), "empty AppTheme JSON recovers to defaults");

        // 3. All 6 curated palettes meet WCAG 2.2 AA contrast thresholds
        check(AppTheme.STYLES.length == 3, "exposes 3 curated visual styles");
        for (AppTheme.StyleOption opt : AppTheme.STYLES) {
            for (boolean dark : new boolean[]{false, true}) {
                AppTheme.Palette p = AppTheme.paletteFor(opt.id, dark);
                String tag = opt.id + "/" + (dark ? "dark" : "light");
                check(p.dark == dark, tag + " dark flag matches");
                check(contrast(p.ink, p.bg) >= 4.5, tag + " INK/BG >= 4.5");
                check(contrast(p.ink, p.surface) >= 4.5, tag + " INK/SURFACE >= 4.5");
                check(contrast(p.ink2, p.bg) >= 4.5, tag + " INK_2/BG >= 4.5");
                check(contrast(p.ink2, p.surface) >= 4.5, tag + " INK_2/SURFACE >= 4.5");
                check(contrast(p.ink3, p.surface) >= 4.5, tag + " INK_3/SURFACE >= 4.5");
                check(contrast(p.onPrimary, p.primary) >= 4.5, tag + " ON_PRIMARY/PRIMARY >= 4.5");
                check(contrast(p.primary, p.surface) >= 3.0, tag + " PRIMARY/SURFACE >= 3.0");
                check(contrast(p.danger, p.surface) >= 3.0, tag + " DANGER/SURFACE >= 3.0");
                int accentText = dark ? p.primaryTint : p.primary;
                check(contrast(accentText, p.primarySoft) >= 4.5, tag + " ACCENT_TEXT/PRIMARY_SOFT >= 4.5");
            }
        }

        // 4. Explicit priority between global theme and custom CardStyle
        CardStyle defaultCard = CardStyle.defaults();
        check(!defaultCard.hasCustomBackground(), "default card has no custom background");
        check(defaultCard.isDarkSurface(darkForced.resolveDark(false)), "default card follows global dark mode");
        check(!defaultCard.isDarkSurface(lightForced.resolveDark(true)), "default card follows global light mode");

        CardStyle lightCustomCard = CardStyle.defaults().withSolid("warm_linen").withBottomQuote("保持节奏", "");
        check(lightCustomCard.hasCustomBackground(), "solid preset is custom background");
        check(!lightCustomCard.isDarkSurface(darkForced.resolveDark(false)), "explicit light solid preset preserved under global dark mode");

        CardStyle photoCard = CardStyle.defaults().withBackgroundImage("bg_sample.jpg");
        check(photoCard.isDarkSurface(lightForced.resolveDark(false)), "photo card preserves dark legibility surface under global light mode");

        // 5. Complete in-app design presets preserve nightMode and custom card media/quotes/patterns
        check(AppTheme.PRESETS.length == 3, "exposes 3 distinct complete design presets");
        check(AppTheme.PRESET_CLASSIC_PAPER.equals(AppTheme.matchingPreset(AppTheme.defaults(), CardStyle.defaults())),
            "default theme + card matches classic_paper preset");
        CardStyle customPhotoQuote = CardStyle.defaults()
            .withBackgroundImage("bg_user123.jpg")
            .withImageCrop(CardStyle.CROP_TOP)
            .withOverlayAlpha(55)
            .withBottomQuote("保持每天一小步", "笔记");
        AppTheme darkCustom = AppTheme.defaults().withNightMode(AppTheme.NIGHT_DARK);

        AppTheme afterStackedTheme = darkCustom.applyPreset(AppTheme.PRESET_STACKED_DECK);
        CardStyle afterStackedCard = AppTheme.applyPresetToCard(customPhotoQuote, AppTheme.PRESET_STACKED_DECK);
        check(AppTheme.STYLE_SOFT_EYE.equals(afterStackedTheme.style) && AppTheme.NIGHT_DARK.equals(afterStackedTheme.nightMode),
            "applying stacked_deck preset updates visual style while preserving nightMode");
        check(afterStackedCard.isStackedLayout() && CardStyle.PROGRESS_SEGMENTS.equals(afterStackedCard.progressStyle),
            "applying stacked_deck preset sets stacked layout and segmented progress");
        check(CardStyle.BG_IMAGE.equals(afterStackedCard.backgroundMode)
            && "bg_user123.jpg".equals(afterStackedCard.backgroundImage)
            && CardStyle.CROP_TOP.equals(afterStackedCard.imageCrop)
            && afterStackedCard.overlayAlpha == 55
            && CardStyle.BOTTOM_QUOTE.equals(afterStackedCard.bottomMode)
            && "保持每天一小步".equals(afterStackedCard.quoteText)
            && "笔记".equals(afterStackedCard.quoteCredit),
            "applying stacked_deck preset preserves user photo, crop, overlay, and quote");
        check(AppTheme.PRESET_STACKED_DECK.equals(AppTheme.matchingPreset(afterStackedTheme, afterStackedCard)),
            "matchingPreset detects stacked_deck even when user keeps custom photo/quote");

        AppTheme afterImmersiveTheme = afterStackedTheme.applyPreset(AppTheme.PRESET_QUIET_IMMERSIVE);
        CardStyle afterImmersiveCard = AppTheme.applyPresetToCard(afterStackedCard, AppTheme.PRESET_QUIET_IMMERSIVE);
        check(AppTheme.STYLE_SLATE_MIST.equals(afterImmersiveTheme.style)
            && AppTheme.NIGHT_DARK.equals(afterImmersiveTheme.nightMode)
            && afterImmersiveCard.isImmersiveLayout()
            && CardStyle.PROGRESS_RING.equals(afterImmersiveCard.progressStyle)
            && "bg_user123.jpg".equals(afterImmersiveCard.backgroundImage)
            && "保持每天一小步".equals(afterImmersiveCard.quoteText),
            "switching stacked_deck -> quiet_immersive preserves nightMode and custom photo/quote");

        System.out.println("AppThemeTest: " + checks + " checks passed");
    }
}
