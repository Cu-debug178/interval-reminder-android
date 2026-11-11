package com.example.reviewreminder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Senses {
    private Senses() {}

    static JSONArray of(JSONObject word){
        if(word==null)return new JSONArray();
        JSONArray senses=word.optJSONArray("senses");
        if(senses!=null&&senses.length()>0)return senses;
        return migrate(word);
    }

    static void ensure(JSONObject word)throws Exception{
        if(word==null)return;
        JSONArray senses=word.optJSONArray("senses");
        if(senses!=null&&senses.length()>0){
            word.put("chinese",labelJoin(senses));
            if(word.optString("example","").trim().isEmpty()){
                JSONObject first=senses.optJSONObject(0);
                if(first!=null)word.put("example",first.optString("example",""));
            }
            return;
        }
        word.put("senses",migrate(word));
    }

    static JSONArray migrate(JSONObject word){
        JSONArray out=new JSONArray();
        String chinese=word==null?"":word.optString("chinese","").trim();
        String example=word==null?"":word.optString("example","").trim();
        if(chinese.isEmpty()){
            try{out.put(sense("","",example,0));}catch(Exception ignored){}
            return out;
        }
        String[] parts=chinese.split("[；;]");
        if(parts.length<=1)parts=new String[]{chinese};
        for(String part:parts){
            String raw=part.trim();if(raw.isEmpty())continue;
            String pos="", meaning=raw;
            Matcher m=Pattern.compile("^(n\\.?|v\\.?|adj\\.?|adv\\.?|prep\\.?|conj\\.?|pron\\.?|num\\.?|art\\.?|int\\.?|vt\\.?|vi\\.?|名词|动词|形容词|副词)\\s*[:：.]?\\s*(.+)$",Pattern.CASE_INSENSITIVE).matcher(raw);
            if(m.matches()){pos=normalizePos(m.group(1));meaning=m.group(2).trim();}
            try{out.put(sense(pos,meaning,out.length()==0?example:"",0));}catch(Exception ignored){}
        }
        if(out.length()==0)try{out.put(sense("",chinese,example,0));}catch(Exception ignored){}
        return out;
    }

    static JSONObject sense(String pos,String meaning,String example,int mastery)throws Exception{
        return new JSONObject()
            .put("pos",pos==null?"":pos.trim())
            .put("meaning",meaning==null?"":meaning.trim())
            .put("example",example==null?"":example.trim())
            .put("mastery",Math.max(0,Math.min(5,mastery)));
    }

    static String label(JSONObject sense){
        if(sense==null)return "";
        String pos=sense.optString("pos","").trim();
        String meaning=sense.optString("meaning","").trim();
        if(pos.isEmpty())return meaning;
        if(meaning.isEmpty())return pos;
        return pos+" "+meaning;
    }

    static String labelJoin(JSONArray senses){
        if(senses==null||senses.length()==0)return "";
        StringBuilder b=new StringBuilder();
        for(int i=0;i<senses.length();i++){
            JSONObject s=senses.optJSONObject(i);if(s==null)continue;
            String text=label(s);if(text.isEmpty())continue;
            if(b.length()>0)b.append("；");
            b.append(text);
        }
        return b.toString();
    }

    static String normalizePos(String raw){
        if(raw==null)return "";
        String v=raw.trim().toLowerCase(Locale.US).replace("．",".");
        if(v.startsWith("n"))return "n.";
        if(v.startsWith("vt")||v.startsWith("vi")||v.equals("v")||v.startsWith("v."))return "v.";
        if(v.startsWith("adj")||v.contains("形容"))return "adj.";
        if(v.startsWith("adv")||v.contains("副词"))return "adv.";
        if(v.contains("名"))return "n.";
        if(v.contains("动"))return "v.";
        return raw.trim();
    }

    /** Returns JSONArray of {label, correct} length up to 4 for a sense quiz. */
    static JSONArray quizOptions(JSONObject targetWord,int senseIndex,JSONArray pool,Random random)throws Exception{
        JSONArray senses=of(targetWord);
        if(senseIndex<0||senseIndex>=senses.length())senseIndex=0;
        JSONObject correct=senses.optJSONObject(senseIndex);
        if(correct==null)correct=sense("","(无释义)","",0);
        List<JSONObject> options=new ArrayList<>();
        options.add(new JSONObject().put("label",label(correct)).put("correct",true).put("senseIndex",senseIndex));
        List<String> used=new ArrayList<>();
        used.add(label(correct));
        if(pool!=null){
            List<Integer> order=new ArrayList<>();
            for(int i=0;i<pool.length();i++)order.add(i);
            Collections.shuffle(order,random);
            for(int idx:order){
                if(options.size()>=4)break;
                JSONObject word=pool.optJSONObject(idx);
                if(word==null||word==targetWord)continue;
                JSONArray other=of(word);
                for(int s=0;s<other.length();s++){
                    if(options.size()>=4)break;
                    String text=label(other.optJSONObject(s));
                    if(text.isEmpty()||used.contains(text))continue;
                    used.add(text);
                    options.add(new JSONObject().put("label",text).put("correct",false).put("senseIndex",-1));
                }
            }
        }
        while(options.size()<4){
            String filler="干扰项 "+(options.size()+1);
            if(used.contains(filler))filler=filler+"·";
            used.add(filler);
            options.add(new JSONObject().put("label",filler).put("correct",false).put("senseIndex",-1));
        }
        Collections.shuffle(options,random);
        JSONArray out=new JSONArray();
        for(JSONObject o:options)out.put(o);
        return out;
    }

    static void rememberSense(JSONObject word,int senseIndex)throws Exception{
        ensure(word);
        JSONArray senses=word.getJSONArray("senses");
        if(senseIndex<0||senseIndex>=senses.length())return;
        JSONObject sense=senses.getJSONObject(senseIndex);
        int mastery=Math.min(5,sense.optInt("mastery",0)+1);
        sense.put("mastery",mastery);
        senses.put(senseIndex,sense);
        word.put("senses",senses).put("chinese",labelJoin(senses));
        Mastery.remember(word);
    }

    static void forgetSense(JSONObject word,int senseIndex)throws Exception{
        ensure(word);
        JSONArray senses=word.getJSONArray("senses");
        if(senseIndex<0||senseIndex>=senses.length())return;
        JSONObject sense=senses.getJSONObject(senseIndex);
        int mastery=Math.max(0,sense.optInt("mastery",0)-1);
        sense.put("mastery",mastery);
        senses.put(senseIndex,sense);
        word.put("senses",senses).put("chinese",labelJoin(senses));
        Mastery.forget(word);
    }
}
