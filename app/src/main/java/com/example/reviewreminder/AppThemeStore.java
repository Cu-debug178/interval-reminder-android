package com.example.reviewreminder;

import android.content.Context;
import android.content.res.Configuration;

/** Local persistence and runtime dark-mode resolution for global {@link AppTheme}. */
final class AppThemeStore {
    static final String PREF_KEY = "appThemeJson";

    private AppThemeStore() {}

    static AppTheme load(Context c) {
        if (c == null) return AppTheme.defaults();
        return AppTheme.fromJsonString(Store.prefs(c).getString(PREF_KEY, ""));
    }

    static boolean save(Context c, AppTheme theme) {
        if (c == null) return false;
        AppTheme safe = theme == null ? AppTheme.defaults() : theme;
        try {
            boolean ok = Store.prefs(c).edit().putString(PREF_KEY, safe.toJson().toString()).commit();
            if (ok) Theme.apply(c);
            return ok;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void reset(Context c) {
        if (c == null) return;
        Store.prefs(c).edit().remove(PREF_KEY).commit();
        Theme.apply(c);
    }

    static boolean isSystemNight(Context c) {
        if (c == null || c.getResources() == null) return false;
        int mask = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean isDark(Context c) {
        return load(c).resolveDark(isSystemNight(c));
    }

    static String signature(Context c) {
        AppTheme t = load(c);
        return t.style + "|" + t.nightMode + "|" + (t.resolveDark(isSystemNight(c)) ? "dark" : "light");
    }
}
