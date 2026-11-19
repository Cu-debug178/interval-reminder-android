import com.example.reviewreminder.IntervalPlan;
import com.example.reviewreminder.LegacyMigration;
import com.example.reviewreminder.PlanIdentity;
import com.example.reviewreminder.ReviewSession;
import com.example.reviewreminder.SpellingPlan;
import com.example.reviewreminder.SpellingSession;
import com.example.reviewreminder.TaskIndex;
import com.example.reviewreminder.TaskTitle;
import com.example.reviewreminder.WordGraph;
import com.example.reviewreminder.WordParser;
import com.example.reviewreminder.Words;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.nio.file.*;

public final class CoreTest {
    static int checks;
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    static void invalid(String csv){try{IntervalPlan.parse(csv);throw new AssertionError("accepted "+csv);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        String defaults="5,30,720,1440,2880,5760,10080,20160";
        check(IntervalPlan.active(defaults,false,"").size()==7,"optional off");check(IntervalPlan.active(defaults,true,"").size()==8,"optional on");
        List<Long> custom=IntervalPlan.active(defaults,false,"43200,86400");check(custom.size()==9&&custom.get(7)==43200,"append intervals");
        check(IntervalPlan.pack(IntervalPlan.parse("1, 30,1440")).equals("1,30,1440"),"round trip");invalid("0");invalid("1,");invalid("-5");invalid("x");invalid("5256001");
        check(Arrays.equals(SpellingPlan.indices(SpellingPlan.ALL,4,new int[]{2}),new int[]{0,1,2,3}),"spell all words");
        check(Arrays.equals(SpellingPlan.indices(SpellingPlan.WRONG_ONLY,5,new int[]{3,1,3,-1,9}),new int[]{3,1}),"wrong-only indices are valid and unique");
        check(Arrays.equals(SpellingPlan.include(new int[]{2,0,2},new int[]{1,2,-1,8},4),new int[]{2,0,1}),"extra forgotten words merge without duplicates or invalid indices");
        check(Arrays.equals(SpellingPlan.include(new int[0],new int[]{3,1},4),new int[]{3,1}),"forgotten words can be added when default spelling is disabled");
        check(SpellingPlan.indices(SpellingPlan.WRONG_ONLY,4,null).length==0,"no missed words means no spelling cards");
        int[] accumulatedWrong=SpellingPlan.wrongBookIndices(4,new int[]{0,2,0,1});
        check(Arrays.equals(accumulatedWrong,new int[]{1,3}),"wrong-book spelling includes every word with a recorded mistake");
        check(Arrays.equals(SpellingPlan.indices(SpellingPlan.WRONG_ONLY,4,accumulatedWrong),new int[]{1,3}),"previously missed words remain in spelling selection after later correct reviews");
        check(SpellingPlan.wrongBookIndices(3,new int[]{0,-1,0}).length==0,"zero and negative error counts are excluded");
        check(SpellingPlan.indices(SpellingPlan.NONE,4,new int[]{1}).length==0,"spelling can be disabled");
        try{SpellingPlan.indices(SpellingPlan.ALL,-1,null);throw new AssertionError("accepted negative word count");}catch(IllegalArgumentException expected){checks++;}
        List<WordParser.Entry> parsed=WordParser.parse("Lesson 1\n01 ambition /x/ n. 野心；雄心\n* ambitious /x/ adj. 有雄心的\n额外释义\n");
        check(parsed.size()==2,"headers and stars");check(parsed.get(0).english.equals("ambition"),"english");check(parsed.get(1).chinese.contains("额外释义"),"continuation");
        List<WordParser.Entry> lesson=WordParser.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/lesson1/words.tsv")),"UTF-8"));
        check(lesson.size()==42,"42 Lesson 1 cards");check(lesson.get(0).english.equals("ambition")&&lesson.get(41).english.equals("commonplace"),"page order");
        for(WordParser.Entry word:lesson)check(!word.english.isEmpty()&&!word.chinese.isEmpty()&&word.example.isEmpty(),"valid card "+word.english);
        ReviewSession s=new ReviewSession(4,false,1);check(s.current()==0,"first");s.answer(false);check(s.current()==1&&s.total()==5,"retry added later");int count=1;while(!s.ended()){s.answer(false);count++;}check(count==12,"bounded retries");check(Arrays.equals(s.missedIndices(),new int[]{0,1,2,3}),"missed indices are unique even after retries");
        ReviewSession repeatMiss=new ReviewSession(1,false,1);repeatMiss.answer(false);repeatMiss.answer(false);repeatMiss.answer(true);check(Arrays.equals(repeatMiss.missedIndices(),new int[]{0}),"repeated misses retain one wrong-book index");
        ReviewSession known=new ReviewSession(42,true,7);Set<Integer> seen=new HashSet<>();while(!known.ended()){seen.add(known.current());known.answer(true);}check(seen.size()==42&&known.total()==42&&known.remembered()==42,"all cards once");
        ReviewSession a=new ReviewSession(8,true,55),b=new ReviewSession(8,true,55);for(int i=0;i<8;i++){check(a.current()==b.current(),"repeatable shuffle");a.answer(true);b.answer(true);}

        List<SpellingSession.Item> ordinary=Arrays.asList(new SpellingSession.Item("a",0),new SpellingSession.Item("b",0));
        SpellingSession spelling=new SpellingSession(ordinary,false);
        check(spelling.current().equals("a")&&spelling.initialTotal()==2,"ordinary queue starts with every word");
        check(spelling.submit("a",true,false).independent&&!spelling.ended(),"first independent word completes but session continues");
        check(!spelling.submit("a",true,false).accepted,"duplicate submit cannot count twice");
        check(spelling.current().equals("b"),"next unfinished word");
        spelling.submit("b",false,false);check(spelling.current().equals("b")&&!spelling.ended(),"single wrong word is requeued");
        spelling.submit("b",true,true);check(!spelling.ended()&&spelling.currentStreak("b")==0,"assisted correct never completes ordinary word");
        spelling.submit("b",true,false);check(spelling.ended()&&spelling.finishedCount()==2,"all ordinary words require independent success");

        SpellingSession wrong=new SpellingSession(Collections.singletonList(new SpellingSession.Item("w",0)),true);
        wrong.submit("w",true,true);check(wrong.currentStreak("w")==0&&!wrong.ended(),"hint resets wrong-word streak");
        wrong.submit("w",true,false);check(wrong.currentStreak("w")==1&&!wrong.ended(),"wrong word learning after one independent correct");
        SpellingSession restored=SpellingSession.restore(wrong.snapshot());
        check(restored.current().equals("w")&&restored.currentStreak("w")==1,"snapshot restores queue and streak");
        restored.submit("w",true,false);check(restored.ended()&&restored.currentStreak("w")==2,"two independent correct answers master wrong word");

        SpellingSession existing=new SpellingSession(Collections.singletonList(new SpellingSession.Item("w",1)),true);
        existing.skip("w");check(existing.currentStreak("w")==0&&!existing.ended(),"skip clears streak and retains word");
        existing.submit("w",true,false);existing.submit("w",true,false);check(existing.ended(),"existing evidence can be reused after a fresh two-streak");
        SpellingSession mastered=new SpellingSession(Collections.singletonList(new SpellingSession.Item("m",2)),true);
        check(mastered.ended()&&mastered.initialTotal()==0,"already mastered words need no additional work");
        SpellingSession spacing=new SpellingSession(ordinary,false);spacing.submit("a",false,false);check(spacing.current().equals("b"),"wrong word reappears after another word when possible");
        try{SpellingSession.restore("broken");throw new AssertionError("accepted broken spelling snapshot");}catch(IllegalArgumentException expected){checks++;}
        SpellingSession missing=new SpellingSession(Arrays.asList(new SpellingSession.Item("gone",0),new SpellingSession.Item("stay",0)),false);
        check(missing.abandon("gone")&&missing.current().equals("stay")&&missing.initialTotal()==1,"missing word leaves the queue without ending the session");
        check(!missing.abandon("gone"),"abandon rejects a word that is not current");
        check(missing.abandon("stay")&&missing.ended()&&missing.initialTotal()==0,"abandoning the last word ends the session");
        SpellingSession kept=SpellingSession.restore(missing.snapshot());
        check(kept.ended()&&kept.initialTotal()==0,"abandoned words stay out of a restored snapshot");

        JSONObject named=new JSONObject().put("id",7).put("title","旧名称").put("words",new JSONArray().put("apple")).put("intervals",new JSONArray().put(5)).put("history",new JSONArray().put(new JSONObject().put("stage",1))).put("windows","07:30-08:30").put("created",42);
        String renamed=TaskTitle.rename(named,"  新名称  ");
        check(renamed.equals("新名称")&&named.optString("title").equals("新名称"),"rename trims and stores the new title");
        check(named.optLong("id")==7&&named.optJSONArray("words").getString(0).equals("apple"),"rename keeps the task id and words");
        check(named.optJSONArray("intervals").getLong(0)==5&&named.optJSONArray("history").getJSONObject(0).optInt("stage")==1,"rename keeps the schedule and history");
        check(named.optString("windows").equals("07:30-08:30")&&named.optLong("created")==42,"rename keeps the windows and created time");
        try{TaskTitle.rename(named,"   ");throw new AssertionError("accepted a blank name");}catch(IllegalArgumentException expected){checks++;}
        try{TaskTitle.rename(named,null);throw new AssertionError("accepted a missing name");}catch(IllegalArgumentException expected){checks++;}
        check(named.optString("title").equals("新名称"),"rejected names leave the current title in place");
        JSONObject renamedPlan=new JSONObject(named.toString()).put("title","后来的名字");
        check(PlanIdentity.same(named,renamedPlan),"a renamed plan still matches the backup of its original id");
        check(!PlanIdentity.same(named,new JSONObject(named.toString()).put("created",99)),"a different creation time is not the same plan");
        check(!PlanIdentity.same(null,named),"a missing plan is not a match");

        JSONObject card=new JSONObject().put("english","Apple").put("chinese","苹果");
        String id=Words.stableId(card);
        check(id.startsWith("w_")&&id.equals(Words.stableId(card)),"word id is stable once assigned");
        JSONObject copy=new JSONObject().put("english","APPLE").put("chinese","苹果");
        check(Words.stableId(copy).equals(id),"the same spelling and meaning share an id before one is saved");
        JSONObject other=new JSONObject().put("english","apple").put("chinese","苹果公司");
        check(!Words.stableId(other).equals(id),"a different meaning stays a different word");
        card.put("english","apricot");
        check(Words.stableId(card).equals(id),"editing the spelling does not mint a new id");
        JSONArray cards=new JSONArray().put(other).put(card);
        check(Words.locate(cards,0,id)==1,"locate follows the id when the hinted slot is a different card");
        check(Words.locate(cards,5,null)==-1,"locate rejects a missing hint when no id is given");

        Map<String,LegacyMigration.Value> legacy=new LinkedHashMap<>();
        String tasksJson=new JSONArray().put(new JSONObject(named.toString())
            .put("customMark","keep-me")
            .put("words",new JSONArray().put(new JSONObject(card.toString()).put("example","An apple a day.").put("connectionImage","ci_old.jpg").put("extraSense","kept")))
        ).toString();
        legacy.put("tasks",new LegacyMigration.Value("string",tasksJson));
        legacy.put("wrongWordStates",new LegacyMigration.Value("string",new JSONObject().put(id,new JSONObject().put("wrongCount",2).put("streak",1)).toString()));
        legacy.put("spellingAttempts",new LegacyMigration.Value("string",new JSONArray().put(new JSONObject().put("wordId",id).put("at",7).put("type","wrong")).toString()));
        legacy.put("voiceProfiles",new LegacyMigration.Value("string",new JSONArray().put(new JSONObject().put("id","voice-1").put("name","我的声音").put("mime","audio/mpeg")).toString()));
        legacy.put("voiceProvider",new LegacyMigration.Value("string","mimo"));
        legacy.put("voiceId",new LegacyMigration.Value("string","voice-1"));
        legacy.put("speechVoice",new LegacyMigration.Value("string","en-US-local"));
        legacy.put("speechRate",new LegacyMigration.Value("int","2"));
        legacy.put("aiConfigs",new LegacyMigration.Value("string",new JSONArray().put(new JSONObject().put("id","cfg").put("name","课堂")).toString()));
        legacy.put("aiActiveId",new LegacyMigration.Value("string","cfg"));
        legacy.put("windows",new LegacyMigration.Value("string","07:30-08:30"));
        legacy.put("nextId",new LegacyMigration.Value("long","41"));
        legacy.put("optional",new LegacyMigration.Value("boolean","true"));
        legacy.put("apiKey",new LegacyMigration.Value("string","device-only-secret"));
        LegacyMigration.Bundle bundle=LegacyMigration.migrate(legacy);
        check(bundle.prefs.size()==legacy.size(),"migration keeps every legacy preference");
        for(Map.Entry<String,LegacyMigration.Value> entry:legacy.entrySet()){
            check(entry.getValue().equals(bundle.prefs.get(entry.getKey())),"migration keeps "+entry.getKey());
        }
        check(bundle.prefs.get("tasks").text.equals(tasksJson),"tasks json is copied verbatim");
        check(bundle.prefs.get("apiKey").text.equals("device-only-secret"),"device secrets stay in the local copy");
        check(bundle.graph.getJSONArray("links").length()==0&&bundle.graph.getJSONArray("groups").length()==0,"pictures are not imported as relations");
        check(bundle.index.tasks.size()==1&&bundle.index.cards.size()==1&&bundle.index.events.size()==1,"plans, cards and review events are indexed");
        check(bundle.index.cards.get(0).json.contains("ci_old.jpg")&&bundle.index.cards.get(0).json.contains("extraSense"),"card json keeps the picture and unknown fields");
        check(bundle.index.cards.get(0).wordId.equals(id),"indexed card uses the stable word id");
        check(bundle.index.events.get(0).stage==1&&bundle.index.events.get(0).json.contains("stage"),"review history is indexed without dropping the event");
        check(jsonEqual(new JSONArray(tasksJson),TaskIndex.rebuild(bundle.index)),"rebuilt tasks match the original json");
        JSONObject rebuilt=TaskIndex.rebuild(bundle.index).getJSONObject(0);
        check(rebuilt.optLong("created")==42&&rebuilt.optString("title").equals("新名称")&&rebuilt.optString("customMark").equals("keep-me"),"created time, title and extra task fields survive");
        check(PlanIdentity.same(named,rebuilt),"a migrated plan still matches the backup of its original creation time");
        JSONObject legacyCard=new JSONObject().put("english","apricot").put("chinese","杏").put("connectionImage","ci_old.jpg");
        check(legacyCard.optString("wordId").isEmpty(),"a migrated card can omit wordId");
        String counted=Words.stableId(new JSONObject(legacyCard.toString()));
        check(counted.equals(Words.stableId(legacyCard))&&!counted.isEmpty(),"relation counts use the computed id when wordId was never stored");
        JSONArray savedCatalog=new JSONArray().put(new JSONObject().put("wordId",counted).put("english","apricot").put("chinese","杏"));
        JSONArray draftWords=new JSONArray()
            .put(new JSONObject().put("english","apricot").put("chinese","杏"))
            .put(new JSONObject().put("english","pear").put("chinese","梨"))
            .put(new JSONObject().put("english"," ").put("chinese","空白"));
        JSONArray mergedCatalog=Words.mergeCatalog(savedCatalog,draftWords);
        check(mergedCatalog.length()==2,"an unsaved sibling joins the catalog and a blank card does not");
        check(mergedCatalog.getJSONObject(0).optString("wordId").equals(counted),"a draft copy of a saved card does not duplicate it");
        String pearId=Words.stableId(new JSONObject().put("english","pear").put("chinese","梨"));
        check(mergedCatalog.getJSONObject(1).optString("wordId").equals(pearId)&&mergedCatalog.getJSONObject(1).optString("english").equals("pear"),"a draft word without wordId is still a link target");
        Map<String,LegacyMigration.Value> older=new LinkedHashMap<>();
        older.put("windows",new LegacyMigration.Value("string",""));
        older.put("intervals",new LegacyMigration.Value("string",defaults));
        LegacyMigration.Bundle partial=LegacyMigration.migrate(older);
        check(partial.prefs.size()==2&&partial.index.tasks.isEmpty()&&partial.prefs.get("windows").text.isEmpty(),"an older install without tasks still migrates its settings");
        check(LegacyMigration.encode(Integer.valueOf(2)).kind.equals("int")&&LegacyMigration.encode(Long.valueOf(41)).kind.equals("long")&&LegacyMigration.encode(Boolean.TRUE).text.equals("true"),"preference types stay distinct");

        JSONArray catalog=new JSONArray()
            .put(new JSONObject().put("wordId","w_act").put("english","act").put("chinese","行动"))
            .put(new JSONObject().put("wordId","w_action").put("english","action").put("chinese","行动"))
            .put(new JSONObject().put("wordId","w_ambition").put("english","ambition").put("chinese","雄心"))
            .put(new JSONObject().put("wordId","w_ambitious").put("english","ambitious").put("chinese","有雄心的"))
            .put(new JSONObject().put("wordId","w_apple").put("english","apple").put("chinese","苹果"))
            .put(new JSONObject().put("wordId","w_apply").put("english","apply").put("chinese","申请"))
            .put(new JSONObject().put("wordId","w_orange").put("english","orange").put("chinese","橙子"));
        JSONArray form=WordGraph.suggestForm(catalog,"w_ambition","ambition",8);
        check(containsEnglish(form,"ambitious")&&!containsEnglish(form,"orange"),"offline form suggestions find a derivation and skip an unrelated word");
        JSONArray act=WordGraph.suggestForm(catalog,"w_act","act",8);
        check(containsEnglish(act,"action"),"offline suggestions recognise a short derived form");
        JSONArray nearApple=WordGraph.suggestForm(catalog,"w_apple","apple",8);
        check(containsEnglish(nearApple,"apply")&&!containsEnglish(nearApple,"orange"),"offline suggestions catch a one-letter spelling neighbour");

        JSONObject graph=WordGraph.empty();
        String meaning=WordGraph.link(graph,"w_apple","w_orange",WordGraph.MEANING,"都是水果");
        String again=WordGraph.link(graph,"w_orange","w_apple",WordGraph.MEANING,"重复");
        check(meaning.equals(again)&&graph.getJSONArray("links").length()==1,"a meaning link is undirected and stored once");
        WordGraph.link(graph,"w_apple","w_apply",WordGraph.FORM,"拼写接近");
        check(graph.getJSONArray("links").length()==2,"meaning and form can both connect the same pair");
        try{WordGraph.link(graph,"w_apple","w_apple",WordGraph.MEANING,"");throw new AssertionError("accepted a self link");}catch(IllegalArgumentException expected){checks++;}
        String group=WordGraph.addGroup(graph,"水果",WordGraph.MEANING);
        WordGraph.addMember(graph,group,"w_apple");
        WordGraph.addMember(graph,group,"w_orange");
        WordGraph.addMember(graph,group,"w_apple");
        check(WordGraph.membersOf(graph,group).length()==2,"a word joins a group once");
        String label=WordGraph.studyLabel(graph,catalog,"w_apple");
        check(label.contains("近义")&&label.contains("orange")&&label.contains("词形")&&label.contains("apply"),"study label lists meaning and form neighbours");
        JSONObject graphCopy=WordGraph.copy(graph);
        check(WordGraph.unlink(graphCopy,meaning)&&graphCopy.getJSONArray("links").length()==1&&WordGraph.membersOf(graphCopy,group).length()==2,"removing a link keeps the other link and the group");
        check(graph.getJSONArray("links").length()==2,"unlinking a copy does not change the saved graph");
        JSONObject incoming=WordGraph.empty();
        WordGraph.link(incoming,"w_act","w_action",WordGraph.FORM,"派生");
        JSONObject merged=WordGraph.merge(graph,incoming,false);
        check(graph.getJSONArray("links").length()==2&&merged.getJSONArray("links").length()==3&&WordGraph.membersOf(merged,group).length()==2,"merge adds relations without dropping existing ones");
        JSONObject replaced=WordGraph.merge(graph,WordGraph.empty(),true);
        check(replaced.getJSONArray("links").length()==0&&replaced.getJSONArray("groups").length()==0,"replace with an empty graph clears relations and leaves the caller's graph intact");
        check(graph.getJSONArray("links").length()==2,"replace does not mutate the current graph");
        try{WordGraph.merge(WordGraph.empty(),new JSONObject().put("links",new JSONArray().put(new JSONObject().put("a","w_apple").put("b","w_apple").put("kind","meaning"))),false);throw new AssertionError("accepted a backed-up self link");}catch(IllegalArgumentException expected){checks++;}

        System.out.println("CoreTest: "+checks+" checks passed");
    }

    static boolean jsonEqual(Object a,Object b) throws Exception {
        if(a==b)return true;
        if(a==null||b==null)return false;
        if(a==JSONObject.NULL||b==JSONObject.NULL)return a==JSONObject.NULL&&b==JSONObject.NULL;
        if(a instanceof JSONObject&&b instanceof JSONObject){
            JSONObject left=(JSONObject)a,right=(JSONObject)b;
            if(left.length()!=right.length())return false;
            java.util.Iterator<String> keys=left.keys();
            while(keys.hasNext()){String key=keys.next();if(!right.has(key)||!jsonEqual(left.get(key),right.get(key)))return false;}
            return true;
        }
        if(a instanceof JSONArray&&b instanceof JSONArray){
            JSONArray left=(JSONArray)a,right=(JSONArray)b;
            if(left.length()!=right.length())return false;
            for(int i=0;i<left.length();i++)if(!jsonEqual(left.get(i),right.get(i)))return false;
            return true;
        }
        if(a instanceof Number&&b instanceof Number)return ((Number)a).doubleValue()==((Number)b).doubleValue();
        return a.equals(b);
    }

    static boolean containsEnglish(JSONArray rows,String english){
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&english.equals(row.optString("english")))return true;}
        return false;
    }
}
