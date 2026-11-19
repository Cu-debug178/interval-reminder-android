package com.example.reviewreminder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** Pure spelling queue: only independent correct answers advance completion. */
public final class SpellingSession {
    public static final class Item {
        public final String id;
        public final int streak;
        public Item(String id,int streak){
            if(id==null||id.trim().isEmpty())throw new IllegalArgumentException("单词 ID 不能为空");
            this.id=id;this.streak=Math.max(0,Math.min(2,streak));
        }
    }
    public static final class Result {
        public final boolean accepted,correct,independent,completedWord;
        public final String wordId;
        private Result(boolean accepted,String wordId,boolean correct,boolean independent,boolean completedWord){
            this.accepted=accepted;this.wordId=wordId;this.correct=correct;this.independent=independent;this.completedWord=completedWord;
        }
        private static Result rejected(String id){return new Result(false,id,false,false,false);}
    }
    private static final class State {
        String id;int streak,remaining;
        State(String id,int streak,int remaining){this.id=id;this.streak=streak;this.remaining=remaining;}
    }

    private final boolean wrongMode;
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>();
    private final LinkedList<State> queue=new LinkedList<>();
    private int initialTotal,finishedCount,attempts,correctCount,wrongCount,assistedCount,skippedCount;

    public SpellingSession(List<Item> items,boolean wrongMode){
        this.wrongMode=wrongMode;
        if(items==null)return;
        for(Item item:items){
            if(item==null||states.containsKey(item.id))continue;
            if(wrongMode&&item.streak>=2)continue;
            State state=new State(item.id,wrongMode?item.streak:0,wrongMode?2-item.streak:1);
            states.put(state.id,state);queue.add(state);initialTotal++;
        }
    }

    public boolean ended(){return queue.isEmpty();}
    public String current(){if(ended())throw new IllegalStateException("拼写已完成");return queue.peek().id;}
    public int initialTotal(){return initialTotal;}
    public int finishedCount(){return finishedCount;}
    public int attempts(){return attempts;}
    public int correctCount(){return correctCount;}
    public int wrongCount(){return wrongCount;}
    public int assistedCount(){return assistedCount;}
    public int skippedCount(){return skippedCount;}
    public int currentStreak(String id){State state=states.get(id);return state==null?0:state.streak;}
    public int remaining(String id){State state=states.get(id);return state==null?0:state.remaining;}
    public List<String> queueIds(){List<String> out=new ArrayList<>();for(State state:queue)out.add(state.id);return Collections.unmodifiableList(out);}

    public Result submit(String expectedId,boolean correct,boolean assisted){
        if(ended()||expectedId==null||!expectedId.equals(current()))return Result.rejected(expectedId);
        State state=queue.peek();attempts++;
        boolean independent=correct&&!assisted;
        if(correct)correctCount++;else wrongCount++;
        if(assisted)assistedCount++;
        if(independent){
            state.streak=Math.min(2,state.streak+1);state.remaining=Math.max(0,state.remaining-1);
        }else{
            state.streak=0;state.remaining=wrongMode?2:1;
        }
        boolean completed=state.remaining==0;
        if(completed){queue.removeFirst();finishedCount++;}
        else requeue();
        return new Result(true,state.id,correct,independent,completed);
    }

    public boolean skip(String expectedId){
        if(ended()||expectedId==null||!expectedId.equals(current()))return false;
        State state=queue.peek();attempts++;skippedCount++;state.streak=0;state.remaining=wrongMode?2:1;requeue();return true;
    }

    private void requeue(){
        State state=queue.removeFirst();
        queue.add(Math.min(3,queue.size()),state);
    }

    public String snapshot(){
        try{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(1);out.writeBoolean(wrongMode);out.writeInt(initialTotal);out.writeInt(finishedCount);
            out.writeInt(attempts);out.writeInt(correctCount);out.writeInt(wrongCount);out.writeInt(assistedCount);out.writeInt(skippedCount);
            out.writeInt(states.size());
            for(State state:states.values()){out.writeUTF(state.id);out.writeInt(state.streak);out.writeInt(state.remaining);}
            out.writeInt(queue.size());for(State state:queue)out.writeUTF(state.id);
            out.flush();return Base64Compat.encode(bytes.toByteArray());
        }catch(Exception e){throw new IllegalStateException("无法保存拼写进度",e);}
    }

    public static SpellingSession restore(String snapshot){
        try{
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64Compat.decode(snapshot)));
            int version=in.readInt();if(version!=1)throw new IllegalArgumentException("拼写进度版本不兼容");
            boolean wrong=in.readBoolean();
            SpellingSession session=new SpellingSession(Collections.emptyList(),wrong);
            session.initialTotal=in.readInt();session.finishedCount=in.readInt();session.attempts=in.readInt();session.correctCount=in.readInt();session.wrongCount=in.readInt();session.assistedCount=in.readInt();session.skippedCount=in.readInt();
            int count=in.readInt();if(count<0||count>10000)throw new IllegalArgumentException("拼写进度损坏");
            for(int i=0;i<count;i++){String id=in.readUTF();int streak=in.readInt(),remaining=in.readInt();session.states.put(id,new State(id,streak,remaining));}
            int queueSize=in.readInt();if(queueSize<0||queueSize>session.states.size())throw new IllegalArgumentException("拼写队列损坏");
            for(int i=0;i<queueSize;i++){State state=session.states.get(in.readUTF());if(state==null)throw new IllegalArgumentException("拼写队列引用损坏");session.queue.add(state);}
            if(session.finishedCount<0||session.finishedCount>session.initialTotal)throw new IllegalArgumentException("拼写完成数损坏");
            return session;
        }catch(Exception e){throw new IllegalArgumentException("无法恢复拼写进度",e);}
    }
}
