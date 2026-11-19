package com.example.reviewreminder;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

public final class PracticeSetupActivity extends ScreenActivity {
    private WordUi ui;private Spinner scope;private Switch random;private TextView count;private final LinkedHashMap<Long,CheckBox> checks=new LinkedHashMap<>();private boolean translationOnly;
    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);translationOnly=getIntent().getBooleanExtra("translationOnly",false);show();}
    private void show(){
        ui.start(translationOnly?"AI 翻译训练":"混合训练");ui.body.addView(ui.text(translationOnly?"选择一个或多个词组，再按难度生成翻译练习。合并后按单词与释义去重，不修改原计划。":"选择一个或多个词组。合并后按单词与释义去重，不修改原计划。",13,WordUi.MUTED));ui.gap(ui.body,16);
        JSONArray tasks=Store.tasks(this);if(tasks.length()==0){ui.body.addView(ui.text("还没有词组，请先录词并创建计划。",16,WordUi.MUTED));return;}
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null||task.optJSONArray("words")==null||task.optJSONArray("words").length()==0)continue;CheckBox check=new CheckBox(this);check.setText(String.format(java.util.Locale.CHINA,"%s · %d 词",task.optString("title"),task.optJSONArray("words").length()));check.setMinHeight(ui.dp(52));check.setTextSize(15);check.setOnCheckedChangeListener((b,c)->updateCount());checks.put(task.optLong("id"),check);ui.body.addView(check);}
        LinearLayout tools=ui.row();tools.addView(ui.textButton("全选",v->{for(CheckBox check:checks.values())check.setChecked(true);}));tools.addView(ui.textButton("清空",v->{for(CheckBox check:checks.values())check.setChecked(false);}));ui.body.addView(tools);ui.gap(ui.body,12);
        scope=new Spinner(this);scope.setMinimumHeight(ui.dp(52));scope.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部单词","仅错词（含已掌握历史）","仅未掌握错词"}));ui.body.addView(scope);scope.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int position,long id){updateCount();}});
        random=new Switch(this);random.setText("随机打乱（不影响必练范围）");random.setMinHeight(ui.dp(52));ui.body.addView(random);count=ui.text("已合并 0 个单词",14,WordUi.GREEN);ui.body.addView(count);ui.gap(ui.body,16);
        if(translationOnly)ui.body.addView(ui.primaryButton("开始 AI 翻译训练",v->launch("translation")));
        else{ui.body.addView(ui.primaryButton("卡片复习",v->launch("review")));ui.gap(ui.body,8);ui.body.addView(ui.secondaryButton("全量拼写",v->launch("spelling")));ui.gap(ui.body,8);ui.body.addView(ui.secondaryButton("错词强化（连续独立正确两次）",v->launch("wrong")));ui.gap(ui.body,8);ui.body.addView(ui.ghostButton("AI 翻译训练",v->launch("translation")));}
    }
    private JSONArray build(String mode)throws Exception{
        List<JSONObject> list=new ArrayList<>();LinkedHashMap<String,Boolean> seen=new LinkedHashMap<>();JSONArray tasks=Store.tasks(this);
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;CheckBox check=checks.get(task.optLong("id"));if(check==null||!check.isChecked())continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null)continue;JSONObject state=WrongBook.stateForWord(this,word);boolean inBook=WrongBook.inBook(state);int selected=scope==null?0:scope.getSelectedItemPosition();if("wrong".equals(mode)&&(!inBook||state.optInt("streak",0)>=2))continue;else if(!"wrong".equals(mode)&&selected==1&&!inBook)continue;else if(!"wrong".equals(mode)&&selected==2&&(!inBook||state.optInt("streak",0)>=2))continue;String id=Words.stableId(word);if(seen.containsKey(id))continue;seen.put(id,true);JSONObject copy=new JSONObject(word.toString());copy.put("_taskId",task.optLong("id")).put("_wordIndex",j).put("wordId",id);list.add(copy);}}
        if(random!=null&&random.isChecked())Collections.shuffle(list);JSONArray out=new JSONArray();for(JSONObject word:list)out.put(word);return out;
    }
    private void launch(String mode){try{JSONArray words=build(mode);if(words.length()==0){ui.toast("当前选择与筛选没有可练单词");return;}String selection=TrainingStore.createSelection(this,words);Intent intent;if("review".equals(mode))intent=new Intent(this,ReviewActivity.class);else if("translation".equals(mode))intent=new Intent(this,TranslationActivity.class);else intent=new Intent(this,SpellingActivity.class).putExtra("wrongMode","wrong".equals(mode)).putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString());intent.putExtra("selectionId",selection).putExtra("practice",true);Motion.open(this,intent);}catch(Exception e){ui.toast(e.getMessage());}}
    private void updateCount(){if(count==null)return;try{count.setText(String.format(java.util.Locale.CHINA,"已合并 %d 个单词",build("review").length()));}catch(Exception e){count.setText("无法计算选词数量");}}
}
