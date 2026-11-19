package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private word-to-word relations stored in {@link LocalStore}. */
final class WordRelations {
    private WordRelations() {}

    static JSONObject graph(Context c){
        try{return LocalStore.inRead(c,new LocalStore.Read<JSONObject>(){public JSONObject read() throws Exception {return load();}});}
        catch(Exception e){return empty();}
    }

    static JSONArray catalog(Context c){
        try{return LocalStore.inRead(c,new LocalStore.Read<JSONArray>(){public JSONArray read(){return catalogLocked();}});}
        catch(Exception e){return new JSONArray();}
    }

    static String studyLabel(Context c,String wordId){
        try{
            JSONObject graph=graph(c);
            return WordGraph.studyLabel(graph,catalog(c),wordId);
        }catch(Exception e){return "";}
    }

    static int countFor(Context c,String wordId){
        try{JSONArray related=WordGraph.related(graph(c),catalog(c),wordId);return related.length();}
        catch(Exception e){return 0;}
    }

    static void link(Context c,final String left,final String right,final String kind,final String note) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {WordGraph.link(graph,left,right,kind,note);}});
    }

    static void linkAll(Context c,final JSONArray specs) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {
            for(int i=0;specs!=null&&i<specs.length();i++){
                JSONObject row=specs.optJSONObject(i);
                if(row==null)continue;
                WordGraph.link(graph,row.optString("left"),row.optString("right"),row.optString("kind"),row.optString("note"));
            }
        }});
    }

    static void unlink(Context c,final String id) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {WordGraph.unlink(graph,id);}});
    }

    static String addGroup(Context c,final String name,final String kind,final String wordId) throws Exception {
        final String[] id=new String[1];
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {
            id[0]=WordGraph.addGroup(graph,name,kind);
            if(wordId!=null&&!wordId.isEmpty())WordGraph.addMember(graph,id[0],wordId);
        }});
        return id[0];
    }

    static void renameGroup(Context c,final String groupId,final String name) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {WordGraph.renameGroup(graph,groupId,name);}});
    }

    static void removeGroup(Context c,final String groupId) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {WordGraph.removeGroup(graph,groupId);}});
    }

    static void addMember(Context c,final String groupId,final String wordId) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {WordGraph.addMember(graph,groupId,wordId);}});
    }

    static void removeMember(Context c,final String groupId,final String wordId) throws Exception {
        edit(c,new Edit(){public void apply(JSONObject graph) throws Exception {WordGraph.removeMember(graph,groupId,wordId);}});
    }

    static void restore(Context c,final JSONArray links,final JSONArray groups,final JSONArray members,final boolean replace) throws Exception {
        LocalStore.inTransaction(c,new LocalStore.Work(){public void run() throws Exception {
            JSONObject incoming=new JSONObject().put("links",links==null?new JSONArray():links).put("groups",groups==null?new JSONArray():groups).put("members",members==null?new JSONArray():members);
            JSONObject merged=WordGraph.merge(replace?WordGraph.empty():load(),incoming,replace);
            store(merged);
        }});
    }

    /** Throws if the graph cannot be read, so a backup does not silently omit relations. */
    static JSONObject export(Context c) throws Exception {
        return LocalStore.inRead(c,new LocalStore.Read<JSONObject>(){public JSONObject read() throws Exception {return load();}});
    }

    private interface Edit { void apply(JSONObject graph) throws Exception; }

    private static void edit(Context c,final Edit edit) throws Exception {
        LocalStore.inTransaction(c,new LocalStore.Work(){public void run() throws Exception {
            JSONObject graph=load();
            edit.apply(graph);
            store(graph);
        }});
    }

    private static JSONObject empty(){try{return WordGraph.empty();}catch(Exception e){return new JSONObject();}}

    private static JSONObject load() throws Exception {
        SQLiteDatabase db=LocalStore.database();
        JSONObject graph=WordGraph.empty();
        Cursor groups=db.rawQuery("SELECT id,name,kind,note,created FROM relation_group ORDER BY created",null);
        try{
            while(groups.moveToNext())graph.getJSONArray("groups").put(new JSONObject().put("id",groups.getString(0)).put("name",groups.getString(1)).put("kind",groups.getString(2)).put("note",groups.getString(3)).put("created",groups.getLong(4)));
        }finally{groups.close();}
        Cursor links=db.rawQuery("SELECT id,a,b,kind,note,created FROM relation_link ORDER BY created",null);
        try{
            while(links.moveToNext())graph.getJSONArray("links").put(new JSONObject().put("id",links.getString(0)).put("a",links.getString(1)).put("b",links.getString(2)).put("kind",links.getString(3)).put("note",links.getString(4)).put("created",links.getLong(5)));
        }finally{links.close();}
        Cursor members=db.rawQuery("SELECT group_id,word_id FROM relation_member",null);
        try{
            while(members.moveToNext())graph.getJSONArray("members").put(new JSONObject().put("groupId",members.getString(0)).put("wordId",members.getString(1)));
        }finally{members.close();}
        return graph;
    }

    private static void store(JSONObject graph) throws Exception {
        SQLiteDatabase db=LocalStore.database();
        db.delete("relation_member",null,null);
        db.delete("relation_link",null,null);
        db.delete("relation_group",null,null);
        SQLiteStatement group=db.compileStatement("INSERT INTO relation_group(id,name,kind,note,created) VALUES(?,?,?,?,?)");
        SQLiteStatement link=db.compileStatement("INSERT INTO relation_link(id,a,b,kind,note,created) VALUES(?,?,?,?,?,?)");
        SQLiteStatement member=db.compileStatement("INSERT INTO relation_member(group_id,word_id) VALUES(?,?)");
        try{
            JSONArray groups=graph.optJSONArray("groups");
            for(int i=0;groups!=null&&i<groups.length();i++){
                JSONObject row=groups.getJSONObject(i);
                group.bindString(1,row.getString("id"));group.bindString(2,row.getString("name"));group.bindString(3,row.getString("kind"));group.bindString(4,row.optString("note"));group.bindLong(5,row.optLong("created"));
                group.executeInsert();group.clearBindings();
            }
            JSONArray links=graph.optJSONArray("links");
            for(int i=0;links!=null&&i<links.length();i++){
                JSONObject row=links.getJSONObject(i);
                link.bindString(1,row.getString("id"));link.bindString(2,row.getString("a"));link.bindString(3,row.getString("b"));link.bindString(4,row.getString("kind"));link.bindString(5,row.optString("note"));link.bindLong(6,row.optLong("created"));
                link.executeInsert();link.clearBindings();
            }
            JSONArray members=graph.optJSONArray("members");
            for(int i=0;members!=null&&i<members.length();i++){
                JSONObject row=members.getJSONObject(i);
                member.bindString(1,row.getString("groupId"));member.bindString(2,row.getString("wordId"));
                member.executeInsert();member.clearBindings();
            }
        }finally{
            group.close();link.close();member.close();
        }
    }

    /** Newer plans win when the same word id appears more than once. */
    private static JSONArray catalogLocked(){
        JSONArray out=new JSONArray();
        Cursor cursor=LocalStore.database().rawQuery("SELECT word_id,english,chinese FROM card ORDER BY task_ord DESC,pos ASC",null);
        try{
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            while(cursor.moveToNext()){
                String id=cursor.getString(0);
                if(id==null||id.isEmpty()||!seen.add(id))continue;
                try{out.put(new JSONObject().put("wordId",id).put("english",cursor.getString(1)).put("chinese",cursor.getString(2)));}catch(Exception ignored){}
            }
        }finally{cursor.close();}
        return out;
    }
}
