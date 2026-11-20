package com.example.reviewreminder;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalized on-device SQLite database ({@code review.db}, schema v2, WAL mode).
 *
 * Domain data (tasks, word cards, review events, wrong-word states, spelling attempts, active
 * spelling sessions, training selections, translation exercises, voice profiles, AI mistakes,
 * drafts, and the vocabulary relation mind-map graph) lives in dedicated indexed tables as the
 * single source of truth. Only lightweight app settings and local secrets stay in {@code pref}.
 *
 * Upgrading from v1 (0.8.1) or legacy {@code review_data} SharedPreferences (<= 0.8.0) migrates
 * every field atomically without dropping unknown properties.
 */
final class LocalStore {
    static final Object LOCK=new Object();
    private static final int DB_VERSION=3;
    private static final String MIGRATED="legacy_prefs_migrated";
    private static final String V2_READY="normalized_v2_ready";
    private static final String SPELLING_ACTIVE_PREFIX="spellingActive:";

    private static final Set<String> TASK_KNOWN=new HashSet<>(Arrays.asList(
        "id","title","description","created","due","stage","snoozes","done","archived","intervals","windows","words","history"
    ));
    private static final Set<String> CARD_KNOWN=new HashSet<>(Arrays.asList(
        "wordId","english","chinese","example","category","senses","mastery","masteryStreak",
        "wrongCount","wrongAt","wrongRounds","spellingStreak","wrongMastered","connectionImage","aiAnalysis"
    ));
    private static final Set<String> EVENT_KNOWN=new HashSet<>(Arrays.asList(
        "stage","at","repeatCount","spellingMode","spellingTotal","spellingCorrect","spellingWrong","spellingSkipped","spellingCompleted"
    ));
    private static final Set<String> MIGRATED_BLOB_KEYS=new HashSet<>(Arrays.asList(
        "tasks","wrongWordStates","spellingAttempts","trainingSelections","trainingSelectionOrder",
        "translationExercises","voiceProfiles","aiMistakes"
    ));

    private static SQLiteDatabase database;
    private static SqlitePrefs prefsInstance;
    private static boolean initialized;
    private static int depth;
    private static boolean failed;
    private static final List<String> pendingNotes=new ArrayList<>();

    private LocalStore() {}

    interface Work { void run() throws Exception; }
    interface Read<T> { T read() throws Exception; }

    static SharedPreferences prefs(Context c){
        synchronized(LOCK){
            ensureOpen(c);
            return prefsInstance;
        }
    }

    static <T> T inRead(Context c,Read<T> read) throws Exception {
        synchronized(LOCK){
            ensureOpen(c);
            return read.read();
        }
    }

    static SQLiteDatabase database(){
        if(database==null)throw new IllegalStateException("本地数据库尚未打开");
        return database;
    }

    static void inTransaction(Context c,Work work) throws Exception {
        List<String> notes=null;
        synchronized(LOCK){
            ensureOpen(c);
            boolean outer=depth==0;
            if(outer){database.beginTransaction();failed=false;}
            depth++;
            boolean ok=false;
            try{
                work.run();
                ok=!failed;
            }finally{
                depth--;
                if(outer){
                    if(ok)database.setTransactionSuccessful();
                    else pendingNotes.clear();
                    database.endTransaction();
                    if(ok)notes=new ArrayList<>(pendingNotes);
                    pendingNotes.clear();
                }
            }
        }
        if(notes!=null&&prefsInstance!=null)prefsInstance.notify(notes);
    }

    static SQLiteDatabase db(Context c){
        synchronized(LOCK){
            ensureOpen(c);
            return database;
        }
    }

    private static void ensureOpen(Context c){
        Context app=c.getApplicationContext();
        if(database==null)database=new Helper(app).getWritableDatabase();
        if(!initialized){
            if(meta(database,MIGRATED)==null)migrateLegacyPrefs(app,database);
            if(meta(database,V2_READY)==null)migratePrefBlobsToTables(database);
            migrateLegacyDraftFile(app,database);
            initialized=true;
        }
        if(prefsInstance==null)prefsInstance=new SqlitePrefs();
    }

    // ── Legacy & v1 -> v2 atomic migrations ───────────────────────────────────

    private static void migrateLegacyPrefs(Context app,SQLiteDatabase db){
        SharedPreferences legacy=app.getSharedPreferences(Store.PREF,Context.MODE_PRIVATE);
        Map<String,?> all=legacy.getAll();
        Map<String,LegacyMigration.Value> encoded=new LinkedHashMap<>();
        try{
            for(Map.Entry<String,?> entry:all.entrySet()){
                if(entry.getKey()==null||entry.getValue()==null)continue;
                encoded.put(entry.getKey(),LegacyMigration.encode(entry.getValue()));
            }
            LegacyMigration.Bundle bundle=LegacyMigration.migrate(encoded);
            db.beginTransaction();
            try{
                for(Map.Entry<String,LegacyMigration.Value> entry:bundle.prefs.entrySet()){
                    putPref(db,entry.getKey(),entry.getValue().kind,entry.getValue().text);
                }
                migratePrefBlobsLocked(db);
                putMeta(db,MIGRATED,"1");
                putMeta(db,V2_READY,"1");
                db.setTransactionSuccessful();
            }finally{
                db.endTransaction();
            }
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("原有学习数据未能迁入本地数据库："+(e.getMessage()==null?"迁移失败":e.getMessage()),e);
        }
    }

    private static void migratePrefBlobsToTables(SQLiteDatabase db){
        db.beginTransaction();
        try{
            migratePrefBlobsLocked(db);
            putMeta(db,V2_READY,"1");
            db.setTransactionSuccessful();
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("数据结构升级失败："+(e.getMessage()==null?"请重试":e.getMessage()),e);
        }finally{
            db.endTransaction();
        }
    }

    private static void migrateLegacyDraftFile(Context app,SQLiteDatabase db){
        try{
            File f=new File(app.getFilesDir(),"word-draft.json");
            if(!f.isFile())return;
            Cursor c=db.rawQuery("SELECT COUNT(*) FROM draft_store WHERE id='current'",null);
            boolean hasRow=false;
            try{if(c.moveToFirst())hasRow=c.getInt(0)>0;}finally{c.close();}
            if(!hasRow){
                String raw=Words.read(new FileInputStream(f));
                if(!raw.trim().isEmpty()){
                    ContentValues cv=new ContentValues();
                    cv.put("id","current");
                    cv.put("json",raw);
                    cv.put("updated_at",f.lastModified());
                    db.insertWithOnConflict("draft_store",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            f.delete();
        }catch(Exception ignored){}
    }

    /** Moves any monolithic JSON blobs stored in {@code pref} into their normalized tables. */
    static void migratePrefBlobsLocked(SQLiteDatabase db) throws Exception {
        String[] tasksPref=pref(db,"tasks");
        if(tasksPref!=null&&"string".equals(tasksPref[0])&&!tasksPref[1].trim().isEmpty()){
            writeAllTasksLocked(db,new JSONArray(tasksPref[1]));
            db.delete("pref","k=?",new String[]{"tasks"});
        }
        String[] wrongPref=pref(db,"wrongWordStates");
        if(wrongPref!=null&&"string".equals(wrongPref[0])&&!wrongPref[1].trim().isEmpty()){
            JSONObject obj=new JSONObject(wrongPref[1]);
            Iterator<String> keys=obj.keys();
            while(keys.hasNext()){
                String id=keys.next();
                JSONObject st=obj.optJSONObject(id);
                if(st!=null)upsertWrongStateLocked(db,id,st);
            }
            db.delete("pref","k=?",new String[]{"wrongWordStates"});
        }
        String[] attemptsPref=pref(db,"spellingAttempts");
        if(attemptsPref!=null&&"string".equals(attemptsPref[0])&&!attemptsPref[1].trim().isEmpty()){
            JSONArray arr=new JSONArray(attemptsPref[1]);
            for(int i=0;i<arr.length();i++){
                JSONObject row=arr.optJSONObject(i);
                if(row!=null)insertAttemptLocked(db,row);
            }
            db.delete("pref","k=?",new String[]{"spellingAttempts"});
        }
        String[] selPref=pref(db,"trainingSelections");
        if(selPref!=null&&"string".equals(selPref[0])&&!selPref[1].trim().isEmpty()){
            JSONObject obj=new JSONObject(selPref[1]);
            Iterator<String> keys=obj.keys();
            while(keys.hasNext()){
                String id=keys.next();
                JSONObject sel=obj.optJSONObject(id);
                if(sel!=null)upsertSelectionLocked(db,id,sel.optLong("created",System.currentTimeMillis()),sel.optJSONArray("words"));
            }
            db.delete("pref","k=?",new String[]{"trainingSelections"});
        }
        db.delete("pref","k=?",new String[]{"trainingSelectionOrder"});

        List<String> sessionKeys=new ArrayList<>();
        Cursor sc=db.rawQuery("SELECT k,v FROM pref WHERE k LIKE 'spellingActive:%'",null);
        try{
            while(sc.moveToNext()){
                String k=sc.getString(0),v=sc.getString(1);
                sessionKeys.add(k);
                try{
                    JSONObject s=new JSONObject(v);
                    String sid=s.optString("sessionId",k.substring(SPELLING_ACTIVE_PREFIX.length()));
                    upsertSpellingSessionLocked(db,sid,s);
                }catch(Exception ignored){}
            }
        }finally{sc.close();}
        for(String k:sessionKeys)db.delete("pref","k=?",new String[]{k});

        String[] transPref=pref(db,"translationExercises");
        if(transPref!=null&&"string".equals(transPref[0])&&!transPref[1].trim().isEmpty()){
            JSONArray arr=new JSONArray(transPref[1]);
            for(int i=0;i<arr.length();i++){
                JSONObject ex=arr.optJSONObject(i);
                if(ex!=null&&!ex.optString("id").isEmpty())upsertTranslationLocked(db,ex);
            }
            db.delete("pref","k=?",new String[]{"translationExercises"});
        }
        String[] voicePref=pref(db,"voiceProfiles");
        if(voicePref!=null&&"string".equals(voicePref[0])&&!voicePref[1].trim().isEmpty()){
            JSONArray arr=new JSONArray(voicePref[1]);
            for(int i=0;i<arr.length();i++){
                JSONObject p=arr.optJSONObject(i);
                if(p!=null&&!p.optString("id").isEmpty())upsertVoiceProfileLocked(db,p);
            }
            db.delete("pref","k=?",new String[]{"voiceProfiles"});
        }
        String[] mistakesPref=pref(db,"aiMistakes");
        if(mistakesPref!=null&&"string".equals(mistakesPref[0])&&!mistakesPref[1].trim().isEmpty()){
            JSONArray arr=new JSONArray(mistakesPref[1]);
            for(int i=0;i<arr.length();i++){
                JSONObject m=arr.optJSONObject(i);
                if(m!=null)insertAiMistakeLocked(db,m.optString("english"),m.optString("sentence"),m.optString("feedback"),m.optLong("at",System.currentTimeMillis()));
            }
            db.delete("pref","k=?",new String[]{"aiMistakes"});
        }
    }

    // ── Normalized Task / Card / ReviewEvent CRUD ─────────────────────────────

    static JSONArray readAllTasksLocked(SQLiteDatabase db) throws Exception {
        JSONArray out=new JSONArray();
        Map<Long,JSONArray> cardsByTask=new HashMap<>();
        Map<Long,JSONArray> eventsByTask=new HashMap<>();

        Cursor cc=db.rawQuery("SELECT task_id,pos,word_id,english,chinese,example,senses_json,mastery,mastery_streak,wrong_count,wrong_at,wrong_rounds_json,spelling_streak,wrong_mastered,connection_image,ai_analysis,extra_json,category FROM card ORDER BY task_id ASC,pos ASC",null);
        try{
            while(cc.moveToNext()){
                long tid=cc.getLong(0);
                JSONArray list=cardsByTask.get(tid);
                if(list==null){list=new JSONArray();cardsByTask.put(tid,list);}
                list.put(cardFromCursor(cc));
            }
        }finally{cc.close();}

        Cursor ec=db.rawQuery("SELECT task_id,pos,stage,at,repeat_count,spelling_mode,spelling_total,spelling_correct,spelling_wrong,spelling_skipped,spelling_completed,extra_json FROM review_event ORDER BY task_id ASC,pos ASC",null);
        try{
            while(ec.moveToNext()){
                long tid=ec.getLong(0);
                JSONArray list=eventsByTask.get(tid);
                if(list==null){list=new JSONArray();eventsByTask.put(tid,list);}
                list.put(eventFromCursor(ec));
            }
        }finally{ec.close();}

        Cursor tc=db.rawQuery("SELECT id,ord,title,description,created,due,stage,snoozes,done,archived,intervals_json,windows,extra_json FROM task ORDER BY ord ASC,id ASC",null);
        try{
            while(tc.moveToNext()){
                long tid=tc.getLong(0);
                JSONObject task=taskFromCursor(tc);
                JSONArray words=cardsByTask.get(tid);
                if(words!=null)task.put("words",words);
                else if(task.optBoolean("_hasWords",false))task.put("words",new JSONArray());
                task.remove("_hasWords");
                JSONArray history=eventsByTask.get(tid);
                task.put("history",history==null?new JSONArray():history);
                out.put(task);
            }
        }finally{tc.close();}
        return out;
    }

    static JSONObject readTaskLocked(SQLiteDatabase db,long taskId) throws Exception {
        Cursor tc=db.rawQuery("SELECT id,ord,title,description,created,due,stage,snoozes,done,archived,intervals_json,windows,extra_json FROM task WHERE id=?",new String[]{Long.toString(taskId)});
        JSONObject task;
        try{
            if(!tc.moveToFirst())return null;
            task=taskFromCursor(tc);
        }finally{tc.close();}
        JSONArray words=new JSONArray();
        Cursor cc=db.rawQuery("SELECT task_id,pos,word_id,english,chinese,example,senses_json,mastery,mastery_streak,wrong_count,wrong_at,wrong_rounds_json,spelling_streak,wrong_mastered,connection_image,ai_analysis,extra_json,category FROM card WHERE task_id=? ORDER BY pos ASC",new String[]{Long.toString(taskId)});
        try{while(cc.moveToNext())words.put(cardFromCursor(cc));}finally{cc.close();}
        if(words.length()>0||task.optBoolean("_hasWords",false))task.put("words",words);
        task.remove("_hasWords");
        JSONArray history=new JSONArray();
        Cursor ec=db.rawQuery("SELECT task_id,pos,stage,at,repeat_count,spelling_mode,spelling_total,spelling_correct,spelling_wrong,spelling_skipped,spelling_completed,extra_json FROM review_event WHERE task_id=? ORDER BY pos ASC",new String[]{Long.toString(taskId)});
        try{while(ec.moveToNext())history.put(eventFromCursor(ec));}finally{ec.close();}
        task.put("history",history);
        return task;
    }

    static void writeAllTasksLocked(SQLiteDatabase db,JSONArray tasks) throws Exception {
        db.delete("review_event",null,null);
        db.delete("card",null,null);
        db.delete("task",null,null);
        if(tasks==null)return;
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);
            if(task==null)continue;
            writeTaskLocked(db,task,i);
        }
    }

    static void writeTaskLocked(SQLiteDatabase db,JSONObject task,int ord) throws Exception {
        long id=task.optLong("id");
        if(ord<0){
            Cursor c=db.rawQuery("SELECT ord FROM task WHERE id=?",new String[]{Long.toString(id)});
            try{if(c.moveToFirst())ord=c.getInt(0);}finally{c.close();}
            if(ord<0){
                Cursor m=db.rawQuery("SELECT COALESCE(MAX(ord),-1)+1 FROM task",null);
                try{ord=m.moveToFirst()?m.getInt(0):0;}finally{m.close();}
            }
        }
        JSONObject extra=extractExtra(task,TASK_KNOWN);
        if(task.has("words")&&task.optJSONArray("words")!=null)extra.put("_hasWords",true);
        ContentValues tv=new ContentValues();
        tv.put("id",id);
        tv.put("ord",ord);
        tv.put("title",task.optString("title",""));
        tv.put("description",task.optString("description",""));
        tv.put("created",task.optLong("created",0));
        tv.put("due",task.optLong("due",0));
        tv.put("stage",task.optInt("stage",0));
        tv.put("snoozes",task.optInt("snoozes",0));
        tv.put("done",task.optBoolean("done",false)?1:0);
        tv.put("archived",task.optBoolean("archived",false)?1:0);
        JSONArray intervals=task.optJSONArray("intervals");
        tv.put("intervals_json",intervals==null?"[]":intervals.toString());
        tv.put("windows",task.optString("windows",""));
        tv.put("extra_json",extra.toString());
        db.insertWithOnConflict("task",null,tv,SQLiteDatabase.CONFLICT_REPLACE);

        db.delete("card","task_id=?",new String[]{Long.toString(id)});
        JSONArray words=task.optJSONArray("words");
        if(words!=null){
            for(int j=0;j<words.length();j++){
                JSONObject word=words.optJSONObject(j);
                if(word!=null)writeCardRowLocked(db,id,j,word);
            }
        }
        db.delete("review_event","task_id=?",new String[]{Long.toString(id)});
        JSONArray history=task.optJSONArray("history");
        if(history!=null){
            for(int j=0;j<history.length();j++){
                JSONObject ev=history.optJSONObject(j);
                if(ev!=null)writeEventRowLocked(db,id,j,ev);
            }
        }
    }

    static void writeCardRowLocked(SQLiteDatabase db,long taskId,int pos,JSONObject word) throws Exception {
        String wordId=Words.stableId(word);
        JSONObject extra=extractExtra(word,CARD_KNOWN);
        ContentValues cv=new ContentValues();
        cv.put("task_id",taskId);
        cv.put("pos",pos);
        cv.put("word_id",wordId);
        cv.put("english",word.optString("english",""));
        cv.put("chinese",word.optString("chinese",""));
        cv.put("example",word.optString("example",""));
        cv.put("category",word.optString("category","").trim());
        JSONArray senses=word.optJSONArray("senses");
        cv.put("senses_json",senses==null?"":senses.toString());
        cv.put("mastery",word.optInt("mastery",0));
        cv.put("mastery_streak",word.optInt("masteryStreak",0));
        cv.put("wrong_count",word.optInt("wrongCount",0));
        cv.put("wrong_at",word.optLong("wrongAt",0));
        JSONArray rounds=word.optJSONArray("wrongRounds");
        cv.put("wrong_rounds_json",rounds==null?"":rounds.toString());
        cv.put("spelling_streak",word.optInt("spellingStreak",0));
        cv.put("wrong_mastered",word.optBoolean("wrongMastered",false)?1:0);
        cv.put("connection_image",word.optString("connectionImage",""));
        cv.put("ai_analysis",word.optString("aiAnalysis",""));
        cv.put("extra_json",extra.toString());
        db.insertWithOnConflict("card",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }

    static void writeEventRowLocked(SQLiteDatabase db,long taskId,int pos,JSONObject ev) throws Exception {
        JSONObject extra=extractExtra(ev,EVENT_KNOWN);
        ContentValues cv=new ContentValues();
        cv.put("task_id",taskId);
        cv.put("pos",pos);
        cv.put("stage",ev.optInt("stage",0));
        cv.put("at",ev.optLong("at",0));
        cv.put("repeat_count",ev.has("repeatCount")?ev.optInt("repeatCount"):-1);
        cv.put("spelling_mode",ev.has("spellingMode")?ev.optInt("spellingMode"):-1);
        cv.put("spelling_total",ev.has("spellingTotal")?ev.optInt("spellingTotal"):-1);
        cv.put("spelling_correct",ev.has("spellingCorrect")?ev.optInt("spellingCorrect"):-1);
        cv.put("spelling_wrong",ev.has("spellingWrong")?ev.optInt("spellingWrong"):-1);
        cv.put("spelling_skipped",ev.has("spellingSkipped")?ev.optInt("spellingSkipped"):-1);
        cv.put("spelling_completed",ev.has("spellingCompleted")?(ev.optBoolean("spellingCompleted")?1:0):-1);
        cv.put("extra_json",extra.toString());
        db.insertWithOnConflict("review_event",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }

    static void deleteTaskLocked(SQLiteDatabase db,long taskId){
        String[] arg={Long.toString(taskId)};
        db.delete("review_event","task_id=?",arg);
        db.delete("card","task_id=?",arg);
        db.delete("task","id=?",arg);
    }

    private static JSONObject taskFromCursor(Cursor c) throws Exception {
        String extraRaw=c.getString(12);
        JSONObject t=(extraRaw==null||extraRaw.isEmpty())?new JSONObject():new JSONObject(extraRaw);
        t.put("id",c.getLong(0));
        t.put("title",c.getString(2));
        String desc=c.getString(3);
        if(!desc.isEmpty())t.put("description",desc);
        t.put("created",c.getLong(4));
        t.put("due",c.getLong(5));
        t.put("stage",c.getInt(6));
        t.put("snoozes",c.getInt(7));
        t.put("done",c.getInt(8)!=0);
        if(c.getInt(9)!=0)t.put("archived",true);
        String iv=c.getString(10);
        t.put("intervals",(iv==null||iv.isEmpty())?new JSONArray():new JSONArray(iv));
        t.put("windows",c.getString(11));
        return t;
    }

    private static JSONObject cardFromCursor(Cursor c) throws Exception {
        String extraRaw=c.getString(16);
        JSONObject w=(extraRaw==null||extraRaw.isEmpty())?new JSONObject():new JSONObject(extraRaw);
        w.put("wordId",c.getString(2));
        w.put("english",c.getString(3));
        w.put("chinese",c.getString(4));
        w.put("example",c.getString(5));
        String senses=c.getString(6);
        if(senses!=null&&!senses.isEmpty())w.put("senses",new JSONArray(senses));
        int mastery=c.getInt(7),mStreak=c.getInt(8);
        if(mastery>0||w.has("mastery"))w.put("mastery",mastery);
        if(mStreak>0||w.has("masteryStreak"))w.put("masteryStreak",mStreak);
        int wc=c.getInt(9);long wa=c.getLong(10);String wr=c.getString(11);
        int ss=c.getInt(12),wm=c.getInt(13);
        if(wc>0||wa>0||ss>0||wm!=0||(wr!=null&&!wr.isEmpty())){
            w.put("wrongCount",wc);
            w.put("wrongAt",wa);
            w.put("wrongRounds",(wr==null||wr.isEmpty())?new JSONArray():new JSONArray(wr));
            w.put("spellingStreak",ss);
            w.put("wrongMastered",wm!=0);
        }
        String ci=c.getString(14);
        if(ci!=null&&!ci.isEmpty())w.put("connectionImage",ci);
        String ai=c.getString(15);
        if(ai!=null&&!ai.isEmpty())w.put("aiAnalysis",ai);
        if(c.getColumnCount()>17){
            String cat=c.getString(17);
            if(cat!=null&&!cat.trim().isEmpty())w.put("category",cat.trim());
        }
        return w;
    }

    private static JSONObject eventFromCursor(Cursor c) throws Exception {
        String extraRaw=c.getString(11);
        JSONObject ev=(extraRaw==null||extraRaw.isEmpty())?new JSONObject():new JSONObject(extraRaw);
        ev.put("stage",c.getInt(2));
        ev.put("at",c.getLong(3));
        if(c.getInt(4)>=0)ev.put("repeatCount",c.getInt(4));
        if(c.getInt(5)>=0)ev.put("spellingMode",c.getInt(5));
        if(c.getInt(6)>=0)ev.put("spellingTotal",c.getInt(6));
        if(c.getInt(7)>=0)ev.put("spellingCorrect",c.getInt(7));
        if(c.getInt(8)>=0)ev.put("spellingWrong",c.getInt(8));
        if(c.getInt(9)>=0)ev.put("spellingSkipped",c.getInt(9));
        if(c.getInt(10)>=0)ev.put("spellingCompleted",c.getInt(10)!=0);
        return ev;
    }

    private static JSONObject extractExtra(JSONObject obj,Set<String> known) throws Exception {
        JSONObject extra=new JSONObject();
        Iterator<String> keys=obj.keys();
        while(keys.hasNext()){
            String key=keys.next();
            if(!known.contains(key))extra.put(key,obj.opt(key));
        }
        return extra;
    }

    // ── Normalized WrongState / Attempt / Selection / Session / Translation / Voice / Mistake helpers ─

    static void upsertWrongStateLocked(SQLiteDatabase db,String wordId,JSONObject state){
        ContentValues cv=new ContentValues();
        cv.put("word_id",wordId);
        cv.put("wrong_count",state.optInt("wrongCount",0));
        cv.put("wrong_at",state.optLong("wrongAt",0));
        cv.put("bookmarked",state.optBoolean("bookmarked",false)?1:0);
        cv.put("streak",state.optInt("streak",0));
        cv.put("mastered",state.optBoolean("mastered",state.optInt("streak",0)>=2)?1:0);
        JSONArray rounds=state.optJSONArray("wrongRounds");
        cv.put("wrong_rounds_json",rounds==null?"[]":rounds.toString());
        JSONArray events=state.optJSONArray("eventIds");
        cv.put("event_ids_json",events==null?"[]":events.toString());
        db.insertWithOnConflict("wrong_state",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }

    static JSONObject readWrongStateLocked(SQLiteDatabase db,String wordId) throws Exception {
        Cursor c=db.rawQuery("SELECT wrong_count,wrong_at,bookmarked,streak,mastered,wrong_rounds_json,event_ids_json FROM wrong_state WHERE word_id=?",new String[]{wordId});
        try{
            if(!c.moveToFirst())return new JSONObject();
            return new JSONObject()
                .put("wrongCount",c.getInt(0))
                .put("wrongAt",c.getLong(1))
                .put("bookmarked",c.getInt(2)!=0)
                .put("streak",c.getInt(3))
                .put("mastered",c.getInt(4)!=0)
                .put("wrongRounds",new JSONArray(c.getString(5)))
                .put("eventIds",new JSONArray(c.getString(6)));
        }finally{c.close();}
    }

    static JSONObject readAllWrongStatesLocked(SQLiteDatabase db) throws Exception {
        JSONObject all=new JSONObject();
        Cursor c=db.rawQuery("SELECT word_id,wrong_count,wrong_at,bookmarked,streak,mastered,wrong_rounds_json,event_ids_json FROM wrong_state",null);
        try{
            while(c.moveToNext()){
                all.put(c.getString(0),new JSONObject()
                    .put("wrongCount",c.getInt(1))
                    .put("wrongAt",c.getLong(2))
                    .put("bookmarked",c.getInt(3)!=0)
                    .put("streak",c.getInt(4))
                    .put("mastered",c.getInt(5)!=0)
                    .put("wrongRounds",new JSONArray(c.getString(6)))
                    .put("eventIds",new JSONArray(c.getString(7))));
            }
        }finally{c.close();}
        return all;
    }

    static void insertAttemptLocked(SQLiteDatabase db,JSONObject row){
        ContentValues cv=new ContentValues();
        cv.put("word_id",row.optString("wordId",""));
        cv.put("task_id",row.optLong("taskId",0));
        cv.put("word_index",row.optInt("wordIndex",-1));
        cv.put("round",row.optInt("round",-1));
        cv.put("type",row.optString("type",""));
        cv.put("event_id",row.optString("eventId",""));
        cv.put("at",row.optLong("at",System.currentTimeMillis()));
        db.insert("spelling_attempt",null,cv);
        db.execSQL("DELETE FROM spelling_attempt WHERE id NOT IN (SELECT id FROM spelling_attempt ORDER BY id DESC LIMIT 500)");
    }

    static JSONArray readAttemptsLocked(SQLiteDatabase db,String wordIdOrNull) throws Exception {
        JSONArray out=new JSONArray();
        Cursor c=wordIdOrNull==null
            ?db.rawQuery("SELECT word_id,task_id,word_index,round,type,event_id,at FROM spelling_attempt ORDER BY id ASC",null)
            :db.rawQuery("SELECT word_id,task_id,word_index,round,type,event_id,at FROM spelling_attempt WHERE word_id=? ORDER BY at DESC LIMIT 50",new String[]{wordIdOrNull});
        try{
            while(c.moveToNext()){
                out.put(new JSONObject()
                    .put("wordId",c.getString(0)).put("taskId",c.getLong(1)).put("wordIndex",c.getInt(2))
                    .put("round",c.getInt(3)).put("type",c.getString(4)).put("eventId",c.getString(5)).put("at",c.getLong(6)));
            }
        }finally{c.close();}
        return out;
    }

    static void upsertSelectionLocked(SQLiteDatabase db,String id,long created,JSONArray words){
        ContentValues cv=new ContentValues();
        cv.put("id",id);
        cv.put("created",created);
        cv.put("words_json",words==null?"[]":words.toString());
        db.insertWithOnConflict("training_selection",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM training_selection WHERE id NOT IN (SELECT id FROM training_selection ORDER BY created DESC LIMIT 12) AND id NOT IN (SELECT selection_id FROM spelling_session)");
    }

    static void upsertSpellingSessionLocked(SQLiteDatabase db,String sessionId,JSONObject s){
        ContentValues cv=new ContentValues();
        cv.put("session_id",sessionId);
        cv.put("selection_id",s.optString("selectionId",""));
        cv.put("wrong_mode",s.optBoolean("wrongMode",false)?1:0);
        cv.put("task_id",s.optLong("taskId",0));
        cv.put("round",s.optInt("round",-1));
        cv.put("history_at",s.optLong("historyAt",0));
        cv.put("attempt_id",s.optString("attemptId",""));
        cv.put("snapshot",s.optString("snapshot",""));
        cv.put("hint_word",s.optString("hintWord",""));
        cv.put("hint_used",s.optBoolean("hintUsed",false)?1:0);
        cv.put("updated_at",s.optLong("updatedAt",System.currentTimeMillis()));
        db.insertWithOnConflict("spelling_session",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }

    static void upsertTranslationLocked(SQLiteDatabase db,JSONObject ex){
        ContentValues cv=new ContentValues();
        cv.put("id",ex.optString("id"));
        cv.put("selection_id",ex.optString("selectionId",""));
        cv.put("direction",ex.optString("direction","en_zh"));
        cv.put("difficulty",ex.optString("difficulty",""));
        cv.put("grading_mode",ex.optInt("gradingMode",0));
        cv.put("prompt",ex.optString("prompt",""));
        cv.put("reference",ex.optString("reference",""));
        cv.put("explanation",ex.optString("explanation",""));
        JSONArray targets=ex.optJSONArray("targets");
        cv.put("targets_json",targets==null?"[]":targets.toString());
        cv.put("user_answer",ex.optString("userAnswer",""));
        cv.put("self_rating",ex.optString("selfRating",""));
        cv.put("note",ex.optString("note",""));
        cv.put("ai_feedback",ex.optString("aiFeedback",""));
        cv.put("reference_viewed",ex.optBoolean("referenceViewed",false)?1:0);
        cv.put("created_at",ex.optLong("createdAt",System.currentTimeMillis()));
        cv.put("updated_at",ex.optLong("updatedAt",0));
        db.insertWithOnConflict("translation_exercise",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM translation_exercise WHERE id NOT IN (SELECT id FROM translation_exercise ORDER BY created_at DESC LIMIT 200)");
    }

    static void upsertVoiceProfileLocked(SQLiteDatabase db,JSONObject p){
        ContentValues cv=new ContentValues();
        cv.put("id",p.optString("id"));
        cv.put("name",p.optString("name",""));
        cv.put("path",p.optString("path",""));
        cv.put("mime",p.optString("mime","audio/mpeg"));
        cv.put("version",p.optLong("version",0));
        cv.put("created",p.optLong("created",0));
        db.insertWithOnConflict("voice_profile",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }

    static void insertAiMistakeLocked(SQLiteDatabase db,String english,String sentence,String feedback,long at){
        ContentValues cv=new ContentValues();
        cv.put("english",english==null?"":english);
        cv.put("sentence",sentence==null?"":sentence);
        cv.put("feedback",feedback==null?"":feedback);
        cv.put("at",at);
        db.insert("ai_mistake",null,cv);
        db.execSQL("DELETE FROM ai_mistake WHERE id NOT IN (SELECT id FROM ai_mistake ORDER BY id DESC LIMIT 80)");
    }

    // ── Pref & Meta helpers ───────────────────────────────────────────────────

    private static void putPref(SQLiteDatabase db,String key,String kind,String value){
        db.execSQL("INSERT OR REPLACE INTO pref(k,kind,v) VALUES(?,?,?)",new Object[]{key,kind,value});
    }

    private static void putMeta(SQLiteDatabase db,String key,String value){
        db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)",new Object[]{key,value});
    }

    private static String meta(SQLiteDatabase db,String key){
        Cursor cursor=db.rawQuery("SELECT v FROM meta WHERE k=?",new String[]{key});
        try{return cursor.moveToFirst()?cursor.getString(0):null;}finally{cursor.close();}
    }

    private static String[] pref(SQLiteDatabase db,String key){
        Cursor cursor=db.rawQuery("SELECT kind,v FROM pref WHERE k=?",new String[]{key});
        try{return cursor.moveToFirst()?new String[]{cursor.getString(0),cursor.getString(1)}:null;}finally{cursor.close();}
    }

    // ── SharedPreferences facade for lightweight settings ─────────────────────

    private static final class Change {
        final String kind,text;final boolean remove;
        Change(String kind,String text){this.kind=kind;this.text=text;this.remove=false;}
        Change(){this.kind=null;this.text=null;this.remove=true;}
    }

    private static final class SqlitePrefs implements SharedPreferences {
        private final List<OnSharedPreferenceChangeListener> listeners=new ArrayList<>();

        void notify(List<String> keys){
            if(keys==null||keys.isEmpty())return;
            List<OnSharedPreferenceChangeListener> current;
            synchronized(listeners){current=new ArrayList<>(listeners);}
            for(String key:keys)for(OnSharedPreferenceChangeListener listener:current){
                if(listener!=null)listener.onSharedPreferenceChanged(this,key);
            }
        }

        public Map<String,?> getAll(){
            synchronized(LOCK){
                ensureReady();
                Map<String,Object> out=new HashMap<>();
                Cursor cursor=database.rawQuery("SELECT k,kind,v FROM pref",null);
                try{
                    while(cursor.moveToNext())out.put(cursor.getString(0),decode(cursor.getString(0),cursor.getString(1),cursor.getString(2)));
                }finally{cursor.close();}
                return out;
            }
        }

        public String getString(String key,String def){return (String)get(key,"string",def);}
        public Set<String> getStringSet(String key,Set<String> def){
            Object value=get(key,"set",null);
            return value==null?def:(Set<String>)value;
        }
        public int getInt(String key,int def){Object value=get(key,"int",null);return value==null?def:(Integer)value;}
        public long getLong(String key,long def){Object value=get(key,"long",null);return value==null?def:(Long)value;}
        public float getFloat(String key,float def){Object value=get(key,"float",null);return value==null?def:(Float)value;}
        public boolean getBoolean(String key,boolean def){Object value=get(key,"boolean",null);return value==null?def:(Boolean)value;}
        public boolean contains(String key){synchronized(LOCK){ensureReady();return pref(database,key)!=null;}}
        public Editor edit(){return new Batch();}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){if(listener!=null)synchronized(listeners){if(!listeners.contains(listener))listeners.add(listener);}}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){synchronized(listeners){listeners.remove(listener);}}

        private void ensureReady(){if(database==null)throw new IllegalStateException("本地数据库尚未打开");}

        private Object get(String key,String kind,Object def){
            synchronized(LOCK){
                ensureReady();
                String[] row=pref(database,key);
                if(row==null)return def;
                if(!kind.equals(row[0]))throw new ClassCastException(key);
                return decode(key,row[0],row[1]);
            }
        }

        private Object decode(String key,String kind,String text){
            try{
                if("string".equals(kind))return text;
                if("int".equals(kind))return Integer.parseInt(text);
                if("long".equals(kind))return Long.parseLong(text);
                if("boolean".equals(kind))return Boolean.parseBoolean(text);
                if("float".equals(kind))return Float.parseFloat(text);
                if("set".equals(kind)){
                    JSONArray array=new JSONArray(text);
                    Set<String> values=new HashSet<>();
                    for(int i=0;i<array.length();i++)values.add(array.optString(i));
                    return values;
                }
            }catch(Exception e){
                throw new IllegalStateException("无法读取 "+key,e);
            }
            throw new IllegalStateException("未知的偏好类型："+kind);
        }
    }

    private static final class Batch implements SharedPreferences.Editor {
        private final LinkedHashMap<String,Change> changes=new LinkedHashMap<>();
        private boolean clear;

        public SharedPreferences.Editor putString(String key,String value){if(value==null)return remove(key);changes.put(key,new Change("string",value));return this;}
        public SharedPreferences.Editor putInt(String key,int value){changes.put(key,new Change("int",Integer.toString(value)));return this;}
        public SharedPreferences.Editor putLong(String key,long value){changes.put(key,new Change("long",Long.toString(value)));return this;}
        public SharedPreferences.Editor putFloat(String key,float value){changes.put(key,new Change("float",Float.toString(value)));return this;}
        public SharedPreferences.Editor putBoolean(String key,boolean value){changes.put(key,new Change("boolean",value?"true":"false"));return this;}
        public SharedPreferences.Editor putStringSet(String key,Set<String> values){
            if(values==null)return remove(key);
            try{
                JSONArray array=new JSONArray();
                for(String value:values)if(value!=null)array.put(value);
                changes.put(key,new Change("set",array.toString()));
            }catch(Exception e){throw new IllegalStateException("无法保存字符串集合",e);}
            return this;
        }
        public SharedPreferences.Editor remove(String key){changes.put(key,new Change());return this;}
        public SharedPreferences.Editor clear(){clear=true;changes.clear();return this;}

        public boolean commit(){
            List<String> notes=null;
            boolean ok;
            synchronized(LOCK){
                ok=commitLocked();
                if(depth==0){notes=new ArrayList<>(pendingNotes);pendingNotes.clear();}
            }
            if(notes!=null&&prefsInstance!=null)prefsInstance.notify(notes);
            return ok;
        }

        public void apply(){if(!commit())throw new IllegalStateException("数据未能写入设备，请检查存储空间");}

        private boolean commitLocked(){
            boolean outer=depth==0;
            if(outer){database.beginTransaction();failed=false;}
            depth++;
            boolean ok=false;
            try{
                List<String> keys=new ArrayList<>();
                if(clear){
                    Cursor cursor=database.rawQuery("SELECT k FROM pref",null);
                    try{while(cursor.moveToNext())keys.add(cursor.getString(0));}finally{cursor.close();}
                    database.delete("pref",null,null);
                }
                for(Map.Entry<String,Change> entry:changes.entrySet()){
                    String key=entry.getKey();
                    keys.add(key);
                    Change change=entry.getValue();
                    if("tasks".equals(key)&&!change.remove&&"string".equals(change.kind)){
                        writeAllTasksLocked(database,new JSONArray(change.text));
                        continue;
                    }
                    if(MIGRATED_BLOB_KEYS.contains(key)&&!change.remove){
                        putPref(database,key,change.kind,change.text);
                        migratePrefBlobsLocked(database);
                        continue;
                    }
                    if(change.remove)database.delete("pref","k=?",new String[]{key});
                    else putPref(database,key,change.kind,change.text);
                }
                pendingNotes.addAll(keys);
                ok=true;
                changes.clear();
                clear=false;
                return true;
            }catch(Exception e){
                failed=true;
                return false;
            }finally{
                depth--;
                if(outer){
                    if(ok&&!failed)database.setTransactionSuccessful();
                    else pendingNotes.clear();
                    database.endTransaction();
                }
            }
        }
    }

    // ── SQLiteOpenHelper (Schema v2) ──────────────────────────────────────────

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context){super(context,"review.db",null,DB_VERSION);}

        @Override public void onConfigure(SQLiteDatabase db){
            super.onConfigure(db);
            db.enableWriteAheadLogging();
        }

        @Override public void onCreate(SQLiteDatabase db){
            createCoreTables(db);
            createDomainTables(db);
            createRelationTables(db);
            createIndices(db);
        }

        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
            if(oldVersion<2){
                db.execSQL("DROP TABLE IF EXISTS review_event");
                db.execSQL("DROP TABLE IF EXISTS card");
                db.execSQL("DROP TABLE IF EXISTS task");
                createDomainTables(db);
                addColumnIfMissing(db,"relation_member","is_root","INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(db,"relation_member","branch","INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(db,"relation_member","pos","INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(db,"relation_link","directed","INTEGER NOT NULL DEFAULT 0");
                createIndices(db);
            }
            if(oldVersion<3){
                addColumnIfMissing(db,"card","category","TEXT NOT NULL DEFAULT ''");
                createIndices(db);
            }
        }

        private static void createCoreTables(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS pref (k TEXT PRIMARY KEY, kind TEXT NOT NULL, v TEXT NOT NULL)");
        }

        private static void createDomainTables(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS task ("
                +"id INTEGER PRIMARY KEY, ord INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, "
                +"created INTEGER NOT NULL, due INTEGER NOT NULL, stage INTEGER NOT NULL, snoozes INTEGER NOT NULL, "
                +"done INTEGER NOT NULL, archived INTEGER NOT NULL, intervals_json TEXT NOT NULL, windows TEXT NOT NULL, extra_json TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS card ("
                +"task_id INTEGER NOT NULL, pos INTEGER NOT NULL, word_id TEXT NOT NULL, english TEXT NOT NULL, "
                +"chinese TEXT NOT NULL, example TEXT NOT NULL, category TEXT NOT NULL DEFAULT '', senses_json TEXT NOT NULL, mastery INTEGER NOT NULL, "
                +"mastery_streak INTEGER NOT NULL, wrong_count INTEGER NOT NULL, wrong_at INTEGER NOT NULL, "
                +"wrong_rounds_json TEXT NOT NULL, spelling_streak INTEGER NOT NULL, wrong_mastered INTEGER NOT NULL, "
                +"connection_image TEXT NOT NULL, ai_analysis TEXT NOT NULL, extra_json TEXT NOT NULL, PRIMARY KEY(task_id,pos))");
            db.execSQL("CREATE TABLE IF NOT EXISTS review_event ("
                +"task_id INTEGER NOT NULL, pos INTEGER NOT NULL, stage INTEGER NOT NULL, at INTEGER NOT NULL, "
                +"repeat_count INTEGER NOT NULL, spelling_mode INTEGER NOT NULL, spelling_total INTEGER NOT NULL, "
                +"spelling_correct INTEGER NOT NULL, spelling_wrong INTEGER NOT NULL, spelling_skipped INTEGER NOT NULL, "
                +"spelling_completed INTEGER NOT NULL, extra_json TEXT NOT NULL, PRIMARY KEY(task_id,pos))");
            db.execSQL("CREATE TABLE IF NOT EXISTS wrong_state ("
                +"word_id TEXT PRIMARY KEY, wrong_count INTEGER NOT NULL, wrong_at INTEGER NOT NULL, "
                +"bookmarked INTEGER NOT NULL, streak INTEGER NOT NULL, mastered INTEGER NOT NULL, "
                +"wrong_rounds_json TEXT NOT NULL, event_ids_json TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS spelling_attempt ("
                +"id INTEGER PRIMARY KEY AUTOINCREMENT, word_id TEXT NOT NULL, task_id INTEGER NOT NULL, "
                +"word_index INTEGER NOT NULL, round INTEGER NOT NULL, type TEXT NOT NULL, event_id TEXT NOT NULL, at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS training_selection (id TEXT PRIMARY KEY, created INTEGER NOT NULL, words_json TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS spelling_session ("
                +"session_id TEXT PRIMARY KEY, selection_id TEXT NOT NULL, wrong_mode INTEGER NOT NULL, task_id INTEGER NOT NULL, "
                +"round INTEGER NOT NULL, history_at INTEGER NOT NULL, attempt_id TEXT NOT NULL, snapshot TEXT NOT NULL, "
                +"hint_word TEXT NOT NULL, hint_used INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS translation_exercise ("
                +"id TEXT PRIMARY KEY, selection_id TEXT NOT NULL, direction TEXT NOT NULL, difficulty TEXT NOT NULL, "
                +"grading_mode INTEGER NOT NULL, prompt TEXT NOT NULL, reference TEXT NOT NULL, explanation TEXT NOT NULL, "
                +"targets_json TEXT NOT NULL, user_answer TEXT NOT NULL, self_rating TEXT NOT NULL, note TEXT NOT NULL, "
                +"ai_feedback TEXT NOT NULL, reference_viewed INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS voice_profile ("
                +"id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, version INTEGER NOT NULL, created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS ai_mistake ("
                +"id INTEGER PRIMARY KEY AUTOINCREMENT, english TEXT NOT NULL, sentence TEXT NOT NULL, feedback TEXT NOT NULL, at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS draft_store (id TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        }

        private static void createRelationTables(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS relation_group (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL, created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS relation_link (id TEXT PRIMARY KEY, a TEXT NOT NULL, b TEXT NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL, directed INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS relation_member (group_id TEXT NOT NULL, word_id TEXT NOT NULL, is_root INTEGER NOT NULL DEFAULT 0, branch INTEGER NOT NULL DEFAULT 0, pos INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(group_id,word_id))");
        }

        private static void createIndices(SQLiteDatabase db){
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_card_word ON card(word_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_card_task ON card(task_id,pos)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_card_category ON card(task_id,category)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_task ON review_event(task_id,at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_attempt_word ON spelling_attempt(word_id,at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_link_a ON relation_link(a)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_link_b ON relation_link(b)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_rel_member_word ON relation_member(word_id)");
        }

        private static void addColumnIfMissing(SQLiteDatabase db,String table,String column,String spec){
            Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null);
            boolean exists=false;
            try{
                while(c.moveToNext())if(column.equals(c.getString(1))){exists=true;break;}
            }finally{c.close();}
            if(!exists)db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+column+" "+spec);
        }
    }
}
