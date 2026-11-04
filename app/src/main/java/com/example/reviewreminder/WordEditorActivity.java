package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

public final class WordEditorActivity extends ScreenActivity {
    private WordUi ui;private JSONObject document;private JSONArray words;private int index;private long taskId;private EditText english,chinese,example,title;private boolean committed,changing,quickReview,editingIssue;private int direction;
    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);taskId=getIntent().getLongExtra("taskId",0);quickReview=getIntent().getBooleanExtra("quickReview",false);index=state==null?0:state.getInt("index");editingIssue=state!=null&&state.getBoolean("editingIssue");try{document=taskId==0?Words.draft(this):Store.find(Store.tasks(this),taskId);if(document==null)throw new Exception("任务已不存在");words=document.optJSONArray("words");if(words==null)words=new JSONArray();if(words.length()==0)words.put(new JSONObject().put("english","").put("chinese","").put("example",""));document.put("words",words);show();}catch(Exception e){ui.toast(e.getMessage());finish();}}
    @Override protected void onSaveInstanceState(Bundle out){save(false);out.putInt("index",index);out.putBoolean("editingIssue",editingIssue);super.onSaveInstanceState(out);}
    private void show(){
        index=Math.max(0,Math.min(index,words.length()-1));
        if(taskId==0&&quickReview&&!editingIssue){showQuickCard();return;}
        ui.start(taskId==0?(quickReview?"修改单词卡":"校对单词卡"):"编辑单词卡");ui.body.addView(ui.text((index+1)+" / "+words.length()+" 张",14,WordUi.MUTED));ui.gap(ui.body,12);
        if(taskId==0&&!quickReview){title=ui.field("词组名称",document.optString("title"),1);String source=document.optString("source");if(!source.isEmpty()){ui.body.addView(ui.text(source,12,WordUi.MUTED));ui.gap(ui.body,12);}}
        JSONObject word=words.optJSONObject(index);english=ui.field("英文单词",word.optString("english"),1);english.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);chinese=ui.field("中文释义",word.optString("chinese"),2);example=ui.field("例句（可留空）",word.optString("example"),2);
        LinearLayout navigation=ui.row();Button prev=ui.button("上一张",false,v->move(-1));prev.setEnabled(index>0);prev.setAlpha(index>0?1:.45f);LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=ui.dp(8);navigation.addView(prev,left);Button next=ui.button("下一张",true,v->move(1));next.setEnabled(index<words.length()-1);next.setAlpha(index<words.length()-1?1:.45f);navigation.addView(next,new LinearLayout.LayoutParams(0,-2,1));ui.body.addView(navigation);ui.gap(ui.body,10);
        ui.body.addView(ui.button("卡片列表",false,v->{if(!save(true))return;String[] labels=new String[words.length()];for(int i=0;i<words.length();i++)labels[i]=(i+1)+". "+words.optJSONObject(i).optString("english","未填写");new AlertDialog.Builder(this).setTitle("选择单词卡").setItems(labels,(d,w)->{index=w;show();}).show();}));ui.gap(ui.body,8);
        LinearLayout more=ui.row();LinearLayout.LayoutParams moreLeft=new LinearLayout.LayoutParams(0,-2,1);moreLeft.rightMargin=ui.dp(8);more.addView(ui.button("添加卡片",false,v->{if(!save(true))return;try{words.put(new JSONObject().put("english","").put("chinese","").put("example",""));index=words.length()-1;show();}catch(Exception e){ui.toast(e.getMessage());}}),moreLeft);more.addView(ui.button("删除此卡",false,v->remove()),new LinearLayout.LayoutParams(0,-2,1));ui.body.addView(more);ui.gap(ui.body,10);
        JSONArray photos=document.optJSONArray("photos");if(photos!=null&&photos.length()>0){ui.body.addView(ui.button("查看原照片",false,v->photo(photos)));ui.gap(ui.body,10);}
        String saveLabel=taskId==0?(quickReview?"保存并返回确认":"保存词组并开始计划"):"保存修改";
        ui.body.addView(ui.button(saveLabel,true,v->{if(!save(true))return;if(taskId==0&&quickReview){editingIssue=false;clearFields();show();}else if(taskId==0)finishProof();else{ui.toast("单词卡已保存");finish();}}));Motion.enter(ui.body,direction);direction=0;
    }
    private void showQuickCard(){
        clearFields();ui.start("快速确认");
        TextView progress=ui.text("第 "+(index+1)+" 张，共 "+words.length()+" 张",14,WordUi.MUTED);progress.setGravity(Gravity.CENTER);ui.body.addView(progress);ui.gap(ui.body,20);
        JSONObject word=words.optJSONObject(index);if(word==null)word=new JSONObject();
        LinearLayout card=ui.col();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(ui.dp(22),ui.dp(30),ui.dp(22),ui.dp(28));card.setBackground(ui.bg(0xFFFFFFFF,24));
        TextView englishCard=ui.text(word.optString("english",""),30,WordUi.INK);englishCard.setGravity(Gravity.CENTER);englishCard.setTypeface(null,1);card.addView(englishCard,new LinearLayout.LayoutParams(-1,-2));
        View divider=new View(this);divider.setBackgroundColor(0xFFE6EEEA);LinearLayout.LayoutParams dividerLp=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(1));dividerLp.topMargin=ui.dp(20);dividerLp.bottomMargin=ui.dp(18);card.addView(divider,dividerLp);
        TextView chineseCard=ui.text(word.optString("chinese",""),21,WordUi.GREEN);chineseCard.setGravity(Gravity.CENTER);card.addView(chineseCard,new LinearLayout.LayoutParams(-1,-2));
        String exampleText=word.optString("example","").trim();if(!exampleText.isEmpty()){TextView exampleCard=ui.text(exampleText,15,WordUi.INK);exampleCard.setGravity(Gravity.CENTER);LinearLayout.LayoutParams exampleLp=new LinearLayout.LayoutParams(-1,-2);exampleLp.topMargin=ui.dp(12);card.addView(exampleCard,exampleLp);}
        ui.body.addView(card,new LinearLayout.LayoutParams(-1,-2));ui.gap(ui.body,24);
        ui.body.addView(ui.button("有问题，修改",false,v->{editingIssue=true;show();}));ui.gap(ui.body,10);
        boolean last=index==words.length()-1;
        ui.body.addView(ui.button(last?"没问题，完成校对":"没问题，下一张",true,v->confirmCurrent()));
        JSONArray photos=document.optJSONArray("photos");if(photos!=null&&photos.length()>0){ui.gap(ui.body,14);ui.body.addView(ui.button("查看原照片",false,v->photo(photos)));}
        Motion.enter(ui.body,direction);direction=0;
    }
    private void confirmCurrent(){
        if(changing)return;
        if(index<words.length()-1){index++;show();return;}
        finishProof();
    }
    private void finishProof(){
        if(taskId!=0){if(save(true)){ui.toast("单词卡已保存");finish();}return;}
        for(int i=0;i<words.length();i++)if(words.optJSONObject(i)==null||words.optJSONObject(i).optString("english").trim().isEmpty()){
            index=i;if(quickReview)editingIssue=true;show();if(english!=null)english.setError("请填写英文单词或删除此卡");return;
        }
        if(!document.optString("title","").trim().isEmpty()){commit();return;}
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("例如：Lesson 1");
        int padding=ui.dp(18);name.setPadding(padding,padding,padding,padding);
        new AlertDialog.Builder(this).setTitle("给这组单词命名").setView(name).setNegativeButton("取消",null).setPositiveButton("创建复习计划",(dialog,which)->{
            String value=name.getText().toString().trim();if(value.isEmpty()){ui.toast("请填写词组名称");return;}try{document.put("title",value);Words.writeDraft(this,document);commit();}catch(Exception e){ui.toast("保存失败："+e.getMessage());}
        }).show();
    }
    private void clearFields(){english=null;chinese=null;example=null;title=null;}
    private boolean save(boolean report){if(document==null||english==null||committed)return true;if(taskId!=0&&english.getText().toString().trim().isEmpty()){if(report)english.setError("英文不能为空");return false;}try{JSONObject word=words.getJSONObject(index);word.put("english",english.getText().toString().trim()).put("chinese",chinese.getText().toString().trim()).put("example",example.getText().toString().trim());if(title!=null)document.put("title",title.getText().toString().trim());document.put("words",words);
        if(taskId==0)Words.writeDraft(this,document);else{JSONArray all=Store.tasks(this);JSONObject current=Store.find(all,taskId);if(current==null)throw new Exception("任务已删除");current.put("words",words);Store.save(this,all);}return true;
        }catch(Exception e){if(report)ui.toast("保存失败："+e.getMessage());return false;}}
    private void move(int step){if(changing||index+step<0||index+step>=words.length()||!save(true))return;changing=true;direction=step;Motion.leave(ui.body,step,()->{if(isFinishing()||isDestroyed())return;index+=step;changing=false;show();});}
    private void remove(){if(changing)return;if(taskId!=0&&words.length()==1){ui.toast("词组至少需要一张卡片，可在主页删除整个任务");return;}new AlertDialog.Builder(this).setMessage("删除当前单词卡？").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(title!=null)try{document.put("title",title.getText().toString().trim());}catch(Exception ignored){}words.remove(index);if(words.length()==0){words.put(new JSONObject());}index=Math.min(index,words.length()-1);show();save(true);}).show();}
    private void photo(JSONArray photos){String[] labels=new String[photos.length()];for(int i=0;i<labels.length;i++)labels[i]="原照片 "+(i+1);new AlertDialog.Builder(this).setItems(labels,(d,w)->{try{Bitmap bitmap=Ocr.decode(new File(photos.optString(w)));ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setImageBitmap(bitmap);ScrollView scroll=new ScrollView(this);scroll.addView(image);new AlertDialog.Builder(this).setView(scroll).setPositiveButton("关闭",null).show();}catch(Exception e){ui.toast("原照片读取失败");}}).show();}
    private void commit(){
        try{if(document.optString("title").isEmpty()){ui.toast("请填写词组名称");return;}for(int i=0;i<words.length();i++)if(words.getJSONObject(i).optString("english").trim().isEmpty()){index=i;show();english.setError("请填写英文单词或删除此卡");return;}
            List<Long> plan=IntervalPlan.active(Store.prefs(this).getString("intervals",Store.DEFAULT_INTERVALS),Store.prefs(this).getBoolean("optional",false),Store.prefs(this).getString("extraIntervals",""));JSONArray intervals=new JSONArray();for(long n:plan)intervals.put(n);
            long now=System.currentTimeMillis(),id=Store.prefs(this).getLong("nextId",0)+1;String windows=ReminderWindows.active(this);JSONObject t=new JSONObject(document.toString()).put("id",id).put("created",now).put("stage",0).put("intervals",intervals).put("windows",windows).put("history",new JSONArray()).put("snoozes",0).put("done",false).put("due",Schedule.due(now,plan.get(0),Store.windows(windows)));
            JSONArray all=Store.tasks(this);all.put(t);Store.save(this,all);Store.prefs(this).edit().putLong("nextId",id).apply();boolean exact=Alarms.schedule(this,t);committed=true;Words.clearDraft(this);ui.toast("已保存 "+words.length()+" 张单词卡"+(exact?"":"；系统可能延迟提醒"));Intent home=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(home);finish();
        }catch(Exception e){ui.toast("无法建立计划："+e.getMessage());}
    }
    @Override protected void onPause(){save(false);super.onPause();}
    @Override public void onBackPressed(){if(quickReview&&editingIssue){if(save(true)){editingIssue=false;clearFields();show();}return;}if(save(true))super.onBackPressed();}
}
