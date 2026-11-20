package com.example.reviewreminder;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Global wrong-word state stored directly in SQLite {@code wrong_state} & {@code spelling_attempt} tables. */
final class WrongBook {
    static final String LEGACY_MIGRATED="wrongLegacyMerged";
    private WrongBook(){}

    static JSONObject state(Context c,String wordId){
        if(wordId==null||wordId.isEmpty())return new JSONObject();
        try{return LocalStore.inRead(c,()->LocalStore.readWrongStateLocked(LocalStore.database(),wordId));}
        catch(Exception e){return new JSONObject();}
    }

    static JSONObject allStates(Context c){
        try{return LocalStore.inRead(c,()->LocalStore.readAllWrongStatesLocked(LocalStore.database()));}
        catch(Exception e){return new JSONObject();}
    }

    static JSONArray attempts(Context c,String wordIdOrNull){
        try{return LocalStore.inRead(c,()->LocalStore.readAttemptsLocked(LocalStore.database(),wordIdOrNull));}
        catch(Exception e){return new JSONArray();}
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
        final JSONObject[] out=new JSONObject[1];
        LocalStore.inTransaction(c,()->{
            JSONObject task=LocalStore.readTaskLocked(LocalStore.database(),taskId);
            if(task==null)throw new IllegalStateException("复习任务已不存在");
            JSONArray words=task.optJSONArray("words");
            int index=Words.locate(words,wordIndex,wordId);
            if(index<0)throw new IllegalStateException("单词卡已不存在");
            JSONObject saved=new JSONObject(words.getJSONObject(index).toString());
            String id=Words.stableId(saved);
            JSONObject state=stateForWord(c,saved);
            if(duplicate(state,eventId)){mirror(state,saved);out[0]=saved;return;}
            state.put("wrongCount",state.optInt("wrongCount",0)+1).put("wrongAt",System.currentTimeMillis()).put("bookmarked",true).put("streak",0).put("mastered",false);
            JSONArray rounds=state.optJSONArray("wrongRounds");if(rounds==null)rounds=new JSONArray();
            boolean found=round<1;for(int i=0;i<rounds.length();i++)if(rounds.optInt(i)==round){found=true;break;}
            if(!found)rounds.put(round);state.put("wrongRounds",rounds);
            addEvent(state,eventId);
            LocalStore.upsertWrongStateLocked(LocalStore.database(),id,state);
            LocalStore.insertAttemptLocked(LocalStore.database(),new JSONObject().put("wordId",id).put("taskId",taskId).put("wordIndex",index).put("round",round).put("type","wrong").put("eventId",eventId==null?"":eventId).put("at",System.currentTimeMillis()));
            mirror(state,saved);
            mirrorCardsSqlLocked(LocalStore.database(),id,state);
            out[0]=saved;
        });
        syncSession(sessionWords,wordIndex,out[0],Words.stableId(out[0]));
        return out[0];
    }

    static JSONObject independent(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return independent(c,taskId,wordIndex,sessionWords,eventId,null);}
    static JSONObject independent(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,String wordId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,true,wordId);}
    static JSONObject assisted(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return assisted(c,taskId,wordIndex,sessionWords,eventId,null);}
    static JSONObject assisted(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,String wordId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,false,wordId);}
    static JSONObject reset(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId)throws Exception{return reset(c,taskId,wordIndex,sessionWords,eventId,null);}
    static JSONObject reset(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,String wordId)throws Exception{return answer(c,taskId,wordIndex,sessionWords,eventId,false,wordId);}

    private static JSONObject answer(Context c,long taskId,int wordIndex,JSONArray sessionWords,String eventId,boolean independent,String wordId)throws Exception{
        final JSONObject[] out=new JSONObject[1];
        LocalStore.inTransaction(c,()->{
            JSONObject task=LocalStore.readTaskLocked(LocalStore.database(),taskId);
            if(task==null)throw new IllegalStateException("复习任务已不存在");
            JSONArray words=task.optJSONArray("words");
            int index=Words.locate(words,wordIndex,wordId);
            if(index<0)throw new IllegalStateException("单词卡已不存在");
            JSONObject saved=new JSONObject(words.getJSONObject(index).toString());
            String id=Words.stableId(saved);
            JSONObject state=stateForWord(c,saved);
            if(duplicate(state,eventId)){mirror(state,saved);out[0]=saved;return;}
            int streak=independent?Math.min(2,state.optInt("streak",0)+1):0;
            state.put("streak",streak).put("mastered",streak>=2);
            addEvent(state,eventId);
            LocalStore.upsertWrongStateLocked(LocalStore.database(),id,state);
            LocalStore.insertAttemptLocked(LocalStore.database(),new JSONObject().put("wordId",id).put("taskId",taskId).put("wordIndex",index).put("round",-1).put("type",independent?"independent":"assisted").put("eventId",eventId==null?"":eventId).put("at",System.currentTimeMillis()));
            mirror(state,saved);
            mirrorCardsSqlLocked(LocalStore.database(),id,state);
            out[0]=saved;
        });
        syncSession(sessionWords,wordIndex,out[0],Words.stableId(out[0]));
        return out[0];
    }

    /** Bookmark a word without changing its independent-correct streak. */
    static boolean manualAdd(Context c,long taskId,int wordIndex)throws Exception{return manualAdd(c,taskId,wordIndex,null);}
    static boolean manualAdd(Context c,long taskId,int wordIndex,String wordId)throws Exception{
        final boolean[] added={false};
        LocalStore.inTransaction(c,()->{
            JSONObject task=LocalStore.readTaskLocked(LocalStore.database(),taskId);if(task==null)return;
            JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,wordId);if(index<0)return;
            JSONObject word=words.getJSONObject(index);String id=Words.stableId(word);
            JSONObject state=stateForWord(c,word);
            state.put("bookmarked",true);if(!state.has("streak"))state.put("streak",0);state.put("mastered",state.optInt("streak",0)>=2);
            LocalStore.upsertWrongStateLocked(LocalStore.database(),id,state);
            mirrorCardsSqlLocked(LocalStore.database(),id,state);
            added[0]=true;
        });
        return added[0];
    }

    /** Put a wrong-book word back at zero consecutive independent corrects. */
    static void unmaster(Context c,long taskId,int wordIndex)throws Exception{
        LocalStore.inTransaction(c,()->{
            JSONObject task=LocalStore.readTaskLocked(LocalStore.database(),taskId);if(task==null)return;
            JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,null);if(index<0)return;
            JSONObject word=words.getJSONObject(index);String id=Words.stableId(word);
            JSONObject state=stateForWord(c,word);
            state.put("bookmarked",true).put("streak",0).put("mastered",false);
            LocalStore.upsertWrongStateLocked(LocalStore.database(),id,state);
            mirrorCardsSqlLocked(LocalStore.database(),id,state);
        });
    }

    static void clear(Context c,long taskId,int wordIndex)throws Exception{
        LocalStore.inTransaction(c,()->{
            JSONObject task=LocalStore.readTaskLocked(LocalStore.database(),taskId);if(task==null)return;
            JSONArray words=task.optJSONArray("words");int index=Words.locate(words,wordIndex,null);if(index<0)return;
            JSONObject word=words.getJSONObject(index);String id=Words.stableId(word);
            JSONObject state=stateForWord(c,word);
            state.put("wrongCount",0).put("wrongRounds",new JSONArray()).put("wrongAt",0).put("bookmarked",false).put("streak",0).put("mastered",false);
            LocalStore.upsertWrongStateLocked(LocalStore.database(),id,state);
            mirrorCardsSqlLocked(LocalStore.database(),id,state);
        });
    }

    static String status(JSONObject state){int streak=state==null?0:state.optInt("streak",0);return streak>=2?"已掌握":streak==1?"学习中":"未掌握";}
    static boolean inBook(JSONObject state){return state!=null&&(state.optBoolean("bookmarked",false)||state.optInt("wrongCount",0)>0);}

    private static void ensureLegacyMerged(Context c)throws Exception{
        android.content.SharedPreferences prefs=Store.prefs(c);if(prefs.getBoolean(LEGACY_MIGRATED,false))return;
        LocalStore.inTransaction(c,()->{
            if(prefs.getBoolean(LEGACY_MIGRATED,false))return;
            SQLiteDatabase db=LocalStore.database();
            JSONObject all=LocalStore.readAllWrongStatesLocked(db);
            JSONArray tasks=LocalStore.readAllTasksLocked(db);
            Map<String,JSONObject> legacy=new LinkedHashMap<>();
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
                if(existing==null){fromWord.put("mastered",fromWord.optInt("streak")>=2);all.put(entry.getKey(),fromWord);continue;}
                boolean cleared=existing.has("wrongCount")&&existing.optInt("wrongCount")==0&&!existing.optBoolean("bookmarked")&&existing.optInt("streak")==0&&existing.optLong("wrongAt")==0;
                if(cleared)continue;
                int wrongCount=Math.max(existing.optInt("wrongCount"),fromWord.optInt("wrongCount"));
                existing.put("wrongCount",wrongCount).put("wrongAt",Math.max(existing.optLong("wrongAt"),fromWord.optLong("wrongAt"))).put("wrongRounds",unionRounds(existing.optJSONArray("wrongRounds"),fromWord.optJSONArray("wrongRounds"))).put("bookmarked",existing.optBoolean("bookmarked")||wrongCount>0);
                if(!existing.has("streak")){int streak=Math.max(0,fromWord.optInt("streak"));existing.put("streak",streak).put("mastered",streak>=2);}
            }
            java.util.Iterator<String> keys=all.keys();
            while(keys.hasNext()){
                String id=keys.next();JSONObject st=all.optJSONObject(id);
                if(st!=null){LocalStore.upsertWrongStateLocked(db,id,st);mirrorCardsSqlLocked(db,id,st);}
            }
            if(!prefs.edit().putBoolean(LEGACY_MIGRATED,true).commit())throw new IllegalStateException("错词状态未能保存");
        });
    }

    /** Single indexed SQL UPDATE across all plans that hold this stable wordId — O(1) instead of rewriting all plans. */
    private static void mirrorCardsSqlLocked(SQLiteDatabase db,String wordId,JSONObject state){
        ContentValues cv=new ContentValues();
        cv.put("wrong_count",state.optInt("wrongCount",0));
        cv.put("wrong_at",state.optLong("wrongAt",0));
        JSONArray rounds=state.optJSONArray("wrongRounds");
        cv.put("wrong_rounds_json",rounds==null?"[]":rounds.toString());
        int streak=state.optInt("streak",0);
        cv.put("spelling_streak",streak);
        cv.put("wrong_mastered",streak>=2?1:0);
        db.update("card",cv,"word_id=?",new String[]{wordId});
    }

    private static void mirror(JSONObject state,JSONObject word){
        try{word.put("wrongCount",state.optInt("wrongCount",0)).put("wrongAt",state.optLong("wrongAt",0)).put("wrongRounds",state.optJSONArray("wrongRounds")==null?new JSONArray():state.optJSONArray("wrongRounds")).put("spellingStreak",state.optInt("streak",0)).put("wrongMastered",state.optInt("streak",0)>=2).put("wordId",Words.stableId(word));}catch(Exception ignored){}
    }
    private static void syncSession(JSONArray words,int hint,JSONObject saved,String id){if(words==null||saved==null)return;int index=Words.locate(words,hint,id);if(index<0)return;try{words.put(index,new JSONObject(saved.toString()));}catch(Exception ignored){}}
    private static boolean duplicate(JSONObject state,String eventId){if(eventId==null||eventId.isEmpty())return false;JSONArray events=state.optJSONArray("eventIds");if(events==null)return false;for(int i=0;i<events.length();i++)if(eventId.equals(events.optString(i)))return true;return false;}
    private static void addEvent(JSONObject state,String eventId)throws Exception{if(eventId==null||eventId.isEmpty())return;JSONArray events=state.optJSONArray("eventIds");if(events==null)events=new JSONArray();events.put(eventId);while(events.length()>40)events.remove(0);state.put("eventIds",events);}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception ignored){}}
    private static JSONArray unionRounds(JSONArray a,JSONArray b){JSONArray out=new JSONArray();java.util.LinkedHashSet<Integer> seen=new java.util.LinkedHashSet<>();JSONArray[] lists=new JSONArray[]{a,b};for(JSONArray list:lists)if(list!=null)for(int i=0;i<list.length();i++){int n=list.optInt(i,-1);if(n>0&&seen.add(n))out.put(n);}return out;}
}
