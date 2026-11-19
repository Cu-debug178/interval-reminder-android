package com.example.reviewreminder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits the legacy tasks JSON into queryable rows without becoming the source of truth.
 * Each task and card keeps its original JSON, so a later rebuild cannot drop fields.
 */
public final class TaskIndex {
    private TaskIndex() {}

    public static final class Task {
        public final int ord;
        public final long id;
        public final String title;
        public final long created;
        public final boolean done;
        public final int stage;
        /** Exact JSON object text from the tasks array. */
        public final String json;
        public Task(int ord,long id,String title,long created,boolean done,int stage,String json){
            this.ord=ord;this.id=id;this.title=title==null?"":title;this.created=created;this.done=done;this.stage=stage;this.json=json;
        }
    }

    public static final class Card {
        public final int taskOrd;
        public final long taskId;
        public final int pos;
        public final String wordId,english,chinese,json;
        public Card(int taskOrd,long taskId,int pos,String wordId,String english,String chinese,String json){
            this.taskOrd=taskOrd;this.taskId=taskId;this.pos=pos;
            this.wordId=wordId==null?"":wordId;this.english=english==null?"":english;this.chinese=chinese==null?"":chinese;this.json=json==null?"{}":json;
        }
    }

    public static final class Event {
        public final int taskOrd;
        public final long taskId;
        public final int pos;
        public final long at;
        public final int stage;
        public final String json;
        public Event(int taskOrd,long taskId,int pos,long at,int stage,String json){
            this.taskOrd=taskOrd;this.taskId=taskId;this.pos=pos;this.at=at;this.stage=stage;this.json=json==null?"{}":json;
        }
    }

    public static final class Indexed {
        public final List<Task> tasks;
        public final List<Card> cards;
        public final List<Event> events;
        Indexed(List<Task> tasks,List<Card> cards,List<Event> events){
            this.tasks=Collections.unmodifiableList(tasks);
            this.cards=Collections.unmodifiableList(cards);
            this.events=Collections.unmodifiableList(events);
        }
    }

    public static Indexed index(String json) throws Exception {
        if(json==null||json.trim().isEmpty())json="[]";
        JSONArray tasks=new JSONArray(json);
        List<Task> taskRows=new ArrayList<>();
        List<Card> cards=new ArrayList<>();
        List<Event> events=new ArrayList<>();
        for(int i=0;i<tasks.length();i++){
            Object raw=tasks.opt(i);
            if(!(raw instanceof JSONObject))throw new IllegalArgumentException("第 "+(i+1)+" 个计划不是对象，已停止处理以免丢数据");
            JSONObject task=(JSONObject)raw;
            String text=task.toString();
            long id=task.optLong("id");
            taskRows.add(new Task(i,id,task.optString("title"),task.optLong("created"),task.optBoolean("done"),task.optInt("stage"),text));
            JSONArray words=task.optJSONArray("words");
            if(words!=null){
                for(int j=0;j<words.length();j++){
                    Object wordRaw=words.opt(j);
                    if(!(wordRaw instanceof JSONObject))throw new IllegalArgumentException("计划 "+id+" 的第 "+(j+1)+" 张单词卡无效，已停止处理以免丢数据");
                    JSONObject word=(JSONObject)wordRaw;
                    String wordId=word.optString("wordId","").trim();
                    if(wordId.isEmpty())wordId=Words.stableId(new JSONObject(word.toString()));
                    cards.add(new Card(i,id,j,wordId,word.optString("english"),word.optString("chinese"),word.toString()));
                }
            }
            JSONArray history=task.optJSONArray("history");
            if(history!=null){
                for(int j=0;j<history.length();j++){
                    Object eventRaw=history.opt(j);
                    if(!(eventRaw instanceof JSONObject))throw new IllegalArgumentException("计划 "+id+" 的第 "+(j+1)+" 条复习记录无效，已停止处理以免丢数据");
                    JSONObject event=(JSONObject)eventRaw;
                    events.add(new Event(i,id,j,event.optLong("at"),event.optInt("stage"),event.toString()));
                }
            }
        }
        return new Indexed(taskRows,cards,events);
    }

    /** Rebuilds the tasks array from the stored task documents, in original order. */
    public static JSONArray rebuild(Indexed indexed) throws Exception {
        JSONArray out=new JSONArray();
        if(indexed==null)return out;
        for(Task task:indexed.tasks)out.put(new JSONObject(task.json));
        return out;
    }
}
