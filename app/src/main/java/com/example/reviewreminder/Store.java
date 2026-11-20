package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade over the normalized SQLite tables in {@link LocalStore}.
 * Prefer targeted {@link #find(Context, long)}, {@link #saveTask}, {@link #updateCard} and
 * {@link #deleteTask} over rewriting all plans at once.
 */
public final class Store {
    static final String PREF = "review_data";
    public static final String DEFAULT_INTERVALS = "5,30,720,1440,2880,5760,10080,20160";
    private Store() {}

    /** Private SQLite-backed preferences for lightweight settings. */
    public static android.content.SharedPreferences prefs(Context c) { return LocalStore.prefs(c); }

    public static JSONArray tasks(Context c) {
        try {
            return LocalStore.inRead(c, () -> LocalStore.readAllTasksLocked(LocalStore.database()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JSONObject find(Context c, long id) {
        try {
            return LocalStore.inRead(c, () -> LocalStore.readTaskLocked(LocalStore.database(), id));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void save(Context c, JSONArray tasks) {
        try {
            LocalStore.inTransaction(c, () -> LocalStore.writeAllTasksLocked(LocalStore.database(), tasks));
        } catch (Exception e) {
            throw new IllegalStateException("数据未能写入设备，请检查存储空间", e);
        }
    }

    public static void saveTask(Context c, JSONObject task) {
        try {
            LocalStore.inTransaction(c, () -> LocalStore.writeTaskLocked(LocalStore.database(), task, -1));
        } catch (Exception e) {
            throw new IllegalStateException("任务未能写入设备，请检查存储空间", e);
        }
    }

    public static void updateCard(Context c, long taskId, int pos, JSONObject card) {
        try {
            LocalStore.inTransaction(c, () -> LocalStore.writeCardRowLocked(LocalStore.database(), taskId, pos, card));
        } catch (Exception e) {
            throw new IllegalStateException("单词卡未能写入设备，请检查存储空间", e);
        }
    }

    public static void deleteTask(Context c, long taskId) {
        try {
            LocalStore.inTransaction(c, () -> LocalStore.deleteTaskLocked(LocalStore.database(), taskId));
        } catch (Exception e) {
            throw new IllegalStateException("删除失败，请检查存储空间", e);
        }
    }

    public static JSONObject find(JSONArray all, long id) {
        for (int i=0; i<all.length(); i++) { JSONObject t=all.optJSONObject(i); if (t != null && t.optLong("id") == id) return t; }
        return null;
    }

    public static List<int[]> windows(String value) {
        List<int[]> out = new ArrayList<>();
        if (value.trim().isEmpty()) return out;
        for (String part : value.split(",")) {
            String[] ends=part.trim().split("-");
            if (ends.length != 2) throw new IllegalArgumentException("时间段格式应为 07:30-08:30");
            int a=time(ends[0]), b=time(ends[1]);
            if (a==b) throw new IllegalArgumentException("时间段起止不能相同；全天请留空");
            out.add(new int[]{a,b});
        }
        return out;
    }

    private static int time(String text) {
        String[] p=text.trim().split(":");
        if (p.length!=2) throw new IllegalArgumentException("时间格式应为 07:30");
        int h=Integer.parseInt(p[0]), m=Integer.parseInt(p[1]);
        if(h<0 || h>23 || m<0 || m>59) throw new IllegalArgumentException("时间无效");
        return h*60+m;
    }
}
