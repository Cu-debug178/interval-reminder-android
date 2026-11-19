package com.example.reviewreminder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

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
        File tmp=new File(c.getFilesDir(),"draft.tmp"),target=new File(c.getFilesDir(),"word-draft.json");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(draft.toString().getBytes("UTF-8"));out.getFD().sync();}
        if(!tmp.renameTo(target))throw new IOException("无法保存草稿");
    }
    public static JSONObject draft(Context c) throws Exception {
        File f=new File(c.getFilesDir(),"word-draft.json");return f.exists()?new JSONObject(read(new FileInputStream(f))):new JSONObject().put("title","").put("words",new JSONArray()).put("photos",new JSONArray());
    }
    public static void clearDraft(Context c){new File(c.getFilesDir(),"word-draft.json").delete();}
    public static boolean hasDraft(Context c){
        File f=new File(c.getFilesDir(),"word-draft.json");
        if(!f.exists())return false;
        try{
            JSONObject draft=new JSONObject(read(new FileInputStream(f)));
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
