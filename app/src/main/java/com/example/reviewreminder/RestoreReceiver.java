package com.example.reviewreminder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
public final class RestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { restore(c); }
    static void restore(Context c) {
        JSONArray all=Store.tasks(c);
        for(int n=0;n<all.length();n++) { JSONObject t=all.optJSONObject(n); if(t!=null && !t.optBoolean("done")) Alarms.schedule(c,t); }
    }
}
