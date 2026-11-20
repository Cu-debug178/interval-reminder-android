package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sentence-writing AI mistakes persisted in SQLite {@code ai_mistake}. */
final class AiNotes {
    private AiNotes() {}

    static JSONArray all(Context c){
        try{
            return LocalStore.inRead(c,()->{
                JSONArray out=new JSONArray();
                Cursor cur=LocalStore.database().rawQuery("SELECT english,sentence,feedback,at FROM ai_mistake ORDER BY id ASC",null);
                try{
                    while(cur.moveToNext()){
                        out.put(new JSONObject().put("english",cur.getString(0)).put("sentence",cur.getString(1)).put("feedback",cur.getString(2)).put("at",cur.getLong(3)));
                    }
                }finally{cur.close();}
                return out;
            });
        }catch(Exception e){return new JSONArray();}
    }

    static void addMistake(Context c,String english,String sentence,String feedback)throws Exception{
        LocalStore.inTransaction(c,()->LocalStore.insertAiMistakeLocked(LocalStore.database(),english,sentence,feedback,System.currentTimeMillis()));
    }

    static void putWordNote(JSONObject word,String key,String value)throws Exception{
        if(word==null)return;
        word.put(key,value==null?"":value);
    }
}
