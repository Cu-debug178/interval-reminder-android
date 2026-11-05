package com.example.reviewreminder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        long id=intent.getLongExtra("id",0);
        JSONObject t=Store.find(Store.tasks(c),id);
        if (t==null || t.optBoolean("done")) return;
        if (t.optLong("due")>System.currentTimeMillis()+1000) { Alarms.schedule(c,t); return; }
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notifications.ensureChannel(c);
        Intent open=new Intent(c,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,(int)id,open,PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        Notification.Builder b=new Notification.Builder(c).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(t.optString("title")).setContentText("第 "+(t.optInt("stage")+1)+" 次复习到时间了")
                .setContentIntent(pi).setAutoCancel(true).setDefaults(Store.prefs(c).getBoolean("reminderSound",true)?Notification.DEFAULT_ALL:0);
        if (Build.VERSION.SDK_INT>=26) {
            try { Notification.Builder.class.getMethod("setChannelId",String.class).invoke(b,Notifications.channel(c)); } catch (Exception ignored) { }
        }
        nm.notify((int)id,b.build());
    }
}
