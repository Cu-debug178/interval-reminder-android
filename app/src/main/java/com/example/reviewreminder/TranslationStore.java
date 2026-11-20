package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Translation exercises persisted in the normalized SQLite {@code translation_exercise} table. */
final class TranslationStore {
    private TranslationStore(){}

    static JSONArray all(Context c){
        try{
            return LocalStore.inRead(c,()->{
                JSONArray out=new JSONArray();
                Cursor cur=LocalStore.database().rawQuery("SELECT id,selection_id,direction,difficulty,grading_mode,prompt,reference,explanation,targets_json,user_answer,self_rating,note,ai_feedback,reference_viewed,created_at,updated_at FROM translation_exercise ORDER BY created_at ASC",null);
                try{
                    while(cur.moveToNext()){
                        JSONObject row=new JSONObject()
                            .put("id",cur.getString(0)).put("selectionId",cur.getString(1)).put("direction",cur.getString(2))
                            .put("difficulty",cur.getString(3)).put("gradingMode",cur.getInt(4)).put("prompt",cur.getString(5))
                            .put("reference",cur.getString(6)).put("explanation",cur.getString(7))
                            .put("targets",new JSONArray(cur.getString(8))).put("userAnswer",cur.getString(9))
                            .put("selfRating",cur.getString(10)).put("note",cur.getString(11)).put("aiFeedback",cur.getString(12))
                            .put("referenceViewed",cur.getInt(13)!=0).put("createdAt",cur.getLong(14));
                        if(cur.getLong(15)>0)row.put("updatedAt",cur.getLong(15));
                        out.put(row);
                    }
                }finally{cur.close();}
                return out;
            });
        }catch(Exception e){return new JSONArray();}
    }

    static JSONObject create(JSONObject source,String selectionId,String direction,String difficulty,int gradingMode)throws Exception{
        return new JSONObject(source.toString()).put("id",UUID.randomUUID().toString()).put("selectionId",selectionId).put("direction",direction).put("difficulty",difficulty).put("gradingMode",gradingMode).put("createdAt",System.currentTimeMillis()).put("userAnswer","").put("selfRating","").put("note","").put("aiFeedback","");
    }

    static void add(Context c,JSONObject exercise)throws Exception{
        LocalStore.inTransaction(c,()->LocalStore.upsertTranslationLocked(LocalStore.database(),exercise));
    }

    static void update(Context c,JSONObject exercise)throws Exception{
        exercise.put("updatedAt",System.currentTimeMillis());
        LocalStore.inTransaction(c,()->LocalStore.upsertTranslationLocked(LocalStore.database(),exercise));
    }

    static JSONObject copyForRetry(JSONObject old)throws Exception{
        JSONObject copy=new JSONObject(old.toString());
        copy.put("id",UUID.randomUUID().toString()).put("createdAt",System.currentTimeMillis()).put("userAnswer","").put("selfRating","").put("note","").put("aiFeedback","").put("referenceViewed",false);
        copy.remove("updatedAt");
        return copy;
    }
}
