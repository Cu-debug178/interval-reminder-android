package com.example.reviewreminder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vocabulary relation graph: word-to-word links (近义 / 扩 / 同根 / 反义 / 词形) plus named
 * mind-map groups (主题归纳) whose members can be marked as core branch roots and clustered by
 * branch colour. Pure JSON logic so it can be stored in SQLite, backed up, and unit-tested
 * without Android.
 */
public final class WordGraph {
    public static final String MEANING="meaning";
    public static final String DERIVE="derive";
    public static final String ROOT="root";
    public static final String ANTONYM="antonym";
    public static final String FORM="form";
    public static final String[] KINDS={MEANING,DERIVE,ROOT,ANTONYM,FORM};
    public static final String[] KIND_LABELS={"近义","扩","同根","反义","词形"};
    public static final String[] KIND_FULL_LABELS={"近义（意思相近）","扩（派生/词族扩展）","同根（共享词根）","反义（意思相反）","词形（拼写/形近）"};

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

    public static boolean validKind(String kind){
        return MEANING.equals(kind)||DERIVE.equals(kind)||ROOT.equals(kind)||ANTONYM.equals(kind)||FORM.equals(kind);
    }

    /** Maps Chinese labels or English aliases onto a canonical kind; defaults to {@link #MEANING}. */
    public static String normalizeKind(String raw){
        if(raw==null)return MEANING;
        String s=raw.trim().toLowerCase(Locale.ROOT);
        if(validKind(s))return s;
        if(s.contains("扩")||s.contains("派生")||s.contains("衍生")||s.equals("expand")||s.equals("derivation")||s.equals("derivative"))return DERIVE;
        if(s.contains("同根")||s.contains("词根")||s.equals("etymology")||s.equals("cognate"))return ROOT;
        if(s.contains("反义")||s.contains("相反")||s.equals("opposite")||s.equals("ant"))return ANTONYM;
        if(s.contains("形近")||s.contains("词形")||s.contains("拼写")||s.equals("lookalike")||s.equals("similar_form"))return FORM;
        return MEANING;
    }

    public static String kindLabel(String kind){
        if(DERIVE.equals(kind))return "扩";
        if(ROOT.equals(kind))return "同根";
        if(ANTONYM.equals(kind))return "反义";
        if(FORM.equals(kind))return "词形";
        return "近义";
    }

    /** Short edge pill label drawn on the visual mind-map canvas. */
    public static String edgeLabel(JSONObject link){
        if(link==null)return "";
        String note=link.optString("note","").trim();
        if(!note.isEmpty()&&note.length()<=6)return note;
        return kindLabel(link.optString("kind"));
    }

    public static boolean isDirectedKind(String kind){return DERIVE.equals(kind);}

    /** Adds a link between two words. Symmetric kinds are canonicalised; derive keeps parent→child order. */
    public static String link(JSONObject graph,String left,String right,String kind,String note) throws Exception {
        return link(graph,left,right,kind,note,isDirectedKind(kind));
    }

    public static String link(JSONObject graph,String left,String right,String kind,String note,boolean directed) throws Exception {
        String a=cleanId(left),b=cleanId(right);
        if(a.equals(b))throw new IllegalArgumentException("不能把一个单词关联到它自己");
        if(!validKind(kind))throw new IllegalArgumentException("关联类型无效");
        String x=directed?a:(a.compareTo(b)<=0?a:b);
        String y=directed?b:(a.compareTo(b)<=0?b:a);
        JSONArray links=links(graph);
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link==null||!kind.equals(link.optString("kind")))continue;
            String la=link.optString("a"),lb=link.optString("b");
            if((x.equals(la)&&y.equals(lb))||(x.equals(lb)&&y.equals(la)))return link.optString("id");
        }
        String id="r_"+UUID.randomUUID().toString();
        links.put(new JSONObject()
            .put("id",id).put("a",x).put("b",y).put("kind",kind)
            .put("note",trim(note,80)).put("directed",directed)
            .put("created",System.currentTimeMillis()));
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
        if(title.length()>32)title=title.substring(0,32);
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
        if(title.length()>32)title=title.substring(0,32);
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
        addMember(graph,groupId,wordId,false,-1);
    }

    public static void addMember(JSONObject graph,String groupId,String wordId,boolean isRoot,int branch) throws Exception {
        if(group(graph,groupId)==null)throw new IllegalArgumentException("归纳不存在");
        String id=cleanId(wordId);
        JSONArray members=members(graph);
        int count=0;
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member==null||!groupId.equals(member.optString("groupId")))continue;
            count++;
            if(id.equals(member.optString("wordId"))){
                if(isRoot)member.put("isRoot",true);
                if(branch>=0)member.put("branch",branch);
                return;
            }
        }
        members.put(new JSONObject()
            .put("groupId",groupId).put("wordId",id)
            .put("isRoot",isRoot).put("branch",Math.max(0,branch)).put("pos",count));
    }

    public static void setMemberRoot(JSONObject graph,String groupId,String wordId,boolean isRoot) throws Exception {
        JSONArray members=members(graph);
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member!=null&&groupId.equals(member.optString("groupId"))&&wordId.equals(member.optString("wordId"))){
                member.put("isRoot",isRoot);
                return;
            }
        }
    }

    public static void setMemberBranch(JSONObject graph,String groupId,String wordId,int branch) throws Exception {
        JSONArray members=members(graph);
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member!=null&&groupId.equals(member.optString("groupId"))&&wordId.equals(member.optString("wordId"))){
                member.put("branch",Math.max(0,branch));
                return;
            }
        }
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
        JSONArray meta=memberMetaOf(graph,groupId);
        for(int i=0;i<meta.length();i++){
            JSONObject m=meta.optJSONObject(i);
            if(m!=null)out.put(m.optString("wordId"));
        }
        return out;
    }

    public static JSONArray memberMetaOf(JSONObject graph,String groupId) {
        JSONArray out=new JSONArray();
        if(graph==null||groupId==null)return out;
        JSONArray members=graph.optJSONArray("members");
        if(members==null)return out;
        List<JSONObject> list=new ArrayList<>();
        for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member!=null&&groupId.equals(member.optString("groupId")))list.add(member);
        }
        Collections.sort(list,(a,b)->Integer.compare(a.optInt("pos",0),b.optInt("pos",0)));
        for(JSONObject m:list)out.put(m);
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
            JSONObject row=describe(catalog,other).put("kind",link.optString("kind")).put("via","link").put("note",link.optString("note")).put("linkId",link.optString("id")).put("directed",link.optBoolean("directed",false)).put("outgoing",wordId.equals(link.optString("a")));
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
        Map<String,List<String>> buckets=new LinkedHashMap<>();
        for(String k:KINDS)buckets.put(k,new ArrayList<String>());
        for(int i=0;i<related.length();i++){
            JSONObject row=related.getJSONObject(i);
            String name=row.optString("english");
            if(name.isEmpty())name=row.optString("chinese");
            if(name.isEmpty())continue;
            String kind=row.optString("kind",MEANING);
            List<String> list=buckets.get(kind);
            if(list==null)list=buckets.get(MEANING);
            list.add(name);
        }
        StringBuilder label=new StringBuilder();
        boolean overflow=false;
        for(String k:KINDS){
            List<String> list=buckets.get(k);
            if(list==null||list.isEmpty())continue;
            if(label.length()>0)label.append(" · ");
            label.append(kindLabel(k)).append(' ');
            int cap=Math.min(3,list.size());
            for(int i=0;i<cap;i++){
                if(i>0)label.append("、");
                label.append(list.get(i));
            }
            if(list.size()>3)overflow=true;
        }
        if(label.length()==0)return "";
        if(overflow)label.append("…");
        String text=label.toString();
        return text.length()>46?text.substring(0,45)+"…":text;
    }

    /**
     * Resolves imported groups and links (keyed by English spelling) against a card catalog and
     * writes them into {@code graph}. Returns {@code int[]{groupsAdded, linksAdded}}.
     */
    public static int[] applyImportedRelations(JSONObject graph,JSONArray catalog,JSONArray importedGroups,JSONArray importedLinks) throws Exception {
        Map<String,String> byEnglish=new HashMap<>();
        if(catalog!=null){
            for(int i=0;i<catalog.length();i++){
                JSONObject card=catalog.optJSONObject(i);
                if(card==null)continue;
                String key=letters(card.optString("english"));
                String id=Words.stableId(card);
                if(!key.isEmpty()&&!id.isEmpty()&&!byEnglish.containsKey(key))byEnglish.put(key,id);
            }
        }
        int groupsAdded=0,linksAdded=0;
        if(importedGroups!=null){
            for(int i=0;i<importedGroups.length();i++){
                JSONObject g=importedGroups.optJSONObject(i);
                if(g==null)continue;
                String name=g.optString("name","").trim();
                if(name.isEmpty())continue;
                String kind=normalizeKind(g.optString("kind",MEANING));
                JSONArray roots=g.optJSONArray("roots");
                JSONArray members=g.optJSONArray("members");
                Set<String> rootIds=new HashSet<>();
                List<String> rootOrder=new ArrayList<>();
                if(roots!=null){
                    for(int j=0;j<roots.length();j++){
                        String wid=byEnglish.get(letters(roots.optString(j)));
                        if(wid!=null&&rootIds.add(wid))rootOrder.add(wid);
                    }
                }
                List<String> memberOrder=new ArrayList<>();
                Set<String> memberIds=new HashSet<>();
                for(String r:rootOrder){if(memberIds.add(r))memberOrder.add(r);}
                if(members!=null){
                    for(int j=0;j<members.length();j++){
                        String wid=byEnglish.get(letters(members.optString(j)));
                        if(wid!=null&&memberIds.add(wid))memberOrder.add(wid);
                    }
                }
                if(memberOrder.isEmpty())continue;
                String groupId=findGroupByName(graph,name);
                if(groupId==null){
                    groupId=addGroup(graph,name,kind);
                    groupsAdded++;
                }
                for(int j=0;j<memberOrder.size();j++){
                    String wid=memberOrder.get(j);
                    boolean isRoot=rootIds.contains(wid);
                    int branch=isRoot?rootOrder.indexOf(wid)%4:0;
                    addMember(graph,groupId,wid,isRoot,branch);
                }
            }
        }
        if(importedLinks!=null){
            for(int i=0;i<importedLinks.length();i++){
                JSONObject l=importedLinks.optJSONObject(i);
                if(l==null)continue;
                String fromKey=letters(l.optString("from",l.optString("a")));
                String toKey=letters(l.optString("to",l.optString("b")));
                String a=byEnglish.get(fromKey),b=byEnglish.get(toKey);
                if(a==null||b==null||a.equals(b))continue;
                String kind=normalizeKind(l.optString("kind",l.optString("note",MEANING)));
                String note=l.optString("note","").trim();
                int before=links(graph).length();
                link(graph,a,b,kind,note,isDirectedKind(kind)||l.optBoolean("directed",false));
                if(links(graph).length()>before)linksAdded++;
            }
        }
        return new int[]{groupsAdded,linksAdded};
    }

    private static String findGroupByName(JSONObject graph,String name){
        JSONArray groups=graph.optJSONArray("groups");
        if(groups==null)return null;
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);
            if(g!=null&&name.equalsIgnoreCase(g.optString("name","").trim()))return g.optString("id");
        }
        return null;
    }

    /**
     * Parses offline mind-map text into {@code {title, words, groups, links}}.
     * Syntax example:
     * <pre>
     * # “优秀的”相关
     * eminent adj. 杰出的，有名的
     *   -扩> eminence n. 卓越
     *   -近义> prominent adj. 显著的
     *     -扩> prominence n. 突出
     * </pre>
     * Returns null when the text has no mind-map markers (so callers can fall back to plain list parsing).
     */
    public static JSONObject parseMindMapText(String raw) throws Exception {
        if(raw==null||raw.trim().isEmpty())return null;
        String[] lines=raw.replace('\r','\n').split("\n");
        boolean hasStructure=false;
        for(String line:lines){
            String t=line.trim();
            if(t.startsWith("#")||t.matches("^[-–—]+\\s*[^\\s>→-]+\\s*[>→-]+.*")||t.matches("^(->|→).*")){
                hasStructure=true;break;
            }
        }
        if(!hasStructure)return null;

        String currentTopic="",firstTopic="";
        JSONArray words=new JSONArray();
        JSONArray groups=new JSONArray();
        JSONArray links=new JSONArray();
        Map<String,JSONObject> wordsByKey=new LinkedHashMap<>();
        JSONObject activeGroup=null;
        List<Integer> indentStack=new ArrayList<>();
        List<String> wordStack=new ArrayList<>();

        for(String line:lines){
            if(line.trim().isEmpty())continue;
            int indent=leadingSpaces(line);
            String t=line.trim();
            if(t.startsWith("#")){
                String topic=t.replaceFirst("^#+\\s*","").trim();
                if(!topic.isEmpty()){
                    currentTopic=topic;
                    if(firstTopic.isEmpty())firstTopic=topic;
                    activeGroup=new JSONObject().put("name",topic).put("kind",MEANING).put("roots",new JSONArray()).put("members",new JSONArray());
                    groups.put(activeGroup);
                    indentStack.clear();wordStack.clear();
                }
                continue;
            }
            String edgeKind=null,edgeNote="",rest=t;
            java.util.regex.Matcher edge=java.util.regex.Pattern.compile("^(?:[-–—]+\\s*([^>→\\-\\s][^>→\\-]*)?\\s*[>→]+|→)\\s*(.+)$").matcher(t);
            if(edge.matches()){
                String label=edge.group(1)==null?"":edge.group(1).trim();
                edgeKind=normalizeKind(label.isEmpty()?MEANING:label);
                edgeNote=label;
                rest=edge.group(2).trim();
            }
            List<WordParser.Entry> parsed=WordParser.parse(rest);
            if(parsed.isEmpty())continue;
            WordParser.Entry entry=parsed.get(0);
            String key=letters(entry.english);
            if(key.isEmpty())continue;
            if(!wordsByKey.containsKey(key)){
                JSONObject card=new JSONObject().put("english",entry.english).put("chinese",entry.chinese).put("example",entry.example);
                if(!currentTopic.isEmpty())card.put("category",currentTopic);
                Words.stableId(card);
                wordsByKey.put(key,card);
                words.put(card);
            }else{
                JSONObject existing=wordsByKey.get(key);
                if(!entry.chinese.isEmpty()&&existing.optString("chinese").isEmpty())existing.put("chinese",entry.chinese);
                if(!currentTopic.isEmpty()&&existing.optString("category").isEmpty())existing.put("category",currentTopic);
            }

            while(!indentStack.isEmpty()&&indent<=indentStack.get(indentStack.size()-1)){
                indentStack.remove(indentStack.size()-1);
                wordStack.remove(wordStack.size()-1);
            }
            if(!wordStack.isEmpty()){
                String parent=wordStack.get(wordStack.size()-1);
                String kind=edgeKind!=null?edgeKind:MEANING;
                links.put(new JSONObject().put("from",parent).put("to",entry.english).put("kind",kind).put("note",edgeNote).put("directed",isDirectedKind(kind)));
            }else if(activeGroup!=null){
                activeGroup.getJSONArray("roots").put(entry.english);
            }
            if(activeGroup!=null)activeGroup.getJSONArray("members").put(entry.english);
            indentStack.add(indent);
            wordStack.add(entry.english);
        }
        if(words.length()==0)return null;
        return new JSONObject().put("title",firstTopic.isEmpty()?currentTopic:firstTopic).put("words",words).put("groups",groups).put("links",links);
    }

    private static int leadingSpaces(String line){
        int n=0;
        for(int i=0;i<line.length();i++){
            char c=line.charAt(i);
            if(c==' ')n++;
            else if(c=='\t')n+=2;
            else break;
        }
        return n;
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
            String kind="词形派生".equals(reason)?DERIVE:FORM;
            found.add(new JSONObject(card.toString()).put("reason",reason).put("kind",kind).put("score",score));
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
                addMember(base,member.optString("groupId"),member.optString("wordId"),member.optBoolean("isRoot",false),member.optInt("branch",0));
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
                boolean directed=link.optBoolean("directed",isDirectedKind(kind));
                String x=directed?a:(a.compareTo(b)<=0?a:b);
                String y=directed?b:(a.compareTo(b)<=0?b:a);
                boolean duplicate=false;
                JSONArray have=links(base);
                for(int j=0;j<have.length();j++){
                    JSONObject old=have.optJSONObject(j);
                    if(old==null||!kind.equals(old.optString("kind")))continue;
                    String oa=old.optString("a"),ob=old.optString("b");
                    if((x.equals(oa)&&y.equals(ob))||(x.equals(ob)&&y.equals(oa))){duplicate=true;break;}
                }
                if(duplicate)continue;
                if(id.isEmpty())id="r_"+UUID.randomUUID().toString();
                have.put(new JSONObject().put("id",id).put("a",x).put("b",y).put("kind",kind).put("note",trim(link.optString("note"),80)).put("directed",directed).put("created",link.optLong("created",System.currentTimeMillis())));
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
