package com.example.reviewreminder;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

public final class WrongBookActivity extends ScreenActivity {
    private LinearLayout body;private Future<?> pending;private int statusFilter=0;private WordUi ui;
    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);render();}
    @Override protected void onResume(){super.onResume();if(body!=null)render();}
    @Override protected void onDestroy(){AiClient.cancel(pending);super.onDestroy();}

    private void render(){
        LinearLayout screen=ui.col();screen.setBackgroundColor(WordUi.BG);screen.addView(ui.pageChrome("错题本"));ScrollView scroll=new ScrollView(this);body=ui.col();body.setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(28));scroll.addView(body);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(screen);ui.systemBars();
        Spinner filter=new Spinner(this);filter.setMinimumHeight(ui.dp(48));filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"未掌握","学习中（1/2）","已掌握（2/2）","全部错词"}));filter.setSelection(statusFilter);body.addView(filter);ui.gap(body,10);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int position,long id){if(position!=statusFilter){statusFilter=position;render();}}});
        body.addView(ui.secondaryButton("AI 情景短文（当前筛选）",v->aiScenarioAll()));ui.gap(body,16);
        List<Object[]> rows=rows();if(rows.isEmpty()){TextView empty=ui.text("这个状态下没有错词。拼写答错或复习时选择“忘记了”会自动收录；已掌握后仍保留历史。",15,WordUi.MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(ui.dp(24),ui.dp(36),ui.dp(24),ui.dp(36));body.addView(empty);return;}
        body.addView(ui.text("连续独立正确两次即为已掌握；再次出错会恢复未掌握。",12,WordUi.MUTED));ui.gap(body,10);for(Object[] row:rows)addWord((JSONObject)row[0],(JSONObject)row[1],(Integer)row[2],(JSONObject)row[3]);
        JSONArray mistakes=AiNotes.all(this);if(mistakes.length()>0){ui.gap(body,16);body.addView(ui.sectionLabel("造句问题"));for(int i=mistakes.length()-1;i>=0&&i>=mistakes.length()-5;i--){JSONObject m=mistakes.optJSONObject(i);if(m!=null)body.addView(ui.text(m.optString("english")+"\n"+m.optString("sentence")+"\n"+m.optString("feedback"),13,WordUi.MUTED));}}
    }

    private List<Object[]> rows(){
        LinkedHashMap<String,Object[]> unique=new LinkedHashMap<>();JSONArray tasks=Store.tasks(this);
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null)continue;JSONObject state=WrongBook.stateForWord(this,word);if(!WrongBook.inBook(state))continue;int streak=state.optInt("streak",0);if(statusFilter<3&&streak!=statusFilter)continue;String id=Words.stableId(word);if(!unique.containsKey(id))unique.put(id,new Object[]{task,word,j,state});}}
        List<Object[]> rows=new ArrayList<>(unique.values());java.util.Collections.sort(rows,(a,b)->Integer.compare(((JSONObject)a[3]).optInt("streak",0),((JSONObject)b[3]).optInt("streak",0)));return rows;
    }

    private void addWord(JSONObject task,JSONObject word,int index,JSONObject state){
        LinearLayout card=ui.card();TextView english=ui.text(word.optString("english"),21,WordUi.INK);english.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(english);card.addView(ui.text(word.optString("chinese"),15,WordUi.GREEN));
        card.addView(ui.text(WrongBook.status(state)+" · 连续独立正确 "+Math.min(2,state.optInt("streak",0))+"/2",12,WordUi.MUTED));
        JSONArray rounds=state.optJSONArray("wrongRounds");StringBuilder labels=new StringBuilder();if(rounds!=null)for(int i=0;i<rounds.length();i++){if(i>0)labels.append("、");labels.append("第").append(rounds.optInt(i)).append("轮");}
        card.addView(ui.text(task.optString("title")+" · 错误 "+state.optInt("wrongCount",0)+" 次\n出错复习："+(labels.length()==0?"未记录":labels),13,WordUi.MUTED));String note=word.optString("aiAnalysis","").trim();if(!note.isEmpty())card.addView(ui.text(note,13,WordUi.INK));ui.gap(card,8);
        LinearLayout actions=ui.row();long taskId=task.optLong("id");actions.addView(ui.primaryButton(state.optInt("streak",0)>=2?"再次强化":"拼写此词",v->startSpelling(taskId,index)),new LinearLayout.LayoutParams(0,ui.dp(50),1));LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(0,ui.dp(50),1);moreLp.leftMargin=ui.dp(8);actions.addView(ui.secondaryButton("更多",v->more(taskId,index,word,state)),moreLp);card.addView(actions);ui.gap(body,10);body.addView(card);
    }

    private void startSpelling(long taskId,int index){try{String selection=TrainingStore.forTask(this,taskId,new int[]{index});Intent intent=new Intent(this,SpellingActivity.class).putExtra("taskId",taskId).putExtra("selectionId",selection).putExtra("round",-1).putExtra("wrongMode",true).putExtra("attemptId",UUID.randomUUID().toString()).putExtra("sessionId",UUID.randomUUID().toString());Motion.open(this,intent);}catch(Exception e){toast(e.getMessage());}}
    private void more(long taskId,int index,JSONObject word,JSONObject state){
        new AlertDialog.Builder(this).setTitle(word.optString("english")).setItems(new CharSequence[]{"查看历史","重新标为未掌握","义项练习","AI 分析","词根对比","清除记录"},(d,which)->{
            if(which==0)showHistory(word);else if(which==1)try{WrongBook.unmaster(this,taskId,index);render();}catch(Exception e){toast(e.getMessage());}else if(which==2)startActivity(new Intent(this,SenseQuizActivity.class).putExtra("taskId",taskId).putExtra("wrongOnly",true));else if(which==3)aiAnalyze(taskId,index,word);else if(which==4)aiRoots(word);else new AlertDialog.Builder(this).setTitle("清除错词记录？").setMessage("单词卡和答题历史会保留，只清除当前错词状态。").setNegativeButton("取消",null).setPositiveButton("清除",(d2,w2)->{try{WrongBook.clear(this,taskId,index);render();}catch(Exception e){toast(e.getMessage());}}).show();
        }).setNegativeButton("取消",null).show();
    }
    private void showHistory(JSONObject word){
        StringBuilder out=new StringBuilder();try{JSONArray all=new JSONArray(Store.prefs(this).getString("spellingAttempts","[]"));String id=Words.stableId(word);for(int i=all.length()-1;i>=0;i--){JSONObject a=all.optJSONObject(i);if(a==null||!id.equals(a.optString("wordId")))continue;out.append(a.optString("type")).append(" · ").append(new java.text.SimpleDateFormat("M月d日 HH:mm",java.util.Locale.CHINA).format(new java.util.Date(a.optLong("at")))).append('\n');if(out.length()>2500)break;}}catch(Exception ignored){}new AlertDialog.Builder(this).setTitle("错词历史").setMessage(out.length()==0?"暂无拼写历史；旧版错误次数仍已保留。":out.toString()).setPositiveButton("关闭",null).show();
    }

    private void aiAnalyze(long taskId,int index,JSONObject word){if(!AiSettings.ready(this)){toast(AiSettings.notReadyMessage(this));return;}toast("正在分析…");pending=AiClient.chatJson(this,AiPrompts.systemWrongAnalysis(),AiPrompts.userWrongAnalysis(word.optString("english"),word.optString("chinese"),word.optInt("wrongCount")),new AiClient.Callback(){@Override public void onResult(JSONObject json){String text="记忆钩子："+json.optString("hooks")+"\n易混点："+json.optString("confusion")+"\n建议："+json.optString("tip");try{JSONArray tasks=Store.tasks(WrongBookActivity.this);JSONObject task=Store.find(tasks,taskId);if(task!=null){JSONArray words=task.getJSONArray("words");JSONObject saved=words.getJSONObject(index);AiNotes.putWordNote(saved,"aiAnalysis",text);Store.save(WrongBookActivity.this,tasks);}}catch(Exception e){toast(e.getMessage());}new AlertDialog.Builder(WrongBookActivity.this).setTitle("错题分析").setMessage(text).setPositiveButton("关闭",null).show();render();}@Override public void onError(String message){toast(message);}});}
    private void aiRoots(JSONObject word){if(!AiSettings.ready(this)){toast(AiSettings.notReadyMessage(this));return;}toast("正在对比…");pending=AiClient.chatJson(this,AiPrompts.systemRoots(),AiPrompts.userRoots(word.optString("english"),word.optString("chinese")),new AiClient.Callback(){@Override public void onResult(JSONObject json){StringBuilder body=new StringBuilder().append("词根：").append(json.optString("root")).append('\n');JSONArray der=json.optJSONArray("derivatives");if(der!=null){body.append("派生：");for(int i=0;i<der.length();i++){if(i>0)body.append("、");body.append(der.optString(i));}body.append('\n');}JSONArray looks=json.optJSONArray("lookalikes");if(looks!=null)for(int i=0;i<looks.length();i++){JSONObject item=looks.optJSONObject(i);if(item!=null)body.append(item.optString("word")).append(" — ").append(item.optString("diff")).append('\n');}body.append("提示：").append(json.optString("tip"));new AlertDialog.Builder(WrongBookActivity.this).setTitle("词根 / 形近对比").setMessage(body.toString().trim()).setPositiveButton("关闭",null).show();}@Override public void onError(String message){toast(message);}});}
    private void aiScenarioAll(){if(!AiSettings.ready(this)){toast(AiSettings.notReadyMessage(this));return;}try{JSONArray list=new JSONArray();for(Object[] row:rows()){JSONObject w=(JSONObject)row[1];list.put(new JSONObject().put("english",w.optString("english")).put("chinese",w.optString("chinese")));if(list.length()>=12)break;}if(list.length()==0){toast("当前筛选没有错词");return;}toast("正在生成情景…");pending=AiClient.chatJson(this,AiPrompts.systemScenario(),AiPrompts.userScenario(list.toString()),new AiClient.Callback(){@Override public void onResult(JSONObject json){new AlertDialog.Builder(WrongBookActivity.this).setTitle("情景练习").setMessage((json.optString("title")+"\n\n"+json.optString("text")).trim()).setPositiveButton("关闭",null).show();}@Override public void onError(String message){toast(message);}});}catch(Exception e){toast(e.getMessage());}}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
