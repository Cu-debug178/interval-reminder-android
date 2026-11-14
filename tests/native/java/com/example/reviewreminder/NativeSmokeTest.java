package com.example.reviewreminder;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;

public final class NativeSmokeTest extends Instrumentation {
    private File output;private StringBuilder log=new StringBuilder();
    public void onCreate(Bundle b){super.onCreate(b);start();}
    private void line(String message)throws Exception{log.append(message).append('\n');File file=new File(output,"results.txt");try(OutputStream out=new FileOutputStream(file)){out.write(log.toString().getBytes("UTF-8"));}upload(file);}
    private void upload(File file){android.util.Log.i("ReviewQA",file.getAbsolutePath());}
    private void check(boolean ok,String message)throws Exception{if(!ok)throw new AssertionError(message);line("PASS "+message);}
    @Override public void onStart(){output=new File(getTargetContext().getExternalFilesDir(null),"qa");output.mkdirs();Bundle result=new Bundle();int code=Activity.RESULT_OK;try{runTests();line("ALL PASSED");}catch(Throwable e){code=Activity.RESULT_CANCELED;try{StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));line("FAIL "+trace);}catch(Exception ignored){android.util.Log.e("ReviewQA","Could not write test log",e);}}result.putString("stream",log.toString());finish(code,result);}
    private Activity open(Class<?> cls,long id){Intent intent=new Intent(getTargetContext(),cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(id!=0)intent.putExtra("taskId",id);Activity a=startActivitySync(intent);waitForIdleSync();return a;}
    private View find(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(text))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=find(g.getChildAt(i),text);if(hit!=null)return hit;}}return null;}
    private View findClickable(View v,String text,boolean exact){if(v instanceof TextView){String value=((TextView)v).getText().toString();if(exact?value.equals(text):value.startsWith(text)){View target=v;while(!target.isClickable()&&target.getParent() instanceof View)target=(View)target.getParent();if(target.isClickable())return target;}}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=findClickable(g.getChildAt(i),text,exact);if(hit!=null)return hit;}}return null;}
    private View findClickable(View v,String text){return findClickable(v,text,false);}
    private View findContains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=findContains(g.getChildAt(i),text);if(hit!=null)return hit;}}return null;}
    private View findDescription(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=findDescription(g.getChildAt(i),text);if(hit!=null)return hit;}}return null;}
    private View findTextInWindows(String text)throws Exception{
        try{Class<?> wmg=Class.forName("android.view.WindowManagerGlobal");Object global=wmg.getMethod("getInstance").invoke(null);Field viewsField=wmg.getDeclaredField("mViews");viewsField.setAccessible(true);java.util.List<?> roots=(java.util.List<?>)viewsField.get(global);if(roots!=null)for(int i=roots.size()-1;i>=0;i--){Object root=roots.get(i);if(!(root instanceof View))continue;View hit=findContains((View)root,text);if(hit!=null)return hit;}}catch(Throwable ignored){}
        return null;
    }
    private View findClickableInWindows(String text,boolean exact)throws Exception{
        try{Class<?> wmg=Class.forName("android.view.WindowManagerGlobal");Object global=wmg.getMethod("getInstance").invoke(null);Field viewsField=wmg.getDeclaredField("mViews");viewsField.setAccessible(true);java.util.List<?> roots=(java.util.List<?>)viewsField.get(global);if(roots!=null)for(int i=roots.size()-1;i>=0;i--){Object root=roots.get(i);if(!(root instanceof View))continue;View hit=findClickable((View)root,text,exact);if(hit!=null)return hit;}}catch(Throwable ignored){}
        return null;
    }
    private void click(Activity a,String text)throws Exception{final Throwable[] error={null};runOnMainSync(()->{try{View v=findClickableInWindows(text,true);if(v==null)v=findClickable(a.getWindow().getDecorView(),text,true);if(v==null)v=findClickableInWindows(text,false);if(v==null)v=findClickable(a.getWindow().getDecorView(),text,false);if(v==null){View d=findDescription(a.getWindow().getDecorView(),text);if(d!=null&&d.isClickable())v=d;}if(v==null)throw new AssertionError("Missing clickable "+text);if(v instanceof RadioButton){((RadioButton)v).setChecked(true);return;}if(v instanceof CheckBox){CheckBox box=(CheckBox)v;box.setChecked(!box.isChecked());return;}if(!v.performClick())throw new AssertionError("Click was not handled: "+text);}catch(Throwable t){error[0]=t;}});waitForIdleSync();Thread.sleep(230);if(error[0]!=null)throw new Exception(error[0]);}
    private void call(Activity a,String method)throws Exception{final Throwable[] error={null};runOnMainSync(()->{try{Method m=a.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(a);}catch(Throwable e){error[0]=e;}});waitForIdleSync();if(error[0]!=null)throw new Exception(error[0]);}
    private void screenshot(Activity a,String name,int width,int height)throws Exception{runOnMainSync(()->{View root=a.getWindow().getDecorView().findViewById(android.R.id.content);float d=a.getResources().getDisplayMetrics().density;int w=(int)(width*d),h=(int)(height*d);root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}b.recycle();});upload(new File(output,name+".png"));line("SCREENSHOT "+name);}
    private void runTests()throws Exception{
        line("START native QA "+android.os.Build.VERSION.RELEASE);Store.prefs(getTargetContext()).edit().clear().commit();Store.prefs(getTargetContext()).edit().putBoolean("motion",false).putBoolean("setupComplete",true).putString("extraIntervals","43200,86400").putString("windows","07:30-08:30,22:00-01:00").putBoolean("respectWindows",true).apply();check(Store.windows("07:30-08:30,22:00-01:00").size()==2,"multiple and overnight windows parse");check(Store.windows("").isEmpty(),"empty windows mean all day");check(ReminderWindows.active(getTargetContext()).equals("07:30-08:30,22:00-01:00"),"enabled window restriction is active");Store.prefs(getTargetContext()).edit().putBoolean("respectWindows",false).commit();check(ReminderWindows.active(getTargetContext()).isEmpty(),"disabled restriction means all day");Store.prefs(getTargetContext()).edit().putBoolean("respectWindows",true).commit();Words.clearDraft(getTargetContext());
        testHapticFeedback();
        Words.lesson(getTargetContext());JSONObject draft=Words.draft(getTargetContext());check(draft.getJSONArray("words").length()==42,"Lesson 1 has 42 cards");
        check(draft.optJSONArray("photos")==null||draft.optJSONArray("photos").length()==0,"Lesson 1 draft has no photo attachments");
        check(!AiSettings.ready(getTargetContext()),"AI is disabled by default without an API key");
        check(AiClient.extractJson("```json\n{\"ok\":true}\n```").optBoolean("ok"),"AiClient strips markdown fences from JSON");
        testAiConfigs();
        JSONObject masteryWord=new JSONObject().put("mastery",2).put("masteryStreak",0);Mastery.remember(masteryWord);check(Mastery.level(masteryWord)==3,"mastery increases on remember");Mastery.forget(masteryWord);check(Mastery.level(masteryWord)==2,"mastery decreases on forget");
        ReviewSession attemptProbe=new ReviewSession(1,false,42L);check(attemptProbe.attemptsForCurrent()==0,"review session exposes first-attempt counter for mastery gating");
        JSONObject senseWord=new JSONObject().put("english","book").put("chinese","n. 书；v. 预订").put("example","");JSONArray senses=Senses.migrate(senseWord);check(senses.length()>=2,"multi-sense chinese migrates to senses[]");check(Senses.label(senses.optJSONObject(0)).contains("书"),"first sense keeps noun meaning");
        Store.prefs(getTargetContext()).edit().putBoolean("setupComplete",false).commit();
        Activity main=open(MainActivity.class,0);Object setup=field(main,"setupDialog");
        check(((android.app.Dialog)setup).isShowing(),"first-run setup wizard opens");
        call(main,"advanceSetup");check(intField(main,"setupStep")==1,"setup advances to out-of-window rule");
        call(main,"advanceSetup");check(intField(main,"setupStep")==2,"setup advances to permission guidance");
        call(main,"advanceSetup");check(Store.prefs(getTargetContext()).getBoolean("setupComplete",false),"setup wizard saves selected preferences");
        screenshot(main,"home-360",360,760);
        check(find(main.getWindow().getDecorView(),"录词")!=null&&find(main.getWindow().getDecorView(),"Lesson 1")!=null&&find(main.getWindow().getDecorView(),"打开校对")!=null,"home shows录词 and Lesson 1 card");
        check(find(main.getWindow().getDecorView(),"提醒设置")==null,"home no longer jumps to reminder settings");
        click(main,"我的");
        check(find(main.getWindow().getDecorView(),"复习计划")!=null&&find(main.getWindow().getDecorView(),"复习与拼写")!=null&&find(main.getWindow().getDecorView(),"AI")!=null,"settings are grouped into category entries including AI");
        click(main,"AI");check(find(main.getWindow().getDecorView(),"启用 AI 功能")!=null&&find(main.getWindow().getDecorView(),"测试连接")!=null&&find(main.getWindow().getDecorView(),"当前模型")!=null&&find(main.getWindow().getDecorView(),"添加配置")!=null,"AI settings expose the current model, enable switch, connection test and add action");
        click(main,"添加配置");check(findClickableInWindows("测试并保存",true)!=null&&findClickableInWindows("获取模型",true)!=null,"add-config sheet tests the connection and can fetch models");click(main,"取消");
        click(main,"返回设置目录");
        click(main,"复习计划");check(find(main.getWindow().getDecorView(),"默认复习间隔")!=null,"interval controls open from their category");
        Switch optionalFirst=(Switch)find(main.getWindow().getDecorView(),"启用第 8 轮");runOnMainSync(()->optionalFirst.setChecked(true));
        click(main,"返回设置目录");check(Store.prefs(getTargetContext()).getBoolean("optional",false),"leaving a settings category automatically saves changes");
        click(main,"复习计划");Switch optionalSecond=(Switch)find(main.getWindow().getDecorView(),"启用第 8 轮");
        runOnMainSync(()->optionalSecond.setChecked(false));click(main,"主页");
        check(!Store.prefs(getTargetContext()).getBoolean("optional",true),"switching to another page saves the active settings category");
        click(main,"我的");click(main,"复习与拼写");
        Switch forgottenSpelling=(Switch)find(main.getWindow().getDecorView(),"额外拼写本轮忘记的单词");Switch forgottenExamples=(Switch)find(main.getWindow().getDecorView(),"复习后为忘记的词补充例句");
        runOnMainSync(()->{forgottenSpelling.setChecked(true);forgottenExamples.setChecked(true);});click(main,"返回设置目录");
        check(Store.prefs(getTargetContext()).getBoolean("spellForgottenWords",false)&&Store.prefs(getTargetContext()).getBoolean("addExamplesForForgotten",false),"forgotten-word spelling and example switches save on category exit");
        click(main,"提醒时段");
        check(find(main.getWindow().getDecorView(),"添加提醒时间段")!=null,"settings use selectable time-window rows");
        check(find(main.getWindow().getDecorView(),"只在选择的时段内提醒")!=null,"time-window restriction has an explicit toggle");
        final TimeWindowPicker[] picker={null};runOnMainSync(()->{picker[0]=new TimeWindowPicker(main,"",null);View add=find(picker[0].view(),"添加提醒时间段");add.performClick();add.performClick();});
        check(picker[0].draftValue().equals("09:00-10:00,09:00-10:00"),"time picker adds multiple editable windows");
        screenshot(main,"settings-collapsed-360",360,760);click(main,"我的");click(main,"复习计划");click(main,"自定义间隔");
        screenshot(main,"settings-expanded-360",360,860);runOnMainSync(()->main.finish());
        JSONObject quickDraft=new JSONObject().put("title","快速校对测试").put("words",new JSONArray().put(new JSONObject().put("english","hello").put("chinese","你好").put("example","")).put(new JSONObject().put("english","world").put("chinese","世界").put("example",""))).put("photos",new JSONArray());Words.writeDraft(getTargetContext(),quickDraft);
        check(Words.hasDraft(getTargetContext()),"hasDraft is true when draft has English cards");
        Activity importWithDraft=open(ImportActivity.class,0);check(find(importWithDraft.getWindow().getDecorView(),"继续校对草稿")!=null&&find(importWithDraft.getWindow().getDecorView(),"AI 整理成单词卡")!=null,"import home shows continue draft and AI import");screenshot(importWithDraft,"import-with-draft-360",360,760);runOnMainSync(()->importWithDraft.finish());
        Intent quickIntent=new Intent(getTargetContext(),WordEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("quickReview",true);Activity quick=startActivitySync(quickIntent);waitForIdleSync();check(find(quick.getWindow().getDecorView(),"hello")!=null&&find(quick.getWindow().getDecorView(),"你好")!=null,"quick proofreading opens a read-only vocabulary card");screenshot(quick,"proof-quick-360",360,760);click(quick,"有问题，修改");check(find(quick.getWindow().getDecorView(),"保存并返回确认")!=null,"problem action opens the card editor");runOnMainSync(()->{try{Field f=WordEditorActivity.class.getDeclaredField("chinese");f.setAccessible(true);((EditText)f.get(quick)).setText("你好（修正）");}catch(Exception e){throw new RuntimeException(e);}});click(quick,"保存并返回确认");check(find(quick.getWindow().getDecorView(),"你好（修正）")!=null,"saving correction returns to the same confirmation card");click(quick,"没问题，下一张");check(find(quick.getWindow().getDecorView(),"world")!=null,"confirm action advances to the next card");
        ActivityMonitor homeMonitor=addMonitor(MainActivity.class.getName(),null,true);
        click(quick,"没问题，完成校对");waitForIdleSync();
        Activity homeFromCommit=homeMonitor.waitForActivityWithTimeout(3000);removeMonitor(homeMonitor);
        if(homeFromCommit!=null)runOnMainSync(()->homeFromCommit.finish());
        check(!Words.hasDraft(getTargetContext())&&!new File(getTargetContext().getFilesDir(),"word-draft.json").exists(),"quick proof completion clears the draft");
        check(Store.tasks(getTargetContext()).length()==1,"quick proof completion creates one review task");
        Activity importNoDraft=open(ImportActivity.class,0);check(find(importNoDraft.getWindow().getDecorView(),"继续校对草稿")==null&&find(importNoDraft.getWindow().getDecorView(),"拍摄单词照片")!=null&&find(importNoDraft.getWindow().getDecorView(),"从相册选择（可多选）")!=null&&find(importNoDraft.getWindow().getDecorView(),"打开 Lesson")==null,"import home hides continue draft, offers multi-photo capture, and removes Lesson 1");screenshot(importNoDraft,"import-no-draft-360",360,760);runOnMainSync(()->importNoDraft.finish());
        Store.save(getTargetContext(),new JSONArray());Store.prefs(getTargetContext()).edit().putLong("nextId",0).commit();
        Words.lesson(getTargetContext());
        Activity editor=open(WordEditorActivity.class,0);check(find(editor.getWindow().getDecorView(),"更多操作")!=null,"word editor tucks secondary actions behind 更多操作");screenshot(editor,"editor-360",360,860);runOnMainSync(()->{try{Field f=WordEditorActivity.class.getDeclaredField("chinese");f.setAccessible(true);((EditText)f.get(editor)).setText("n. 野心；雄心；抱负（已校对）");}catch(Exception e){throw new RuntimeException(e);}});click(editor,"下一张");check(Words.draft(getTargetContext()).getJSONArray("words").getJSONObject(0).getString("chinese").contains("已校对"),"editing card persists on next");call(editor,"commit");waitForIdleSync();
        JSONArray all=Store.tasks(getTargetContext());check(all.length()==1,"word group becomes a task");JSONObject task=all.getJSONObject(0);check(task.getJSONArray("intervals").length()==9,"custom intervals appended to task snapshot");check(task.getString("windows").equals("07:30-08:30,22:00-01:00"),"word task snapshots enabled time windows");check(!new File(getTargetContext().getFilesDir(),"word-draft.json").exists(),"committed draft cleared");
        long id=task.getLong("id");Activity review=open(ReviewActivity.class,id);screenshot(review,"review-front-360",360,760);check(find(review.getWindow().getDecorView(),"记得")==null,"feedback hidden before reveal");click(review,"显示答案");screenshot(review,"review-answer-360",360,760);check(find(review.getWindow().getDecorView(),"忘记了")!=null,"forgotten answer action is clearly labeled");click(review,"忘记了");check(Store.find(Store.tasks(getTargetContext()),id).getInt("stage")==0,"card answer does not advance stage");
        JSONObject wrongWord=Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words").getJSONObject(0);check(wrongWord.optInt("wrongCount")==1&&wrongWord.optJSONArray("wrongRounds").optInt(0)==1,"review misses record count and review round");int beforeDuplicate=wrongWord.optInt("wrongCount");JSONArray sessionWords=Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words");WrongBook.record(getTargetContext(),id,0,1,sessionWords,"qa-idempotent-event");WrongBook.record(getTargetContext(),id,0,1,sessionWords,"qa-idempotent-event");wrongWord=Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words").getJSONObject(0);check(wrongWord.optInt("wrongCount")==beforeDuplicate+1,"wrong-book event IDs prevent duplicate mistake counts");        check(wrongWord.optJSONArray("wrongRounds").length()==1,"wrong rounds are unique even with repeated mistakes in one round");
        View cardTap=(View)field(review,"cardView");String counterBefore=((TextView)field(review,"counter")).getText().toString();
        runOnMainSync(()->cardTap.performClick());waitForIdleSync();
        check(find(review.getWindow().getDecorView(),"忘记了")!=null&&((TextView)field(review,"counter")).getText().toString().equals(counterBefore),"first tap on the card reveals and does not advance");
        runOnMainSync(()->{try{((View)field(review,"cardView")).performClick();}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(400);waitForIdleSync();
        check(!((TextView)field(review,"counter")).getText().toString().equals(counterBefore),"second tap on the revealed card advances like 记得");
        final View[] held={null};runOnMainSync(()->{try{held[0]=(View)field(review,"cardView");}catch(Exception e){throw new RuntimeException(e);}long t=android.os.SystemClock.uptimeMillis();float x=held[0].getWidth()/2f,y=Math.max(1,held[0].getHeight()/3f);held[0].dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,x,y,0));});
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+120);waitForIdleSync();
        check(findTextInWindows("编辑单词卡")!=null,"long-press on the card opens the word editor");
        runOnMainSync(()->{long t=android.os.SystemClock.uptimeMillis();held[0].dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_CANCEL,0,0,0));});
        click(review,"取消");
        int guard=0;while(find(review.getWindow().getDecorView(),"本轮已看完")==null&&guard++<60){click(review,"显示答案");click(review,"记得");}
        check(guard<60,"review queue terminates after retry");screenshot(review,"review-summary-360",360,760);
        check(find(review.getWindow().getDecorView(),"更多选项")!=null,"summary keeps secondary actions behind 更多选项");
        click(review,"更多选项");
        check(findClickableInWindows("换向再复习",false)!=null&&findClickableInWindows("错词义项练习",false)!=null&&findClickableInWindows("AI 本轮复盘",false)!=null,"summary more menu offers flip review, wrong-sense quiz, and AI recap");
        click(review,"取消");
        click(review,"完成本轮并选择拼写");check(!Store.prefs(getTargetContext()).getBoolean("spellingConfigured",false),"spelling default is not configured before first explicit choice");
        click(review,"拼写本词组错题本中的错词");ActivityMonitor spellingMonitor=addMonitor(SpellingActivity.class.getName(),null,false);click(review,"完成本轮");
        check(Store.find(Store.tasks(getTargetContext()),id).getInt("stage")==1,"explicit completion advances exactly one stage");
        check(Store.prefs(getTargetContext()).getInt("spellingMode",SpellingPlan.NONE)==SpellingPlan.WRONG_ONLY&&Store.prefs(getTargetContext()).getBoolean("spellingConfigured",false),"first spelling choice is saved as the default");
        Activity spelling=spellingMonitor.waitForActivityWithTimeout(5000);removeMonitor(spellingMonitor);waitForIdleSync();
        check(spelling!=null&&find(spelling.getWindow().getDecorView(),"1 / 1 个")!=null,"review launches wrong-only spelling with cumulative wrong-book entries");
        EditText spellingInput=(EditText)field(spelling,"answerInput");runOnMainSync(()->spellingInput.setText("not-the-word"));click(spelling,"检查拼写");
        check(Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words").getJSONObject(0).optInt("wrongCount")==beforeDuplicate+2,"spelling errors are added to wrong-book counts");
        ActivityMonitor exampleMonitor=addMonitor(ExampleActivity.class.getName(),null,false);click(spelling,"查看结果");check(find(spelling.getWindow().getDecorView(),"继续补充例句")!=null,"spelling flow offers missed words without examples");click(spelling,"继续补充例句");Activity examples=exampleMonitor.waitForActivityWithTimeout(5000);removeMonitor(exampleMonitor);waitForIdleSync();check(examples!=null&&find(examples.getWindow().getDecorView(),"ambition")!=null,"forgotten word opens its example editor");EditText exampleInput=(EditText)field(examples,"exampleInput");runOnMainSync(()->exampleInput.setText("She has ambition."));click(examples,"保存并完成");check(Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words").getJSONObject(0).getString("example").equals("She has ambition."),"example is saved to the original vocabulary card");click(examples,"返回主页");
        final Throwable[] completionError={null};runOnMainSync(()->{try{Method method=review.getClass().getDeclaredMethod("complete",int.class);method.setAccessible(true);method.invoke(review,SpellingPlan.WRONG_ONLY);}catch(Throwable e){completionError[0]=e;}});
        check(completionError[0]==null,"duplicate completion call returns without error");check(Store.find(Store.tasks(getTargetContext()),id).getInt("stage")==1,"duplicate completion does not advance stage");
        Activity wrongBook=open(WrongBookActivity.class,0);check(find(wrongBook.getWindow().getDecorView(),"ambition")!=null&&findContains(wrongBook.getWindow().getDecorView(),"错误 3 次")!=null&&findContains(wrongBook.getWindow().getDecorView(),"第1轮")!=null,"wrong book displays cumulative count and unique review rounds");runOnMainSync(()->wrongBook.finish());
        testCalendarAndFeedback(id);
        Store.prefs(getTargetContext()).edit().putBoolean("motion",true).commit();Activity wide=open(MainActivity.class,0);Thread.sleep(250);check(Motion.enabled(wide),"animations enabled in native runtime");screenshot(wide,"home-800",800,760);click(wide,"我的");click(wide,"复习计划");Thread.sleep(200);check(find(wide.getWindow().getDecorView(),"第 1 轮").isShown(),"interval category layout is available");click(wide,"返回设置目录");click(wide,"复习计划");Thread.sleep(200);check(find(wide.getWindow().getDecorView(),"第 1 轮").isShown(),"category navigation remains stable after auto-save");
        click(wide,"主页");Activity animated=open(ReviewActivity.class,id);click(animated,"显示答案");click(animated,"记得");check(find(animated.getWindow().getDecorView(),"2 / ")!=null,"animated answer advances one card");screenshot(animated,"review-motion-360",360,760);runOnMainSync(()->animated.finish());runOnMainSync(()->wide.finish());
        Activity imported=open(ImportActivity.class,0);check(find(imported.getWindow().getDecorView(),"更多录入方式")!=null&&find(imported.getWindow().getDecorView(),"拍摄单词照片")!=null&&find(imported.getWindow().getDecorView(),"从相册选择（可多选）")!=null,"import home offers multi-photo cloud entry and tucked local parse");click(imported,"更多录入方式");check(findClickableInWindows("本地解析并校对",false)!=null,"更多录入方式 exposes local parse");click(imported,"取消");screenshot(imported,"import-result-360",360,760);runOnMainSync(()->imported.finish());
        int practiceStage=Store.find(Store.tasks(getTargetContext()),id).getInt("stage");int practiceWrong=Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words").getJSONObject(0).optInt("wrongCount");
        Intent practiceIntent=new Intent(getTargetContext(),ReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("taskId",id).putExtra("practice",true);
        Activity practice=startActivitySync(practiceIntent);waitForIdleSync();
        check(find(practice.getWindow().getDecorView(),"任意复习")!=null&&findContains(practice.getWindow().getDecorView(),"不计计划轮次")!=null,"practice mode labels itself as free review");
        click(practice,"显示答案");click(practice,"忘记了");
        check(Store.find(Store.tasks(getTargetContext()),id).getInt("stage")==practiceStage,"practice mode does not advance stage");
        check(Store.find(Store.tasks(getTargetContext()),id).getJSONArray("words").getJSONObject(0).optInt("wrongCount")==practiceWrong,"practice mode does not record wrong-book counts");
        screenshot(practice,"practice-360",360,760);runOnMainSync(()->practice.finish());
        Activity homeTasks=open(MainActivity.class,0);check(find(homeTasks.getWindow().getDecorView(),"待复习")!=null&&find(homeTasks.getWindow().getDecorView(),"任意复习")!=null&&find(homeTasks.getWindow().getDecorView(),"Lesson 1")!=null,"home lists task review cards with practice entry and Lesson card");screenshot(homeTasks,"home-tasks-360",360,760);runOnMainSync(()->homeTasks.finish());
        Activity create=open(CreateActivity.class,0);check(find(create.getWindow().getDecorView(),"创造力造句")!=null||find(create.getWindow().getDecorView(),"提交批改")!=null,"create activity opens for sentence practice");screenshot(create,"create-360",360,760);runOnMainSync(()->create.finish());
        Activity senseQuiz=open(SenseQuizActivity.class,id);check(find(senseQuiz.getWindow().getDecorView(),"词性义项")!=null||find(senseQuiz.getWindow().getDecorView(),"选出正确")!=null,"sense quiz opens for task words");screenshot(senseQuiz,"sense-quiz-360",360,760);runOnMainSync(()->senseQuiz.finish());
        liveAiProbe();
    }
    private void liveAiProbe()throws Exception{
        java.io.File external=getTargetContext().getExternalFilesDir(null);
        java.io.File env=external==null?null:new java.io.File(external,"interval-ai-keys.env");
        if(env==null||!env.exists()){line("SKIP live AI probe (no app files/interval-ai-keys.env)");return;}
        java.util.Map<String,String> keys=new java.util.HashMap<>();
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.FileReader(env))){
            String line;while((line=reader.readLine())!=null){
                line=line.trim();if(line.isEmpty()||line.startsWith("#")||!line.contains("="))continue;
                int eq=line.indexOf('=');keys.put(line.substring(0,eq).trim(),line.substring(eq+1).trim());
            }
        }
        String deepseek=keys.get("DEEPSEEK_API_KEY"), mimo=keys.get("MIMO_API_KEY");
        android.content.Context ctx=getTargetContext();
        if(deepseek!=null&&!deepseek.isEmpty()){
            check(AiSettings.save(ctx,true,AiSettings.PROVIDER_DEEPSEEK,AiSettings.DEEPSEEK_URL,AiSettings.DEEPSEEK_MODEL,deepseek),"DeepSeek settings save with encrypted key");
            final String[] err={null};final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            AiClient.chatJson(ctx,AiPrompts.systemPing(),AiPrompts.userPing(),new AiClient.Callback(){
                public void onResult(JSONObject json){err[0]=json.optBoolean("ok",true)?null:"bad ping json";done.countDown();}
                public void onError(String message){err[0]=message;done.countDown();}
            });
            check(done.await(90,java.util.concurrent.TimeUnit.SECONDS),"DeepSeek ping completed");
            check(err[0]==null,"DeepSeek live ping succeeds");
            line("PASS DeepSeek live ping");
        }
        if(mimo!=null&&!mimo.isEmpty()){
            check(AiSettings.save(ctx,true,AiSettings.PROVIDER_MIMO,AiSettings.MIMO_URL,AiSettings.MIMO_MODEL,mimo),"MiMo settings save with encrypted key");
            final String[] err={null};final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            AiClient.chatJson(ctx,AiPrompts.systemPing(),AiPrompts.userPing(),new AiClient.Callback(){
                public void onResult(JSONObject json){err[0]=json.optBoolean("ok",true)?null:"bad ping json";done.countDown();}
                public void onError(String message){err[0]=message;done.countDown();}
            });
            check(done.await(90,java.util.concurrent.TimeUnit.SECONDS),"MiMo ping completed");
            if(err[0]!=null)line("MiMo live ping detail: "+err[0]);
            check(err[0]==null,"MiMo live ping succeeds");
            line("PASS MiMo live ping");
        }
        AiSettings.reset(ctx);
    }
    private void testAiConfigs()throws Exception{
        android.content.Context ctx=getTargetContext();
        AiSettings.reset(ctx);
        check(AiSettings.normalizeBase("https://api.openai.com/v1/chat/completions/").equals("https://api.openai.com/v1"),"base URL drops chat/completions and a trailing slash");
        check(AiSettings.normalizeBase("https://api.deepseek.com/v1/models").equals("https://api.deepseek.com/v1"),"base URL drops a trailing /models");
        java.util.List<String> deepseek=AiSettings.modelUrls("https://api.deepseek.com");
        check(deepseek.get(0).equals("https://api.deepseek.com/models")&&deepseek.get(1).equals("https://api.deepseek.com/v1/models"),"unversioned base tries /models then /v1/models");
        check(AiSettings.modelUrls("https://api.xiaomimimo.com/v1/").get(0).equals("https://api.xiaomimimo.com/v1/models"),"base that already has /v1 lists models beside it");
        check(AiSettings.modelUrls("https://openrouter.ai/api/v1").get(0).equals("https://openrouter.ai/api/v1/models"),"OpenRouter-style /v1 base lists /v1/models");
        java.util.List<String> ids=AiClient.parseModelIds("{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"},{\"id\":\"b\"}]}");
        check(ids.size()==2&&ids.get(0).equals("a")&&ids.get(1).equals("b"),"model ids are de-duplicated and sorted");
        String secret="sk-test-secret-key-value";
        String explained=AiClient.explainHttp(401,"{\"error\":{\"message\":\"invalid "+secret+"\"}}",secret);
        check(explained.startsWith("密钥被拒绝")&&!explained.contains(secret),"auth errors stay short and hide the key");
        check(AiSettings.validateBase("not a url")!=null&&AiSettings.configs(ctx).length()==0,"a bad address is rejected and does not create a config");
        AiSecrets.writeApiKey(ctx,secret);
        Store.prefs(ctx).edit().remove("aiConfigs").putInt("aiProvider",AiSettings.PROVIDER_DEEPSEEK).putString("aiBaseUrl",AiSettings.DEEPSEEK_URL).putString("aiModel","deepseek-chat").commit();
        check(AiSettings.configs(ctx).length()==1&&"deepseek-chat".equals(AiSettings.model(ctx)),"legacy key migrates into one config and keeps its model");
        check(AiSecrets.readLegacyApiKey(ctx).isEmpty(),"legacy key slot is cleared after migration");
        String masked=AiSecrets.mask(AiSettings.apiKey(ctx));
        check(!masked.equals(AiSettings.apiKey(ctx))&&!masked.contains(secret),"saved key is masked for display");
        AiSettings.reset(ctx);
        check(AiSettings.save(ctx,false,AiSettings.PROVIDER_DEEPSEEK,AiSettings.DEEPSEEK_URL,AiSettings.DEEPSEEK_MODEL,secret),"first config saves");
        check(AiSettings.save(ctx,false,AiSettings.PROVIDER_CUSTOM,"https://example.com/v1","custom-model","sk-custom-12345678"),"second config saves and becomes active");
        check(AiSettings.configs(ctx).length()==2,"both configs are kept");
        check("custom-model".equals(AiSettings.model(ctx))&&AiSettings.chatUrl(ctx).equals("https://example.com/v1/chat/completions"),"active config supplies the model and chat URL");
        String deepseekId=null;
        org.json.JSONArray configs=AiSettings.configs(ctx);
        for(int i=0;i<configs.length();i++)if(configs.getJSONObject(i).optInt("provider")==AiSettings.PROVIDER_DEEPSEEK)deepseekId=configs.getJSONObject(i).getString("id");
        check(deepseekId!=null&&AiSettings.setActive(ctx,deepseekId)&&AiSettings.DEEPSEEK_MODEL.equals(AiSettings.model(ctx)),"active config can be switched and its model is used");
        check(!AiSettings.ready(ctx),"AI stays off until the switch is enabled");
        check(AiSettings.setEnabled(ctx,true)&&AiSettings.ready(ctx),"enabled active config is ready");
        check(AiSettings.delete(ctx,deepseekId)&&AiSettings.configs(ctx).length()==1,"deleting one config leaves the other");
        AiSettings.reset(ctx);
        check(!AiSettings.ready(ctx)&&AiSettings.configs(ctx).length()==0,"reset removes configs and disables AI");
    }
    private void testCalendarAndFeedback(long id)throws Exception{
        Store.prefs(getTargetContext()).edit().putBoolean("motion",false).putInt("reviewMode",1).putBoolean("autoSpeak",true).commit();
        Activity review=open(ReviewActivity.class,id);Thread.sleep(700);boolean[] loaded=(boolean[])field(field(review,"interaction"),"loaded");check(loaded[0]&&loaded[1]&&loaded[2],"custom feedback sounds load");check(field(review,"tts")==null,"Chinese-first card does not reveal the answer through speech");check(!find(review.getWindow().getDecorView(),"朗读单词").isShown(),"manual pronunciation hidden until English revealed");click(review,"显示答案");check(find(review.getWindow().getDecorView(),"朗读单词").isShown(),"manual pronunciation available after reveal");runOnMainSync(()->review.finish());
        Store.prefs(getTargetContext()).edit().putInt("reviewMode",0).commit();Activity main=open(MainActivity.class,0);check(find(main.getWindow().getDecorView(),"已完成")!=null,"completed tasks section is on home");click(main,"已完成");check(find(main.getWindow().getDecorView(),"今天完成")==null,"completed section collapses");click(main,"已完成");check(find(main.getWindow().getDecorView(),"今天完成")!=null,"completed section defaults to today");click(main,"日历");check(find(main.getWindow().getDecorView(),"完成第 1 轮")!=null,"calendar shows actual review history");screenshot(main,"calendar-360",360,760);
        java.util.Calendar c=java.util.Calendar.getInstance();c.set(java.util.Calendar.DAY_OF_MONTH,1);c.add(java.util.Calendar.MONTH,1);String next=new java.text.SimpleDateFormat("yyyy年M月d日",java.util.Locale.CHINA).format(c.getTime());runOnMainSync(()->findDescription(main.getWindow().getDecorView(),"下个月").performClick());waitForIdleSync();check(findDescription(main.getWindow().getDecorView(),next)!=null,"calendar displays all dates in the next month");runOnMainSync(()->findDescription(main.getWindow().getDecorView(),next).performClick());check(find(main.getWindow().getDecorView(),"当天没有计划或完成记录")!=null,"selecting another date updates its events");
        click(main,"我的");check(find(main.getWindow().getDecorView(),"复习统计")!=null,"statistics shown on profile");check(find(main.getWindow().getDecorView(),"完成 1 轮")!=null,"heatmap counts actual completed review rounds");click(main,"声音与触感");check(find(main.getWindow().getDecorView(),"朗读声音与语速")!=null,"speech settings entry available");check(find(main.getWindow().getDecorView(),"揭晓答案时自动朗读单词和例句")!=null,"auto-speak setting mentions reveal and examples");check(SpeechSettings.rate(2)==0.85f&&SpeechSettings.rate(-1)==0.60f&&SpeechSettings.rate(99)==1.15f,"speech rates and bounds");testSpeechSettings(main);screenshot(main,"profile-360",360,760);click(main,"试听：记得");click(main,"试听：忘记了");check(find(main.getWindow().getDecorView(),"复习确认音效")!=null,"custom sound previews and setting available");Switch sound=(Switch)find(main.getWindow().getDecorView(),"到期通知使用声音");runOnMainSync(()->sound.setChecked(false));click(main,"返回设置目录");check(!Store.prefs(getTargetContext()).getBoolean("reminderSound",true),"category exit automatically saves sound settings");check(Notifications.channel(getTargetContext()).equals("reviews_silent"),"silent channel selected");runOnMainSync(()->main.finish());
    }
    private void testSpeechSettings(Activity main)throws Exception{
        final Object[] settings=new Object[1];Constructor<SpeechSettings> constructor=SpeechSettings.class.getDeclaredConstructor(Activity.class);constructor.setAccessible(true);
        runOnMainSync(()->{try{settings[0]=constructor.newInstance(main);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        android.app.Dialog dialog=(android.app.Dialog)field(settings[0],"dialog");Segmented rate=(Segmented)field(settings[0],"rate");Button cancel=(Button)field(settings[0],"cancel");check(dialog.isShowing()&&((ViewGroup)rate.getChildAt(1)).getChildCount()==5,"speech dialog exposes five rates");
        runOnMainSync(()->{rate.setSelected(1,false);cancel.performClick();});check(Store.prefs(getTargetContext()).getInt("speechRate",2)==2,"cancel does not save speech rate");
        runOnMainSync(()->{try{settings[0]=constructor.newInstance(main);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        Segmented saveRate=(Segmented)field(settings[0],"rate");Button save=(Button)field(settings[0],"save");runOnMainSync(()->{saveRate.setSelected(3,false);save.performClick();});check(Store.prefs(getTargetContext()).getInt("speechRate",2)==3,"speech rate persists after save");
    }
    private void testHapticFeedback()throws Exception{
        android.content.SharedPreferences prefs=Store.prefs(getTargetContext());boolean oldHaptic=prefs.getBoolean("haptic",true),oldSound=prefs.getBoolean("feedbackSound",true);
        prefs.edit().putBoolean("haptic",true).putBoolean("feedbackSound",false).commit();Feedback feedback=new Feedback();HapticProbe probe=new HapticProbe(getTargetContext());
        feedback.respond(probe,Feedback.RETRY);check(probe.effect==android.view.HapticFeedbackConstants.LONG_PRESS,"forgotten answer uses a distinct haptic");
        probe.effect=-1;feedback.reveal(probe);check(probe.effect==android.view.HapticFeedbackConstants.CLOCK_TICK,"revealing the answer has immediate haptic feedback");
        probe.effect=-1;Motion.press(probe);long time=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,0,0,0);
        probe.dispatchTouchEvent(down);down.recycle();
        check(probe.effect==android.view.HapticFeedbackConstants.CLOCK_TICK,"shared press vibrates on finger down");
        probe.effect=-1;feedback.respond(probe,Feedback.RETRY);feedback.reveal(probe);Feedback.tick(probe);
        check(probe.effect==-1,"click, reveal, and answer do not vibrate again after finger down");
        android.view.MotionEvent up=android.view.MotionEvent.obtain(time,time+20,android.view.MotionEvent.ACTION_UP,0,0,0);
        probe.dispatchTouchEvent(up);up.recycle();
        probe.effect=-1;feedback.respond(probe,Feedback.RETRY);check(probe.effect==android.view.HapticFeedbackConstants.LONG_PRESS,"a later answer still uses the forgotten haptic");
        prefs.edit().putBoolean("haptic",false).commit();probe.effect=-1;feedback.respond(probe,Feedback.RETRY);check(probe.effect==-1,"haptic preference suppresses vibration");
        android.view.MotionEvent downOff=android.view.MotionEvent.obtain(time,time+40,android.view.MotionEvent.ACTION_DOWN,0,0,0);
        probe.dispatchTouchEvent(downOff);downOff.recycle();
        check(probe.effect==-1,"haptic preference suppresses the finger-down vibration");
        probe.effect=-1;Feedback.pressToggle(probe,true);check(probe.effect==-1,"turning the haptic switch off does not vibrate");
        Feedback.pressToggle(probe,false);check(probe.effect==android.view.HapticFeedbackConstants.CLOCK_TICK,"turning the haptic switch on vibrates before the preference is saved");
        android.view.MotionEvent upOff=android.view.MotionEvent.obtain(time,time+50,android.view.MotionEvent.ACTION_UP,0,0,0);
        probe.dispatchTouchEvent(upOff);upOff.recycle();
        prefs.edit().putBoolean("haptic",oldHaptic).putBoolean("feedbackSound",oldSound).commit();
    }
    private static final class HapticProbe extends View{
        int effect=-1;
        HapticProbe(android.content.Context context){super(context);}
        @Override public boolean performHapticFeedback(int feedbackConstant){effect=feedbackConstant;return true;}
    }
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private int intField(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.getInt(target);}
}
