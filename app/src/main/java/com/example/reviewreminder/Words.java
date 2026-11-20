package com.example.reviewreminder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Words {
    private Words() {}

    public static JSONArray array(List<WordParser.Entry> entries) throws Exception {
        JSONArray out=new JSONArray();
        for(WordParser.Entry e:entries){
            JSONObject word=new JSONObject().put("english",e.english).put("chinese",e.chinese).put("example",e.example);
            word.put("wordId",stableId(word));
            out.put(word);
        }
        return out;
    }

    /** Stable across plans while keeping different meanings as separate records. */
    public static String stableId(JSONObject word){
        if(word==null)return "";
        String saved=word.optString("wordId","").trim();
        if(!saved.isEmpty())return saved;
        String source=word.optString("english","").trim().toLowerCase(Locale.US)+"\n"+word.optString("chinese","").trim();
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] hash=digest.digest(source.getBytes("UTF-8"));
            StringBuilder id=new StringBuilder("w_");
            for(int i=0;i<12;i++)id.append(String.format(Locale.US,"%02x",hash[i]));
            word.put("wordId",id.toString());
            return id.toString();
        }catch(Exception e){
            String fallback="w_"+Integer.toHexString(source.hashCode());
            try{word.put("wordId",fallback);}catch(Exception ignored){}
            return fallback;
        }
    }

    /**
     * Saved cards first, then extra cards (an unsaved draft) whose id is not already listed.
     * A card without {@code wordId} still joins under {@link #stableId}, so overlay data and a
     * draft can be linked before that field is written back.
     */
    public static JSONArray mergeCatalog(JSONArray saved,JSONArray extras) throws Exception {
        JSONArray out=new JSONArray();
        Set<String> seen=new LinkedHashSet<>();
        addCatalog(out,seen,saved);
        addCatalog(out,seen,extras);
        return out;
    }

    private static void addCatalog(JSONArray out,Set<String> seen,JSONArray words) throws Exception {
        if(words==null)return;
        for(int i=0;i<words.length();i++){
            JSONObject word=words.optJSONObject(i);
            if(word==null)continue;
            if(word.optString("english").trim().isEmpty()&&word.optString("wordId").trim().isEmpty())continue;
            JSONObject copy=new JSONObject(word.toString());
            String id=stableId(copy);
            if(id.isEmpty()||!seen.add(id))continue;
            JSONObject row=new JSONObject().put("wordId",id).put("english",copy.optString("english")).put("chinese",copy.optString("chinese")).put("example",copy.optString("example"));
            String cat=copy.optString("category","").trim();
            if(!cat.isEmpty())row.put("category",cat);
            out.put(row);
        }
    }

    /** Prefer the hinted card when it still has this id; otherwise find the card after edits or reordering. */
    public static int locate(JSONArray words,int hint,String wordId){
        if(words==null)return -1;
        if(wordId!=null&&!wordId.isEmpty()){
            if(hint>=0&&hint<words.length()){
                JSONObject word=words.optJSONObject(hint);
                if(word!=null&&wordId.equals(stableId(word)))return hint;
            }
            for(int i=0;i<words.length();i++){
                JSONObject word=words.optJSONObject(i);
                if(word!=null&&wordId.equals(stableId(word)))return i;
            }
            return -1;
        }
        return hint>=0&&hint<words.length()&&words.optJSONObject(hint)!=null?hint:-1;
    }

    public static String read(InputStream in) throws IOException {
        try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[8192];int n;while((n=source.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}
    }

    public static void writeDraft(Context c,JSONObject draft) throws Exception {
        LocalStore.inTransaction(c,()->{
            ContentValues cv=new ContentValues();
            cv.put("id","current");
            cv.put("json",draft.toString());
            cv.put("updated_at",System.currentTimeMillis());
            LocalStore.database().insertWithOnConflict("draft_store",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public static JSONObject draft(Context c) throws Exception {
        return LocalStore.inRead(c,()->{
            Cursor cur=LocalStore.database().rawQuery("SELECT json FROM draft_store WHERE id='current'",null);
            try{
                if(cur.moveToFirst()){
                    String raw=cur.getString(0);
                    if(raw!=null&&!raw.trim().isEmpty())return new JSONObject(raw);
                }
            }finally{cur.close();}
            return new JSONObject().put("title","").put("words",new JSONArray()).put("photos",new JSONArray());
        });
    }

    public static void clearDraft(Context c){
        try{LocalStore.inTransaction(c,()->LocalStore.database().delete("draft_store","id='current'",null));}
        catch(Exception ignored){}
        new File(c.getFilesDir(),"word-draft.json").delete();
    }

    public static boolean hasDraft(Context c){
        try{
            JSONObject draft=draft(c);
            JSONArray words=draft.optJSONArray("words");
            if(words==null)return false;
            for(int i=0;i<words.length();i++){
                JSONObject word=words.optJSONObject(i);
                if(word!=null&&!word.optString("english","").trim().isEmpty())return true;
            }
            return false;
        }catch(Exception e){return false;}
    }

    public static void lesson(Context c) throws Exception {
        JSONObject d=new JSONObject().put("title","Lesson 1").put("source","内置 Lesson 1 词表，待校对");
        d.put("words",array(WordParser.parse(read(c.getAssets().open("lesson1/words.tsv")))));
        d.put("photos",new JSONArray());
        writeDraft(c,d);
    }
}
