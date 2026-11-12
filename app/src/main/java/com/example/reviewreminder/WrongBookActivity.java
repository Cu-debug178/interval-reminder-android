package com.example.reviewreminder;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.Future;

public final class WrongBookActivity extends ScreenActivity {
    private LinearLayout body;private Future<?> pending;
    @Override public void onCreate(Bundle state){super.onCreate(state);render();}
    @Override protected void onResume(){super.onResume();if(body!=null)render();}
    @Override protected void onDestroy(){AiClient.cancel(pending);super.onDestroy();}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private WordUi ui(){return new WordUi(this);}

    private void render(){
        LinearLayout screen=new LinearLayout(this);screen.setOrientation(LinearLayout.VERTICAL);screen.setBackgroundColor(WordUi.BG);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(18),dp(8),dp(18),dp(8));
        android.widget.ImageButton back=new android.widget.ImageButton(this);back.setImageResource(R.drawable.ic_arrow_left);back.setContentDescription("返回");back.setBackground(ui().bg(0xFFDEF2EA,16));back.setOnClickListener(v->finish());Motion.press(back);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=new TextView(this);title.setText("错题本");title.setTextSize(21);title.setTypeface(null,1);title.setTextColor(WordUi.INK);LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(-2,-2);titleLp.leftMargin=dp(12);header.addView(title,titleLp);screen.addView(header);
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(10),dp(20),dp(28));scroll.addView(body);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(screen);

        body.addView(ui().button("AI 情景短文（全部错词）",false,v->aiScenarioAll()));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,dp(12));body.addView(new android.view.View(this),gap);

        JSONArray mistakes=AiNotes.all(this);
        if(mistakes.length()>0){
            TextView section=new TextView(this);section.setText("造句问题");section.setTextSize(16);section.setTypeface(null,1);section.setTextColor(WordUi.INK);body.addView(section);
            for(int i=mistakes.length()-1;i>=0&&i>=mistakes.length()-8;i--){
                JSONObject m=mistakes.optJSONObject(i);if(m==null)continue;
                TextView row=new TextView(this);row.setText(m.optString("english")+"\n"+m.optString("sentence")+"\n"+m.optString("feedback"));row.setTextSize(13);row.setTextColor(WordUi.MUTED);row.setPadding(0,dp(8),0,dp(8));body.addView(row);
            }
            body.addView(new android.view.View(this),gap);
        }

        JSONArray tasks=Store.tasks(this);
        java.util.List<Object[]> rows=new java.util.ArrayList<>();
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null||word.optInt("wrongCount")<=0)continue;rows.add(new Object[]{task,word,j});}}
        java.util.Collections.sort(rows,(a,b)->{
            int ma=Mastery.level((JSONObject)a[1]), mb=Mastery.level((JSONObject)b[1]);
            if(ma!=mb)return Integer.compare(ma,mb);
            return Integer.compare(((JSONObject)b[1]).optInt("wrongCount"),((JSONObject)a[1]).optInt("wrongCount"));
        });
        if(rows.isEmpty()){TextView empty=new TextView(this);empty.setText("还没有错词。复习时选择“忘记了”或拼写错误的单词会自动收录到这里。");empty.setTextSize(15);empty.setTextColor(WordUi.MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(45),dp(24),dp(45));body.addView(empty);}
        else{
            TextView tip=new TextView(this);tip.setText("已按掌握度从低到高排列，优先练弱项。");tip.setTextSize(12);tip.setTextColor(WordUi.MUTED);tip.setPadding(0,0,0,dp(10));body.addView(tip);
            for(Object[] row:rows)addWord((JSONObject)row[0],(JSONObject)row[1],(Integer)row[2]);
        }
    }

    private void addWord(JSONObject task,JSONObject word,int index){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(ui().bg(Color.WHITE,20));card.setElevation(dp(2));
        TextView english=new TextView(this);english.setText(word.optString("english"));english.setTextSize(21);english.setTypeface(null,1);english.setTextColor(WordUi.INK);card.addView(english);
        TextView chinese=new TextView(this);chinese.setText(word.optString("chinese"));chinese.setTextSize(15);chinese.setTextColor(WordUi.GREEN);card.addView(chinese);
        TextView mastery=new TextView(this);mastery.setText(Mastery.label(Mastery.level(word)));mastery.setTextSize(12);mastery.setTextColor(WordUi.MUTED);card.addView(mastery);
        JSONArray rounds=word.optJSONArray("wrongRounds");StringBuilder labels=new StringBuilder();if(rounds!=null)for(int i=0;i<rounds.length();i++){if(i>0)labels.append("、");labels.append("第").append(rounds.optInt(i)).append("轮");}
        TextView record=new TextView(this);record.setText(task.optString("title")+" · 错误 "+word.optInt("wrongCount")+" 次\n出错复习："+(labels.length()==0?"未记录":labels));record.setTextSize(13);record.setTextColor(WordUi.MUTED);record.setPadding(0,dp(8),0,dp(8));card.addView(record);
        String note=word.optString("aiAnalysis","").trim();if(!note.isEmpty()){TextView analysis=new TextView(this);analysis.setText(note);analysis.setTextSize(13);analysis.setTextColor(WordUi.INK);analysis.setPadding(0,0,0,dp(8));card.addView(analysis);}

        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(ui().button("拼写此词",true,v->{Intent intent=new Intent(this,SpellingActivity.class).putExtra("taskId",task.optLong("id")).putExtra("indices",new int[]{index}).putExtra("round",-1).putExtra("attemptId",UUID.randomUUID().toString());startActivity(intent);}),new LinearLayout.LayoutParams(0,dp(50),1));
        LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(0,dp(50),1);moreLp.leftMargin=dp(8);
        long taskId=task.optLong("id");
        actions.addView(ui().button("更多",false,v->new AlertDialog.Builder(this).setTitle(word.optString("english")).setItems(new CharSequence[]{"义项练习","AI 分析","词根对比","清除记录"},(d,which)->{
            if(which==0)startActivity(new Intent(this,SenseQuizActivity.class).putExtra("taskId",taskId).putExtra("wrongOnly",true));
            else if(which==1)aiAnalyze(taskId,index,word);
            else if(which==2)aiRoots(word);
            else new AlertDialog.Builder(this).setTitle("清除错词记录？").setMessage("单词卡会保留，只清除错误次数和轮次记录。").setNegativeButton("取消",null).setPositiveButton("清除",(d2,w2)->{try{WrongBook.clear(this,taskId,index);render();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}).show();
        }).setNegativeButton("取消",null).show()),moreLp);
        card.addView(actions);

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);body.addView(card,lp);
    }

    private void aiAnalyze(long taskId,int index,JSONObject word){
        if(!AiSettings.ready(this)){Toast.makeText(this,AiSettings.notReadyMessage(this),Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,"正在分析…",Toast.LENGTH_SHORT).show();
        pending=AiClient.chatJson(this,AiPrompts.systemWrongAnalysis(),AiPrompts.userWrongAnalysis(word.optString("english"),word.optString("chinese"),word.optInt("wrongCount")),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                String text="记忆钩子："+json.optString("hooks")+"\n易混点："+json.optString("confusion")+"\n建议："+json.optString("tip");
                try{
                    JSONArray tasks=Store.tasks(WrongBookActivity.this);JSONObject task=Store.find(tasks,taskId);
                    if(task!=null){JSONArray words=task.getJSONArray("words");JSONObject saved=words.getJSONObject(index);AiNotes.putWordNote(saved,"aiAnalysis",text);words.put(index,saved);task.put("words",words);Store.save(WrongBookActivity.this,tasks);}
                }catch(Exception e){Toast.makeText(WrongBookActivity.this,e.getMessage(),Toast.LENGTH_LONG).show();}
                new AlertDialog.Builder(WrongBookActivity.this).setTitle("错题分析").setMessage(text).setPositiveButton("关闭",null).show();
                render();
            }
            @Override public void onError(String message){Toast.makeText(WrongBookActivity.this,message,Toast.LENGTH_LONG).show();}
        });
    }

    private void aiRoots(JSONObject word){
        if(!AiSettings.ready(this)){Toast.makeText(this,AiSettings.notReadyMessage(this),Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,"正在对比…",Toast.LENGTH_SHORT).show();
        pending=AiClient.chatJson(this,AiPrompts.systemRoots(),AiPrompts.userRoots(word.optString("english"),word.optString("chinese")),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                StringBuilder body=new StringBuilder();
                body.append("词根：").append(json.optString("root")).append('\n');
                JSONArray der=json.optJSONArray("derivatives");if(der!=null){body.append("派生：");for(int i=0;i<der.length();i++){if(i>0)body.append("、");body.append(der.optString(i));}body.append('\n');}
                JSONArray looks=json.optJSONArray("lookalikes");if(looks!=null)for(int i=0;i<looks.length();i++){JSONObject item=looks.optJSONObject(i);if(item==null)continue;body.append(item.optString("word")).append(" — ").append(item.optString("diff")).append('\n');}
                body.append("提示：").append(json.optString("tip"));
                new AlertDialog.Builder(WrongBookActivity.this).setTitle("词根 / 形近对比").setMessage(body.toString().trim()).setPositiveButton("关闭",null).show();
            }
            @Override public void onError(String message){Toast.makeText(WrongBookActivity.this,message,Toast.LENGTH_LONG).show();}
        });
    }

    private void aiScenarioAll(){
        if(!AiSettings.ready(this)){Toast.makeText(this,AiSettings.notReadyMessage(this),Toast.LENGTH_LONG).show();return;}
        try{
            JSONArray list=new JSONArray();JSONArray tasks=Store.tasks(this);
            for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;for(int j=0;j<words.length();j++){JSONObject w=words.optJSONObject(j);if(w!=null&&w.optInt("wrongCount")>0)list.put(new JSONObject().put("english",w.optString("english")).put("chinese",w.optString("chinese")));if(list.length()>=12)break;}if(list.length()>=12)break;}
            if(list.length()==0){Toast.makeText(this,"暂无错词可生成情景",Toast.LENGTH_LONG).show();return;}
            Toast.makeText(this,"正在生成情景…",Toast.LENGTH_SHORT).show();
            pending=AiClient.chatJson(this,AiPrompts.systemScenario(),AiPrompts.userScenario(list.toString()),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){
                    String msg=json.optString("title")+"\n\n"+json.optString("text");
                    new AlertDialog.Builder(WrongBookActivity.this).setTitle("情景练习").setMessage(msg.trim()).setPositiveButton("关闭",null).show();
                }
                @Override public void onError(String message){Toast.makeText(WrongBookActivity.this,message,Toast.LENGTH_LONG).show();}
            });
        }catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
