package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Global wrong-word state keyed by stable word ID; per-word fields remain as compatibility mirrors. */
final class WrongBook {
    private static final String STATES="wrongWordStates",ATTEMPTS="spellingAttempts";
    static final String LEGACY_MIGRATED="wrongLegacyMerged";
    private WrongBook(){}

    static JSONObject state(Context c,String wordId){
        try{JSONObject state=states(c).optJSONObject(wordId);return state==null?new JSONObject():new JSONObject(state.toString());}catch(Exception e){return new JSONObject();}
    }

    static JSONObject stateForWord(Context c,JSONObject word){
        if(word==null)return new JSONObject();
        try{ensureLegacyMerged(c);}catch(Exception ignored){}
        String id=Words.stableId(word);JSONObject state=state(c,id);
        if(state.length()==0&&word.optInt("wrongCount",0)>0){
            int streak=Math.max(word.optInt("spellingStreak",0),word.optBoolean("wrongMastered",false)?2:0);
            put(state,"wrongCount",word.optInt("wrongCount"));put(state,"wrongAt",word.optLong("wrongAt"));put(state,"wrongRounds",word.optJSONArray("wrongRounds")==null?new JSONArray():word.optJSONArray("wrongRounds"));put(state,"bookmarked",true);put(state,"streak",streak);put(state,"mastered",streak>=2);
        }
        return state;
    }

    static JSONObject record(Context c,long taskId,int wordIndex,int round,JSONArray sessionWords)throws Exception{return record(c,taskId,wordIndex,round,sessionWords,null,null);}
    static JSONObject record(Context c,long taskId,int wordIndex,int round,JSONArray sessionWords,String eventId)throws Exception{return record(c,taskId,wordIndex,round,sessionWords,eventId,null);}
    static JSONObject record(Context c,long taskId,int wordIndex,int round,JSONArray sessionWords,String eventId,String wordId)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)throw new IllegalStateException("复习任务已不存在");
        JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,wordId);if(index<0)throw new IllegalStateException("单词卡已不存在");
        JSONObject saved=new JSONObject(words.getJSONObject(index).toString());String id=Words.stableId(saved);JSONObject state=stateForWord(c,saved);
        if(duplicate(state,eventId)){mirror(state,saved);return saved;}
        state.put("wrongCount",state.optInt("wrongCount",0)+1).put("wrongAt",System.currentTimeMillis()).put("bookmarked",true).put("streak",0).put("mastered",false);
        JSONArray rounds=state.optJSONArray("wrongRounds");if(rounds==null)rounds=new JSONArray();boolean found=round<1;for(int i=0;i<rounds.length();i++)if(rounds.optInt(i)==round){found=true;break;}if(!found)rounds.put(round);state.put("wrongRounds",rounds);
        addEvent(state,eventId);saveState(c,id,state);addAttempt(c,id,taskId,index,round,"wrong",eventId);
        mirror(state,saved);mirrorAll(c,id,state);syncSession(sessionWords,wordIndex,saved,id);return saved;
    }

    static JSONObject independent(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return independent(c,taskId,wordIndex,sessionWords,eventId,null);}
    static JSONObject independent(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,String wordId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,true,wordId);}
    static JSONObject assisted(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return assisted(c,taskId,wordIndex,sessionWords,eventId,null);}
    static JSONObject assisted(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,String wordId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,false,wordId);}
    static JSONObject reset(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return reset(c,taskId,wordIndex,sessionWords,eventId,null);}
    static JSONObject reset(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,String wordId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,false,wordId);}

    private static JSONObject answer(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,boolean independent,String wordId)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)throw new IllegalStateException("复习任务已不存在");JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,wordId);if(index<0)throw new IllegalStateException("单词卡已不存在");
        JSONObject saved=new JSONObject(words.getJSONObject(index).toString());String id=Words.stableId(saved);JSONObject state=stateForWord(c,saved);if(duplicate(state,eventId)){mirror(state,saved);return saved;}
        int streak=independent?Math.min(2,state.optInt("streak",0)+1):0;state.put("streak",streak).put("mastered",streak>=2);addEvent(state,eventId);saveState(c,id,state);addAttempt(c,id,taskId,index,-1,independent?"independent":"assisted",eventId);
        mirror(state,saved);mirrorAll(c,id,state);syncSession(sessionWords,wordIndex,saved,id);return saved;
    }

    /** Bookmark a word without changing its independent-correct streak. */
    static boolean manualAdd(Context c,long taskId,int wordIndex)throws Exception{return manualAdd(c,taskId,wordIndex,null);}
    static boolean manualAdd(Context c,long taskId,int wordIndex,String wordId)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)return false;JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,wordId);if(index<0)return false;JSONObject word=words.getJSONObject(index);String id=Words.stableId(word);JSONObject state=stateForWord(c,word);state.put("bookmarked",true);if(!state.has("streak"))state.put("streak",0);state.put("mastered",state.optInt("streak",0)>=2);saveState(c,id,state);mirrorAll(c,id,state);return true;
    }

    /** Put a wrong-book word back at zero consecutive independent corrects. */
    static void unmaster(Context c,long taskId,int wordIndex)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)return;JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,null);if(index<0)return;JSONObject word=words.getJSONObject(index);String id=Words.stableId(word);JSONObject state=stateForWord(c,word);state.put("bookmarked",true).put("streak",0).put("mastered",false);saveState(c,id,state);mirrorAll(c,id,state);
    }

    static void clear(Context c,long taskId,int wordIndex)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)return;JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,null);if(index<0)return;JSONObject word=words.getJSONObject(index);String id=Words.stableId(word);JSONObject state=stateForWord(c,word);state.put("wrongCount",0).put("wrongRounds",new JSONArray()).put("wrongAt",0).put("bookmarked",false).put("streak",0).put("mastered",false);saveState(c,id,state);mirrorAll(c,id,state);
    }

    static String status(JSONObject state){int streak=state==null?0:state.optInt("streak",0);return streak>=2?"已掌握":streak==1?"学习中":"未掌握";}
    static boolean inBook(JSONObject state){return state!=null&&(state.optBoolean("bookmarked",false)||state.optInt("wrongCount",0)>0);}

    private static void ensureLegacyMerged(Context c)throws Exception{
        android.content.SharedPreferences prefs=Store.prefs(c);if(prefs.getBoolean(LEGACY_MIGRATED,false))return;
        JSONObject all=states(c);JSONArray tasks=Store.tasks(c);Map<String,JSONObject> legacy=new LinkedHashMap<>();
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;
            for(int j=0;j<words.length();j++){
                JSONObject word=words.optJSONObject(j);if(word==null)continue;
                int count=word.optInt("wrongCount",0);JSONArray rounds=word.optJSONArray("wrongRounds");int streak=word.optInt("spellingStreak",0);if(word.optBoolean("wrongMastered",false))streak=Math.max(streak,2);
                if(count<=0&&(rounds==null||rounds.length()==0)&&streak<=0)continue;
                String id=Words.stableId(word);JSONObject acc=legacy.get(id);if(acc==null){acc=new JSONObject();legacy.put(id,acc);}
                acc.put("wrongCount",Math.max(acc.optInt("wrongCount"),count)).put("wrongAt",Math.max(acc.optLong("wrongAt"),word.optLong("wrongAt"))).put("wrongRounds",unionRounds(acc.optJSONArray("wrongRounds"),rounds)).put("streak",Math.max(acc.optInt("streak"),streak)).put("bookmarked",true);
            }
        }
        for(Map.Entry<String,JSONObject> entry:legacy.entrySet()){
            JSONObject fromWord=entry.getValue();JSONObject existing=all.optJSONObject(entry.getKey());
            if(existing==null){fromWord.put("mastered",fromWord.optInt("streak")>=2);all.put(entry.getKey(),fromWord);}
            else if(!existing.has("wrongCount")){
                int wrongCount=Math.max(existing.optInt("wrongCount"),fromWord.optInt("wrongCount"));int streak=Math.max(existing.optInt("streak"),fromWord.optInt("streak"));
                existing.put("wrongCount",wrongCount).put("wrongAt",Math.max(existing.optLong("wrongAt"),fromWord.optLong("wrongAt"))).put("wrongRounds",unionRounds(existing.optJSONArray("wrongRounds"),fromWord.optJSONArray("wrongRounds"))).put("bookmarked",existing.optBoolean("bookmarked")||wrongCount>0).put("streak",streak).put("mastered",streak>=2);
            }
        }
        boolean mirrored=false;
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;
            for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null)continue;JSONObject state=all.optJSONObject(Words.stableId(word));if(state==null)continue;mirror(state,word);mirrored=true;}
        }
        android.content.SharedPreferences.Editor edit=prefs.edit().putString(STATES,all.toString()).putBoolean(LEGACY_MIGRATED,true);if(mirrored)edit.putString("tasks",tasks.toString());
        if(!edit.commit())throw new IllegalStateException("错词状态未能保存");
    }

    private static void mirror(JSONObject state,JSONObject word){
        try{word.put("wrongCount",state.optInt("wrongCount",0)).put("wrongAt",state.optLong("wrongAt",0)).put("wrongRounds",state.optJSONArray("wrongRounds")==null?new JSONArray():state.optJSONArray("wrongRounds")).put("spellingStreak",state.optInt("streak",0)).put("wrongMastered",state.optInt("streak",0)>=2).put("wordId",Words.stableId(word));}catch(Exception ignored){}
    }
    private static void mirrorAll(Context c,String id,JSONObject state)throws Exception{
        JSONArray tasks=Store.tasks(c);boolean changed=false;
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null||!id.equals(Words.stableId(word)))continue;mirror(state,word);changed=true;}}
        if(changed)Store.save(c,tasks);
    }
    private static void syncSession(JSONArray words,int hint,JSONObject saved,String id){if(words==null)return;int index=Words.locate(words,hint,id);if(index<0)return;try{words.put(index,new JSONObject(saved.toString()));}catch(Exception ignored){}}
    private static JSONObject states(Context c){try{return new JSONObject(Store.prefs(c).getString(STATES,"{}"));}catch(Exception e){return new JSONObject();}}
    private static void saveState(Context c,String id,JSONObject state){JSONObject all=states(c);try{all.put(id,state);}catch(Exception ignored){}if(!Store.prefs(c).edit().putString(STATES,all.toString()).commit())throw new IllegalStateException("错词状态未能保存");}
    private static boolean duplicate(JSONObject state,String eventId){if(eventId==null||eventId.isEmpty())return false;JSONArray events=state.optJSONArray("eventIds");if(events==null)return false;for(int i=0;i<events.length();i++)if(eventId.equals(events.optString(i)))return true;return false;}
    private static void addEvent(JSONObject state,String eventId)throws Exception{if(eventId==null||eventId.isEmpty())return;JSONArray events=state.optJSONArray("eventIds");if(events==null)events=new JSONArray();events.put(eventId);while(events.length()>40)events.remove(0);state.put("eventIds",events);}
    private static void addAttempt(Context c,String wordId,long taskId,int wordIndex,int round,String type,String eventId)throws Exception{JSONArray all;try{all=new JSONArray(Store.prefs(c).getString(ATTEMPTS,"[]"));}catch(Exception e){all=new JSONArray();}all.put(new JSONObject().put("wordId",wordId).put("taskId",taskId).put("wordIndex",wordIndex).put("round",round).put("type",type).put("eventId",eventId).put("at",System.currentTimeMillis()));while(all.length()>500)all.remove(0);Store.prefs(c).edit().putString(ATTEMPTS,all.toString()).apply();}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception ignored){}}
    private static JSONArray unionRounds(JSONArray a,JSONArray b){JSONArray out=new JSONArray();java.util.LinkedHashSet<Integer> seen=new java.util.LinkedHashSet<>();JSONArray[] lists=new JSONArray[]{a,b};for(JSONArray list:lists)if(list!=null)for(int i=0;i<list.length();i++){int n=list.optInt(i,-1);if(n>0&&seen.add(n))out.put(n);}return out;}
}
