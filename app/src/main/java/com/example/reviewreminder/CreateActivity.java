package com.example.reviewreminder;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Future;

public final class CreateActivity extends ScreenActivity {
    private WordUi ui;private JSONArray pool;private int wordIndex=-1;private EditText sentence;private Future<?> pending;private TextView prompt,feedback;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);
        try{
            pool=buildPool();
            if(pool.length()==0)throw new Exception("还没有单词卡，请先录词并创建复习任务");
            if(state!=null)wordIndex=state.getInt("wordIndex",-1);
            if(wordIndex<0||wordIndex>=pool.length())pickWord();
            show();
        }catch(Exception e){ui.toast(e.getMessage());finish();}
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("wordIndex",wordIndex);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){AiClient.cancel(pending);super.onDestroy();}

    private JSONArray buildPool()throws Exception{
        JSONArray out=new JSONArray();JSONArray tasks=Store.tasks(this);
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;
            JSONArray words=task.optJSONArray("words");if(words==null)continue;
            for(int j=0;j<words.length();j++){
                JSONObject w=words.optJSONObject(j);if(w==null)continue;
                String en=w.optString("english","").trim();if(en.isEmpty())continue;
                out.put(new JSONObject().put("english",en).put("chinese",w.optString("chinese")).put("taskId",task.optLong("id")).put("wordIndex",j));
            }
        }
        return out;
    }

    private void pickWord(){wordIndex=new Random().nextInt(pool.length());}

    private void show(){
        JSONObject item=pool.optJSONObject(wordIndex);if(item==null){finish();return;}
        ui.start("创造力造句");
        ui.body.addView(ui.text("用指定单词写一句英文，AI 会批改并记录问题。",13,WordUi.MUTED));ui.gap(ui.body,16);
        LinearLayout card=ui.col();card.setGravity(Gravity.CENTER);card.setPadding(ui.dp(18),ui.dp(22),ui.dp(18),ui.dp(22));card.setBackground(ui.bg(0xFFFFFFFF,18));
        prompt=ui.text(item.optString("english"),28,WordUi.INK);prompt.setGravity(Gravity.CENTER);prompt.setTypeface(null,1);card.addView(prompt);
        TextView zh=ui.text(item.optString("chinese"),16,WordUi.GREEN);zh.setGravity(Gravity.CENTER);card.addView(zh);
        ui.body.addView(card);ui.gap(ui.body,14);
        sentence=ui.field("你的造句","",3);
        feedback=ui.text("",14,WordUi.MUTED);ui.body.addView(feedback);ui.gap(ui.body,12);
        ui.body.addView(ui.button("提交批改",true,v->grade(item)));ui.gap(ui.body,10);
        ui.body.addView(ui.button("换一个词",false,v->{pickWord();show();}));
        Motion.enter(ui.body);
    }

    private void grade(JSONObject item){
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        String text=sentence.getText().toString().trim();
        if(text.isEmpty()){ui.toast("请先写一句");return;}
        feedback.setText("正在批改…");
        String english=item.optString("english");
        pending=AiClient.chatJson(this,AiPrompts.systemCreateGrade(),AiPrompts.userCreateGrade(english,text),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                boolean ok=json.optBoolean("ok",false);
                String corrected=json.optString("corrected",text).trim();
                String tip=json.optString("feedback","").trim();
                JSONArray issues=json.optJSONArray("issues");
                StringBuilder body=new StringBuilder();
                body.append(ok?"通过：":"需改进：").append(tip.isEmpty()?(ok?"用法正确":"请看改正建议"):tip);
                if(!corrected.isEmpty()&&!corrected.equals(text))body.append("\n建议：").append(corrected);
                if(issues!=null)for(int i=0;i<issues.length();i++){String issue=issues.optString(i).trim();if(!issue.isEmpty())body.append("\n· ").append(issue);}
                feedback.setText(body.toString());
                if(!ok)try{AiNotes.addMistake(CreateActivity.this,english,text,tip.isEmpty()?body.toString():tip);ui.toast("已记入造句问题，可在错题本查看");}catch(Exception e){ui.toast(e.getMessage());}
            }
            @Override public void onError(String message){feedback.setText("");ui.toast(message);}
        });
    }
}
