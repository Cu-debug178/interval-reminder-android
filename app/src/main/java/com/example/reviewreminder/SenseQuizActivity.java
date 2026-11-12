package com.example.reviewreminder;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Phase 3: English front → choose POS/sense among distractors.
 *
 * Same modal language as the flashcard session: progress line, the headword on a white card,
 * options as full-width rows. Choosing marks the right answer (pine + check) and, if needed,
 * the wrong pick (terracotta + ✕); the user continues with one pinned button.
 */
public final class SenseQuizActivity extends ScreenActivity {
    private WordUi ui;
    private long taskId;
    private JSONArray words;
    private List<int[]> queue;
    private int position;
    private int correct,wrong;
    private boolean answered;
    private int correctSense=-1;
    private final Feedback interaction=new Feedback();
    private final Random random=new Random();

    // Chrome kept across questions.
    private boolean chrome;
    private ProgressLine progress;
    private TextView counter;
    private FrameLayout stage;
    private View question;
    private final List<LinearLayout> optionRows=new ArrayList<>();
    private final List<Boolean> optionCorrect=new ArrayList<>();
    private TextView result;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        interaction.prepare(this);
        ui=new WordUi(this);
        taskId=getIntent().getLongExtra("taskId",0);
        try{
            JSONObject task=Store.find(Store.tasks(this),taskId);
            if(task==null)throw new Exception("任务不存在");
            words=task.getJSONArray("words");
            if(words.length()==0)throw new Exception("词组为空");
            queue=buildQueue(state);
            if(state!=null){
                position=state.getInt("position");
                correct=state.getInt("correct");
                wrong=state.getInt("wrong");
                answered=state.getBoolean("answered");
                // The pick was already recorded before the restart; move on instead of locking the page.
                if(answered){answered=false;position++;}
            }
            show();
        }catch(Exception e){
            finish();
            ui.toast(e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putInt("position",position);
        out.putInt("correct",correct);
        out.putInt("wrong",wrong);
        out.putBoolean("answered",answered);
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
                JSONArray item=packed.optJSONArray(i);
                if(item==null||item.length()<2)continue;
                list.add(new int[]{item.optInt(0),item.optInt(1)});
            }
            if(!list.isEmpty())return list;
        }
        boolean wrongOnly=getIntent().getBooleanExtra("wrongOnly",false);
        for(int i=0;i<words.length();i++){
            JSONObject word=words.optJSONObject(i);
            if(word==null)continue;
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

    // ── Question page ─────────────────────────────────────────────────────────

    private void buildChrome(){
        ui.startModal("词性义项");
        ui.body.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_4),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_24));
        progress=new ProgressLine(this);
        progress.set(0,false);
        ui.body.addView(progress,WordUi.lp());
        counter=ui.type("",Theme.T_CALLOUT);
        counter.setTextColor(Theme.INK_2);
        counter.setFontFeatureSettings("tnum");
        counter.setMinHeight(ui.dp(28));
        counter.setGravity(Gravity.CENTER_VERTICAL);
        ui.body.addView(counter,ui.lpMargin(Theme.SPACE_12,0));
        stage=new FrameLayout(this);
        ui.body.addView(stage,ui.lpMargin(Theme.SPACE_16,0));
        chrome=true;
        Notice.setBottomOffset(this,Theme.BUTTON_HEIGHT+Theme.SPACE_24);
    }

    private void show(){
        show(0);
    }

    private void show(int enterFrom){
        if(position>=queue.size()){summary();return;}
        int[] item=queue.get(position);
        JSONObject word=words.optJSONObject(item[0]);
        if(word==null){position++;show(enterFrom);return;}
        correctSense=item[1];
        boolean fresh=!chrome;
        if(fresh)buildChrome();
        counter.setText((position+1)+" / "+queue.size());
        progress.set(position/(float)queue.size(),!fresh);
        ui.pin();

        LinearLayout page=ui.col();

        LinearLayout card=ui.col();
        card.setGravity(Gravity.CENTER);
        card.setMinimumHeight(ui.dp(168));
        card.setPadding(ui.dp(Theme.SPACE_24),ui.dp(Theme.SPACE_24),ui.dp(Theme.SPACE_24),ui.dp(Theme.SPACE_24));
        card.setBackground(Theme.shape(this,Theme.SURFACE,24));
        TextView overline=ui.type("英文单词",Theme.T_OVERLINE);
        overline.setGravity(Gravity.CENTER);
        card.addView(overline,WordUi.lp());
        String headword=word.optString("english");
        TextView english=new TextView(this);
        english.setText(headword);
        english.setGravity(Gravity.CENTER);
        english.setTypeface(Theme.SERIF);
        english.setTextColor(Theme.INK);
        english.setTextSize(headword.length()>22?26:headword.length()>13?30:34);
        english.setLetterSpacing(-0.01f);
        english.setLineSpacing(0,1.1f);
        card.addView(english,ui.lpMargin(Theme.SPACE_16,Theme.SPACE_8));
        page.addView(card,WordUi.lp());

        TextView prompt=ui.type("选出正确的词性 / 释义",Theme.T_CALLOUT);
        prompt.setTypeface(Theme.MEDIUM);
        prompt.setPadding(ui.dp(Theme.SPACE_4),0,0,0);
        page.addView(prompt,ui.lpMargin(Theme.SPACE_24,Theme.SPACE_12));

        optionRows.clear();
        optionCorrect.clear();
        try{
            JSONArray options=Senses.quizOptions(word,correctSense,words,random);
            for(int i=0;i<options.length();i++){
                JSONObject opt=options.getJSONObject(i);
                boolean isCorrect=opt.optBoolean("correct");
                String label=opt.optString("label");
                final int senseIndex=opt.optInt("senseIndex",-1);
                LinearLayout row=optionRow(label);
                row.setOnClickListener(v->pick(isCorrect,senseIndex,item[0],(LinearLayout)v));
                optionRows.add(row);
                optionCorrect.add(isCorrect);
                page.addView(row,ui.lpMargin(i==0?0:Theme.SPACE_8,0));
            }
        }catch(Exception e){
            ui.error(e.getMessage());
        }

        result=ui.type("",Theme.T_CALLOUT);
        result.setGravity(Gravity.CENTER);
        result.setVisibility(View.INVISIBLE);
        page.addView(result,ui.lpMargin(Theme.SPACE_16,0));

        stage.removeAllViews();
        stage.addView(page,new FrameLayout.LayoutParams(-1,-2));
        question=page;
        Motion.enter(page,enterFrom);
    }

    /** Full-width selectable row on white: label · (mark appears after choosing). */
    private LinearLayout optionRow(String label){
        LinearLayout row=ui.row();
        row.setMinimumHeight(ui.dp(Theme.ROW_HEIGHT));
        row.setPadding(ui.dp(Theme.ROW_PAD),ui.dp(Theme.SPACE_12),ui.dp(Theme.ROW_PAD),ui.dp(Theme.SPACE_12));
        row.setBackground(Theme.shape(this,Theme.SURFACE,Theme.RADIUS_BUTTON));
        TextView text=ui.type(label,Theme.T_BODY);
        text.setTextSize(16);
        row.addView(text,WordUi.weight());
        ImageView mark=new ImageView(this);
        mark.setVisibility(View.GONE);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(ui.dp(Theme.ICON_SMALL+2),ui.dp(Theme.ICON_SMALL+2));
        mp.leftMargin=ui.dp(Theme.SPACE_12);
        row.addView(mark,mp);
        row.setClickable(true);
        row.setFocusable(true);
        Theme.applyRipple(row,row.getBackground(),Theme.RIPPLE_LIGHT);
        Motion.press(row,.985f);
        return row;
    }

    private void pick(boolean isCorrect,int senseIndex,int wordIndex,LinearLayout chosen){
        if(answered)return;
        answered=true;
        try{
            JSONArray tasks=Store.tasks(this);
            JSONObject task=Store.find(tasks,taskId);
            if(task==null)throw new Exception("任务不存在");
            JSONArray savedWords=task.getJSONArray("words");
            if(isCorrect){
                JSONObject saved=new JSONObject(savedWords.getJSONObject(wordIndex).toString());
                Senses.rememberSense(saved,senseIndex>=0?senseIndex:correctSense);
                savedWords.put(wordIndex,saved);
                task.put("words",savedWords);
                Store.save(this,tasks);
                words.put(wordIndex,saved);
                correct++;
            }else{
                WrongBook.record(this,taskId,wordIndex,Math.max(1,task.optInt("stage")+1),savedWords,"sense:"+System.currentTimeMillis()+":"+wordIndex+":"+correctSense);
                tasks=Store.tasks(this);
                task=Store.find(tasks,taskId);
                savedWords=task.getJSONArray("words");
                JSONObject saved=new JSONObject(savedWords.getJSONObject(wordIndex).toString());
                Senses.forgetSense(saved,correctSense);
                savedWords.put(wordIndex,saved);
                task.put("words",savedWords);
                Store.save(this,tasks);
                words.put(wordIndex,saved);
                wrong++;
            }
        }catch(Exception e){
            answered=false;
            ui.error(e.getMessage());
            return;
        }
        interaction.respond(chosen,isCorrect?Feedback.KNOWN:Feedback.RETRY);
        markOptions(chosen,isCorrect);
        result.setText(isCorrect?"正确":"再记一下这个义项");
        result.setTextColor(isCorrect?Theme.PRIMARY:Theme.DANGER);
        result.setVisibility(View.VISIBLE);
        Motion.enter(result,0,60);
        progress.set((position+1)/(float)queue.size(),true);
        boolean last=position+1>=queue.size();
        ui.pin(ui.primaryButton(last?"查看结果":"下一题",v->next()));
        if(ui.footer.getChildCount()>0)Motion.enter(ui.footer.getChildAt(0));
    }

    /** Correct option turns pine with a check; a wrong pick turns terracotta with ✕; the rest step back. */
    private void markOptions(LinearLayout chosen,boolean chosenCorrect){
        for(int i=0;i<optionRows.size();i++){
            LinearLayout row=optionRows.get(i);
            row.setOnTouchListener(null);
            row.setOnClickListener(null);
            row.setClickable(false);
            row.setScaleX(1);
            row.setScaleY(1);
            boolean isCorrect=optionCorrect.get(i);
            TextView text=(TextView)row.getChildAt(0);
            ImageView mark=(ImageView)row.getChildAt(1);
            if(isCorrect){
                paint(row,text,mark,Theme.PRIMARY_SOFT,Theme.PRIMARY,R.drawable.ic_check);
            }else if(row==chosen&&!chosenCorrect){
                paint(row,text,mark,Theme.DANGER_SOFT,Theme.DANGER,R.drawable.ic_x);
            }else if(Motion.enabled(this)){
                row.animate().alpha(.45f).setDuration(Theme.ENTER_MS).setInterpolator(Motion.EASE).start();
            }else row.setAlpha(.45f);
        }
    }

    private void paint(LinearLayout row,TextView text,ImageView mark,int fill,int fg,int icon){
        row.setBackground(Theme.shape(this,fill,Theme.RADIUS_BUTTON));
        text.setTextColor(fg);
        text.setTypeface(Theme.MEDIUM);
        mark.setImageResource(icon);
        mark.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
        mark.setVisibility(View.VISIBLE);
        Motion.enter(mark);
    }

    private void next(){
        if(!answered)return;
        answered=false;
        position++;
        View current=question;
        if(current==null){show(1);return;}
        Motion.leave(current,1,()->{
            if(isFinishing()||isDestroyed())return;
            show(1);
        });
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private void summary(){
        chrome=false;
        question=null;
        ui.startModal("义项练习完成");

        LinearLayout hero=ui.col();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0,ui.dp(Theme.SPACE_40),0,0);
        FrameLayout halo=new FrameLayout(this);
        halo.setBackground(Theme.oval(Theme.PRIMARY_SOFT));
        halo.addView(ui.icon(R.drawable.ic_circle_check,Theme.PRIMARY),new FrameLayout.LayoutParams(ui.dp(30),ui.dp(30),Gravity.CENTER));
        hero.addView(halo,new LinearLayout.LayoutParams(ui.dp(68),ui.dp(68)));
        TextView title=ui.type("这组义项练完了",Theme.T_TITLE);
        title.setGravity(Gravity.CENTER);
        hero.addView(title,ui.lpMargin(Theme.SPACE_20,0));
        TextView score=ui.type("正确 "+correct+" · 错误 "+wrong,Theme.T_CALLOUT);
        score.setGravity(Gravity.CENTER);
        score.setFontFeatureSettings("tnum");
        hero.addView(score,ui.lpMargin(Theme.SPACE_8,0));
        if(wrong>0){
            TextView note=ui.type("答错的义项已记入错题本。",Theme.T_CAPTION);
            note.setGravity(Gravity.CENTER);
            hero.addView(note,ui.lpMargin(Theme.SPACE_16,0));
        }
        ui.body.addView(hero,WordUi.lp());

        Button home=ui.textButton("返回",v->finish());
        home.setTextColor(Theme.INK_2);
        ui.pin(ui.primaryButton("再练一轮",v->{
            position=0;
            correct=0;
            wrong=0;
            answered=false;
            try{
                queue=buildQueue(null);
            }catch(Exception e){
                ui.error(e.getMessage());
                return;
            }
            show();
        }),home);

        Motion.stagger(hero);
        interaction.confirm(getWindow().getDecorView());
    }
}
