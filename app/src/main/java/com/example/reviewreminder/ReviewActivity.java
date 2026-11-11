package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReviewActivity extends ScreenActivity {
    private WordUi ui;private long id,seed;private int expectedStage,mode,forceMode=-1,lastAutoSpoken=-1;private JSONArray words;private ReviewSession session;private boolean revealed,completed,changing,ttsReady,active=true;private String pendingSpeech,pendingExample;private TextToSpeech tts;private final Feedback interaction=new Feedback();private StringBuilder answers=new StringBuilder();private LinearLayout feedback;private View cardView;private java.util.concurrent.Future<?> pendingAi;
    @Override public void onCreate(Bundle state){super.onCreate(state);interaction.prepare(this);ui=new WordUi(this);id=getIntent().getLongExtra("taskId",0);forceMode=getIntent().hasExtra("forceMode")?getIntent().getIntExtra("forceMode",-1):(state==null?-1:state.getInt("forceMode",-1));try{JSONObject task=Store.find(Store.tasks(this),id);if(task==null)throw new Exception("任务不存在");words=task.getJSONArray("words");if(words.length()==0)throw new Exception("词组为空");expectedStage=state==null?task.optInt("stage"):state.getInt("stage");mode=Store.prefs(this).getInt("reviewMode",0);seed=state==null?System.nanoTime():state.getLong("seed");session=new ReviewSession(words.length(),Store.prefs(this).getBoolean("shuffleWords",false),seed);String previous=state==null?"":state.getString("answers","");for(char answer:previous.toCharArray())if(!session.ended()){session.answer(answer=='1');answers.append(answer);}if(state!=null){revealed=state.getBoolean("revealed");lastAutoSpoken=state.getInt("lastAutoSpoken",-1);}show();}catch(Exception e){ui.toast(e.getMessage());finish();}}
    @Override protected void onSaveInstanceState(Bundle out){out.putLong("seed",seed);out.putInt("stage",expectedStage);out.putInt("forceMode",forceMode);out.putInt("lastAutoSpoken",lastAutoSpoken);out.putString("answers",answers.toString());out.putBoolean("revealed",revealed);super.onSaveInstanceState(out);}
    private boolean chineseFirst(JSONObject word){
        if(forceMode==0)return false;
        if(forceMode==1)return !word.optString("chinese").isEmpty();
        return !word.optString("chinese").isEmpty()&&(mode==1||(mode==2&&((seed+session.current()*31)&1)==0));
    }
    private boolean sessionChineseBiased(){return forceMode==1||(forceMode<0&&mode==1);}
    private void speak(String value,boolean automatic){
        if(automatic&&lastAutoSpoken==session.answered())return;
        speakWordThenExample(value,null,automatic);
    }
    private void speakWordThenExample(String english,String example,boolean automatic){
        if(automatic&&!Store.prefs(this).getBoolean("autoSpeak",true))return;
        if(english==null||english.trim().isEmpty())return;
        if(automatic)lastAutoSpoken=session.answered();
        pendingSpeech=english.trim();
        pendingExample=example==null?"":example.trim();
        ensureTts(automatic);
    }
    private void ensureTts(boolean automatic){
        if(tts==null){tts=new TextToSpeech(this,status->new Handler(Looper.getMainLooper()).post(()->{if(isFinishing()||isDestroyed())return;if(status==TextToSpeech.SUCCESS&&tts!=null){ttsReady=SpeechSettings.configure(tts,Store.prefs(this).getString("speechVoice",""),Store.prefs(this).getInt("speechRate",2));if(ttsReady&&active)playPendingSpeech();else if(!ttsReady&&!automatic)ui.toast("设备的英文朗读不可用");}else if(!automatic)ui.toast("设备的英文朗读不可用");}));}
        else if(ttsReady)playPendingSpeech();
        else if(!automatic)ui.toast("朗读正在准备或设备缺少英文语音");
    }
    private void playPendingSpeech(){
        if(!ttsReady||tts==null||!active||pendingSpeech==null||pendingSpeech.isEmpty())return;
        String word=pendingSpeech;String example=pendingExample==null?"":pendingExample;
        pendingSpeech=null;pendingExample=null;
        tts.stop();
        tts.speak(word,TextToSpeech.QUEUE_FLUSH,null,"word");
        if(!example.isEmpty())tts.speak(example,TextToSpeech.QUEUE_ADD,null,"example");
    }
    private void show(){
        if(session.ended()){summary();return;}ui.start("快速复习");ui.body.addView(ui.text((session.answered()+1)+" / "+session.total()+" 张",14,WordUi.MUTED));ui.gap(ui.body,18);JSONObject word=words.optJSONObject(session.current());boolean zh=chineseFirst(word);
        LinearLayout card=ui.col();card.setGravity(Gravity.CENTER);card.setMinimumHeight(ui.dp(300));card.setPadding(ui.dp(22),ui.dp(30),ui.dp(22),ui.dp(30));card.setBackground(ui.bg(0xFFFFFFFF,20));
        TextView label=ui.text(zh?"中文释义":"英文单词",13,WordUi.MUTED);label.setGravity(Gravity.CENTER);card.addView(label);ui.gap(card,20);TextView front=ui.text(zh?word.optString("chinese"):word.optString("english"),zh?25:32,WordUi.INK);front.setGravity(Gravity.CENTER);front.setTypeface(null,1);card.addView(front);ui.gap(card,24);
        TextView back=ui.text(zh?word.optString("english"):word.optString("chinese",""),22,WordUi.GREEN);back.setGravity(Gravity.CENTER);back.setVisibility(revealed?View.VISIBLE:View.INVISIBLE);card.addView(back);
        String example=word.optString("example");TextView sentence=ui.text(example,16,WordUi.MUTED);sentence.setGravity(Gravity.CENTER);sentence.setPadding(0,ui.dp(18),0,0);sentence.setVisibility(example.isEmpty()?View.GONE:revealed?View.VISIBLE:View.INVISIBLE);card.addView(sentence);
        TextView mastery=ui.text(Mastery.label(Mastery.level(word)),13,WordUi.MUTED);mastery.setGravity(Gravity.CENTER);mastery.setPadding(0,ui.dp(14),0,0);mastery.setVisibility(revealed?View.VISIBLE:View.INVISIBLE);card.addView(mastery);
        card.setElevation(ui.dp(2));
        ui.body.addView(card);cardView=card;ui.gap(ui.body,20);
        FrameLayout actionSlot=new FrameLayout(this);actionSlot.setMinimumHeight(ui.dp(56));feedback=ui.col();actionSlot.addView(feedback,new FrameLayout.LayoutParams(-1,-2));
        Button pronounce=ui.button("朗读单词",false,v->speak(word.optString("english"),false));pronounce.setVisibility(!zh||revealed?View.VISIBLE:View.GONE);
        Button reveal=ui.button("显示答案",true,v->revealAnswer(v,word,back,sentence,example,pronounce));reveal.setVisibility(revealed?View.GONE:View.VISIBLE);actionSlot.addView(reveal,new FrameLayout.LayoutParams(-1,-2));ui.body.addView(actionSlot);if(revealed)showFeedback();
        ui.gap(ui.body,10);ui.body.addView(pronounce);ui.gap(ui.body,10);ui.body.addView(ui.button("编辑这张卡",false,v->{if(!changing)edit(word);}));Motion.enter(card,1);
        if(revealed)speakWordThenExample(word.optString("english"),example,true);
        else if(!zh)speak(word.optString("english"),true);
    }
    private void revealAnswer(View button,JSONObject word,TextView back,TextView sentence,String example,Button pronounce){
        if(revealed||changing)return;
        interaction.reveal(button);
        revealed=true;back.setVisibility(View.VISIBLE);Motion.enter(back);
        if(!example.isEmpty()){sentence.setVisibility(View.VISIBLE);Motion.enter(sentence);}
        button.setVisibility(View.GONE);pronounce.setVisibility(View.VISIBLE);
        lastAutoSpoken=-1;
        speakWordThenExample(word.optString("english"),example,true);
        showFeedback();
    }
    private void showFeedback(){final int position=session.answered();LinearLayout buttons=ui.row();LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=ui.dp(8);buttons.addView(ui.button("忘记了",false,v->answer(false,position,v)),left);buttons.addView(ui.button("记得",true,v->answer(true,position,v)),new LinearLayout.LayoutParams(0,-2,1));feedback.removeAllViews();feedback.addView(buttons);Motion.enter(buttons);}
    private void answer(boolean known,int expected,View source){if(changing||!revealed||session.ended()||session.answered()!=expected)return;boolean firstAttempt=session.attemptsForCurrent()==0;try{if(!known)WrongBook.record(this,id,session.current(),expectedStage+1,words,"review:"+seed+":"+expected+":"+session.current());Mastery.applyAnswer(this,id,session.current(),known,firstAttempt,words);}catch(Exception e){ui.toast("结果保存失败，尚未提交本题："+e.getMessage());return;}interaction.respond(source,known?Feedback.KNOWN:Feedback.RETRY);changing=true;pendingSpeech=null;pendingExample=null;if(ttsReady)tts.stop();session.answer(known);answers.append(known?'1':'0');revealed=false;Motion.leave(cardView,known?1:-1,()->{if(isFinishing()||isDestroyed())return;changing=false;show();});}
    private void edit(JSONObject word){LinearLayout form=ui.col();form.setPadding(ui.dp(20),0,ui.dp(20),0);EditText en=new EditText(this),zh=new EditText(this),ex=new EditText(this);en.setText(word.optString("english"));zh.setText(word.optString("chinese"));ex.setText(word.optString("example"));en.setHint("英文");zh.setHint("中文");ex.setHint("例句（可留空）");form.addView(en);form.addView(zh);form.addView(ex);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("编辑单词卡").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(en.getText().toString().trim().isEmpty()){en.setError("英文不能为空");return;}try{word.put("english",en.getText().toString().trim()).put("chinese",zh.getText().toString().trim()).put("example",ex.getText().toString().trim());JSONArray all=Store.tasks(this);JSONObject current=Store.find(all,id);if(current==null)throw new Exception("任务不存在");current.put("words",words);Store.save(this,all);d.dismiss();show();}catch(Exception e){ui.toast(e.getMessage());}}));d.show();}
    private void summary(){ui.start("本轮已看完");ui.body.addView(ui.text("共 "+words.length()+" 个单词，标记“忘记了” "+session.repeats()+" 次",18,WordUi.INK));ui.gap(ui.body,8);ui.body.addView(ui.text("平均掌握度 "+String.format(java.util.Locale.CHINA,"%.1f",Mastery.average(words))+"/5",14,WordUi.MUTED));ui.gap(ui.body,18);JSONObject task=Store.find(Store.tasks(this),id);boolean eligible=task!=null&&!task.optBoolean("done")&&task.optInt("stage")==expectedStage;
        if(eligible){ui.body.addView(ui.button("完成本轮并选择拼写",true,v->chooseSpelling()));ui.gap(ui.body,12);}
        ui.body.addView(ui.button("再复习一遍",false,v->{seed=System.nanoTime();session=new ReviewSession(words.length(),Store.prefs(this).getBoolean("shuffleWords",false),seed);lastAutoSpoken=-1;answers.setLength(0);revealed=false;show();}));ui.gap(ui.body,10);
        int flipTo=sessionChineseBiased()?0:1;
        ui.body.addView(ui.button(flipTo==1?"换向再复习（先中文）":"换向再复习（先英文）",false,v->{Motion.open(this,new android.content.Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("forceMode",flipTo));finish();}));ui.gap(ui.body,10);
        if(session.repeats()>0){ui.body.addView(ui.button("错词义项练习",false,v->{Motion.open(this,new android.content.Intent(this,SenseQuizActivity.class).putExtra("taskId",id).putExtra("wrongOnly",true));finish();}));ui.gap(ui.body,10);}
        ui.body.addView(ui.button("AI 本轮复盘",false,v->aiSummary()));ui.gap(ui.body,10);
        ui.body.addView(ui.button("返回主页",false,v->finish()));Motion.enter(ui.body);
    }
    private void aiSummary(){
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        try{
            JSONArray weak=new JSONArray();int[] missed=session.missedIndices();
            for(int index:missed){JSONObject w=words.optJSONObject(index);if(w!=null)weak.put(w.optString("english"));}
            JSONObject payload=new JSONObject().put("total",words.length()).put("forgotten",session.repeats()).put("weakWords",weak).put("avgMastery",Mastery.average(words));
            ui.toast("正在生成复盘…");
            pendingAi=AiClient.chatJson(this,AiPrompts.systemReviewSummary(),AiPrompts.userReviewSummary(payload.toString()),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){
                    String body=json.optString("summary","")+"\n\n下一步："+json.optString("next","");
                    JSONArray weakList=json.optJSONArray("weak");if(weakList!=null&&weakList.length()>0){StringBuilder w=new StringBuilder("薄弱词：");for(int i=0;i<weakList.length();i++){if(i>0)w.append("、");w.append(weakList.optString(i));}body=w+"\n\n"+body;}
                    new AlertDialog.Builder(ReviewActivity.this).setTitle("AI 本轮复盘").setMessage(body.trim()).setPositiveButton("关闭",null).show();
                }
                @Override public void onError(String message){ui.toast(message);}
            });
        }catch(Exception e){ui.toast(e.getMessage());}
    }
    private void chooseSpelling(){
        android.content.SharedPreferences prefs=Store.prefs(this);
        int saved=prefs.getInt("spellingMode",SpellingPlan.NONE);
        boolean configured=prefs.getBoolean("spellingConfigured",false);
        if(configured&&!prefs.getBoolean("askSpellingMode",false)){complete(saved);return;}
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(22),ui.dp(8),ui.dp(22),ui.dp(4));
        TextView note=ui.text("选择后会保存为默认方式。以后可在“我的 > 复习与拼写”调整。",14,WordUi.MUTED);content.addView(note);
        RadioGroup choices=new RadioGroup(this);String[] labels={"拼写全部单词","拼写本词组错题本中的错词","不进行拼写"};
        for(int i=0;i<labels.length;i++){RadioButton option=new RadioButton(this);option.setId(i);option.setText(labels[i]);option.setMinHeight(ui.dp(48));choices.addView(option);}
        choices.check(Math.max(0,Math.min(2,saved)));content.addView(choices);
        CheckBox askAgain=new CheckBox(this);askAgain.setText("以后每轮复习后都重新询问");askAgain.setChecked(prefs.getBoolean("askSpellingMode",false));askAgain.setMinHeight(ui.dp(48));content.addView(askAgain);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(configured?"完成并选择拼写":"设置复习后的拼写").setView(content)
            .setNegativeButton("继续练习",null).setPositiveButton("完成本轮",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{int selected=choices.getCheckedRadioButtonId();if(selected<0){ui.toast("请选择一种拼写方式");return;}Store.prefs(this).edit().putInt("spellingMode",selected).putBoolean("spellingConfigured",true).putBoolean("askSpellingMode",askAgain.isChecked()).apply();dialog.dismiss();complete(selected);}));dialog.show();
    }
    private void complete(int spellingMode){
        if(completed)return;
        try{
            JSONArray all=Store.tasks(this);JSONObject task=Store.find(all,id);
            if(task==null||task.optBoolean("done")||task.optInt("stage")!=expectedStage){ui.toast("本轮状态已变化，未重复推进阶段");finish();return;}
            long now=System.currentTimeMillis();int next=expectedStage+1;
            JSONArray history=task.getJSONArray("history");history.put(new JSONObject().put("stage",next).put("at",now).put("repeatCount",session.repeats()).put("spellingMode",spellingMode));
            task.put("stage",next).put("snoozes",0);
            if(next>=task.getJSONArray("intervals").length())task.put("done",true);
            else task.put("due",Schedule.due(now,task.getJSONArray("intervals").getLong(next),Store.windows(task.optString("windows"))));
            if(!Store.prefs(this).edit().putString("tasks",all.toString()).putInt("spellingMode",spellingMode).commit())throw new IllegalStateException("复习结果未能写入设备");
            Alarms.cancel(this,id);if(!task.optBoolean("done"))Alarms.schedule(this,task);completed=true;ui.toast("本轮复习已完成");

            JSONArray storedWords=task.optJSONArray("words");int[] wrongCounts=new int[words.length()];
            if(storedWords!=null)for(int i=0;i<Math.min(wrongCounts.length,storedWords.length());i++){JSONObject word=storedWords.optJSONObject(i);if(word!=null)wrongCounts[i]=word.optInt("wrongCount");}
            int[] indices=SpellingPlan.indices(spellingMode,words.length(),SpellingPlan.wrongBookIndices(words.length(),wrongCounts));
            int[] forgotten=session.missedIndices();
            if(Store.prefs(this).getBoolean("spellForgottenWords",false))indices=SpellingPlan.include(indices,forgotten,words.length());
            int[] examples=Store.prefs(this).getBoolean("addExamplesForForgotten",false)?ExampleActivity.missingExampleIndices(storedWords,forgotten):new int[0];

            if(indices.length>0){
                android.content.Intent nextStep=new android.content.Intent(this,SpellingActivity.class).putExtra("taskId",id).putExtra("round",next).putExtra("indices",indices).putExtra("historyAt",now).putExtra("exampleIndices",examples);
                Motion.open(this,nextStep);finish();
            }else if(examples.length>0){
                Motion.open(this,new android.content.Intent(this,ExampleActivity.class).putExtra("taskId",id).putExtra("indices",examples));finish();
            }else finish();
        }catch(Exception e){ui.toast("保存失败："+e.getMessage());}
    }
    @Override public void onBackPressed(){if(Store.prefs(this).getBoolean("skipReviewExitPrompt",false)){finish();return;}LinearLayout body=ui.col();body.setPadding(ui.dp(20),0,ui.dp(20),0);body.addView(ui.text("单词修改已保存，但本轮不会标记完成。",14,WordUi.INK));CheckBox dontRemind=new CheckBox(this);dontRemind.setText("不再提示");dontRemind.setMinHeight(ui.dp(48));body.addView(dontRemind);new AlertDialog.Builder(this).setTitle("退出本轮复习？").setView(body).setNegativeButton("继续复习",null).setPositiveButton("退出",(d,w)->{if(dontRemind.isChecked())Store.prefs(this).edit().putBoolean("skipReviewExitPrompt",true).apply();finish();}).show();}
    @Override protected void onResume(){super.onResume();active=true;}
    @Override protected void onPause(){active=false;pendingSpeech=null;pendingExample=null;if(tts!=null)tts.stop();super.onPause();}
    @Override protected void onDestroy(){AiClient.cancel(pendingAi);if(tts!=null)tts.shutdown();interaction.release();super.onDestroy();}
}
