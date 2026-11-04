package com.example.reviewreminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public final class Alarms {
    private Alarms() {}
    private static PendingIntent pending(Context c, long id) {
        Intent i=new Intent(c, ReminderReceiver.class).setAction("review."+id).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (int)id, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }
    public static void cancel(Context c, long id) {
        ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pending(c,id));
        ((android.app.NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int)id);
    }
    public static boolean schedule(Context c, JSONObject task) {
        if (task.optBoolean("done")) return true;
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(System.currentTimeMillis()+1000,task.optLong("due")), pending(c,task.optLong("id")));
            return true;
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(System.currentTimeMillis()+1000,task.optLong("due")), pending(c,task.optLong("id")));
            return false;
        }
    }
}
