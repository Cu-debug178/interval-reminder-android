package com.example.reviewreminder;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Phase 3: English front → choose POS/sense among distractors. */
public final class SenseQuizActivity extends ScreenActivity {
    private WordUi ui;private long taskId;private JSONArray words;private List<int[]> queue;private int position;private int correct,wrong;private boolean answered;private int correctSense=-1;private final Feedback interaction=new Feedback();private final Random random=new Random();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);interaction.prepare(this);ui=new WordUi(this);
        taskId=getIntent().getLongExtra("taskId",0);
        try{
            JSONObject task=Store.find(Store.tasks(this),taskId);
            if(task==null)throw new Exception("任务不存在");
            words=task.getJSONArray("words");
            if(words.length()==0)throw new Exception("词组为空");
            queue=buildQueue(state);
            if(state!=null){position=state.getInt("position");correct=state.getInt("correct");wrong=state.getInt("wrong");answered=state.getBoolean("answered");}
            show();
        }catch(Exception e){ui.toast(e.getMessage());finish();}
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putInt("position",position);out.putInt("correct",correct);out.putInt("wrong",wrong);out.putBoolean("answered",answered);
        JSONArray packed=new JSONArray();
        for(int[] item:queue)packed.put(new JSONArray().put(item[0]).put(item[1]));
        out.putString("queue",packed.toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy(){interaction.release();super.onDestroy();}

    private List<int[]> buildQueue(Bundle state)throws Exception{
        List<int[]> list=new ArrayList<>();
        if(state!=null&&state.getString("queue")!=null){
            JSONArray packed=new JSONArray(state.getString("queue"));
            for(int i=0;i<packed.length();i++){
                JSONArray item=packed.optJSONArray(i);if(item==null||item.length()<2)continue;
                list.add(new int[]{item.optInt(0),item.optInt(1)});
            }
            if(!list.isEmpty())return list;
        }
        boolean wrongOnly=getIntent().getBooleanExtra("wrongOnly",false);
        for(int i=0;i<words.length();i++){
            JSONObject word=words.optJSONObject(i);if(word==null)continue;
            if(wrongOnly&&word.optInt("wrongCount")<=0)continue;
            JSONArray senses=Senses.of(word);
            for(int s=0;s<senses.length();s++)list.add(new int[]{i,s});
        }
        if(list.isEmpty()){
            for(int i=0;i<words.length();i++)list.add(new int[]{i,0});
        }
        Collections.shuffle(list,random);
        if(list.size()>40)list=new ArrayList<>(list.subList(0,40));
        return list;
    }

    private void show(){
        if(position>=queue.size()){summary();return;}
        int[] item=queue.get(position);
        JSONObject word=words.optJSONObject(item[0]);
        if(word==null){position++;show();return;}
        correctSense=item[1];
        ui.start("词性义项");
        ui.body.addView(ui.text((position+1)+" / "+queue.size(),14,WordUi.MUTED));ui.gap(ui.body,16);
        LinearLayout card=ui.col();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(ui.dp(22),ui.dp(34),ui.dp(22),ui.dp(30));card.setBackground(ui.bg(0xFFFFFFFF,24));
        TextView english=ui.text(word.optString("english"),32,WordUi.INK);english.setGravity(Gravity.CENTER);english.setTypeface(null,1);card.addView(english);
        ui.body.addView(ui.text("选出正确的词性 / 释义",14,WordUi.MUTED));ui.gap(ui.body,10);
        ui.body.addView(card);ui.gap(ui.body,18);
        try{
            JSONArray options=Senses.quizOptions(word,correctSense,words,random);
            for(int i=0;i<options.length();i++){
                JSONObject opt=options.getJSONObject(i);
                boolean isCorrect=opt.optBoolean("correct");
                String label=opt.optString("label");
                final int senseIndex=opt.optInt("senseIndex",-1);
                ui.body.addView(ui.button(label,false,v->pick(isCorrect,senseIndex,item[0])));
                ui.gap(ui.body,10);
            }
        }catch(Exception e){ui.toast(e.getMessage());}
        Motion.enter(ui.body);
    }

    private void pick(boolean isCorrect,int senseIndex,int wordIndex){
        if(answered)return;answered=true;
        try{
            JSONArray tasks=Store.tasks(this);JSONObject task=Store.find(tasks,taskId);
            if(task==null)throw new Exception("任务不存在");
            JSONArray savedWords=task.getJSONArray("words");
            if(isCorrect){
                interaction.preview(this,Feedback.KNOWN);
                JSONObject saved=new JSONObject(savedWords.getJSONObject(wordIndex).toString());
                Senses.rememberSense(saved,senseIndex>=0?senseIndex:correctSense);
                savedWords.put(wordIndex,saved);task.put("words",savedWords);Store.save(this,tasks);
                words.put(wordIndex,saved);correct++;
            }else{
                interaction.preview(this,Feedback.RETRY);
                WrongBook.record(this,taskId,wordIndex,Math.max(1,task.optInt("stage")+1),savedWords,"sense:"+System.currentTimeMillis()+":"+wordIndex+":"+correctSense);
                tasks=Store.tasks(this);task=Store.find(tasks,taskId);
                savedWords=task.getJSONArray("words");
                JSONObject saved=new JSONObject(savedWords.getJSONObject(wordIndex).toString());
                Senses.forgetSense(saved,correctSense);
                savedWords.put(wordIndex,saved);task.put("words",savedWords);Store.save(this,tasks);
                words.put(wordIndex,saved);wrong++;
            }
            ui.toast(isCorrect?"正确":"再记一下这个义项");
            ui.body.postDelayed(()->{if(isFinishing()||isDestroyed())return;answered=false;position++;show();},650);
        }catch(Exception e){answered=false;ui.toast(e.getMessage());}
    }

    private void summary(){
        ui.start("义项练习完成");
        ui.body.addView(ui.text("正确 "+correct+" · 错误 "+wrong,16,WordUi.INK));ui.gap(ui.body,18);
        ui.body.addView(ui.button("再练一轮",true,v->{position=0;correct=0;wrong=0;answered=false;try{queue=buildQueue(null);}catch(Exception e){ui.toast(e.getMessage());return;}show();}));ui.gap(ui.body,10);
        ui.body.addView(ui.button("返回",false,v->finish()));
        Motion.enter(ui.body);
        interaction.preview(this,Feedback.COMPLETE);
    }
}
