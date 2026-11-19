package com.example.reviewreminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-device SQLite store. The first open copies the legacy {@code review_data} preferences,
 * including the tasks JSON, and indexes plans, cards and review events in the same transaction.
 * A failed commit rolls back. Secrets stay in this private database; backup export strips them.
 */
final class LocalStore {
    static final Object LOCK=new Object();
    private static final String MIGRATED="legacy_prefs_migrated";
    private static SQLiteDatabase database;
    private static SqlitePrefs prefsInstance;
    private static boolean migrated;
    private static int depth;
    private static boolean failed;
    private static final List<String> pendingNotes=new ArrayList<>();

    private LocalStore() {}

    interface Work { void run() throws Exception; }

    static SharedPreferences prefs(Context c){
        synchronized(LOCK){
            ensureOpen(c);
            return prefsInstance;
        }
    }

    interface Read<T> { T read() throws Exception; }

    /** Read while holding the store lock so a commit cannot land between queries. */
    static <T> T inRead(Context c,Read<T> read) throws Exception {
        synchronized(LOCK){
            ensureOpen(c);
            return read.read();
        }
    }

    /** The open database. Only call while {@link #LOCK} is already held. */
    static SQLiteDatabase database(){
        if(database==null)throw new IllegalStateException("本地数据库尚未打开");
        return database;
    }

    /** Runs {@code work} in one transaction. Nested calls join the transaction already open on this thread. */
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
        if(!migrated){
            if(meta(MIGRATED)==null)migrate(app);
            migrated=true;
        }
        if(prefsInstance==null)prefsInstance=new SqlitePrefs();
    }

    private static void migrate(Context app){
        android.content.SharedPreferences legacy=app.getSharedPreferences(Store.PREF,Context.MODE_PRIVATE);
        Map<String,?> all=legacy.getAll();
        Map<String,LegacyMigration.Value> encoded=new LinkedHashMap<>();
        try{
            for(Map.Entry<String,?> entry:all.entrySet()){
                if(entry.getKey()==null||entry.getValue()==null)continue;
                encoded.put(entry.getKey(),LegacyMigration.encode(entry.getValue()));
            }
            LegacyMigration.Bundle bundle=LegacyMigration.migrate(encoded);
            database.beginTransaction();
            try{
                for(Map.Entry<String,LegacyMigration.Value> entry:bundle.prefs.entrySet()){
                    putPref(entry.getKey(),entry.getValue().kind,entry.getValue().text);
                }
                writeIndex(bundle.index);
                putMeta(MIGRATED,"1");
                database.setTransactionSuccessful();
            }finally{
                database.endTransaction();
            }
        }catch(RuntimeException e){
            throw e;
        }catch(Exception e){
            throw new IllegalStateException("原有学习数据未能迁入本地数据库："+(e.getMessage()==null?"迁移失败":e.getMessage()),e);
        }
    }

    private static void writeIndex(TaskIndex.Indexed indexed) throws Exception {
        database.delete("review_event",null,null);
        database.delete("card",null,null);
        database.delete("task",null,null);
        if(indexed==null)return;
        SQLiteStatement task=database.compileStatement("INSERT INTO task(ord,id,title,created,done,stage,json) VALUES(?,?,?,?,?,?,?)");
        SQLiteStatement card=database.compileStatement("INSERT INTO card(task_ord,pos,task_id,word_id,english,chinese,json) VALUES(?,?,?,?,?,?,?)");
        SQLiteStatement event=database.compileStatement("INSERT INTO review_event(task_ord,pos,task_id,at,stage,json) VALUES(?,?,?,?,?,?)");
        try{
            for(TaskIndex.Task row:indexed.tasks){
                task.bindLong(1,row.ord);task.bindLong(2,row.id);task.bindString(3,row.title);task.bindLong(4,row.created);task.bindLong(5,row.done?1:0);task.bindLong(6,row.stage);task.bindString(7,row.json);
                task.executeInsert();task.clearBindings();
            }
            for(TaskIndex.Card row:indexed.cards){
                card.bindLong(1,row.taskOrd);card.bindLong(2,row.pos);card.bindLong(3,row.taskId);card.bindString(4,row.wordId);card.bindString(5,row.english);card.bindString(6,row.chinese);card.bindString(7,row.json);
                card.executeInsert();card.clearBindings();
            }
            for(TaskIndex.Event row:indexed.events){
                event.bindLong(1,row.taskOrd);event.bindLong(2,row.pos);event.bindLong(3,row.taskId);event.bindLong(4,row.at);event.bindLong(5,row.stage);event.bindString(6,row.json);
                event.executeInsert();event.clearBindings();
            }
        }finally{
            task.close();card.close();event.close();
        }
    }

    private static void reindexTasks(String json) throws Exception {
        writeIndex(TaskIndex.index(json==null||json.isEmpty()?"[]":json));
    }

    private static void putPref(String key,String kind,String value){
        database.execSQL("INSERT OR REPLACE INTO pref(k,kind,v) VALUES(?,?,?)",new Object[]{key,kind,value});
    }

    private static void putMeta(String key,String value){
        database.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)",new Object[]{key,value});
    }

    private static String meta(String key){
        Cursor cursor=database.rawQuery("SELECT v FROM meta WHERE k=?",new String[]{key});
        try{return cursor.moveToFirst()?cursor.getString(0):null;}finally{cursor.close();}
    }

    private static String[] pref(String key){
        Cursor cursor=database.rawQuery("SELECT kind,v FROM pref WHERE k=?",new String[]{key});
        try{return cursor.moveToFirst()?new String[]{cursor.getString(0),cursor.getString(1)}:null;}finally{cursor.close();}
    }

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
        public boolean contains(String key){synchronized(LOCK){ensureReady();return pref(key)!=null;}}
        public Editor edit(){return new Batch();}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){if(listener!=null)synchronized(listeners){if(!listeners.contains(listener))listeners.add(listener);}}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){synchronized(listeners){listeners.remove(listener);}}

        private void ensureReady(){if(database==null)throw new IllegalStateException("本地数据库尚未打开");}

        private Object get(String key,String kind,Object def){
            synchronized(LOCK){
                ensureReady();
                String[] row=pref(key);
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
                boolean tasksChanged=clear||changes.containsKey("tasks");
                if(clear){
                    Cursor cursor=database.rawQuery("SELECT k FROM pref",null);
                    try{while(cursor.moveToNext())keys.add(cursor.getString(0));}finally{cursor.close();}
                    database.delete("pref",null,null);
                }
                for(Map.Entry<String,Change> entry:changes.entrySet()){
                    keys.add(entry.getKey());
                    Change change=entry.getValue();
                    if(change.remove)database.delete("pref","k=?",new String[]{entry.getKey()});
                    else putPref(entry.getKey(),change.kind,change.text);
                }
                if(tasksChanged){
                    String[] tasks=pref("tasks");
                    reindexTasks(tasks==null?null:tasks[1]);
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

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context){super(context,"review.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            db.execSQL("CREATE TABLE pref (k TEXT PRIMARY KEY, kind TEXT NOT NULL, v TEXT NOT NULL)");
            db.execSQL("CREATE TABLE task (ord INTEGER PRIMARY KEY, id INTEGER NOT NULL, title TEXT NOT NULL, created INTEGER NOT NULL, done INTEGER NOT NULL, stage INTEGER NOT NULL, json TEXT NOT NULL)");
            db.execSQL("CREATE TABLE card (task_ord INTEGER NOT NULL, pos INTEGER NOT NULL, task_id INTEGER NOT NULL, word_id TEXT NOT NULL, english TEXT NOT NULL, chinese TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(task_ord,pos))");
            db.execSQL("CREATE TABLE review_event (task_ord INTEGER NOT NULL, pos INTEGER NOT NULL, task_id INTEGER NOT NULL, at INTEGER NOT NULL, stage INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(task_ord,pos))");
            db.execSQL("CREATE TABLE relation_link (id TEXT PRIMARY KEY, a TEXT NOT NULL, b TEXT NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL, created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE relation_group (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL, created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE relation_member (group_id TEXT NOT NULL, word_id TEXT NOT NULL, PRIMARY KEY(group_id,word_id))");
            db.execSQL("CREATE INDEX idx_card_word ON card(word_id)");
            db.execSQL("CREATE INDEX idx_link_a ON relation_link(a)");
            db.execSQL("CREATE INDEX idx_link_b ON relation_link(b)");
            db.execSQL("CREATE INDEX idx_event_task ON review_event(task_id,at)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {}
    }
}
