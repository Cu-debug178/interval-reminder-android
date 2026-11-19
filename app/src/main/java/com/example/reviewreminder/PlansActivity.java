package com.example.reviewreminder;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PlansActivity extends ScreenActivity {
    private WordUi ui;private int filter;
    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);show();}
    @Override protected void onResume(){super.onResume();if(ui!=null&&ui.body!=null)show();}
    private void show(){ui.start("全部计划");ui.body.addView(ui.text("进行中、未开始、已完成和归档计划都可自由查看。",13,WordUi.MUTED));ui.gap(ui.body,12);Spinner spinner=new Spinner(this);spinner.setMinimumHeight(ui.dp(48));spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部计划","未开始","进行中","已完成","已归档"}));spinner.setSelection(filter);ui.body.addView(spinner);ui.gap(ui.body,16);spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int position,long id){if(position!=filter){filter=position;show();}}});JSONArray tasks=Store.tasks(this);int shown=0;for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task!=null&&matches(task)){add(task);shown++;}}if(shown==0)ui.body.addView(ui.text("这个分类还没有计划。",15,WordUi.MUTED));}
    private boolean matches(JSONObject task){String state=status(task);if(filter==0)return true;if(filter==1)return "未开始".equals(state);if(filter==2)return "进行中".equals(state);if(filter==3)return "已完成".equals(state);return "已归档".equals(state);}
    private void add(JSONObject task){LinearLayout card=ui.card();LinearLayout top=ui.row();TextView title=ui.text(task.optString("title"),17,WordUi.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));top.addView(ui.chip(status(task),WordUi.SOFT,WordUi.GREEN));card.addView(top);ui.gap(card,10);int total=task.optJSONArray("intervals")==null?0:task.optJSONArray("intervals").length(),words=task.optJSONArray("words")==null?0:task.optJSONArray("words").length();card.addView(ui.text(words+" 个单词 · 进度 "+Math.min(task.optInt("stage"),total)+" / "+total+" · 掌握 "+String.format(java.util.Locale.CHINA,"%.1f",Mastery.average(task.optJSONArray("words")))+"/5",13,WordUi.MUTED));ui.gap(card,12);int percent=total==0?0:Math.min(100,Math.round(Math.min(task.optInt("stage"),total)*100f/total));card.addView(ui.progress(percent),new LinearLayout.LayoutParams(-1,ui.dp(5)));card.setContentDescription(task.optString("title")+"，"+status(task)+"，进度 "+percent+"%");card.setOnClickListener(v->Motion.open(this,new Intent(this,PlanDetailActivity.class).putExtra("taskId",task.optLong("id"))));Motion.press(card);ui.body.addView(card);ui.gap(ui.body,12);}
    static String status(JSONObject task){if(task.optBoolean("archived"))return"已归档";if(task.optBoolean("done"))return"已完成";return task.optInt("stage")==0&&(task.optJSONArray("history")==null||task.optJSONArray("history").length()==0)?"未开始":"进行中";}
}
