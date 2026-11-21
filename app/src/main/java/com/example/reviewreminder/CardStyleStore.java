package com.example.reviewreminder;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Persists {@link CardStyle} compact JSON in {@link Store#prefs(Context)} under {@code cardStyle}
 * and reconciles referenced local image files in {@link CardStyleImages}.
 */
final class CardStyleStore {
    static final String PREF_KEY = "cardStyle";

    private CardStyleStore() {}

    static CardStyle load(Context c) {
        String raw = Store.prefs(c).getString(PREF_KEY, "");
        CardStyle style = CardStyle.fromJsonString(raw);
        // Gracefully degrade if a private image file was deleted or is missing on disk.
        if (CardStyle.BG_IMAGE.equals(style.backgroundMode) && !CardStyleImages.exists(c, style.backgroundImage)) {
            style = style.withBackgroundDefault();
        }
        if (CardStyle.BOTTOM_IMAGE.equals(style.bottomMode) && !CardStyleImages.exists(c, style.bottomImage)) {
            style = style.withBottomNone();
        }
        return style;
    }

    static boolean save(Context c, CardStyle style) {
        CardStyle clean = style == null ? CardStyle.defaults() : style;
        try {
            boolean ok = Store.prefs(c).edit().putString(PREF_KEY, clean.toJson().toString()).commit();
            if (ok) {
                CardStyleImages.clearMemoryCache();
                CardStyleImages.pruneUnused(c, clean);
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean reset(Context c) {
        return save(c, CardStyle.defaults());
    }

    static boolean isNightMode(Context c) {
        if (c == null) return false;
        int mask = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }
}
