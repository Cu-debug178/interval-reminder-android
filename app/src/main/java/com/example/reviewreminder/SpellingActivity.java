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
import java.util.UUID;

public final class SpellingActivity extends ScreenActivity {
    private WordUi ui;
    private long taskId;
    private int round;
    private long historyAt;
    private String attemptId;
    private JSONArray words;
    private int[] indices,exampleIndices;
    private int position,correct,wrong;
    private boolean answered,answerWasCorrect;
    private EditText answerInput;
    private String draft="";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);taskId=getIntent().getLongExtra("taskId",0);round=getIntent().getIntExtra("round",-1);historyAt=getIntent().getLongExtra("historyAt",0);exampleIndices=getIntent().getIntArrayExtra("exampleIndices");if(exampleIndices==null)exampleIndices=new int[0];attemptId=getIntent().getStringExtra("attemptId");if(attemptId==null||attemptId.isEmpty()){attemptId=UUID.randomUUID().toString();getIntent().putExtra("attemptId",attemptId);}
        try{JSONObject task=Store.find(Store.tasks(this),taskId);if(task==null)throw new Exception("复习任务已不存在");words=task.getJSONArray("words");indices=SpellingPlan.indices(SpellingPlan.WRONG_ONLY,words.length(),getIntent().getIntArrayExtra("indices"));if(state!=null){position=Math.max(0,Math.min(indices.length,state.getInt("position")));correct=Math.max(0,state.getInt("correct"));wrong=Math.max(0,state.getInt("wrong"));answered=state.getBoolean("answered");answerWasCorrect=state.getBoolean("answerWasCorrect");draft=state.getString("draft","");}show();}
        catch(Exception e){ui.toast(e.getMessage());finish();}
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("position",position);out.putInt("correct",correct);out.putInt("wrong",wrong);out.putBoolean("answered",answered);out.putBoolean("answerWasCorrect",answerWasCorrect);if(answerInput!=null)out.putString("draft",answerInput.getText().toString());super.onSaveInstanceState(out);}

    private void show(){
        if(position>=indices.length){summary();return;}int wordIndex=indices[position];JSONObject word=words.optJSONObject(wordIndex);if(word==null){advance();return;}
        ui.start("拼写练习");ui.body.addView(ui.text((position+1)+" / "+indices.length+" 个",14,WordUi.MUTED));ui.gap(ui.body,20);
        LinearLayout card=ui.col();card.setGravity(Gravity.CENTER);card.setMinimumHeight(ui.dp(250));card.setPadding(ui.dp(22),ui.dp(28),ui.dp(22),ui.dp(28));card.setBackground(ui.bg(Color.WHITE,20));
        TextView label=ui.text("根据中文写出英文",13,WordUi.MUTED);label.setGravity(Gravity.CENTER);card.addView(label);ui.gap(card,22);
        TextView meaning=ui.text(word.optString("chinese",""),27,WordUi.INK);meaning.setGravity(Gravity.CENTER);meaning.setTypeface(null,1);card.addView(meaning);
        String example=word.optString("example","");if(!example.isEmpty()){ui.gap(card,14);TextView sentence=ui.text(example,15,WordUi.MUTED);sentence.setGravity(Gravity.CENTER);card.addView(sentence);}
        ui.body.addView(card);ui.gap(ui.body,20);
        EditText answer=new EditText(this);answerInput=answer;answer.setSingleLine(true);answer.setTextSize(20);answer.setHint("输入英文单词");answer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);answer.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12));answer.setBackground(ui.bg(Color.WHITE,14));answer.setText(draft);answer.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){draft=s.toString();}public void afterTextChanged(android.text.Editable e){}});ui.body.addView(answer,new LinearLayout.LayoutParams(-1,ui.dp(58)));ui.gap(ui.body,12);
        TextView result=ui.text("",18,WordUi.GREEN);result.setGravity(Gravity.CENTER);ui.body.addView(result);
        Button submit=ui.button(answered?(position+1<indices.length?"下一个":"查看结果"):"检查拼写",true,null);ui.gap(ui.body,10);ui.body.addView(submit);
        if(answered){answer.setEnabled(false);result.setText(answerWasCorrect?"正确":"正确拼写："+word.optString("english"));}
        submit.setOnClickListener(v->{
            if(answered){advance();return;}String typed=answer.getText().toString().trim();if(typed.isEmpty()){answer.setError("请输入英文单词");return;}
            String expected=word.optString("english").trim();if(expected.equalsIgnoreCase(typed)){answered=true;answerWasCorrect=true;correct++;result.setText("正确");}
            else{try{String event=(historyAt>0?"spelling:"+historyAt:"practice:"+attemptId)+":"+position+":"+wordIndex;WrongBook.record(this,taskId,wordIndex,round,words,event);answered=true;answerWasCorrect=false;wrong++;result.setText("正确拼写："+expected);}catch(Exception e){ui.toast("错题记录保存失败："+e.getMessage());return;}}
            answer.setEnabled(false);
            submit.setText(position+1<indices.length?"下一个":"查看结果");
        });
        ui.body.addView(ui.button("跳过此词",false,v->{if(!answered)advance();}));
        answer.setOnEditorActionListener((v,action,event)->{submit.performClick();return true;});Motion.enter(card);
    }
    private void advance(){position++;answered=false;answerWasCorrect=false;draft="";show();}
    private void summary(){saveResult();ui.start("拼写完成");ui.body.addView(ui.text("共 "+indices.length+" 个单词，正确 "+correct+" 个，拼写错误 "+wrong+" 个",18,WordUi.INK));ui.gap(ui.body,18);ui.body.addView(ui.button(exampleIndices.length>0?"继续补充例句":"返回主页",true,v->continueAfterSpelling()));}
    private void continueAfterSpelling(){
        int[] missing=ExampleActivity.missingExampleIndices(words,exampleIndices);
        if(missing.length>0)Motion.open(this,new android.content.Intent(this,ExampleActivity.class).putExtra("taskId",taskId).putExtra("indices",missing));
        finish();
    }
    private void saveResult(){if(historyAt<=0)return;try{JSONArray tasks=Store.tasks(this);JSONObject task=Store.find(tasks,taskId);if(task==null)return;JSONArray history=task.optJSONArray("history");if(history==null)return;for(int i=history.length()-1;i>=0;i--){JSONObject entry=history.optJSONObject(i);if(entry!=null&&entry.optLong("at")==historyAt&&entry.optInt("stage")==round){entry.put("spellingTotal",indices.length).put("spellingCorrect",correct).put("spellingWrong",wrong).put("spellingSkipped",Math.max(0,indices.length-correct-wrong));Store.save(this,tasks);return;}}}catch(Exception e){Toast.makeText(this,"拼写成绩暂未写入："+e.getMessage(),Toast.LENGTH_LONG).show();}}
    @Override public void onBackPressed(){finish();}
}
