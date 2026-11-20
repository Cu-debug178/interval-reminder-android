package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private word-to-word relations and mind-map groups stored in {@link LocalStore}. */
final class WordRelations {
    private WordRelations() {}

    static JSONObject graph(Context c){
        try{return LocalStore.inRead(c,()->load());}
        catch(Exception e){return empty();}
    }

    /** Saved cards only. An unsaved draft is merged by the caller that is editing it, not here. */
    static JSONArray catalog(Context c){
        try{return LocalStore.inRead(c,()->catalogLocked());}
        catch(Exception e){return new JSONArray();}
    }

    static String studyLabel(Context c,String wordId){
        try{
            return LocalStore.inRead(c,()->WordGraph.studyLabel(load(),catalogLocked(),wordId));
        }catch(Exception e){return "";}
    }

    static int countFor(Context c,String wordId){
        try{
            return LocalStore.inRead(c,()->WordGraph.related(load(),catalogLocked(),wordId).length());
        }catch(Exception e){return 0;}
    }

    static java.util.Set<Long> tasksWithRelations(Context c){
        java.util.Set<Long> out=new java.util.HashSet<>();
        try{
            return LocalStore.inRead(c,()->{
                SQLiteDatabase db=LocalStore.database();
                Cursor cur=db.rawQuery(
                    "SELECT DISTINCT c.task_id FROM card c WHERE "
                        +"EXISTS(SELECT 1 FROM relation_member m WHERE m.word_id=c.word_id) OR "
                        +"EXISTS(SELECT 1 FROM relation_link l WHERE l.a=c.word_id OR l.b=c.word_id)",
                    null);
                try{while(cur.moveToNext())out.add(cur.getLong(0));}finally{cur.close();}
                return out;
            });
        }catch(Exception e){return out;}
    }

    static boolean taskHasRelations(Context c,long taskId){
        try{
            return LocalStore.inRead(c,()->{
                SQLiteDatabase db=LocalStore.database();
                Cursor cur=db.rawQuery(
                    "SELECT 1 FROM card c WHERE c.task_id=? AND ("
                        +"EXISTS(SELECT 1 FROM relation_member m WHERE m.word_id=c.word_id) OR "
                        +"EXISTS(SELECT 1 FROM relation_link l WHERE l.a=c.word_id OR l.b=c.word_id)"
                    +") LIMIT 1",
                    new String[]{Long.toString(taskId)});
                try{return cur.moveToFirst();}finally{cur.close();}
            });
        }catch(Exception e){return false;}
    }

    static String link(Context c,final String left,final String right,final String kind,final String note) throws Exception {
        final String[] id=new String[1];
        edit(c,graph->{id[0]=WordGraph.link(graph,left,right,kind,note);});
        return id[0];
    }

    static void linkAll(Context c,final JSONArray specs) throws Exception {
        edit(c,graph->{
            for(int i=0;specs!=null&&i<specs.length();i++){
                JSONObject row=specs.optJSONObject(i);
                if(row==null)continue;
                WordGraph.link(graph,row.optString("left"),row.optString("right"),row.optString("kind"),row.optString("note"));
            }
        });
    }

    static void unlink(Context c,final String id) throws Exception {
        edit(c,graph->WordGraph.unlink(graph,id));
    }

    static String addGroup(Context c,final String name,final String kind,final String wordId) throws Exception {
        return addGroup(c,name,kind,wordId,true);
    }

    static String addGroup(Context c,final String name,final String kind,final String wordId,final boolean asRoot) throws Exception {
        final String[] id=new String[1];
        edit(c,graph->{
            id[0]=WordGraph.addGroup(graph,name,kind);
            if(wordId!=null&&!wordId.isEmpty())WordGraph.addMember(graph,id[0],wordId,asRoot,0);
        });
        return id[0];
    }

    static void renameGroup(Context c,final String groupId,final String name) throws Exception {
        edit(c,graph->WordGraph.renameGroup(graph,groupId,name));
    }

    static void removeGroup(Context c,final String groupId) throws Exception {
        edit(c,graph->WordGraph.removeGroup(graph,groupId));
    }

    static void addMember(Context c,final String groupId,final String wordId) throws Exception {
        edit(c,graph->WordGraph.addMember(graph,groupId,wordId));
    }

    static void addMember(Context c,final String groupId,final String wordId,final boolean isRoot,final int branch) throws Exception {
        edit(c,graph->WordGraph.addMember(graph,groupId,wordId,isRoot,branch));
    }

    static void setMemberRoot(Context c,final String groupId,final String wordId,final boolean isRoot) throws Exception {
        edit(c,graph->WordGraph.setMemberRoot(graph,groupId,wordId,isRoot));
    }

    static void setMemberBranch(Context c,final String groupId,final String wordId,final int branch) throws Exception {
        edit(c,graph->WordGraph.setMemberBranch(graph,groupId,wordId,branch));
    }

    static void removeMember(Context c,final String groupId,final String wordId) throws Exception {
        edit(c,graph->WordGraph.removeMember(graph,groupId,wordId));
    }

    /** Resolves imported groups/links against current saved cards + extra cards inside one transaction. */
    static int[] applyImported(Context c,final JSONArray extraCards,final JSONArray groups,final JSONArray links) throws Exception {
        final int[][] counts=new int[1][];
        LocalStore.inTransaction(c,()->{
            JSONObject graph=load();
            JSONArray catalog=Words.mergeCatalog(catalogLocked(),extraCards);
            counts[0]=WordGraph.applyImportedRelations(graph,catalog,groups,links);
            store(graph);
        });
        return counts[0];
    }

    static void restore(Context c,final JSONArray links,final JSONArray groups,final JSONArray members,final boolean replace) throws Exception {
        LocalStore.inTransaction(c,()->{
            JSONObject incoming=new JSONObject().put("links",links==null?new JSONArray():links).put("groups",groups==null?new JSONArray():groups).put("members",members==null?new JSONArray():members);
            JSONObject merged=WordGraph.merge(replace?WordGraph.empty():load(),incoming,replace);
            store(merged);
        });
    }

    /** Throws if the graph cannot be read, so a backup does not silently omit relations. */
    static JSONObject export(Context c) throws Exception {
        return LocalStore.inRead(c,()->load());
    }

    private interface Edit { void apply(JSONObject graph) throws Exception; }

    private static void edit(Context c,final Edit edit) throws Exception {
        LocalStore.inTransaction(c,()->{
            JSONObject graph=load();
            edit.apply(graph);
            store(graph);
        });
    }

    private static JSONObject empty(){try{return WordGraph.empty();}catch(Exception e){return new JSONObject();}}

    private static JSONObject load() throws Exception {
        SQLiteDatabase db=LocalStore.database();
        JSONObject graph=WordGraph.empty();
        Cursor groups=db.rawQuery("SELECT id,name,kind,note,created FROM relation_group ORDER BY created",null);
        try{
            while(groups.moveToNext())graph.getJSONArray("groups").put(new JSONObject().put("id",groups.getString(0)).put("name",groups.getString(1)).put("kind",groups.getString(2)).put("note",groups.getString(3)).put("created",groups.getLong(4)));
        }finally{groups.close();}
        Cursor links=db.rawQuery("SELECT id,a,b,kind,note,directed,created FROM relation_link ORDER BY created",null);
        try{
            while(links.moveToNext())graph.getJSONArray("links").put(new JSONObject().put("id",links.getString(0)).put("a",links.getString(1)).put("b",links.getString(2)).put("kind",links.getString(3)).put("note",links.getString(4)).put("directed",links.getInt(5)!=0).put("created",links.getLong(6)));
        }finally{links.close();}
        Cursor members=db.rawQuery("SELECT group_id,word_id,is_root,branch,pos FROM relation_member ORDER BY group_id ASC,pos ASC",null);
        try{
            while(members.moveToNext())graph.getJSONArray("members").put(new JSONObject().put("groupId",members.getString(0)).put("wordId",members.getString(1)).put("isRoot",members.getInt(2)!=0).put("branch",members.getInt(3)).put("pos",members.getInt(4)));
        }finally{members.close();}
        return graph;
    }

    private static void store(JSONObject graph) throws Exception {
        SQLiteDatabase db=LocalStore.database();
        db.delete("relation_member",null,null);
        db.delete("relation_link",null,null);
        db.delete("relation_group",null,null);
        SQLiteStatement group=db.compileStatement("INSERT INTO relation_group(id,name,kind,note,created) VALUES(?,?,?,?,?)");
        SQLiteStatement link=db.compileStatement("INSERT INTO relation_link(id,a,b,kind,note,directed,created) VALUES(?,?,?,?,?,?,?)");
        SQLiteStatement member=db.compileStatement("INSERT INTO relation_member(group_id,word_id,is_root,branch,pos) VALUES(?,?,?,?,?)");
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
                link.bindString(1,row.getString("id"));link.bindString(2,row.getString("a"));link.bindString(3,row.getString("b"));link.bindString(4,row.getString("kind"));link.bindString(5,row.optString("note"));link.bindLong(6,row.optBoolean("directed",false)?1:0);link.bindLong(7,row.optLong("created"));
                link.executeInsert();link.clearBindings();
            }
            JSONArray members=graph.optJSONArray("members");
            for(int i=0;members!=null&&i<members.length();i++){
                JSONObject row=members.getJSONObject(i);
                member.bindString(1,row.getString("groupId"));member.bindString(2,row.getString("wordId"));member.bindLong(3,row.optBoolean("isRoot",false)?1:0);member.bindLong(4,row.optInt("branch",0));member.bindLong(5,row.optInt("pos",i));
                member.executeInsert();member.clearBindings();
            }
        }finally{
            group.close();link.close();member.close();
        }
    }

    /** Newer plans win when the same word id appears more than once. */
    private static JSONArray catalogLocked(){
        JSONArray out=new JSONArray();
        Cursor cursor=LocalStore.database().rawQuery("SELECT c.word_id,c.english,c.chinese,c.example,c.category FROM card c JOIN task t ON t.id=c.task_id ORDER BY t.ord DESC,c.pos ASC",null);
        try{
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            while(cursor.moveToNext()){
                String id=cursor.getString(0);
                if(id==null||id.isEmpty()||!seen.add(id))continue;
                try{
                    JSONObject row=new JSONObject().put("wordId",id).put("english",cursor.getString(1)).put("chinese",cursor.getString(2)).put("example",cursor.getString(3));
                    String cat=cursor.getString(4);
                    if(cat!=null&&!cat.trim().isEmpty())row.put("category",cat.trim());
                    out.put(row);
                }catch(Exception ignored){}
            }
        }finally{cursor.close();}
        return out;
    }
}
