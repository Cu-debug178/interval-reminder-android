package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Short-lived training selections and resumable spelling state backed by normalized SQLite tables. */
final class TrainingStore {
    private TrainingStore(){}

    static String createSelection(Context c,JSONArray words)throws Exception{
        String id=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        LocalStore.inTransaction(c,()->LocalStore.upsertSelectionLocked(LocalStore.database(),id,now,words));
        return id;
    }

    /** Writes an edited card back into a saved selection so rotation reloads the new text. */
    static void updateWord(Context c,String selectionId,JSONObject word)throws Exception{
        if(selectionId==null||word==null)return;
        LocalStore.inTransaction(c,()->{
            JSONObject selection=readSelectionLocked(LocalStore.database(),selectionId);
            if(selection==null)return;
            JSONArray words=selection.optJSONArray("words");if(words==null)return;
            String id=Words.stableId(word);int index=Words.locate(words,word.optInt("_wordIndex",-1),id);
            if(index<0)return;
            JSONObject saved=words.getJSONObject(index);
            saved.put("english",word.optString("english")).put("chinese",word.optString("chinese")).put("example",word.optString("example")).put("category",word.optString("category","").trim()).put("wordId",id);
            LocalStore.upsertSelectionLocked(LocalStore.database(),selectionId,selection.optLong("created",System.currentTimeMillis()),words);
        });
    }

    static JSONObject selection(Context c,String id){
        if(id==null||id.isEmpty())return null;
        try{return LocalStore.inRead(c,()->readSelectionLocked(LocalStore.database(),id));}
        catch(Exception e){return null;}
    }

    static JSONObject allSelections(Context c){
        try{
            return LocalStore.inRead(c,()->{
                JSONObject out=new JSONObject();
                Cursor cur=LocalStore.database().rawQuery("SELECT id,created,words_json FROM training_selection ORDER BY created ASC",null);
                try{
                    while(cur.moveToNext()){
                        String id=cur.getString(0);
                        out.put(id,new JSONObject().put("id",id).put("created",cur.getLong(1)).put("words",new JSONArray(cur.getString(2))));
                    }
                }finally{cur.close();}
                return out;
            });
        }catch(Exception e){return new JSONObject();}
    }

    static JSONArray selectionOrder(Context c){
        try{
            return LocalStore.inRead(c,()->{
                JSONArray out=new JSONArray();
                Cursor cur=LocalStore.database().rawQuery("SELECT id FROM training_selection ORDER BY created ASC",null);
                try{while(cur.moveToNext())out.put(cur.getString(0));}finally{cur.close();}
                return out;
            });
        }catch(Exception e){return new JSONArray();}
    }

    static String forTask(Context c,long taskId,int[] indices)throws Exception{
        JSONObject task=Store.find(c,taskId);if(task==null)throw new IllegalStateException("复习任务已不存在");
        JSONArray source=task.optJSONArray("words"),out=new JSONArray();if(source==null)return createSelection(c,out);
        if(indices==null){indices=new int[source.length()];for(int i=0;i<indices.length;i++)indices[i]=i;}
        for(int index:indices){JSONObject word=source.optJSONObject(index);if(word==null)continue;JSONObject copy=new JSONObject(word.toString());copy.put("_taskId",taskId).put("_wordIndex",index).put("wordId",Words.stableId(word));out.put(copy);}
        return createSelection(c,out);
    }

    static void saveSpelling(Context c,String sessionId,String selectionId,boolean wrongMode,long taskId,int round,long historyAt,String attemptId,String snapshot,String hintWord,boolean hintUsed)throws Exception{
        JSONObject value=new JSONObject().put("sessionId",sessionId).put("selectionId",selectionId).put("wrongMode",wrongMode).put("taskId",taskId).put("round",round).put("historyAt",historyAt).put("attemptId",attemptId).put("snapshot",snapshot).put("hintWord",hintWord).put("hintUsed",hintUsed).put("updatedAt",System.currentTimeMillis());
        LocalStore.inTransaction(c,()->LocalStore.upsertSpellingSessionLocked(LocalStore.database(),sessionId,value));
    }

    static JSONObject spelling(Context c,String sessionId){
        if(sessionId==null||sessionId.isEmpty())return null;
        try{return LocalStore.inRead(c,()->readSpellingLocked(LocalStore.database(),sessionId));}
        catch(Exception e){return null;}
    }

    /** Most recent unfinished spelling session so the user can resume after leaving or restarting. */
    static JSONObject latestSpelling(Context c){
        try{
            return LocalStore.inRead(c,()->{
                Cursor cur=LocalStore.database().rawQuery("SELECT session_id,selection_id,wrong_mode,task_id,round,history_at,attempt_id,snapshot,hint_word,hint_used,updated_at FROM spelling_session ORDER BY updated_at DESC LIMIT 1",null);
                try{return cur.moveToFirst()?spellingFromCursor(cur):null;}finally{cur.close();}
            });
        }catch(Exception e){return null;}
    }

    static JSONObject allSpelling(Context c){
        try{
            return LocalStore.inRead(c,()->{
                JSONObject out=new JSONObject();
                Cursor cur=LocalStore.database().rawQuery("SELECT session_id,selection_id,wrong_mode,task_id,round,history_at,attempt_id,snapshot,hint_word,hint_used,updated_at FROM spelling_session ORDER BY updated_at ASC",null);
                try{while(cur.moveToNext())out.put(cur.getString(0),spellingFromCursor(cur));}finally{cur.close();}
                return out;
            });
        }catch(Exception e){return new JSONObject();}
    }

    static void clearSpelling(Context c,String sessionId){
        if(sessionId==null||sessionId.isEmpty())return;
        try{LocalStore.inTransaction(c,()->LocalStore.database().delete("spelling_session","session_id=?",new String[]{sessionId}));}
        catch(Exception ignored){}
    }

    private static JSONObject readSelectionLocked(SQLiteDatabase db,String id)throws Exception{
        Cursor cur=db.rawQuery("SELECT id,created,words_json FROM training_selection WHERE id=?",new String[]{id});
        try{
            if(!cur.moveToFirst())return null;
            return new JSONObject().put("id",cur.getString(0)).put("created",cur.getLong(1)).put("words",new JSONArray(cur.getString(2)));
        }finally{cur.close();}
    }

    private static JSONObject readSpellingLocked(SQLiteDatabase db,String id)throws Exception{
        Cursor cur=db.rawQuery("SELECT session_id,selection_id,wrong_mode,task_id,round,history_at,attempt_id,snapshot,hint_word,hint_used,updated_at FROM spelling_session WHERE session_id=?",new String[]{id});
        try{return cur.moveToFirst()?spellingFromCursor(cur):null;}finally{cur.close();}
    }

    private static JSONObject spellingFromCursor(Cursor c)throws Exception{
        return new JSONObject()
            .put("sessionId",c.getString(0)).put("selectionId",c.getString(1)).put("wrongMode",c.getInt(2)!=0)
            .put("taskId",c.getLong(3)).put("round",c.getInt(4)).put("historyAt",c.getLong(5))
            .put("attemptId",c.getString(6)).put("snapshot",c.getString(7)).put("hintWord",c.getString(8))
            .put("hintUsed",c.getInt(9)!=0).put("updatedAt",c.getLong(10));
    }
}
