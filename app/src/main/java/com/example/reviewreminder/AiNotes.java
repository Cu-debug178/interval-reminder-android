package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

final class AiNotes {
    private AiNotes() {}

    static JSONArray all(Context c){
        try{return new JSONArray(Store.prefs(c).getString("aiMistakes","[]"));}catch(Exception e){return new JSONArray();}
    }

    static void addMistake(Context c,String english,String sentence,String feedback)throws Exception{
        JSONArray all=all(c);
        all.put(new JSONObject().put("english",english).put("sentence",sentence).put("feedback",feedback).put("at",System.currentTimeMillis()));
        while(all.length()>80)all.remove(0);
        if(!Store.prefs(c).edit().putString("aiMistakes",all.toString()).commit())throw new IllegalStateException("造句记录未能保存");
    }

    static void putWordNote(JSONObject word,String key,String value)throws Exception{
        if(word==null)return;
        word.put(key,value==null?"":value);
    }
}
