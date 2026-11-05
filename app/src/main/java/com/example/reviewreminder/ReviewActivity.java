package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class ReviewActivity extends ScreenActivity {
    private WordUi ui;private long id,seed;private int expectedStage,mode,lastAutoSpoken=-1;private JSONArray words;private ReviewSession session;private boolean revealed,completed,changing,ttsReady,active=true;private String pendingSpeech;private TextToSpeech tts;private final Feedback interaction=new Feedback();private StringBuilder answers=new StringBuilder();private LinearLayout feedback;private View cardView;
    @Override public void onCreate(Bundle state){super.onCreate(state);interaction.prepare(this);ui=new WordUi(this);id=getIntent().getLongExtra("taskId",0);try{JSONObject task=Store.find(Store.tasks(this),id);if(task==null)throw new Exception("任务不存在");words=task.getJSONArray("words");if(words.length()==0)throw new Exception("词组为空");expectedStage=state==null?task.optInt("stage"):state.getInt("stage");mode=Store.prefs(this).getInt("reviewMode",0);seed=state==null?System.nanoTime():state.getLong("seed");session=new ReviewSession(words.length(),Store.prefs(this).getBoolean("shuffleWords",false),seed);String previous=state==null?"":state.getString("answers","");for(char answer:previous.toCharArray())if(!session.ended()){session.answer(answer=='1');answers.append(answer);}if(state!=null){revealed=state.getBoolean("revealed");lastAutoSpoken=state.getInt("lastAutoSpoken",-1);}show();}catch(Exception e){ui.toast(e.getMessage());finish();}}
    @Override protected void onSaveInstanceState(Bundle out){out.putLong("seed",seed);out.putInt("stage",expectedStage);out.putInt("lastAutoSpoken",lastAutoSpoken);out.putString("answers",answers.toString());out.putBoolean("revealed",revealed);super.onSaveInstanceState(out);}
    private boolean chineseFirst(JSONObject word){return !word.optString("chinese").isEmpty()&&(mode==1||(mode==2&&((seed+session.current()*31)&1)==0));}
    private void speak(String value,boolean automatic){
        if(automatic&&!Store.prefs(this).getBoolean("autoSpeak",true))return;
        if(automatic&&lastAutoSpoken==session.answered())return;
        if(value==null||value.trim().isEmpty())return;
        if(automatic)lastAutoSpoken=session.answered();
        pendingSpeech=value;
        if(tts==null){tts=new TextToSpeech(this,status->{if(status==TextToSpeech.SUCCESS&&tts!=null){int language=tts.setLanguage(Locale.ENGLISH);ttsReady=language!=TextToSpeech.LANG_MISSING_DATA&&language!=TextToSpeech.LANG_NOT_SUPPORTED;if(ttsReady&&active&&pendingSpeech!=null){String pending=pendingSpeech;pendingSpeech=null;tts.speak(pending,TextToSpeech.QUEUE_FLUSH,null,"word");}}else if(!automatic)ui.toast("设备的英文朗读不可用");});}
        else if(ttsReady){pendingSpeech=null;tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,"word");}
        else if(!automatic)ui.toast("朗读正在准备或设备缺少英文语音");
    }
    private void show(){
        if(session.ended()){summary();return;}ui.start("快速复习");ui.body.addView(ui.text((session.answered()+1)+" / "+session.total()+" 张",14,WordUi.MUTED));ui.gap(ui.body,18);JSONObject word=words.optJSONObject(session.current());boolean zh=chineseFirst(word);
        LinearLayout card=ui.col();card.setGravity(Gravity.CENTER);card.setMinimumHeight(ui.dp(300));card.setPadding(ui.dp(22),ui.dp(30),ui.dp(22),ui.dp(30));card.setBackground(ui.bg(0xFFFFFFFF,20));
        TextView label=ui.text(zh?"中文释义":"英文单词",13,WordUi.MUTED);label.setGravity(Gravity.CENTER);card.addView(label);ui.gap(card,20);TextView front=ui.text(zh?word.optString("chinese"):word.optString("english"),zh?25:32,WordUi.INK);front.setGravity(Gravity.CENTER);front.setTypeface(null,1);card.addView(front);ui.gap(card,24);
        TextView back=ui.text(zh?word.optString("english"):word.optString("chinese",""),22,WordUi.GREEN);back.setGravity(Gravity.CENTER);back.setVisibility(revealed?View.VISIBLE:View.INVISIBLE);card.addView(back);
        String example=word.optString("example");TextView sentence=ui.text(example,16,WordUi.MUTED);sentence.setGravity(Gravity.CENTER);sentence.setPadding(0,ui.dp(18),0,0);sentence.setVisibility(example.isEmpty()?View.GONE:revealed?View.VISIBLE:View.INVISIBLE);card.addView(sentence);ui.body.addView(card);cardView=card;ui.gap(ui.body,20);
        FrameLayout actionSlot=new FrameLayout(this);actionSlot.setMinimumHeight(ui.dp(56));feedback=ui.col();actionSlot.addView(feedback,new FrameLayout.LayoutParams(-1,-2));
        Button reveal=ui.button("显示答案",true,v->{if(revealed||changing)return;revealed=true;back.setVisibility(View.VISIBLE);Motion.enter(back);if(!example.isEmpty()){sentence.setVisibility(View.VISIBLE);Motion.enter(sentence);}v.setVisibility(View.GONE);if(zh)speak(word.optString("english"),true);showFeedback();});reveal.setVisibility(revealed?View.GONE:View.VISIBLE);actionSlot.addView(reveal,new FrameLayout.LayoutParams(-1,-2));ui.body.addView(actionSlot);if(revealed)showFeedback();
        ui.gap(ui.body,10);Button pronounce=ui.button("朗读单词",false,v->speak(word.optString("english"),false));pronounce.setVisibility(!zh||revealed?View.VISIBLE:View.GONE);ui.body.addView(pronounce);if(zh&&!revealed)reveal.setOnClickListener(v->{if(revealed||changing)return;revealed=true;back.setVisibility(View.VISIBLE);Motion.enter(back);if(!example.isEmpty()){sentence.setVisibility(View.VISIBLE);Motion.enter(sentence);}v.setVisibility(View.GONE);pronounce.setVisibility(View.VISIBLE);speak(word.optString("english"),true);showFeedback();});
        ui.gap(ui.body,10);ui.body.addView(ui.button("编辑这张卡",false,v->{if(!changing)edit(word);}));Motion.enter(card,1);if(!zh||revealed)speak(word.optString("english"),true);
    }
    private void showFeedback(){final int position=session.answered();LinearLayout buttons=ui.row();LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=ui.dp(8);buttons.addView(ui.button("再看一次",false,v->answer(false,position)),left);buttons.addView(ui.button("记得",true,v->answer(true,position)),new LinearLayout.LayoutParams(0,-2,1));feedback.removeAllViews();feedback.addView(buttons);Motion.enter(buttons);}
    private void answer(boolean known,int expected){if(changing||!revealed||session.ended()||session.answered()!=expected)return;interaction.respond(cardView,known?Feedback.KNOWN:Feedback.RETRY);changing=true;pendingSpeech=null;if(ttsReady)tts.stop();session.answer(known);answers.append(known?'1':'0');revealed=false;Motion.leave(cardView,known?1:-1,()->{if(isFinishing()||isDestroyed())return;changing=false;show();});}
    private void edit(JSONObject word){LinearLayout form=ui.col();form.setPadding(ui.dp(20),0,ui.dp(20),0);EditText en=new EditText(this),zh=new EditText(this),ex=new EditText(this);en.setText(word.optString("english"));zh.setText(word.optString("chinese"));ex.setText(word.optString("example"));en.setHint("英文");zh.setHint("中文");ex.setHint("例句（可留空）");form.addView(en);form.addView(zh);form.addView(ex);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("编辑单词卡").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(en.getText().toString().trim().isEmpty()){en.setError("英文不能为空");return;}try{word.put("english",en.getText().toString().trim()).put("chinese",zh.getText().toString().trim()).put("example",ex.getText().toString().trim());JSONArray all=Store.tasks(this);JSONObject current=Store.find(all,id);if(current==null)throw new Exception("任务不存在");current.put("words",words);Store.save(this,all);d.dismiss();show();}catch(Exception e){ui.toast(e.getMessage());}}));d.show();}
    private void summary(){ui.start("本轮已看完");ui.body.addView(ui.text("共 "+words.length()+" 个单词，标记“再看一次” "+session.repeats()+" 次",18,WordUi.INK));ui.gap(ui.body,22);JSONObject task=Store.find(Store.tasks(this),id);boolean eligible=task!=null&&!task.optBoolean("done")&&task.optInt("stage")==expectedStage;
        if(eligible){ui.body.addView(ui.button("完成本轮并安排下一次",true,v->new AlertDialog.Builder(this).setTitle("确认完成本次复习？").setMessage("下一阶段从现在重新计算；若仍未掌握，可以返回继续练习。").setNegativeButton("继续练习",null).setPositiveButton("完成",(d,w)->complete()).show()));ui.gap(ui.body,12);}
        ui.body.addView(ui.button("再复习一遍",false,v->{seed=System.nanoTime();session=new ReviewSession(words.length(),Store.prefs(this).getBoolean("shuffleWords",false),seed);lastAutoSpoken=-1;answers.setLength(0);revealed=false;show();}));ui.gap(ui.body,12);ui.body.addView(ui.button("返回主页",false,v->finish()));Motion.enter(ui.body);
    }
    private void complete(){if(completed)return;try{JSONArray all=Store.tasks(this);JSONObject task=Store.find(all,id);if(task==null||task.optBoolean("done")||task.optInt("stage")!=expectedStage){ui.toast("本轮状态已变化，未重复推进阶段");finish();return;}long now=System.currentTimeMillis();int next=expectedStage+1;task.getJSONArray("history").put(new JSONObject().put("stage",next).put("at",now).put("repeatCount",session.repeats()));task.put("stage",next).put("snoozes",0);if(next>=task.getJSONArray("intervals").length())task.put("done",true);else task.put("due",Schedule.due(now,task.getJSONArray("intervals").getLong(next),Store.windows(task.optString("windows"))));Store.save(this,all);Alarms.cancel(this,id);if(!task.optBoolean("done"))Alarms.schedule(this,task);completed=true;ui.toast("本轮复习已完成");finish();}catch(Exception e){ui.toast("保存失败："+e.getMessage());}}
    @Override public void onBackPressed(){new AlertDialog.Builder(this).setTitle("退出本轮复习？").setMessage("单词修改已保存，但本轮不会标记完成。").setNegativeButton("继续复习",null).setPositiveButton("退出",(d,w)->finish()).show();}
    @Override protected void onResume(){super.onResume();active=true;}
    @Override protected void onPause(){active=false;pendingSpeech=null;if(tts!=null)tts.stop();super.onPause();}
    @Override protected void onDestroy(){if(tts!=null)tts.shutdown();interaction.release();super.onDestroy();}
}
