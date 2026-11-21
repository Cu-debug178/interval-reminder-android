package com.example.reviewreminder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        long id = intent.getLongExtra("id", 0);
        JSONObject t = Store.find(Store.tasks(c), id);
        if (t == null || t.optBoolean("done")) return;
        if (t.optLong("due") > System.currentTimeMillis() + 1000) {
            Alarms.schedule(c, t);
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notifications.ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, (int) id, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        boolean soundOn = Store.prefs(c).getBoolean("reminderSound", true);
        String title = t.optString("title");
        int round = t.optInt("stage") + 1;
        Notification.Builder b = new Notification.Builder(c)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("第 " + round + " 次复习到时间了")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setDefaults(soundOn ? Notification.DEFAULT_ALL : 0);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, Notifications.channel(c));
            } catch (Exception ignored) {
            }
        }
        nm.notify((int) id, b.build());

        if (soundOn && Store.prefs(c).getBoolean("reminderVoiceSpeak", false) && ringerNormal(c)) {
            speakReminder(c, title, round);
        }
    }

    private static boolean ringerNormal(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
        } catch (Exception ignored) {
            return true;
        }
    }

    private void speakReminder(Context c, String title, int round) {
        final PendingResult pending = goAsync();
        final SpeechManager speech = new SpeechManager(c.getApplicationContext());
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                try { speech.shutdown(); } catch (Exception ignored) {}
                try { pending.finish(); } catch (Exception ignored) {}
            }
        };
        main.postDelayed(finish, 9000L);
        String line = (title == null || title.trim().isEmpty() ? "复习任务" : title.trim()) + "，第 " + round + " 次复习到时间了";
        speech.speak(line, "reminder", null, null, -1, finish);
    }
}
