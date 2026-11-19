package com.example.reviewreminder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Undirected vocabulary relations: a meaning or form link between two words, plus named groups
 * (归纳) that collect either kind. The model is plain JSON so it can be stored, backed up and tested
 * without Android.
 */
public final class WordGraph {
    public static final String MEANING="meaning";
    public static final String FORM="form";
    private static final String[] SUFFIXES={
        "ation","tion","sion","ness","ment","able","ible","ence","ance","ious","ous","ive","ing","est","ful","less","ity","ion","er","ed","ly","es","or","al","s"
    };
    private WordGraph() {}

    public static JSONObject empty() throws Exception {
        return new JSONObject().put("links",new JSONArray()).put("groups",new JSONArray()).put("members",new JSONArray());
    }

    public static JSONObject copy(JSONObject graph) throws Exception {
        return graph==null?empty():new JSONObject(graph.toString());
    }

    public static boolean validKind(String kind){return MEANING.equals(kind)||FORM.equals(kind);}

    public static String kindLabel(String kind){return FORM.equals(kind)?"词形":"近义";}

    /** Adds an undirected link. The same pair and kind is kept once. Returns the link id. */
    public static String link(JSONObject graph,String left,String right,String kind,String note) throws Exception {
        String a=cleanId(left),b=cleanId(right);
        if(a.equals(b))throw new IllegalArgumentException("不能把一个单词关联到它自己");
        if(!validKind(kind))throw new IllegalArgumentException("关联类型无效");
        String x=a.compareTo(b)<=0?a:b;
        String y=a.compareTo(b)<=0?b:a;
        JSONArray links=links(graph);
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link!=null&&x.equals(link.optString("a"))&&y.equals(link.optString("b"))&&kind.equals(link.optString("kind")))return link.optString("id");
        }
        String id="r_"+UUID.randomUUID().toString();
        links.put(new JSONObject().put("id",id).put("a",x).put("b",y).put("kind",kind).put("note",trim(note,80)).put("created",System.currentTimeMillis()));
        return id;
    }

    public static boolean unlink(JSONObject graph,String id) throws Exception {
        if(id==null||id.isEmpty())return false;
        JSONArray links=links(graph),next=new JSONArray();
        boolean removed=false;
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link==null)continue;
            if(id.equals(link.optString("id")))removed=true;
            else next.put(link);
        }
        graph.put("links",next);
        return removed;
    }

    public static String addGroup(JSONObject graph,String name,String kind) throws Exception {
        String title=name==null?"":name.trim();
        if(title.isEmpty())throw new IllegalArgumentException("请填写归纳名称");
        if(title.length()>24)title=title.substring(0,24);
        if(!validKind(kind))throw new IllegalArgumentException("归纳类型无效");
        String id="g_"+UUID.randomUUID().toString();
        groups(graph).put(new JSONObject().put("id",id).put("name",title).put("kind",kind).put("note","").put("created",System.currentTimeMillis()));
        return id;
    }

    public static void renameGroup(JSONObject graph,String groupId,String name) throws Exception {
        JSONObject group=group(graph,groupId);
        if(group==null)throw new IllegalArgumentException("归纳不存在");
        String title=name==null?"":name.trim();
        if(title.isEmpty())throw new IllegalArgumentException("请填写归纳名称");
        if(title.length()>24)title=title.substring(0,24);
        group.put("name",title);
    }

    public static void removeGroup(JSONObject graph,String groupId) throws Exception {
        JSONArray groups=groups(graph),next=new JSONArray();
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);
            if(group!=null&&!groupId.equals(group.optString("id")))next.put(group);
        }
        graph.put("groups",next);
        JSONArray members=members(graph),kept=new JSONArray();
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member!=null&&!groupId.equals(member.optString("groupId")))kept.put(member);
        }
        graph.put("members",kept);
    }

    public static void addMember(JSONObject graph,String groupId,String wordId) throws Exception {
        if(group(graph,groupId)==null)throw new IllegalArgumentException("归纳不存在");
        String id=cleanId(wordId);
        JSONArray members=members(graph);
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member!=null&&groupId.equals(member.optString("groupId"))&&id.equals(member.optString("wordId")))return;
        }
        members.put(new JSONObject().put("groupId",groupId).put("wordId",id));
    }

    public static void removeMember(JSONObject graph,String groupId,String wordId) throws Exception {
        JSONArray members=members(graph),next=new JSONArray();
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member==null)continue;
            if(groupId.equals(member.optString("groupId"))&&wordId!=null&&wordId.equals(member.optString("wordId")))continue;
            next.put(member);
        }
        graph.put("members",next);
    }

    public static JSONArray linksFor(JSONObject graph,String wordId) throws Exception {
        JSONArray out=new JSONArray();
        if(wordId==null||wordId.isEmpty()||graph==null)return out;
        JSONArray links=links(graph);
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link==null)continue;
            if(wordId.equals(link.optString("a"))||wordId.equals(link.optString("b")))out.put(new JSONObject(link.toString()));
        }
        return out;
    }

    public static JSONArray groupsFor(JSONObject graph,String wordId) throws Exception {
        JSONArray out=new JSONArray();
        if(graph==null||wordId==null)return out;
        JSONArray members=members(graph);
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member==null||!wordId.equals(member.optString("wordId")))continue;
            JSONObject group=group(graph,member.optString("groupId"));
            if(group!=null)out.put(new JSONObject(group.toString()));
        }
        return out;
    }

    public static JSONArray membersOf(JSONObject graph,String groupId) {
        JSONArray out=new JSONArray();
        if(graph==null||groupId==null)return out;
        JSONArray members=graph.optJSONArray("members");
        if(members==null)return out;
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member!=null&&groupId.equals(member.optString("groupId")))out.put(member.optString("wordId"));
        }
        return out;
    }

    /**
     * Other words to show while studying. Direct links win over a shared group when both exist.
     * Each item is {@code {wordId, english, chinese, kind, via, note}}.
     */
    public static JSONArray related(JSONObject graph,JSONArray catalog,String wordId) throws Exception {
        JSONArray out=new JSONArray();
        if(graph==null||wordId==null||wordId.isEmpty())return out;
        Map<String,JSONObject> seen=new LinkedHashMap<>();
        JSONArray links=linksFor(graph,wordId);
        for(int i=0;i<links.length();i++){
            JSONObject link=links.getJSONObject(i);
            String other=wordId.equals(link.optString("a"))?link.optString("b"):link.optString("a");
            JSONObject row=describe(catalog,other).put("kind",link.optString("kind")).put("via","link").put("note",link.optString("note")).put("linkId",link.optString("id"));
            seen.put(other,row);
        }
        JSONArray groups=groupsFor(graph,wordId);
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.getJSONObject(i);
            JSONArray members=membersOf(graph,group.optString("id"));
            for(int j=0;j<members.length();j++){
                String other=members.optString(j);
                if(other.isEmpty()||other.equals(wordId)||seen.containsKey(other))continue;
                seen.put(other,describe(catalog,other).put("kind",group.optString("kind")).put("via","group").put("note",group.optString("name")).put("groupId",group.optString("id")));
            }
        }
        for(JSONObject row:seen.values())out.put(row);
        return out;
    }

    public static String studyLabel(JSONObject graph,JSONArray catalog,String wordId) throws Exception {
        JSONArray related=related(graph,catalog,wordId);
        if(related.length()==0)return "";
        StringBuilder meaning=new StringBuilder(),form=new StringBuilder();
        int meaningCount=0,formCount=0;
        for(int i=0;i<related.length();i++){
            JSONObject row=related.getJSONObject(i);
            String name=row.optString("english");
            if(name.isEmpty())name=row.optString("chinese");
            if(name.isEmpty())continue;
            if(FORM.equals(row.optString("kind"))){if(formCount<3){if(form.length()>0)form.append("、");form.append(name);}formCount++;}
            else{if(meaningCount<3){if(meaning.length()>0)meaning.append("、");meaning.append(name);}meaningCount++;}
        }
        StringBuilder label=new StringBuilder();
        if(meaning.length()>0)label.append("近义 ").append(meaning);
        if(form.length()>0){if(label.length()>0)label.append(" · ");label.append("词形 ").append(form);}
        if(label.length()==0)return "";
        if(meaningCount>3||formCount>3)label.append("…");
        String text=label.toString();
        return text.length()>42?text.substring(0,41)+"…":text;
    }

    /**
     * Offline form suggestions from the existing catalog: shared derivation or a small spelling
     * distance. Nothing here needs a network, and the caller decides whether to keep a suggestion.
     */
    public static JSONArray suggestForm(JSONArray catalog,String wordId,String english,int limit) throws Exception {
        String self=letters(english);
        JSONArray out=new JSONArray();
        if(self.length()<3||catalog==null)return out;
        List<JSONObject> found=new ArrayList<>();
        for(int i=0;i<catalog.length();i++){
            JSONObject card=catalog.optJSONObject(i);
            if(card==null)continue;
            String id=card.optString("wordId");
            if(id.isEmpty()||id.equals(wordId))continue;
            String other=letters(card.optString("english"));
            if(other.length()<3||other.equals(self))continue;
            String reason=formReason(self,other);
            if(reason==null)continue;
            int score=score(self,other,reason);
            found.add(new JSONObject(card.toString()).put("reason",reason).put("score",score));
        }
        Collections.sort(found,new Comparator<JSONObject>(){
            public int compare(JSONObject a,JSONObject b){
                int score=b.optInt("score")-a.optInt("score");
                if(score!=0)return score;
                return a.optString("english").compareToIgnoreCase(b.optString("english"));
            }
        });
        int cap=limit<=0?8:Math.min(limit,12);
        for(int i=0;i<found.size()&&i<cap;i++){
            JSONObject row=found.get(i);
            row.remove("score");
            out.put(row);
        }
        return out;
    }

    /** Replace drops the current graph. Merge keeps existing ids and skips duplicate pairs. */
    public static JSONObject merge(JSONObject current,JSONObject incoming,boolean replace) throws Exception {
        JSONObject base=replace?empty():copy(current==null?empty():current);
        if(incoming==null)return base;
        JSONArray groups=incoming.optJSONArray("groups");
        if(groups!=null){
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null||group.optString("id").isEmpty()||group.optString("name").trim().isEmpty()||!validKind(group.optString("kind"))){
                    throw new IllegalArgumentException("归纳数据无效");
                }
                if(group(base,group.optString("id"))==null)groups(base).put(new JSONObject(group.toString()));
            }
        }
        JSONArray members=incoming.optJSONArray("members");
        if(members!=null){
            for(int i=0;i<members.length();i++){
                JSONObject member=members.optJSONObject(i);
                if(member==null||member.optString("groupId").isEmpty()||member.optString("wordId").isEmpty())throw new IllegalArgumentException("归纳成员无效");
                if(group(base,member.optString("groupId"))==null)throw new IllegalArgumentException("归纳成员指向不存在的组");
                addMember(base,member.optString("groupId"),member.optString("wordId"));
            }
        }
        JSONArray links=incoming.optJSONArray("links");
        if(links!=null){
            for(int i=0;i<links.length();i++){
                JSONObject link=links.optJSONObject(i);
                if(link==null)throw new IllegalArgumentException("词汇关系无效");
                String id=link.optString("id");
                String a=link.optString("a"),b=link.optString("b"),kind=link.optString("kind");
                if(a.isEmpty()||b.isEmpty()||a.equals(b)||!validKind(kind))throw new IllegalArgumentException("词汇关系无效");
                if(!id.isEmpty()&&findLink(base,id)!=null)continue;
                String x=a.compareTo(b)<=0?a:b,y=a.compareTo(b)<=0?b:a;
                boolean duplicate=false;
                JSONArray have=links(base);
                for(int j=0;j<have.length();j++){
                    JSONObject old=have.optJSONObject(j);
                    if(old!=null&&x.equals(old.optString("a"))&&y.equals(old.optString("b"))&&kind.equals(old.optString("kind"))){duplicate=true;break;}
                }
                if(duplicate)continue;
                if(id.isEmpty())id="r_"+UUID.randomUUID().toString();
                have.put(new JSONObject().put("id",id).put("a",x).put("b",y).put("kind",kind).put("note",trim(link.optString("note"),80)).put("created",link.optLong("created",System.currentTimeMillis())));
            }
        }
        return base;
    }

    private static String formReason(String a,String b){
        String shorter=a.length()<=b.length()?a:b;
        String longer=a.length()<=b.length()?b:a;
        if(longer.startsWith(shorter)){
            String rest=longer.substring(shorter.length());
            if(suffix(rest))return "词形派生";
            if(shorter.length()>=4&&rest.length()>=1&&rest.length()<=6)return "词形包含";
        }
        if(a.length()>=5&&b.length()>=5&&Math.abs(a.length()-b.length())<=2){
            int distance=distance(a,b);
            if(distance>=1&&distance<=2)return "拼写相近";
        }
        return null;
    }

    private static boolean suffix(String rest){
        if(rest.isEmpty())return false;
        for(String suffix:SUFFIXES)if(suffix.equals(rest))return true;
        return false;
    }

    private static int score(String a,String b,String reason){
        if("词形派生".equals(reason))return 100-Math.abs(a.length()-b.length());
        if("词形包含".equals(reason))return 70-Math.abs(a.length()-b.length());
        return 50-distance(a,b)*10;
    }

    static int distance(String a,String b){
        int n=a.length(),m=b.length();
        int[] row=new int[m+1];
        for(int j=0;j<=m;j++)row[j]=j;
        for(int i=1;i<=n;i++){
            int prev=row[0];
            row[0]=i;
            for(int j=1;j<=m;j++){
                int cur=row[j];
                int cost=a.charAt(i-1)==b.charAt(j-1)?0:1;
                row[j]=Math.min(Math.min(row[j]+1,row[j-1]+1),prev+cost);
                prev=cur;
            }
        }
        return row[m];
    }

    private static JSONObject describe(JSONArray catalog,String wordId) throws Exception {
        JSONObject row=new JSONObject().put("wordId",wordId).put("english","").put("chinese","");
        if(catalog==null)return row;
        for(int i=0;i<catalog.length();i++){
            JSONObject card=catalog.optJSONObject(i);
            if(card!=null&&wordId.equals(card.optString("wordId"))){
                row.put("english",card.optString("english")).put("chinese",card.optString("chinese"));
                break;
            }
        }
        return row;
    }

    private static JSONObject findLink(JSONObject graph,String id){
        JSONArray links=graph.optJSONArray("links");
        if(links==null)return null;
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link!=null&&id.equals(link.optString("id")))return link;
        }
        return null;
    }

    private static JSONObject group(JSONObject graph,String id){
        JSONArray groups=graph.optJSONArray("groups");
        if(groups==null||id==null)return null;
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);
            if(group!=null&&id.equals(group.optString("id")))return group;
        }
        return null;
    }

    private static JSONArray links(JSONObject graph) throws Exception {
        JSONArray links=graph.optJSONArray("links");
        if(links==null){links=new JSONArray();graph.put("links",links);}
        return links;
    }

    private static JSONArray groups(JSONObject graph) throws Exception {
        JSONArray groups=graph.optJSONArray("groups");
        if(groups==null){groups=new JSONArray();graph.put("groups",groups);}
        return groups;
    }

    private static JSONArray members(JSONObject graph) throws Exception {
        JSONArray members=graph.optJSONArray("members");
        if(members==null){members=new JSONArray();graph.put("members",members);}
        return members;
    }

    private static String cleanId(String value){
        String id=value==null?"":value.trim();
        if(id.isEmpty())throw new IllegalArgumentException("请选择单词");
        return id;
    }

    private static String trim(String value,int max){
        String text=value==null?"":value.trim();
        return text.length()<=max?text:text.substring(0,max);
    }

    static String letters(String value){
        if(value==null)return "";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++){
            char c=Character.toLowerCase(value.charAt(i));
            if(c>='a'&&c<='z')out.append(c);
        }
        return out.toString();
    }

}
