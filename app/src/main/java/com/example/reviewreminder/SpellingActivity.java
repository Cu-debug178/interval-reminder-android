package com.example.reviewreminder;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SpellingActivity extends ScreenActivity {
    private WordUi ui;private long taskId,historyAt;private int round;private String attemptId,sessionId,selectionId;
    private JSONArray words;private SpellingSession session;private boolean wrongMode,answered,answerWasCorrect,hintUsed;
    private int[] exampleIndices=new int[0];
    private EditText answerInput;private String draft="",draftWordId="";private SpeechManager speech;private final Feedback interaction=new Feedback();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);interaction.prepare(this);speech=new SpeechManager(this);
        taskId=getIntent().getLongExtra("taskId",0);round=getIntent().getIntExtra("round",-1);historyAt=getIntent().getLongExtra("historyAt",0);exampleIndices=getIntent().getIntArrayExtra("exampleIndices");if(exampleIndices==null)exampleIndices=new int[0];
        attemptId=getIntent().getStringExtra("attemptId");if(attemptId==null||attemptId.isEmpty())attemptId=UUID.randomUUID().toString();
        sessionId=state==null?getIntent().getStringExtra("sessionId"):state.getString("sessionId");if(sessionId==null||sessionId.isEmpty())sessionId=UUID.randomUUID().toString();
        selectionId=getIntent().getStringExtra("selectionId");wrongMode=getIntent().getBooleanExtra("wrongMode",false);
        try{
            JSONObject saved=TrainingStore.spelling(this,sessionId);
            if(saved!=null){selectionId=saved.optString("selectionId",selectionId);wrongMode=saved.optBoolean("wrongMode",wrongMode);taskId=saved.optLong("taskId",taskId);round=saved.optInt("round",round);historyAt=saved.optLong("historyAt",historyAt);attemptId=saved.optString("attemptId",attemptId);}
            JSONObject selection=TrainingStore.selection(this,selectionId);
            if(selection==null&&taskId>0)selectionId=TrainingStore.forTask(this,taskId,getIntent().getIntArrayExtra("indices"));
            if(selection==null)selection=TrainingStore.selection(this,selectionId);
            if(selection==null)throw new IllegalStateException("拼写选词已失效，请重新开始");words=selection.getJSONArray("words");selectionId=selection.optString("id",selectionId);
            if(saved!=null)session=SpellingSession.restore(saved.getString("snapshot"));else{
                List<SpellingSession.Item> items=new ArrayList<>();for(int i=0;i<words.length();i++){JSONObject word=words.optJSONObject(i);if(word==null)continue;items.add(new SpellingSession.Item(Words.stableId(word),WrongBook.stateForWord(this,word).optInt("streak",word.optInt("spellingStreak",0))));}
                session=new SpellingSession(items,wrongMode);
            }
            String currentId=session.ended()?"":session.current();
            boolean storedHint=saved!=null&&!currentId.isEmpty()&&currentId.equals(saved.optString("hintWord"))&&saved.optBoolean("hintUsed");
            if(state!=null){hintUsed=state.getBoolean("hintUsed",false)||storedHint;String owned=state.getString("draftWordId","");draft=currentId.equals(owned)?state.getString("draft",""):"";}
            else if(storedHint)hintUsed=true;
            saveProgress();show();
        }catch(Exception e){ui.toast(e.getMessage());finish();}
    }

    @Override protected void onSaveInstanceState(Bundle out){out.putString("sessionId",sessionId);if(answerInput!=null)out.putString("draft",answerInput.getText().toString());out.putString("draftWordId",draftWordId);out.putBoolean("hintUsed",hintUsed);super.onSaveInstanceState(out);}

    private void show(){
        int dropped=0;while(!session.ended()&&find(session.current())==null){if(!session.abandon(session.current())||++dropped>10000){ui.toast("有单词已不在本次练习中");finish();return;}}
        if(dropped>0)saveProgress();
        if(session.ended()){summary();return;}
        String wordId=session.current();JSONObject word=find(wordId);draftWordId=wordId;
        answered=false;answerWasCorrect=false;
        ui.start(wrongMode?"错词强化":"拼写练习");
        String progress="已完成 "+session.finishedCount()+" / "+session.initialTotal()+" 个";
        if(wrongMode)progress+=" · 本题连续独立正确 "+session.currentStreak(wordId)+" / 2";
        ui.body.addView(ui.text(progress,14,WordUi.MUTED));ui.gap(ui.body,16);
        LinearLayout card=ui.card();card.setGravity(Gravity.CENTER);card.setMinimumHeight(ui.dp(238));card.setPadding(ui.dp(22),ui.dp(28),ui.dp(22),ui.dp(28));
        TextView label=ui.text("根据中文写出英文",13,WordUi.MUTED);label.setGravity(Gravity.CENTER);card.addView(label);ui.gap(card,20);
        TextView meaning=ui.text(word.optString("chinese",""),27,WordUi.INK);meaning.setGravity(Gravity.CENTER);meaning.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(meaning);
        String example=word.optString("example","");if(!example.isEmpty()){ui.gap(card,14);TextView sentence=ui.text(example,15,WordUi.MUTED);sentence.setGravity(Gravity.CENTER);sentence.setContentDescription("朗读例句："+example);sentence.setOnClickListener(v->speech.speak(example,"example"));Motion.press(sentence);card.addView(sentence);}
        ui.body.addView(card);ui.gap(ui.body,16);
        EditText answer=new EditText(this);answerInput=answer;answer.setSingleLine(true);answer.setTextSize(20);answer.setHint("输入英文单词");answer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);answer.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12));answer.setBackground(ui.bg(Color.WHITE,14));answer.setText(draft);answer.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){draft=s.toString();}public void afterTextChanged(android.text.Editable e){}});ui.body.addView(answer,new LinearLayout.LayoutParams(-1,ui.dp(58)));ui.gap(ui.body,10);
        TextView result=ui.text("",15,WordUi.GREEN);result.setGravity(Gravity.CENTER);ui.body.addView(result);ui.gap(ui.body,8);
        Button submit=ui.button("检查拼写",true,null);ui.body.addView(submit);ui.gap(ui.body,10);
        Button hint=ui.button(hintUsed?"已使用读音提示 · 再听一次":"读音提示",false,v->{hintUsed=true;saveProgress();speech.speak(word.optString("english"),"spelling_hint");});ui.body.addView(hint);ui.gap(ui.body,8);
        LinearLayout secondary=ui.row();secondary.addView(ui.ghostButton("跳过此词",v->{if(!answered)skip(word,false,null,null);}),new LinearLayout.LayoutParams(0,ui.dp(48),1));LinearLayout.LayoutParams revealLp=new LinearLayout.LayoutParams(0,ui.dp(48),1);revealLp.leftMargin=ui.dp(8);secondary.addView(ui.ghostButton("查看答案",v->{if(!answered)skip(word,true,result,submit);}),revealLp);ui.body.addView(secondary);
        submit.setOnClickListener(v->{
            if(answered){draft="";show();return;}String typed=answer.getText().toString().trim();if(typed.isEmpty()){answer.setError("请输入英文单词");return;}
            String before=session.snapshot();String expected=word.optString("english").trim();boolean correct=expected.equalsIgnoreCase(typed);String id=Words.stableId(word);boolean assisted=hintUsed;SpellingSession.Result outcome=session.submit(id,correct,assisted);if(!outcome.accepted)return;
            try{applyOutcome(word,outcome);}catch(Exception e){session=SpellingSession.restore(before);ui.toast("结果保存失败，请重试："+e.getMessage());return;}
            answered=true;answerWasCorrect=correct;answer.setEnabled(false);hintUsed=false;saveProgress();
            if(correct){result.setText(outcome.independent?"正确 · 已计入独立拼写":"拼写正确 · 使用提示，稍后仍需独立复习");speech.speak(expected,"spelling_success");}
            else result.setText("正确拼写："+expected+"\n稍后会再次出现");
            interaction.respond(submit,correct?Feedback.KNOWN:Feedback.RETRY);submit.setText(session.ended()?"查看结果":"下一个");
        });
        answer.setOnEditorActionListener((v,action,event)->{submit.performClick();return true;});Motion.enter(card);
    }

    private void applyOutcome(JSONObject word,SpellingSession.Result outcome)throws Exception{
        long sourceTask=word.optLong("_taskId",taskId);int index=word.optInt("_wordIndex",-1);if(sourceTask<=0||index<0)return;
        String event="spelling:"+attemptId+":"+session.attempts()+":"+outcome.wordId;
        if(!outcome.correct)WrongBook.record(this,sourceTask,index,round,null,event,outcome.wordId);
        else if(outcome.independent)WrongBook.independent(this,sourceTask,index,null,event,outcome.wordId);
        else WrongBook.assisted(this,sourceTask,index,null,event,outcome.wordId);
    }

    private void skip(JSONObject word,boolean reveal,TextView result,Button submit){
        String before=session.snapshot(),id=Words.stableId(word);if(!session.skip(id))return;
        try{long sourceTask=word.optLong("_taskId",taskId);int index=word.optInt("_wordIndex",-1);if(sourceTask>0&&index>=0)WrongBook.reset(this,sourceTask,index,null,"spelling:"+attemptId+":"+session.attempts()+":"+id,id);}catch(Exception e){session=SpellingSession.restore(before);ui.toast(e.getMessage());return;}
        hintUsed=false;saveProgress();if(reveal){answered=true;answerWasCorrect=false;answerInput.setEnabled(false);result.setText("正确拼写："+word.optString("english")+"\n已查看答案，本词不会计入完成");submit.setText(session.ended()?"查看结果":"下一个");}else{draft="";show();}
    }

    private JSONObject find(String id){for(int i=0;i<words.length();i++){JSONObject word=words.optJSONObject(i);if(word!=null&&id.equals(Words.stableId(word)))return word;}return null;}
    private void saveProgress(){try{if(session!=null)TrainingStore.saveSpelling(this,sessionId,selectionId,wrongMode,taskId,round,historyAt,attemptId,session.snapshot(),session.ended()?"":session.current(),hintUsed);}catch(Exception e){ui.toast(e.getMessage());}}
    private void summary(){
        saveResult();TrainingStore.clearSpelling(this,sessionId);ui.start("拼写完成");interaction.confirm(ui.body);ui.body.addView(ui.text(session.initialTotal()+" 个目标词均已完成独立拼写",20,WordUi.INK));ui.gap(ui.body,8);ui.body.addView(ui.text("独立/辅助共答对 "+session.correctCount()+" 次 · 错误 "+session.wrongCount()+" 次 · 跳过 "+session.skippedCount()+" 次",14,WordUi.MUTED));ui.gap(ui.body,20);ui.body.addView(ui.button(exampleIndices.length>0?"继续补充例句":"返回",true,v->continueAfter()));Motion.enter(ui.body);
    }
    private void continueAfter(){int[] missing=ExampleActivity.missingExampleIndices(words,exampleIndices);if(missing.length>0)Motion.open(this,new android.content.Intent(this,ExampleActivity.class).putExtra("taskId",taskId).putExtra("indices",missing));finish();
    }
    private void saveResult(){if(historyAt<=0)return;try{JSONArray tasks=Store.tasks(this);JSONObject task=Store.find(tasks,taskId);if(task==null)return;JSONArray history=task.optJSONArray("history");if(history==null)return;for(int i=history.length()-1;i>=0;i--){JSONObject entry=history.optJSONObject(i);if(entry!=null&&entry.optLong("at")==historyAt&&entry.optInt("stage")==round){entry.put("spellingTotal",session.initialTotal()).put("spellingCorrect",session.correctCount()).put("spellingWrong",session.wrongCount()).put("spellingSkipped",session.skippedCount()).put("spellingCompleted",session.ended());Store.save(this,tasks);return;}}}catch(Exception e){Toast.makeText(this,"拼写成绩暂未写入："+e.getMessage(),Toast.LENGTH_LONG).show();}}
    @Override public void onBackPressed(){if(session!=null&&session.ended()){finish();return;}saveProgress();ui.toast("进度已保存，本次仍记为未完成");finish();}
    @Override protected void onPause(){speech.stop();super.onPause();}
    @Override protected void onDestroy(){speech.shutdown();interaction.release();super.onDestroy();}
}
