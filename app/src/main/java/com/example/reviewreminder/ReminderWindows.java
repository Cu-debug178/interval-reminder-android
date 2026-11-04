package com.example.reviewreminder;

import android.content.Context;

final class ReminderWindows {
    private ReminderWindows() {}
    static boolean restricted(Context context){String windows=Store.prefs(context).getString("windows","");return Store.prefs(context).getBoolean("respectWindows",!windows.isEmpty());}
    static String active(Context context){return restricted(context)?Store.prefs(context).getString("windows",""):"";}
}
