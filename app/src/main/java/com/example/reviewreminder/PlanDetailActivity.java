package com.example.reviewreminder;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PlanDetailActivity extends ScreenActivity {
    private WordUi ui;private long id;private SpeechManager speech;
    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);speech=new SpeechManager(this);id=getIntent().getLongExtra("taskId",0);show();}
    @Override protected void onResume(){super.onResume();if(ui!=null&&ui.body!=null)show();}
    private void show(){ui.start("计划详情");JSONObject task=Store.find(Store.tasks(this),id);if(task==null){ui.body.addView(ui.text("计划已不存在",16,WordUi.MUTED));return;}TextView title=ui.text(task.optString("title"),25,WordUi.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);ui.body.addView(title);ui.body.addView(ui.text(PlansActivity.status(task)+" · "+date(task.optLong("created")),13,WordUi.MUTED));ui.gap(ui.body,8);ui.body.addView(ui.textButton("重命名",v->rename(task)));if(!task.optString("description").isEmpty())ui.body.addView(ui.text(task.optString("description"),14,WordUi.INK));ui.gap(ui.body,16);
        JSONArray words=task.optJSONArray("words"),history=task.optJSONArray("history");int total=task.optJSONArray("intervals")==null?0:task.optJSONArray("intervals").length();LinearLayout progress=ui.card();TextView progressTitle=ui.text("学习进度",16,WordUi.INK);progressTitle.setTypeface(null,android.graphics.Typeface.BOLD);progress.addView(progressTitle);ui.gap(progress,8);progress.addView(ui.text("第 "+Math.min(task.optInt("stage"),total)+" / "+total+" 轮 · "+(words==null?0:words.length())+" 个单词 · 平均掌握 "+String.format(Locale.CHINA,"%.1f",Mastery.average(words))+"/5",13,WordUi.MUTED));ui.gap(progress,12);int percent=total==0?0:Math.min(100,Math.round(Math.min(task.optInt("stage"),total)*100f/total));progress.addView(ui.progress(percent),new LinearLayout.LayoutParams(-1,ui.dp(6)));ui.gap(progress,12);progress.addView(ui.text("提醒时段："+(task.optString("windows").isEmpty()?"全天":task.optString("windows")),12,WordUi.MUTED));ui.body.addView(progress);ui.gap(ui.body,16);
        if(words!=null&&words.length()>0){ui.body.addView(ui.primaryButton("按原顺序查看卡片",v->review(false)));ui.gap(ui.body,8);ui.body.addView(ui.secondaryButton("随机复习",v->review(true)));ui.gap(ui.body,8);ui.body.addView(ui.secondaryButton("全量拼写",v->spelling()));ui.gap(ui.body,8);ui.body.addView(ui.ghostButton("AI 翻译训练",v->{try{String selection=TrainingStore.forTask(this,id,null);Motion.open(this,new Intent(this,TranslationActivity.class).putExtra("selectionId",selection));}catch(Exception e){ui.toast(e.getMessage());}}));ui.gap(ui.body,8);ui.body.addView(ui.ghostButton("校对单词卡",v->Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("taskId",id))));ui.gap(ui.body,16);}
        ui.body.addView(ui.sectionLabel("单词目录 · 点击可朗读或单独拼写"));if(words!=null)for(int i=0;i<words.length();i++){JSONObject word=words.optJSONObject(i);if(word==null)continue;LinearLayout row=ui.card();TextView en=ui.text((i+1)+". "+word.optString("english"),16,WordUi.INK);en.setTypeface(null,android.graphics.Typeface.BOLD);row.addView(en);row.addView(ui.text(word.optString("chinese"),14,WordUi.GREEN));JSONObject state=WrongBook.stateForWord(this,word);if(WrongBook.inBook(state))row.addView(ui.text(WrongBook.status(state),12,WordUi.MUTED));int index=i;row.setOnClickListener(v->wordActions(word,index));Motion.press(row);ui.body.addView(row);ui.gap(ui.body,8);}
        ui.gap(ui.body,12);ui.body.addView(ui.sectionLabel("复习记录"));if(history==null||history.length()==0)ui.body.addView(ui.text("暂无复习记录",14,WordUi.MUTED));else for(int i=history.length()-1;i>=0;i--){JSONObject h=history.optJSONObject(i);if(h!=null)ui.body.addView(ui.text("第 "+h.optInt("stage")+" 轮 · "+date(h.optLong("at"))+(h.has("spellingTotal")?" · 拼写 "+h.optInt("spellingCorrect")+"/"+h.optInt("spellingTotal"):""),13,WordUi.MUTED));}
        ui.gap(ui.body,16);ui.body.addView(ui.ghostButton(task.optBoolean("archived")?"取消归档":"归档计划（保留全部资料）",v->{try{JSONArray all=Store.tasks(this);JSONObject current=Store.find(all,id);current.put("archived",!current.optBoolean("archived"));Store.save(this,all);show();}catch(Exception e){ui.toast(e.getMessage());}}));ui.gap(ui.body,8);ui.body.addView(ui.dangerTextButton("删除计划",v->confirmDelete()));
    }
    private void review(boolean random){try{String selection=TrainingStore.forTask(this,id,null);if(random){JSONObject old=TrainingStore.selection(this,selection);JSONArray words=old.getJSONArray("words");List<JSONObject> list=new ArrayList<>();for(int i=0;i<words.length();i++)list.add(words.optJSONObject(i));Collections.shuffle(list);JSONArray shuffled=new JSONArray();for(JSONObject word:list)shuffled.put(word);selection=TrainingStore.createSelection(this,shuffled);}Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("selectionId",selection).putExtra("practice",true));}catch(Exception e){ui.toast(e.getMessage());}}
    private void spelling(){try{String selection=TrainingStore.forTask(this,id,null);Motion.open(this,new Intent(this,SpellingActivity.class).putExtra("taskId",id).putExtra("selectionId",selection).putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString()));}catch(Exception e){ui.toast(e.getMessage());}}
    private void rename(JSONObject task){
        EditText field=ui.input("任务名称",task.optString("title"));
        field.setSingleLine(true);
        new Sheet(this).title("重命名").message("只改名称。单词、计划和复习记录都保留。").content(field)
            .secondary("取消",null)
            .primaryIf("保存",()->{
                try{
                    JSONArray all=Store.tasks(this);JSONObject current=Store.find(all,id);
                    if(current==null){ui.toast("计划已不存在");return true;}
                    TaskTitle.rename(current,field.getText().toString());
                    Store.save(this,all);show();ui.toast("已改名为「"+current.optString("title")+"」");return true;
                }catch(IllegalArgumentException e){field.setError(e.getMessage());field.requestFocus();Feedback.tick(field);return false;}
                catch(Exception e){ui.toast(e.getMessage());return false;}
            }).show();
        field.requestFocus();
    }
    private void wordActions(JSONObject word,int index){
        String example=word.optString("example").trim();
        Sheet sheet=new Sheet(this).title(word.optString("english")).message(word.optString("chinese")+(example.isEmpty()?"":"\n\n"+example));
        sheet.item(R.drawable.ic_layers,"相关词","近义、词形与归纳",()->Motion.open(this,new Intent(this,RelationActivity.class).putExtra("wordId",Words.stableId(word)).putExtra("english",word.optString("english")).putExtra("chinese",word.optString("chinese"))));
        if(ConnectionImages.exists(this,word.optString("connectionImage")))sheet.item(R.drawable.ic_images,"查看附带图片",()->openConnection(word));
        sheet.item(R.drawable.ic_volume_2,"朗读单词",()->speech.speak(word.optString("english"),"word"));
        sheet.item(R.drawable.ic_spell_check,"拼写此词",()->startSpelling(index));
        sheet.secondary("关闭",null).show();
    }
    private void openConnection(JSONObject word){Motion.open(this,new Intent(this,ConnectionImageActivity.class).putExtra("path",word.optString("connectionImage")).putExtra("english",word.optString("english")));}
    private void startSpelling(int index){try{String selection=TrainingStore.forTask(this,id,new int[]{index});Motion.open(this,new Intent(this,SpellingActivity.class).putExtra("taskId",id).putExtra("selectionId",selection).putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString()));}catch(Exception e){ui.toast(e.getMessage());}}
    private void confirmDelete(){Sheet.confirm(this,"删除计划？","单词、计划和复习记录将被删除，不能恢复。","保留","删除",true,()->{JSONArray old=Store.tasks(this),next=new JSONArray();for(int i=0;i<old.length();i++){JSONObject t=old.optJSONObject(i);if(t!=null&&t.optLong("id")!=id)next.put(t);}Store.save(this,next);Alarms.cancel(this,id);finish();});}
    private String date(long time){return new SimpleDateFormat("yyyy年M月d日 HH:mm",Locale.CHINA).format(new Date(time));}
    @Override protected void onPause(){speech.stop();super.onPause();}
    @Override protected void onDestroy(){speech.shutdown();super.onDestroy();}
}
