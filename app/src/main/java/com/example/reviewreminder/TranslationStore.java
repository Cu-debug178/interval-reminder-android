package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

final class TranslationStore {
    private static final String KEY="translationExercises";
    private TranslationStore(){}
    static JSONArray all(Context c){try{return new JSONArray(Store.prefs(c).getString(KEY,"[]"));}catch(Exception e){return new JSONArray();}}
    static JSONObject create(JSONObject source,String selectionId,String direction,String difficulty,int gradingMode)throws Exception{return new JSONObject(source.toString()).put("id",UUID.randomUUID().toString()).put("selectionId",selectionId).put("direction",direction).put("difficulty",difficulty).put("gradingMode",gradingMode).put("createdAt",System.currentTimeMillis()).put("userAnswer","").put("selfRating","").put("note","").put("aiFeedback","");}
    static void add(Context c,JSONObject exercise)throws Exception{JSONArray all=all(c);all.put(exercise);while(all.length()>200)all.remove(0);save(c,all);}
    static void update(Context c,JSONObject exercise)throws Exception{JSONArray all=all(c);for(int i=0;i<all.length();i++){JSONObject old=all.optJSONObject(i);if(old!=null&&old.optString("id").equals(exercise.optString("id"))){exercise.put("updatedAt",System.currentTimeMillis());all.put(i,exercise);save(c,all);return;}}throw new IllegalStateException("题目已不存在");}
    static JSONObject copyForRetry(JSONObject old)throws Exception{JSONObject copy=new JSONObject(old.toString());copy.put("id",UUID.randomUUID().toString()).put("createdAt",System.currentTimeMillis()).put("userAnswer","").put("selfRating","").put("note","").put("aiFeedback","");copy.remove("updatedAt");return copy;}
    private static void save(Context c,JSONArray all){if(!Store.prefs(c).edit().putString(KEY,all.toString()).commit())throw new IllegalStateException("翻译练习未能保存");}
}
