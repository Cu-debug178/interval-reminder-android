package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class Store {
    private static final String PREF = "review_data";
    public static final String DEFAULT_INTERVALS = "5,30,720,1440,2880,5760,10080,20160";
    private Store() {}
    public static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, 0); }
    public static JSONArray tasks(Context c) {
        try { return new JSONArray(prefs(c).getString("tasks", "[]")); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void save(Context c, JSONArray tasks) { if(!prefs(c).edit().putString("tasks", tasks.toString()).commit())throw new IllegalStateException("数据未能写入设备，请检查存储空间"); }
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
