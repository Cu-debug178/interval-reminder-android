package com.example.reviewreminder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copies a 0.8.0 (and older) {@code review_data} preference map without dropping entries.
 * Word relations are not invented from legacy connection images.
 */
public final class LegacyMigration {
    private LegacyMigration() {}

    public static final class Value {
        public final String kind;
        public final String text;
        public Value(String kind,String text){
            if(kind==null||text==null)throw new IllegalArgumentException("偏好项不完整");
            this.kind=kind;this.text=text;
        }
        @Override public boolean equals(Object other){
            if(!(other instanceof Value))return false;
            Value value=(Value)other;
            return kind.equals(value.kind)&&text.equals(value.text);
        }
        @Override public int hashCode(){return kind.hashCode()*31+text.hashCode();}
    }

    public static final class Bundle {
        public final Map<String,Value> prefs;
        public final TaskIndex.Indexed index;
        /** Always empty. Pictures stay on the word; they are not relations. */
        public final JSONObject graph;
        Bundle(Map<String,Value> prefs,TaskIndex.Indexed index,JSONObject graph){
            this.prefs=prefs;this.index=index;this.graph=graph;
        }
    }

    public static Value encode(Object value) throws Exception {
        if(value instanceof String)return new Value("string",(String)value);
        if(value instanceof Integer)return new Value("int",Integer.toString((Integer)value));
        if(value instanceof Long)return new Value("long",Long.toString((Long)value));
        if(value instanceof Boolean)return new Value("boolean",((Boolean)value)?"true":"false");
        if(value instanceof Float)return new Value("float",Float.toString((Float)value));
        if(value instanceof Collection){
            JSONArray array=new JSONArray();
            for(Object item:(Collection<?>)value){
                if(item!=null)array.put(String.valueOf(item));
            }
            return new Value("set",array.toString());
        }
        throw new IllegalArgumentException("无法迁移的偏好类型："+(value==null?"null":value.getClass().getName()));
    }

    /**
     * Copies every entry, indexes tasks from the verbatim {@code tasks} string, and leaves the
     * relation graph empty. A null value is rejected by {@link #encode} before it gets here.
     */
    public static Bundle migrate(Map<String,Value> legacy) throws Exception {
        Map<String,Value> prefs=new LinkedHashMap<>();
        if(legacy!=null){
            for(Map.Entry<String,Value> entry:legacy.entrySet()){
                if(entry.getKey()==null||entry.getKey().isEmpty()||entry.getValue()==null){
                    throw new IllegalArgumentException("偏好项缺少名称或内容，已停止迁移");
                }
                prefs.put(entry.getKey(),new Value(entry.getValue().kind,entry.getValue().text));
            }
        }
        Value tasks=prefs.get("tasks");
        if(tasks!=null&&!"string".equals(tasks.kind))throw new IllegalArgumentException("计划数据不是文本，已停止迁移");
        TaskIndex.Indexed index=TaskIndex.index(tasks==null?"[]":tasks.text);
        return new Bundle(prefs,index,WordGraph.empty());
    }
}
