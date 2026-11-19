package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Global wrong-word state keyed by stable word ID; per-word fields remain as compatibility mirrors. */
final class WrongBook {
    private static final String STATES="wrongWordStates",ATTEMPTS="spellingAttempts";
    private WrongBook(){}

    static JSONObject state(Context c,String wordId){
        try{JSONObject state=states(c).optJSONObject(wordId);return state==null?new JSONObject():new JSONObject(state.toString());}catch(Exception e){return new JSONObject();}
    }

    static JSONObject stateForWord(Context c,JSONObject word){
        String id=Words.stableId(word);JSONObject state=state(c,id);
        if(state.length()==0&&word!=null&&word.optInt("wrongCount",0)>0){
            put(state,"wrongCount",word.optInt("wrongCount"));put(state,"wrongAt",word.optLong("wrongAt"));put(state,"wrongRounds",word.optJSONArray("wrongRounds")==null?new JSONArray():word.optJSONArray("wrongRounds"));put(state,"bookmarked",true);put(state,"streak",0);put(state,"mastered",false);
        }
        return state;
    }

    static JSONObject record(Context c,long taskId,int wordIndex,int round,JSONArray sessionWords)throws Exception{return record(c,taskId,wordIndex,round,sessionWords,null);}
    static JSONObject record(Context c,long taskId,int wordIndex,int round,JSONArray sessionWords,String eventId)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)throw new IllegalStateException("复习任务已不存在");
        JSONArray words=task.optJSONArray("words");if(words==null||wordIndex<0||wordIndex>=words.length())throw new IllegalStateException("单词卡已不存在");
        JSONObject saved=new JSONObject(words.getJSONObject(wordIndex).toString());String id=Words.stableId(saved);JSONObject state=stateForWord(c,saved);
        if(duplicate(state,eventId)){mirror(state,saved);return saved;}
        state.put("wrongCount",state.optInt("wrongCount",0)+1).put("wrongAt",System.currentTimeMillis()).put("bookmarked",true).put("streak",0).put("mastered",false);
        JSONArray rounds=state.optJSONArray("wrongRounds");if(rounds==null)rounds=new JSONArray();boolean found=round<1;for(int i=0;i<rounds.length();i++)if(rounds.optInt(i)==round){found=true;break;}if(!found)rounds.put(round);state.put("wrongRounds",rounds);
        addEvent(state,eventId);saveState(c,id,state);addAttempt(c,id,taskId,wordIndex,round,"wrong",eventId);
        mirror(state,saved);words.put(wordIndex,saved);task.put("words",words);Store.save(c,tasks);syncSession(sessionWords,wordIndex,saved);return saved;
    }

    static JSONObject independent(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,true);}
    static JSONObject assisted(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,false);}
    static JSONObject reset(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,false);}

    private static JSONObject answer(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,boolean independent)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)throw new IllegalStateException("复习任务已不存在");JSONArray words=task.optJSONArray("words");if(words==null||wordIndex<0||wordIndex>=words.length())throw new IllegalStateException("单词卡已不存在");
        JSONObject saved=new JSONObject(words.getJSONObject(wordIndex).toString());String id=Words.stableId(saved);JSONObject state=stateForWord(c,saved);if(duplicate(state,eventId)){mirror(state,saved);return saved;}
        int streak=independent?Math.min(2,state.optInt("streak",0)+1):0;state.put("streak",streak).put("mastered",streak>=2);addEvent(state,eventId);saveState(c,id,state);addAttempt(c,id,taskId,wordIndex,-1,independent?"independent":"assisted",eventId);
        mirror(state,saved);words.put(wordIndex,saved);task.put("words",words);Store.save(c,tasks);syncSession(sessionWords,wordIndex,saved);return saved;
    }

    static void manualAdd(Context c,long taskId,int wordIndex)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)return;JSONArray words=task.optJSONArray("words");if(words==null||wordIndex<0||wordIndex>=words.length())return;JSONObject word=words.getJSONObject(wordIndex);String id=Words.stableId(word);JSONObject state=stateForWord(c,word);state.put("bookmarked",true).put("streak",0).put("mastered",false);saveState(c,id,state);mirror(state,word);Store.save(c,tasks);
    }

    static void clear(Context c,long taskId,int wordIndex)throws Exception{
        JSONArray tasks=Store.tasks(c);JSONObject task=Store.find(tasks,taskId);if(task==null)return;JSONArray words=task.optJSONArray("words");if(words==null||wordIndex<0||wordIndex>=words.length())return;JSONObject word=words.getJSONObject(wordIndex);String id=Words.stableId(word);JSONObject state=stateForWord(c,word);state.put("wrongCount",0).put("wrongRounds",new JSONArray()).put("wrongAt",0).put("bookmarked",false).put("streak",0).put("mastered",false);saveState(c,id,state);mirror(state,word);Store.save(c,tasks);
    }

    static String status(JSONObject state){int streak=state==null?0:state.optInt("streak",0);return streak>=2?"已掌握":streak==1?"学习中":"未掌握";}
    static boolean inBook(JSONObject state){return state!=null&&(state.optBoolean("bookmarked",false)||state.optInt("wrongCount",0)>0);}

    private static void mirror(JSONObject state,JSONObject word){
        try{word.put("wrongCount",state.optInt("wrongCount",0)).put("wrongAt",state.optLong("wrongAt",0)).put("wrongRounds",state.optJSONArray("wrongRounds")==null?new JSONArray():state.optJSONArray("wrongRounds")).put("spellingStreak",state.optInt("streak",0)).put("wrongMastered",state.optInt("streak",0)>=2).put("wordId",Words.stableId(word));}catch(Exception ignored){}
    }
    private static void syncSession(JSONArray words,int index,JSONObject saved){if(words!=null&&index>=0&&index<words.length())try{words.put(index,new JSONObject(saved.toString()));}catch(Exception ignored){}}
    private static JSONObject states(Context c){try{return new JSONObject(Store.prefs(c).getString(STATES,"{}"));}catch(Exception e){return new JSONObject();}}
    private static void saveState(Context c,String id,JSONObject state){JSONObject all=states(c);try{all.put(id,state);}catch(Exception ignored){}if(!Store.prefs(c).edit().putString(STATES,all.toString()).commit())throw new IllegalStateException("错词状态未能保存");}
    private static boolean duplicate(JSONObject state,String eventId){if(eventId==null||eventId.isEmpty())return false;JSONArray events=state.optJSONArray("eventIds");if(events==null)return false;for(int i=0;i<events.length();i++)if(eventId.equals(events.optString(i)))return true;return false;}
    private static void addEvent(JSONObject state,String eventId)throws Exception{if(eventId==null||eventId.isEmpty())return;JSONArray events=state.optJSONArray("eventIds");if(events==null)events=new JSONArray();events.put(eventId);while(events.length()>40)events.remove(0);state.put("eventIds",events);}
    private static void addAttempt(Context c,String wordId,long taskId,int wordIndex,int round,String type,String eventId)throws Exception{JSONArray all;try{all=new JSONArray(Store.prefs(c).getString(ATTEMPTS,"[]"));}catch(Exception e){all=new JSONArray();}all.put(new JSONObject().put("wordId",wordId).put("taskId",taskId).put("wordIndex",wordIndex).put("round",round).put("type",type).put("eventId",eventId).put("at",System.currentTimeMillis()));while(all.length()>500)all.remove(0);Store.prefs(c).edit().putString(ATTEMPTS,all.toString()).apply();}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception ignored){}}
}
