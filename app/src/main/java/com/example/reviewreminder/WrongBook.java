package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

final class WrongBook {
    private WrongBook() {}

    static JSONObject record(Context context,long taskId,int wordIndex,int round,JSONArray sessionWords)throws Exception{
        return record(context,taskId,wordIndex,round,sessionWords,null);
    }

    static JSONObject record(Context context,long taskId,int wordIndex,int round,JSONArray sessionWords,String eventId)throws Exception{
        JSONArray tasks=Store.tasks(context);JSONObject task=Store.find(tasks,taskId);
        if(task==null)throw new IllegalStateException("复习任务已不存在");
        JSONArray words=task.optJSONArray("words");if(words==null||wordIndex<0||wordIndex>=words.length())throw new IllegalStateException("单词卡已不存在");
        JSONObject saved=new JSONObject(words.getJSONObject(wordIndex).toString());
        JSONArray events=saved.optJSONArray("wrongEventIds");if(events==null)events=new JSONArray();
        if(eventId!=null&&!eventId.isEmpty())for(int i=0;i<events.length();i++)if(eventId.equals(events.optString(i))){if(sessionWords!=null&&wordIndex<sessionWords.length())sessionWords.put(wordIndex,new JSONObject(saved.toString()));return saved;}
        saved.put("wrongCount",saved.optInt("wrongCount",0)+1).put("wrongAt",System.currentTimeMillis());
        if(eventId!=null&&!eventId.isEmpty()){events.put(eventId);while(events.length()>24)events.remove(0);saved.put("wrongEventIds",events);}
        JSONArray rounds=saved.optJSONArray("wrongRounds");if(rounds==null)rounds=new JSONArray();boolean found=round<1;
        for(int i=0;i<rounds.length();i++)if(rounds.optInt(i)==round){found=true;break;}
        if(!found)rounds.put(round);saved.put("wrongRounds",rounds);words.put(wordIndex,saved);task.put("words",words);Store.save(context,tasks);
        if(sessionWords!=null&&wordIndex<sessionWords.length())sessionWords.put(wordIndex,new JSONObject(saved.toString()));
        return saved;
    }

    static void clear(Context context,long taskId,int wordIndex)throws Exception{
        JSONArray tasks=Store.tasks(context);JSONObject task=Store.find(tasks,taskId);if(task==null)return;
        JSONArray words=task.optJSONArray("words");if(words==null||wordIndex<0||wordIndex>=words.length())return;
        JSONObject word=words.getJSONObject(wordIndex);word.put("wrongCount",0).put("wrongRounds",new JSONArray()).put("wrongEventIds",new JSONArray()).put("wrongAt",0);Store.save(context,tasks);
    }
}
