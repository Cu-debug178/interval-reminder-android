package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

final class Notifications {
    private Notifications() {}
    static boolean enabled(Context context){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)return false;
        try{return (Boolean)NotificationManager.class.getMethod("areNotificationsEnabled").invoke(context.getSystemService(Context.NOTIFICATION_SERVICE));}
        catch(Exception ignored){return true;}
    }
    static boolean exactAllowed(Context context){
        if(!exactPermissionNeeded(context))return true;
        try{return (Boolean)AlarmManager.class.getMethod("canScheduleExactAlarms").invoke(context.getSystemService(Context.ALARM_SERVICE));}
        catch(Exception ignored){return false;}
    }
    static boolean exactPermissionNeeded(Context context){
        if(Build.VERSION.SDK_INT<31)return false;
        int target;
        try{target=context.getPackageManager().getApplicationInfo(context.getPackageName(),0).targetSdkVersion;}catch(Exception ignored){target=28;}
        return target>=31;
    }
    static void openNotifications(Activity activity){
        ensureChannel(activity);
        Intent intent=new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE",activity.getPackageName());
        try{activity.startActivity(intent);}catch(Exception ignored){openAppDetails(activity);}
    }
    static void openExactAlarmSettings(Activity activity){
        Intent intent=new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").setData(Uri.parse("package:"+activity.getPackageName()));
        try{activity.startActivity(intent);}catch(Exception ignored){openAppDetails(activity);}
    }
    private static void openAppDetails(Activity activity){try{activity.startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS").setData(Uri.parse("package:"+activity.getPackageName())));}catch(Exception ignored){}}
    private static void ensureChannel(Context context){
        if(Build.VERSION.SDK_INT<26)return;
        try{
            Class<?> channelType=Class.forName("android.app.NotificationChannel");
            Object channel=channelType.getConstructor(String.class,CharSequence.class,int.class).newInstance("reviews","复习提醒",3);
            NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationManager.class.getMethod("createNotificationChannel",channelType).invoke(manager,channel);
        }catch(Exception ignored){}
    }
}
