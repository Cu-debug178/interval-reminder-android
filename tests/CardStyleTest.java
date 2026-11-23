import com.example.reviewreminder.CardStyle;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CardStyleTest {
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        // 1. Defaults & summary
        CardStyle def = CardStyle.defaults();
        check(def.isDefault(), "defaults() is default");
        check(CardStyle.BG_DEFAULT.equals(def.backgroundMode), "default background mode");
        check(CardStyle.BOTTOM_NONE.equals(def.bottomMode), "default bottom mode");
        check(def.overlayAlpha == CardStyle.OVERLAY_DEFAULT, "default overlay alpha");
        check(!def.isDarkSurface(false), "default paper is light in light mode");
        check(def.isDarkSurface(true), "default paper adapts to dark mode");

        // 2. Original sample quotes (no invented attributions)
        check(CardStyle.SAMPLE_QUOTES.length >= 4, "provides original sample quotes");
        for (CardStyle.SampleQuote q : CardStyle.SAMPLE_QUOTES) {
            check(q.text != null && !q.text.trim().isEmpty(), "sample quote text non-empty");
            check(q.credit != null && q.credit.isEmpty(), "sample quotes have no invented attribution");
        }

        // 3. Solid & gradient presets (light & dark surface detection)
        CardStyle warm = def.withSolid("warm_linen");
        check(CardStyle.BG_SOLID.equals(warm.backgroundMode) && !warm.isDarkSurface(true), "warm linen solid is light surface");
        CardStyle night = def.withSolid("night_ink");
        check(night.isDarkSurface(false), "night ink solid is dark surface even in light mode");
        CardStyle grad = def.withGradient("deep_forest");
        check(CardStyle.BG_GRADIENT.equals(grad.backgroundMode) && grad.isDarkSurface(false), "deep forest gradient is dark surface");
        CardStyle unknownSolid = def.withSolid("nonexistent_preset");
        check(unknownSolid.backgroundPreset.equals(CardStyle.SOLIDS[0].id), "unknown solid falls back to first preset");

        // 4. Photo background, crop anchor, single overlay clamp & filename sanitization
        CardStyle img = def.withBackgroundImage("bg_1234abcd.jpg")
            .withImageCrop(CardStyle.CROP_TOP)
            .withOverlayAlpha(95);
        check(CardStyle.BG_IMAGE.equals(img.backgroundMode), "valid local image name keeps image mode");
        check("bg_1234abcd.jpg".equals(img.backgroundImage), "valid local image name stored");
        check(CardStyle.CROP_TOP.equals(img.imageCrop), "crop anchor preserved");
        check(img.overlayAlpha == CardStyle.OVERLAY_MAX, "overlay alpha clamped at max 80");
        check(img.withOverlayAlpha(-10).overlayAlpha == CardStyle.OVERLAY_MIN, "overlay alpha clamped at min 0");
        check(img.isDarkSurface(false), "photo background uses dark scrim contrast");

        // Reject Base64, URIs, and path traversal in image names (never allow image Base64 in prefs!)
        CardStyle badTraversal = def.withBackgroundImage("../secret.jpg");
        check(CardStyle.BG_DEFAULT.equals(badTraversal.backgroundMode) && badTraversal.backgroundImage.isEmpty(), "path traversal rejected");
        CardStyle badBase64 = def.withBackgroundImage("data:image/jpeg;base64,/9j/4AAQ");
        check(CardStyle.BG_DEFAULT.equals(badBase64.backgroundMode) && badBase64.backgroundImage.isEmpty(), "Base64 data URI rejected from prefs model");
        CardStyle badBottom = def.withBottomImage("content://media/external/images/1");
        check(CardStyle.BOTTOM_NONE.equals(badBottom.bottomMode) && badBottom.bottomImage.isEmpty(), "content URI rejected from bottom image model");

        // 5. Quote / credit sanitization & length bounds + pattern + bottom image
        StringBuilder longQuote = new StringBuilder();
        for (int i = 0; i < 160; i++) longQuote.append('词');
        CardStyle quoted = img.withBottomQuote("  " + longQuote + "\n  ", "  个人笔记  ");
        check(CardStyle.BOTTOM_QUOTE.equals(quoted.bottomMode), "quote mode set");
        check(quoted.quoteText.length() == CardStyle.MAX_QUOTE_CHARS, "long quote trimmed to MAX_QUOTE_CHARS");
        check("个人笔记".equals(quoted.quoteCredit), "credit trimmed cleanly");

        CardStyle patterned = def.withBottomPattern(CardStyle.PATTERN_BOTANICAL);
        check(CardStyle.BOTTOM_PATTERN.equals(patterned.bottomMode) && CardStyle.PATTERN_BOTANICAL.equals(patterned.bottomPattern), "botanical pattern preserved");

        CardStyle bottomImg = def.withBottomImage("bt_abcd5678.jpg");
        check(CardStyle.BOTTOM_IMAGE.equals(bottomImg.bottomMode) && "bt_abcd5678.jpg".equals(bottomImg.bottomImage), "valid bottom image name preserved");

        // 6. Layout modes, progress styles, theme-switch priority, and legacy JSON migration
        check(CardStyle.LAYOUT_CLASSIC.equals(def.layoutMode) && !def.isImmersiveLayout(), "default layout is classic framed");
        check(CardStyle.PROGRESS_LINE.equals(def.progressStyle), "default progress style is restrained line");
        CardStyle immersiveSeg = def.withLayoutMode(CardStyle.LAYOUT_IMMERSIVE).withProgressStyle(CardStyle.PROGRESS_SEGMENTS);
        check(immersiveSeg.isImmersiveLayout() && !immersiveSeg.isStackedLayout() && CardStyle.PROGRESS_SEGMENTS.equals(immersiveSeg.progressStyle), "immersive + segments set");
        check(!immersiveSeg.isDefault(), "immersive layout is non-default");
        CardStyle stackedStyle = def.withLayoutMode(CardStyle.LAYOUT_STACKED).withProgressStyle(CardStyle.PROGRESS_SEGMENTS);
        check(stackedStyle.isStackedLayout() && !stackedStyle.isImmersiveLayout() && "堆叠卡片".equals(CardStyle.layoutLabel(stackedStyle.layoutMode)), "stacked layout supported and labeled");
        check(!stackedStyle.isDefault() && stackedStyle.equals(CardStyle.fromJsonString(stackedStyle.toJson().toString())), "stacked layout round-trips cleanly");
        CardStyle ringStyle = def.withProgressStyle(CardStyle.PROGRESS_RING);
        check(CardStyle.PROGRESS_RING.equals(ringStyle.progressStyle), "ring progress style preserved");
        check(CardStyle.LAYOUT_CLASSIC.equals(def.withLayoutMode("invalid").layoutMode), "invalid layout normalizes to classic");
        check(CardStyle.PROGRESS_LINE.equals(def.withProgressStyle("invalid").progressStyle), "invalid progress style normalizes to line");
        // Legacy JSON without layoutMode / progressStyle fields migrates cleanly to classic + line while preserving custom bg/quote
        String legacyJson = "{\"backgroundMode\":\"gradient\",\"backgroundPreset\":\"pine_mist\",\"bottomMode\":\"quote\",\"quoteText\":\"保持节奏\"}";
        CardStyle migrated = CardStyle.fromJsonString(legacyJson);
        check(CardStyle.LAYOUT_CLASSIC.equals(migrated.layoutMode) && CardStyle.PROGRESS_LINE.equals(migrated.progressStyle), "legacy JSON defaults to classic + line");
        check(CardStyle.BG_GRADIENT.equals(migrated.backgroundMode) && "保持节奏".equals(migrated.quoteText), "legacy JSON preserves background & quote");

        // 7. JSON round-trip & corrupt JSON recovery
        CardStyle fullCustom = quoted.withLayoutMode(CardStyle.LAYOUT_IMMERSIVE).withProgressStyle(CardStyle.PROGRESS_RING);
        JSONObject json = fullCustom.toJson();
        String raw = json.toString();
        check(!raw.contains("base64") && !raw.contains("data:image"), "serialized prefs JSON contains no Base64 image data");
        CardStyle restored = CardStyle.fromJsonString(raw);
        check(fullCustom.equals(restored), "JSON round-trip preserves full style including layoutMode and progressStyle");
        check(CardStyle.fromJsonString("{not valid json").isDefault(), "corrupt JSON gracefully recovers to defaults");
        check(CardStyle.fromJsonString("").isDefault(), "blank JSON recovers to defaults");

        // 7. Manifest permission hygiene: ensure NO broad storage permissions were added
        Path manifest = Paths.get("app/src/main/AndroidManifest.xml");
        if (Files.exists(manifest)) {
            String xml = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            check(!xml.contains("READ_EXTERNAL_STORAGE"), "manifest has no READ_EXTERNAL_STORAGE");
            check(!xml.contains("WRITE_EXTERNAL_STORAGE"), "manifest has no WRITE_EXTERNAL_STORAGE");
            check(!xml.contains("MANAGE_EXTERNAL_STORAGE"), "manifest has no MANAGE_EXTERNAL_STORAGE");
            check(!xml.contains("READ_MEDIA_IMAGES"), "manifest has no READ_MEDIA_IMAGES");
            check(xml.contains("CardStyleActivity"), "manifest registers CardStyleActivity");
        }

        System.out.println("CardStyleTest: " + checks + " checks passed");
    }
}
