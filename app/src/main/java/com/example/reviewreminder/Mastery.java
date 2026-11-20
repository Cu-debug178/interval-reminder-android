package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

final class Mastery {
    private Mastery() {}

    static int level(JSONObject word){return Math.max(0,Math.min(5,word==null?0:word.optInt("mastery",0)));}

    static void remember(JSONObject word)throws Exception{
        if(word==null)return;
        int streak=Math.max(0,word.optInt("masteryStreak",0))+1;
        int mastery=level(word);
        if(streak>=1)mastery=Math.min(5,mastery+1);
        word.put("mastery",mastery).put("masteryStreak",streak);
    }

    static void forget(JSONObject word)throws Exception{
        if(word==null)return;
        int mastery=Math.max(0,level(word)-1);
        word.put("mastery",mastery).put("masteryStreak",0);
    }

    /** Only the first attempt in a review pass may raise mastery; retries do not. */
    static void applyAnswer(Context context,long taskId,int wordIndex,boolean known,boolean firstAttempt,JSONArray sessionWords)throws Exception{
        applyAnswer(context,taskId,wordIndex,known,firstAttempt,sessionWords,null);
    }

    static void applyAnswer(Context context,long taskId,int wordIndex,boolean known,boolean firstAttempt,JSONArray sessionWords,String wordId)throws Exception{
        final JSONObject[] out=new JSONObject[1];
        LocalStore.inTransaction(context,()->{
            JSONObject task=LocalStore.readTaskLocked(LocalStore.database(),taskId);
            if(task==null)throw new IllegalStateException("复习任务已不存在");
            JSONArray words=task.optJSONArray("words");
            int index=Words.locate(words,wordIndex,wordId);
            if(index<0)throw new IllegalStateException("单词卡已不存在");
            JSONObject saved=new JSONObject(words.getJSONObject(index).toString());
            if(known){if(firstAttempt)remember(saved);}else forget(saved);
            LocalStore.writeCardRowLocked(LocalStore.database(),taskId,index,saved);
            out[0]=saved;
        });
        if(sessionWords!=null&&out[0]!=null){
            int sessionIndex=Words.locate(sessionWords,wordIndex,wordId!=null?wordId:Words.stableId(out[0]));
            if(sessionIndex>=0)sessionWords.put(sessionIndex,new JSONObject(out[0].toString()));
        }
    }

    static double average(JSONArray words){
        if(words==null||words.length()==0)return 0;
        int sum=0,count=0;
        for(int i=0;i<words.length();i++){JSONObject w=words.optJSONObject(i);if(w==null)continue;sum+=level(w);count++;}
        return count==0?0:(double)sum/count;
    }

    static String label(int mastery){return "掌握 "+Math.max(0,Math.min(5,mastery))+"/5";}
}
