package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Short-lived immutable training selections and resumable spelling state. */
final class TrainingStore {
    private static final String SELECTIONS="trainingSelections",ORDER="trainingSelectionOrder",ACTIVE="spellingActive";
    private TrainingStore(){}

    static String createSelection(Context c,JSONArray words)throws Exception{
        String id=UUID.randomUUID().toString();
        JSONObject selection=new JSONObject().put("id",id).put("created",System.currentTimeMillis()).put("words",words);
        JSONObject all=selections(c);all.put(id,selection);
        JSONArray order=order(c);order.put(id);while(order.length()>12){String old=order.optString(0);order.remove(0);all.remove(old);}
        if(!Store.prefs(c).edit().putString(SELECTIONS,all.toString()).putString(ORDER,order.toString()).commit())throw new IllegalStateException("训练选择未能保存");
        return id;
    }

    /** Writes an edited card back into a saved selection so rotation reloads the new text. */
    static void updateWord(Context c,String selectionId,JSONObject word)throws Exception{
        if(selectionId==null||word==null)return;
        JSONObject all=selections(c);JSONObject selection=all.optJSONObject(selectionId);if(selection==null)return;
        JSONArray words=selection.optJSONArray("words");if(words==null)return;
        String id=Words.stableId(word);int index=Words.locate(words,word.optInt("_wordIndex",-1),id);
        if(index<0)return;
        JSONObject saved=words.getJSONObject(index);
        saved.put("english",word.optString("english")).put("chinese",word.optString("chinese")).put("example",word.optString("example")).put("wordId",id);
        if(!Store.prefs(c).edit().putString(SELECTIONS,all.toString()).commit())throw new IllegalStateException("训练选词未能保存");
    }

    static JSONObject selection(Context c,String id){
        if(id==null)return null;
        return selections(c).optJSONObject(id);
    }

    static String forTask(Context c,long taskId,int[] indices)throws Exception{
        JSONObject task=Store.find(Store.tasks(c),taskId);if(task==null)throw new IllegalStateException("复习任务已不存在");
        JSONArray source=task.optJSONArray("words"),out=new JSONArray();if(source==null)return createSelection(c,out);
        if(indices==null){indices=new int[source.length()];for(int i=0;i<indices.length;i++)indices[i]=i;}
        for(int index:indices){JSONObject word=source.optJSONObject(index);if(word==null)continue;JSONObject copy=new JSONObject(word.toString());copy.put("_taskId",taskId).put("_wordIndex",index).put("wordId",Words.stableId(word));out.put(copy);}
        return createSelection(c,out);
    }

    static void saveSpelling(Context c,String sessionId,String selectionId,boolean wrongMode,long taskId,int round,long historyAt,String attemptId,String snapshot,String hintWord,boolean hintUsed)throws Exception{
        JSONObject value=new JSONObject().put("sessionId",sessionId).put("selectionId",selectionId).put("wrongMode",wrongMode).put("taskId",taskId).put("round",round).put("historyAt",historyAt).put("attemptId",attemptId).put("snapshot",snapshot).put("hintWord",hintWord).put("hintUsed",hintUsed).put("updatedAt",System.currentTimeMillis());
        if(!Store.prefs(c).edit().putString(ACTIVE+":"+sessionId,value.toString()).commit())throw new IllegalStateException("拼写进度未能保存");
    }

    static JSONObject spelling(Context c,String sessionId){
        if(sessionId==null)return null;
        try{return new JSONObject(Store.prefs(c).getString(ACTIVE+":"+sessionId,""));}catch(Exception e){return null;}
    }

    static void clearSpelling(Context c,String sessionId){if(sessionId!=null)Store.prefs(c).edit().remove(ACTIVE+":"+sessionId).apply();}

    private static JSONObject selections(Context c){try{return new JSONObject(Store.prefs(c).getString(SELECTIONS,"{}"));}catch(Exception e){return new JSONObject();}}
    private static JSONArray order(Context c){try{return new JSONArray(Store.prefs(c).getString(ORDER,"[]"));}catch(Exception e){return new JSONArray();}}
}
