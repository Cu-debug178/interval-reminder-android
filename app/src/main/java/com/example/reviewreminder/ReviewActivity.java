package com.example.reviewreminder;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The flashcard session — the heart of the app.
 *
 * Anatomy: modal top bar (✕ · title · ⋯), a thin progress line with a quiet counter, one large
 * white card that fills the stage (first tap reveals, second tap continues as “记得”), and the
 * answer actions pinned at the bottom. Long-press opens the word editor. The speak control keeps
 * its own larger hit area and never flips or advances the card. The card leaves in the direction
 * of the answer and the next one arrives from behind it.
 * The summary is a calm completion moment with the round's numbers and the next step.
 *
 * Scheduling, mastery and wrong-book writes are unchanged from the previous design.
 */
public final class ReviewActivity extends ScreenActivity {
    private WordUi ui;
    private long id,seed;
    private int expectedStage,mode,forceMode=-1,lastAutoSpoken=-1;
    private JSONArray words;
    private ReviewSession session;
    private boolean revealed,completed,changing,ttsReady,active=true,practice,taskDone,aiBusy;
    /** The word group's own name, so the session reads as this task rather than a generic drill. */
    private String taskTitle="复习";
    private String pendingSpeech,pendingExample;
    private TextToSpeech tts;
    private final Feedback interaction=new Feedback();
    private StringBuilder answers=new StringBuilder();
    private View cardView;
    private java.util.concurrent.Future<?> pendingAi;

    // Session chrome (kept while cards change; rebuilt after the summary).
    private boolean sessionChrome;
    private ProgressLine progress;
    private TextView counter;
    private FrameLayout deck;
    /** Horizontal side the next card enters from: -1 left, 1 right, 0 rises in place. */
    private int enterFrom;

    // Current card parts.
    private JSONObject currentWord;
    private String currentExample="";
    private View hairline,masteryRow;
    private TextView back,sentence;
    private Button pronounce;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        interaction.prepare(this);
        ui=new WordUi(this);
        id=getIntent().getLongExtra("taskId",0);
        practice=getIntent().getBooleanExtra("practice",false)||(state!=null&&state.getBoolean("practice"));
        forceMode=getIntent().hasExtra("forceMode")?getIntent().getIntExtra("forceMode",-1):(state==null?-1:state.getInt("forceMode",-1));
        try{
            JSONObject task=Store.find(Store.tasks(this),id);
            if(task==null)throw new Exception("任务不存在");
            words=task.getJSONArray("words");
            if(words.length()==0)throw new Exception("词组为空");
            String title=task.optString("title").trim();
            if(!title.isEmpty())taskTitle=title;
            taskDone=task.optBoolean("done");
            expectedStage=state==null?task.optInt("stage"):state.getInt("stage");
            mode=Store.prefs(this).getInt("reviewMode",0);
            seed=state==null?System.nanoTime():state.getLong("seed");
            session=new ReviewSession(words.length(),Store.prefs(this).getBoolean("shuffleWords",false),seed);
            String previous=state==null?"":state.getString("answers","");
            for(char answer:previous.toCharArray()){
                if(!session.ended()){
                    session.answer(answer=='1');
                    answers.append(answer);
                }
            }
            if(state!=null){
                revealed=state.getBoolean("revealed");
                lastAutoSpoken=state.getInt("lastAutoSpoken",-1);
            }
            show();
        }catch(Exception e){
            // Finish first so the message falls back to a system toast that outlives this screen.
            finish();
            ui.toast(e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putLong("seed",seed);
        out.putInt("stage",expectedStage);
        out.putInt("forceMode",forceMode);
        out.putInt("lastAutoSpoken",lastAutoSpoken);
        out.putString("answers",answers.toString());
        out.putBoolean("revealed",revealed);
        out.putBoolean("practice",practice);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume(){super.onResume();active=true;}

    @Override protected void onPause(){
        active=false;
        pendingSpeech=null;
        pendingExample=null;
        if(tts!=null)tts.stop();
        super.onPause();
    }

    @Override protected void onDestroy(){
        AiClient.cancel(pendingAi);
        if(tts!=null)tts.shutdown();
        interaction.release();
        super.onDestroy();
    }

    @Override public void onBackPressed(){
        if(practice||Store.prefs(this).getBoolean("skipReviewExitPrompt",false)){finish();return;}
        LinearLayout box=ui.col();
        CheckBox dontRemind=checkBox("不再提示",false);
        box.addView(dontRemind,WordUi.lp());
        new Sheet(this)
            .title("退出本轮复习？")
            .message("已答单词的掌握度会保留，但本轮不会记为完成。")
            .content(box)
            .secondary("继续复习",null)
            .primary("退出",()->{
                if(dontRemind.isChecked())Store.prefs(this).edit().putBoolean("skipReviewExitPrompt",true).apply();
                finish();
            })
            .show();
    }

    // ── Direction & speech (unchanged behaviour) ──────────────────────────────

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
        if(tts==null){
            tts=new TextToSpeech(this,status->new Handler(Looper.getMainLooper()).post(()->{
                if(isFinishing()||isDestroyed())return;
                if(status==TextToSpeech.SUCCESS&&tts!=null){
                    ttsReady=SpeechSettings.configure(tts,Store.prefs(this).getString("speechVoice",""),Store.prefs(this).getInt("speechRate",2));
                    if(ttsReady&&active)playPendingSpeech();
                    else if(!ttsReady&&!automatic)ui.toast("设备的英文朗读不可用");
                }else if(!automatic)ui.toast("设备的英文朗读不可用");
            }));
        }
        else if(ttsReady)playPendingSpeech();
        else if(!automatic)ui.toast("朗读正在准备或设备缺少英文语音");
    }

    private void playPendingSpeech(){
        if(!ttsReady||tts==null||!active||pendingSpeech==null||pendingSpeech.isEmpty())return;
        String word=pendingSpeech;
        String example=pendingExample==null?"":pendingExample;
        pendingSpeech=null;
        pendingExample=null;
        tts.stop();
        tts.speak(word,TextToSpeech.QUEUE_FLUSH,null,"word");
        if(!example.isEmpty())tts.speak(example,TextToSpeech.QUEUE_ADD,null,"example");
    }

    // ── Session page ──────────────────────────────────────────────────────────

    /** Top bar, progress and an empty stage; built once per pass so cards can change in place. */
    private void buildSession(){
        ui.startModal(practice?"任意复习":taskTitle,R.drawable.ic_ellipsis,"更多",v->more());
        ui.body.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_4),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_12));

        progress=new ProgressLine(this);
        progress.set(0,false);
        ui.body.addView(progress,WordUi.lp());

        LinearLayout meta=ui.row();
        meta.setMinimumHeight(ui.dp(28));
        counter=ui.type("",Theme.T_CALLOUT);
        counter.setTextColor(Theme.INK_2);
        counter.setFontFeatureSettings("tnum");
        meta.addView(counter,WordUi.weight());
        if(practice)meta.addView(ui.chip("不计计划轮次",Theme.SUNK,Theme.INK_2));
        else if(!taskDone)meta.addView(ui.chip("第 "+(expectedStage+1)+" 轮",Theme.SUNK,Theme.INK_2));
        ui.body.addView(meta,ui.lpMargin(Theme.SPACE_12,0));

        deck=new FrameLayout(this);
        deck.setClipChildren(false);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,0,1);
        dp.topMargin=ui.dp(Theme.SPACE_16);
        ui.body.addView(deck,dp);

        sessionChrome=true;
        enterFrom=0;
        Notice.setBottomOffset(this,Theme.BUTTON_HEIGHT+Theme.SPACE_24);
    }

    private void show(){
        if(session.ended()){summary();return;}
        boolean fresh=!sessionChrome;
        if(fresh)buildSession();

        JSONObject word=words.optJSONObject(session.current());
        boolean zh=chineseFirst(word);
        currentWord=word;
        currentExample=word.optString("example");

        counter.setText(counterText());
        progress.set(session.answered()/(float)session.total(),!fresh);

        View card=buildCard(word,zh);
        deck.removeAllViews();
        deck.addView(card,new FrameLayout.LayoutParams(-1,-1));
        cardView=card;

        if(revealed)showFeedback();
        else pinReveal();

        Motion.enter(card,enterFrom);
        enterFrom=0;

        if(revealed)speakWordThenExample(word.optString("english"),currentExample,true);
        else if(!zh)speak(word.optString("english"),true);
    }

    private String counterText(){return (session.answered()+1)+" / "+session.total();}

    /**
     * One card: overline at the top, the prompt centred, the answer block reserved (INVISIBLE)
     * underneath so revealing never shifts the layout, and pronunciation at the bottom.
     */
    private View buildCard(JSONObject word,boolean zh){
        LinearLayout card=ui.col();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setMinimumHeight(ui.dp(360));
        card.setPadding(ui.dp(Theme.SPACE_24),ui.dp(Theme.SPACE_24),ui.dp(Theme.SPACE_24),ui.dp(Theme.SPACE_20));
        card.setBackground(Theme.shape(this,Theme.SURFACE,24));

        // A forgotten card returns later in the queue (and the total grows); saying so keeps
        // the counter's jump from reading as an error.
        String side=zh?"中文释义":"英文单词";
        TextView overline=ui.type(session.attemptsForCurrent()>0?"再看一次 · "+side:side,Theme.T_OVERLINE);
        overline.setGravity(Gravity.CENTER);
        card.addView(overline,WordUi.lp());

        LinearLayout content=ui.col();
        content.setGravity(Gravity.CENTER);
        content.setPadding(0,ui.dp(Theme.SPACE_16),0,ui.dp(Theme.SPACE_16));

        String frontText=zh?word.optString("chinese"):word.optString("english");
        TextView front=new TextView(this);
        front.setText(frontText);
        front.setGravity(Gravity.CENTER);
        front.setTextColor(Theme.INK);
        if(zh){
            front.setTypeface(Theme.MEDIUM);
            front.setTextSize(frontText.length()>18?20:24);
            front.setLineSpacing(0,1.3f);
        }else{
            front.setTypeface(Theme.SERIF);
            front.setTextSize(headwordSize(frontText,36));
            front.setLetterSpacing(-0.01f);
            front.setLineSpacing(0,1.1f);
        }
        content.addView(front,WordUi.lp());

        hairline=new View(this);
        hairline.setBackgroundColor(Theme.HAIRLINE);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(ui.dp(32),Math.max(1,ui.dp(1)));
        hp.topMargin=ui.dp(Theme.SPACE_24);
        hp.bottomMargin=ui.dp(Theme.SPACE_24);
        content.addView(hairline,hp);

        String backText=zh?word.optString("english"):word.optString("chinese","");
        back=new TextView(this);
        back.setText(backText);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(Theme.INK);
        if(zh){
            back.setTypeface(Theme.SERIF);
            back.setTextSize(headwordSize(backText,30));
            back.setLineSpacing(0,1.1f);
        }else{
            back.setTypeface(Theme.REGULAR);
            back.setTextSize(20);
            back.setLineSpacing(0,1.3f);
        }
        content.addView(back,WordUi.lp());

        sentence=new TextView(this);
        sentence.setText(currentExample);
        sentence.setGravity(Gravity.CENTER);
        sentence.setTypeface(Theme.SERIF_ITALIC);
        sentence.setTextSize(15);
        sentence.setTextColor(Theme.INK_2);
        sentence.setLineSpacing(0,1.3f);
        content.addView(sentence,ui.lpMargin(Theme.SPACE_16,0));

        int level=Mastery.level(word);
        LinearLayout mastery=ui.row();
        mastery.setGravity(Gravity.CENTER);
        mastery.addView(new StageDots(this,5,level,false).ringNext(false));
        TextView masteryLabel=ui.type(Mastery.label(level),Theme.T_CAPTION);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
        mp.leftMargin=ui.dp(Theme.SPACE_8);
        mastery.addView(masteryLabel,mp);
        content.addView(mastery,ui.lpMargin(Theme.SPACE_24,0));
        masteryRow=mastery;

        card.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        pronounce=ui.quietPill("朗读单词",R.drawable.ic_volume_2,v->speak(word.optString("english"),false));
        // A long-press on the speaker must not fall through into edit, reveal, or a second speak.
        pronounce.setOnLongClickListener(v->true);
        pronounce.setVisibility(!zh||revealed?View.VISIBLE:View.INVISIBLE);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);
        pp.gravity=Gravity.CENTER_HORIZONTAL;
        card.addView(pronounce,pp);

        int answerState=revealed?View.VISIBLE:View.INVISIBLE;
        hairline.setVisibility(answerState);
        back.setVisibility(answerState);
        sentence.setVisibility(currentExample.isEmpty()?View.GONE:answerState);
        masteryRow.setVisibility(answerState);
        bindCard(card,pronounce);
        return card;
    }

    /**
     * Card gestures. A tap reveals, and the next tap does what “记得” does. A long-press opens the
     * editor and cancels that tap. The speak pill is unchanged in size; touches in a wider margin
     * around it are forwarded to the pill so a finger can hit it without flipping the card.
     */
    private void bindCard(View card,Button speak){
        card.setClickable(true);
        card.setFocusable(true);
        card.setLongClickable(false);
        Theme.applyRipple(card,card.getBackground(),Theme.RIPPLE_LIGHT);
        final int slop=ViewConfiguration.get(this).getScaledTouchSlop();
        final int timeout=ViewConfiguration.getLongPressTimeout();
        final int extra=ui.dp(16);
        final float[] down=new float[2];
        final boolean[] speaking={false},held={false},swallow={false};
        final Runnable longPress=()->{
            held[0]=true;
            card.setPressed(false);
            card.animate().cancel();
            card.setScaleX(1);
            card.setScaleY(1);
            if(changing||currentWord==null||!card.isAttachedToWindow())return;
            if(Store.prefs(this).getBoolean("haptic",true))card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            edit(currentWord);
        };
        card.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                speaking[0]=speakHit(card,speak,event,extra);
                held[0]=false;
                swallow[0]=false;
                down[0]=event.getX();
                down[1]=event.getY();
                card.removeCallbacks(longPress);
                if(speaking[0]){forwardSpeak(speak,event,true);return true;}
                if(!changing)Feedback.press(view);
                if(view.isEnabled()&&Motion.enabled(view.getContext()))
                    view.animate().scaleX(.99f).scaleY(.99f).setStartDelay(0).setDuration(90).setInterpolator(Motion.EASE).start();
                card.postDelayed(longPress,timeout);
                return false;
            }
            if(speaking[0]){
                boolean inside=speakHit(card,speak,event,extra);
                if(!inside){forwardSpeak(speak,event,false);speaking[0]=false;swallow[0]=true;return true;}
                forwardSpeak(speak,event,true);
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)speaking[0]=false;
                return true;
            }
            if(swallow[0]){
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)swallow[0]=false;
                return true;
            }
            if(action==MotionEvent.ACTION_MOVE){
                if(Math.hypot(event.getX()-down[0],event.getY()-down[1])>slop)card.removeCallbacks(longPress);
                return false;
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                card.removeCallbacks(longPress);
                Feedback.releasePress(view);
                if(Motion.enabled(view.getContext()))
                    view.animate().scaleX(1).scaleY(1).setStartDelay(0).setDuration(Theme.PRESS_MS+60).setInterpolator(Motion.EASE_OUT).start();
                else{view.setScaleX(1);view.setScaleY(1);}
                if(held[0])view.setPressed(false);
                return held[0];
            }
            return false;
        });
        card.setOnClickListener(v->{
            if(changing||currentWord==null)return;
            if(!revealed)revealAnswer(v);
            else answer(true,session.answered(),v);
        });
    }

    /** Expanded finger target around the speak pill. The pill's own layout is left alone. */
    private boolean speakHit(View card,Button speak,MotionEvent event,int extra){
        if(speak==null||speak.getVisibility()!=View.VISIBLE||speak.getWidth()==0)return false;
        Rect rect=new Rect();
        speak.getHitRect(rect);
        rect.inset(-extra,-extra);
        if(rect.left<0)rect.left=0;
        if(rect.top<0)rect.top=0;
        if(rect.right>card.getWidth())rect.right=card.getWidth();
        if(rect.bottom>card.getHeight())rect.bottom=card.getHeight();
        return rect.contains((int)event.getX(),(int)event.getY());
    }

    /** Sends the gesture to the center of the pill when the finger is inside the expanded target. */
    private void forwardSpeak(Button speak,MotionEvent event,boolean inside){
        MotionEvent copy=MotionEvent.obtain(event);
        if(inside)copy.setLocation(speak.getWidth()/2f,speak.getHeight()/2f);
        else{copy.setAction(MotionEvent.ACTION_CANCEL);copy.setLocation(speak.getWidth()/2f,speak.getHeight()/2f);}
        speak.dispatchTouchEvent(copy);
        copy.recycle();
    }

    /** Long headwords step down so they keep to one or two calm lines. */
    private static int headwordSize(String value,int base){
        int n=value==null?0:value.length();
        if(n>22)return base-10;
        if(n>13)return base-6;
        return base;
    }

    private void pinReveal(){
        ui.pin(ui.primaryButton("显示答案",this::revealAnswer));
    }

    private void revealAnswer(View source){
        if(revealed||changing)return;
        interaction.reveal(source);
        revealed=true;

        hairline.setVisibility(View.VISIBLE);
        back.setVisibility(View.VISIBLE);
        Motion.enter(hairline,0,0);
        Motion.enter(back,0,30);
        int delay=60;
        if(!currentExample.isEmpty()){
            sentence.setVisibility(View.VISIBLE);
            Motion.enter(sentence,0,delay);
            delay+=30;
        }
        masteryRow.setVisibility(View.VISIBLE);
        Motion.enter(masteryRow,0,delay);
        if(pronounce.getVisibility()!=View.VISIBLE){
            pronounce.setVisibility(View.VISIBLE);
            Motion.enter(pronounce,0,delay);
        }
        cardView.setScaleX(1);
        cardView.setScaleY(1);

        lastAutoSpoken=-1;
        speakWordThenExample(currentWord.optString("english"),currentExample,true);
        showFeedback();
        View pair=ui.footer.getChildAt(0);
        if(pair!=null)Motion.enter(pair);
    }

    private void showFeedback(){
        final int position=session.answered();
        ui.pinPair(
            ui.secondaryButton("忘记了",v->answer(false,position,v)),
            ui.primaryButton("记得",v->answer(true,position,v)));
    }

    private void answer(boolean known,int expected,View source){
        if(changing||!revealed||session.ended()||session.answered()!=expected)return;
        boolean firstAttempt=session.attemptsForCurrent()==0;
        try{
            if(!practice){
                if(!known)WrongBook.record(this,id,session.current(),expectedStage+1,words,"review:"+seed+":"+expected+":"+session.current());
                Mastery.applyAnswer(this,id,session.current(),known,firstAttempt,words);
            }
        }catch(Exception e){
            ui.error("结果保存失败，尚未提交本题："+e.getMessage());
            return;
        }
        interaction.respond(source,known?Feedback.KNOWN:Feedback.RETRY);
        changing=true;
        pendingSpeech=null;
        pendingExample=null;
        if(ttsReady)tts.stop();
        session.answer(known);
        answers.append(known?'1':'0');
        revealed=false;

        // Progress moves at once; the card follows the answer — 记得 to the right, 忘记了 to the left.
        progress.set(session.answered()/(float)session.total(),true);
        if(!session.ended())counter.setText(counterText());
        int toward=known?1:-1;
        enterFrom=-toward;
        Motion.leave(cardView,-toward,()->{
            if(isFinishing()||isDestroyed())return;
            changing=false;
            show();
        });
    }

    // ── Card menu & edit ──────────────────────────────────────────────────────

    private void more(){
        if(changing||currentWord==null)return;
        JSONObject word=currentWord;
        new Sheet(this)
            .item(R.drawable.ic_pen_line,"编辑这张卡","修改英文、中文或例句",()->edit(word))
            .show();
    }

    private void edit(JSONObject word){
        LinearLayout form=ui.col();
        EditText en=formField(form,"英文","",word.optString("english"));
        EditText zh=formField(form,"中文","",word.optString("chinese"));
        EditText ex=formField(form,"例句","可留空",word.optString("example"));
        // The sheet is its own window and covers bottom notices, so a failed
        // save is reported inline, right under the fields.
        TextView failure=ui.type("",Theme.T_CALLOUT);
        failure.setTextColor(Theme.DANGER);
        failure.setPadding(ui.dp(Theme.SPACE_4),0,ui.dp(Theme.SPACE_4),0);
        failure.setVisibility(View.GONE);
        form.addView(failure,ui.lpMargin(Theme.SPACE_12,0));
        new Sheet(this)
            .title("编辑单词卡")
            .content(form)
            .secondary("取消",null)
            .primaryIf("保存",()->{
                if(en.getText().toString().trim().isEmpty()){
                    en.setError("英文不能为空");
                    en.requestFocus();
                    return false;
                }
                Object oldEnglish=word.opt("english");
                Object oldChinese=word.opt("chinese");
                Object oldExample=word.opt("example");
                try{
                    word.put("english",en.getText().toString().trim())
                        .put("chinese",zh.getText().toString().trim())
                        .put("example",ex.getText().toString().trim());
                    JSONArray all=Store.tasks(this);
                    JSONObject current=Store.find(all,id);
                    if(current==null)throw new Exception("任务不存在");
                    current.put("words",words);
                    Store.save(this,all);
                }catch(Exception e){
                    restore(word,"english",oldEnglish);
                    restore(word,"chinese",oldChinese);
                    restore(word,"example",oldExample);
                    String reason=e.getMessage();
                    failure.setText("保存失败："+(reason==null||reason.isEmpty()?"请稍后重试":reason));
                    if(failure.getVisibility()!=View.VISIBLE){
                        failure.setVisibility(View.VISIBLE);
                        Motion.enter(failure);
                    }
                    return false;
                }
                show();
                ui.toast("单词卡已保存");
                return true;
            })
            .show();
    }

    private static void restore(JSONObject word,String key,Object value){
        try{
            if(value==null)word.remove(key);
            else word.put(key,value);
        }catch(Exception ignored){}
    }

    private EditText formField(LinearLayout form,String label,String hint,String value){
        TextView l=ui.type(label,Theme.T_CALLOUT);
        l.setTypeface(Theme.MEDIUM);
        l.setTextColor(Theme.INK_2);
        l.setPadding(ui.dp(Theme.SPACE_4),0,0,0);
        form.addView(l,ui.lpMargin(form.getChildCount()==0?Theme.SPACE_4:Theme.SPACE_16,Theme.SPACE_6));
        EditText e=ui.input(hint,value);
        form.addView(e,WordUi.lp());
        return e;
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private void summary(){
        sessionChrome=false;
        cardView=null;
        ui.startModal(practice?"任意复习":taskTitle);
        LinearLayout body=ui.body;

        JSONObject task=Store.find(Store.tasks(this),id);
        boolean eligible=!practice&&task!=null&&!task.optBoolean("done")&&task.optInt("stage")==expectedStage;

        LinearLayout hero=ui.col();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0,ui.dp(Theme.SPACE_32),0,0);
        FrameLayout halo=new FrameLayout(this);
        halo.setBackground(Theme.oval(Theme.PRIMARY_SOFT));
        halo.addView(ui.icon(R.drawable.ic_circle_check,Theme.PRIMARY),new FrameLayout.LayoutParams(ui.dp(30),ui.dp(30),Gravity.CENTER));
        hero.addView(halo,new LinearLayout.LayoutParams(ui.dp(68),ui.dp(68)));
        TextView title=ui.type(practice?"这次练习结束了":"本轮已看完",Theme.T_TITLE);
        title.setGravity(Gravity.CENTER);
        hero.addView(title,ui.lpMargin(Theme.SPACE_20,0));
        TextView sentenceLine=ui.type("共 "+words.length()+" 个单词，标记“忘记了” "+session.repeats()+" 次",Theme.T_CALLOUT);
        sentenceLine.setGravity(Gravity.CENTER);
        hero.addView(sentenceLine,ui.lpMargin(Theme.SPACE_8,0));
        body.addView(hero,WordUi.lp());

        LinearLayout stats=ui.card();
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(ui.dp(Theme.SPACE_8),ui.dp(Theme.SPACE_20),ui.dp(Theme.SPACE_8),ui.dp(Theme.SPACE_20));
        stats.setGravity(Gravity.CENTER_VERTICAL);
        TextView wordCount=statColumn(stats,"单词");
        statDivider(stats);
        TextView forgotCount=statColumn(stats,"忘记");
        statDivider(stats);
        TextView average=statColumn(stats,"平均掌握");
        String avg=String.format(Locale.CHINA,"%.1f",Mastery.average(words));
        SpannableString avgText=new SpannableString(avg+"/5");
        avgText.setSpan(new AbsoluteSizeSpan(Theme.BODY_SIZE,true),avg.length(),avgText.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        avgText.setSpan(new ForegroundColorSpan(Theme.INK_3),avg.length(),avgText.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        average.setText(avgText);
        body.addView(stats,ui.lpMargin(Theme.SPACE_32,0));
        Motion.count(wordCount,words.length(),"");
        Motion.count(forgotCount,session.repeats(),"");

        String hint=summaryHint(task,eligible);
        if(hint!=null){
            TextView note=ui.type(hint,Theme.T_CAPTION);
            note.setGravity(Gravity.CENTER);
            note.setPadding(ui.dp(Theme.SPACE_12),0,ui.dp(Theme.SPACE_12),0);
            body.addView(note,ui.lpMargin(Theme.SPACE_16,0));
        }

        String againLabel=practice?"再练一遍":"再复习一遍";
        View.OnClickListener again=v->{
            seed=System.nanoTime();
            session=new ReviewSession(words.length(),Store.prefs(this).getBoolean("shuffleWords",false),seed);
            lastAutoSpoken=-1;
            answers.setLength(0);
            revealed=false;
            show();
        };
        LinearLayout links=ui.row();
        links.setGravity(Gravity.CENTER);
        Button moreOptions=ui.textButton("更多选项",v->summaryMore());
        // Leaving an eligible round asks first, exactly like ✕ and system back.
        Button home=ui.textButton("返回主页",v->{if(eligible)onBackPressed();else finish();});
        home.setTextColor(Theme.INK_2);
        links.addView(moreOptions);
        View dot=new View(this);
        dot.setBackground(Theme.oval(Theme.HAIRLINE));
        LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(ui.dp(4),ui.dp(4));
        dotLp.leftMargin=ui.dp(Theme.SPACE_8);
        dotLp.rightMargin=ui.dp(Theme.SPACE_8);
        links.addView(dot,dotLp);
        links.addView(home);

        if(eligible)ui.pin(ui.primaryButton(completeLabel(),v->chooseSpelling()),ui.secondaryButton(againLabel,again),links);
        else ui.pin(ui.primaryButton(againLabel,again),links);

        Motion.stagger(body);
        syncNoticeOffset();
    }

    /** Names what the completion action will actually do, given the saved spelling default. */
    private String completeLabel(){
        SharedPreferences prefs=Store.prefs(this);
        if(!prefs.getBoolean("spellingConfigured",false)||prefs.getBoolean("askSpellingMode",false))return "完成本轮并选择拼写";
        int saved=prefs.getInt("spellingMode",SpellingPlan.NONE);
        if(saved==SpellingPlan.ALL)return "完成并拼写";
        if(saved==SpellingPlan.WRONG_ONLY)return "完成并拼写错词";
        // complete() still adds this round's forgotten words when that option is on.
        if(prefs.getBoolean("spellForgottenWords",false)&&session.repeats()>0)return "完成并拼写忘记的词";
        return "完成本轮";
    }

    /** One line under the numbers that says what happens next. */
    private String summaryHint(JSONObject task,boolean eligible){
        if(practice)return "任意复习不计入计划轮次，也不会推进提醒。";
        if(task==null)return null;
        if(!eligible)return "这一轮的计划已记录，本次复习不会再推进提醒。";
        try{
            JSONArray intervals=task.getJSONArray("intervals");
            int next=expectedStage+1;
            if(next>=intervals.length())return "这是计划中的最后一轮，完成后这组单词就全部复习完了。";
            long due=Schedule.due(System.currentTimeMillis(),intervals.getLong(next),Store.windows(task.optString("windows")));
            return "完成后记为第 "+next+" 轮，下一次复习约在"+when(due)+"。";
        }catch(Exception e){
            return null;
        }
    }

    private static String when(long at){
        Calendar today=Calendar.getInstance();
        clearTime(today);
        Calendar day=Calendar.getInstance();
        day.setTimeInMillis(at);
        clearTime(day);
        long days=Math.round((day.getTimeInMillis()-today.getTimeInMillis())/86400000.0);
        String time=new SimpleDateFormat("HH:mm",Locale.CHINA).format(new Date(at));
        if(days<=0)return "今天 "+time;
        if(days==1)return "明天 "+time;
        if(days==2)return "后天 "+time;
        return new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(at));
    }

    private static void clearTime(Calendar c){
        c.set(Calendar.HOUR_OF_DAY,0);
        c.set(Calendar.MINUTE,0);
        c.set(Calendar.SECOND,0);
        c.set(Calendar.MILLISECOND,0);
    }

    private TextView statColumn(LinearLayout parent,String label){
        LinearLayout column=ui.col();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView value=ui.type("0",Theme.T_TITLE);
        value.setFontFeatureSettings("tnum");
        value.setGravity(Gravity.CENTER);
        column.addView(value,WordUi.lp());
        TextView caption=ui.type(label,Theme.T_CAPTION);
        caption.setGravity(Gravity.CENTER);
        column.addView(caption,ui.lpMargin(Theme.SPACE_4,0));
        parent.addView(column,WordUi.weight());
        return value;
    }

    private void statDivider(LinearLayout parent){
        View line=new View(this);
        line.setBackgroundColor(Theme.HAIRLINE);
        parent.addView(line,new LinearLayout.LayoutParams(Math.max(1,ui.dp(.75f)),ui.dp(32)));
    }

    /** Keeps result notices above the pinned footer. */
    private void syncNoticeOffset(){
        ui.footer.post(()->{
            float density=getResources().getDisplayMetrics().density;
            Notice.setBottomOffset(this,(int)(ui.footer.getHeight()/density));
        });
    }

    private void summaryMore(){
        ArrayList<String> labels=new ArrayList<>();
        int flipTo=sessionChineseBiased()?0:1;
        labels.add(flipTo==1?"换向再复习（先中文）":"换向再复习（先英文）");
        if(session.repeats()>0)labels.add("错词义项练习");
        if(!practice)labels.add("AI 本轮复盘");
        Sheet sheet=new Sheet(this).title("更多选项");
        for(String label:labels){
            int icon=label.startsWith("换向")?R.drawable.ic_repeat:label.startsWith("错词")?R.drawable.ic_target:R.drawable.ic_sparkles;
            sheet.item(icon,label,()->{
                if(label.startsWith("换向")){
                    forward(new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("forceMode",flipTo).putExtra("practice",practice));
                }else if(label.startsWith("错词")){
                    forward(new Intent(this,SenseQuizActivity.class).putExtra("taskId",id).putExtra("wrongOnly",true));
                }else aiSummary();
            });
        }
        sheet.secondary("取消",null).show();
    }

    private void aiSummary(){
        if(aiBusy)return;
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        JSONObject payload;
        try{
            JSONArray weak=new JSONArray();
            int[] missed=session.missedIndices();
            for(int index:missed){
                JSONObject w=words.optJSONObject(index);
                if(w!=null)weak.put(w.optString("english"));
            }
            payload=new JSONObject()
                .put("total",words.length())
                .put("forgotten",session.repeats())
                .put("weakWords",weak)
                .put("avgMastery",Mastery.average(words));
        }catch(Exception e){
            ui.error(e.getMessage());
            return;
        }

        // The sheet opens at once with a quiet loading state; the result replaces it in place.
        // AiClient.cancel() only interrupts the worker, so late callbacks are filtered by identity.
        LinearLayout loading=ui.col();
        loading.setGravity(Gravity.CENTER_HORIZONTAL);
        loading.setPadding(0,ui.dp(Theme.SPACE_12),0,ui.dp(Theme.SPACE_8));
        TextView status=ui.type("正在生成…",Theme.T_CALLOUT);
        status.setGravity(Gravity.CENTER);
        loading.addView(status,WordUi.lp());
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Theme.PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.SUNK));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ui.dp(140),ui.dp(4));
        bp.topMargin=ui.dp(Theme.SPACE_12);
        loading.addView(bar,bp);

        aiBusy=true;
        final java.util.concurrent.Future<?>[] req=new java.util.concurrent.Future<?>[1];
        Sheet sheet=new Sheet(this).title("AI 本轮复盘").content(loading).secondary("关闭",null);
        sheet.onDismiss(()->{
            if(pendingAi==req[0]){AiClient.cancel(pendingAi);pendingAi=null;}
            aiBusy=false;
        });
        sheet.show();
        try{
            req[0]=AiClient.chatJson(this,AiPrompts.systemReviewSummary(),AiPrompts.userReviewSummary(payload.toString()),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){
                    if(pendingAi!=req[0]||!sheet.isShowing())return;
                    aiBusy=false;
                    String body=json.optString("summary","")+"\n\n下一步："+json.optString("next","");
                    JSONArray weakList=json.optJSONArray("weak");
                    if(weakList!=null&&weakList.length()>0){
                        StringBuilder w=new StringBuilder("薄弱词：");
                        for(int i=0;i<weakList.length();i++){
                            if(i>0)w.append("、");
                            w.append(weakList.optString(i));
                        }
                        body=w+"\n\n"+body;
                    }
                    TextView text=ui.type(body.trim(),Theme.T_BODY);
                    Motion.layout(sheet.body);
                    sheet.clearContent().content(text);
                }
                @Override public void onError(String message){
                    if(pendingAi!=req[0]||!sheet.isShowing())return;
                    aiBusy=false;
                    TextView error=ui.type(message,Theme.T_CALLOUT);
                    error.setTextColor(Theme.DANGER);
                    Motion.layout(sheet.body);
                    sheet.clearContent().content(error);
                }
            });
            pendingAi=req[0];
        }catch(Exception e){
            sheet.dismiss();
            ui.error(e.getMessage());
        }
    }

    // ── Completion ────────────────────────────────────────────────────────────

    private void chooseSpelling(){
        SharedPreferences prefs=Store.prefs(this);
        int saved=prefs.getInt("spellingMode",SpellingPlan.NONE);
        boolean configured=prefs.getBoolean("spellingConfigured",false);
        if(configured&&!prefs.getBoolean("askSpellingMode",false)){complete(saved);return;}

        LinearLayout content=ui.col();
        RadioGroup choices=new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        String[] labels={"拼写全部单词","拼写本词组错题本中的错词","不拼写"};
        for(int i=0;i<labels.length;i++){
            RadioGroup.LayoutParams p=new RadioGroup.LayoutParams(-1,-2);
            if(i>0)p.topMargin=ui.dp(Theme.SPACE_8);
            choices.addView(choiceRow(labels[i],i),p);
        }
        choices.check(Math.max(0,Math.min(2,saved)));
        paintChoices(choices);
        choices.setOnCheckedChangeListener((group,checked)->{
            paintChoices(group);
            Feedback.tick(group);
        });
        content.addView(choices,ui.lpMargin(Theme.SPACE_4,0));
        CheckBox askAgain=checkBox("以后每轮复习后都重新询问",prefs.getBoolean("askSpellingMode",false));
        content.addView(askAgain,ui.lpMargin(Theme.SPACE_8,0));

        Sheet sheet=new Sheet(this);
        sheet.title(configured?"完成并选择拼写":"设置复习后的拼写")
            .message("会保存为默认方式，可在「我的 › 复习与拼写」修改。")
            .content(content)
            .secondary("取消",null);
        sheet.primaryIf("完成本轮",()->{
            int selected=choices.getCheckedRadioButtonId();
            if(selected<0){ui.toast("请选择一种拼写方式");return false;}
            Store.prefs(this).edit()
                .putInt("spellingMode",selected)
                .putBoolean("spellingConfigured",true)
                .putBoolean("askSpellingMode",askAgain.isChecked())
                .apply();
            sheet.dismiss();
            complete(selected);
            return true;
        });
        sheet.show();
    }

    /** A roomy selectable row: white with a hairline, pine-tinted with a filled mark when chosen. */
    private RadioButton choiceRow(String label,int id){return ui.choice(label,null,id);}

    private static void paintChoices(RadioGroup group){
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            if(child instanceof RadioButton)((RadioButton)child).setTypeface(((RadioButton)child).isChecked()?Theme.MEDIUM:Theme.REGULAR);
        }
    }

    private CheckBox checkBox(String label,boolean checked){return ui.check(label,checked);}

    /**
     * A forward step that replaces this screen. finish() plays the "back" transition, so the
     * forward one is applied last and the next page keeps sliding in from the trailing edge.
     */
    private void forward(Intent next){Motion.replace(this,next);}

    private void complete(int spellingMode){
        if(completed)return;
        try{
            JSONArray all=Store.tasks(this);
            JSONObject task=Store.find(all,id);
            if(task==null||task.optBoolean("done")||task.optInt("stage")!=expectedStage){
                finish();
                ui.toast("本轮状态已变化，未重复推进阶段");
                return;
            }
            long now=System.currentTimeMillis();
            int next=expectedStage+1;
            JSONArray history=task.getJSONArray("history");
            history.put(new JSONObject().put("stage",next).put("at",now).put("repeatCount",session.repeats()).put("spellingMode",spellingMode));
            task.put("stage",next).put("snoozes",0);
            if(next>=task.getJSONArray("intervals").length())task.put("done",true);
            else task.put("due",Schedule.due(now,task.getJSONArray("intervals").getLong(next),Store.windows(task.optString("windows"))));
            if(!Store.prefs(this).edit().putString("tasks",all.toString()).putInt("spellingMode",spellingMode).commit())throw new IllegalStateException("复习结果未能写入设备");
            Alarms.cancel(this,id);
            if(!task.optBoolean("done"))Alarms.schedule(this,task);
            completed=true;
            interaction.confirm(getWindow().getDecorView());

            JSONArray storedWords=task.optJSONArray("words");
            int[] wrongCounts=new int[words.length()];
            if(storedWords!=null){
                for(int i=0;i<Math.min(wrongCounts.length,storedWords.length());i++){
                    JSONObject word=storedWords.optJSONObject(i);
                    if(word!=null)wrongCounts[i]=word.optInt("wrongCount");
                }
            }
            int[] indices=SpellingPlan.indices(spellingMode,words.length(),SpellingPlan.wrongBookIndices(words.length(),wrongCounts));
            int[] forgotten=session.missedIndices();
            if(Store.prefs(this).getBoolean("spellForgottenWords",false))indices=SpellingPlan.include(indices,forgotten,words.length());
            int[] examples=Store.prefs(this).getBoolean("addExamplesForForgotten",false)?ExampleActivity.missingExampleIndices(storedWords,forgotten):new int[0];

            if(indices.length>0){
                Intent nextStep=new Intent(this,SpellingActivity.class)
                    .putExtra("taskId",id)
                    .putExtra("round",next)
                    .putExtra("indices",indices)
                    .putExtra("historyAt",now)
                    .putExtra("exampleIndices",examples);
                forward(nextStep);
            }else if(examples.length>0){
                forward(new Intent(this,ExampleActivity.class).putExtra("taskId",id).putExtra("indices",examples).putExtra("notice","第 "+next+" 轮已记录"));
            }else{
                String msg=task.optBoolean("done")?"已完成全部 "+next+" 轮":"已完成第 "+next+" 轮 · 下次 "+when(task.optLong("due"));
                finish();
                // Shown after finish() so it survives the transition as a system toast.
                ui.toast(msg);
            }
        }catch(Exception e){
            ui.error("保存失败："+e.getMessage());
        }
    }
}
