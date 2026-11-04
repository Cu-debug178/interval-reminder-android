import com.example.reviewreminder.IntervalPlan;
import com.example.reviewreminder.WordParser;
import com.example.reviewreminder.ReviewSession;
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
        List<WordParser.Entry> parsed=WordParser.parse("Lesson 1\n01 ambition /x/ n. 野心；雄心\n* ambitious /x/ adj. 有雄心的\n额外释义\n");
        check(parsed.size()==2,"headers and stars");check(parsed.get(0).english.equals("ambition"),"english");check(parsed.get(1).chinese.contains("额外释义"),"continuation");
        List<WordParser.Entry> lesson=WordParser.parse(new String(Files.readAllBytes(Paths.get("app/src/main/assets/lesson1/words.tsv")),"UTF-8"));
        check(lesson.size()==42,"42 Lesson 1 cards");check(lesson.get(0).english.equals("ambition")&&lesson.get(41).english.equals("commonplace"),"page order");
        for(WordParser.Entry word:lesson)check(!word.english.isEmpty()&&!word.chinese.isEmpty()&&word.example.isEmpty(),"valid card "+word.english);
        ReviewSession s=new ReviewSession(4,false,1);check(s.current()==0,"first");s.answer(false);check(s.current()==1&&s.total()==5,"retry added later");int count=1;while(!s.ended()){s.answer(false);count++;}check(count==12,"bounded retries");
        ReviewSession known=new ReviewSession(42,true,7);Set<Integer> seen=new HashSet<>();while(!known.ended()){seen.add(known.current());known.answer(true);}check(seen.size()==42&&known.total()==42&&known.remembered()==42,"all cards once");
        ReviewSession a=new ReviewSession(8,true,55),b=new ReviewSession(8,true,55);for(int i=0;i<8;i++){check(a.current()==b.current(),"repeatable shuffle");a.answer(true);b.answer(true);}
        System.out.println("CoreTest: "+checks+" checks passed");
    }
}
