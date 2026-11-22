package com.example.reviewreminder;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
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
import java.util.UUID;

/**
 * Immersive flashcard session.
 *
 * Anatomy: modal top bar (✕ · title · 🔊 · ⋯), a thin progress line with a quiet counter, and one
 * full-stage white card with zero buttons.
 *
 * Interactions:
 *   - First tap on card: reveal answer
 *   - Second tap on card (after reveal): “记得” (glides right)
 *   - Swipe down on card (before or after reveal): “忘记” (glides down with live indicator & haptic)
 *   - Long-press on card: edit word card
 *   - Tap example sentence (when revealed): speak example sentence
 *   - Tap inline relation summary (when revealed): open interactive word-relation mind map
 */
public final class ReviewActivity extends ScreenActivity {
    private WordUi ui;
    private long id,seed;
    private int expectedStage,mode,forceMode=-1,lastAutoSpoken=-1;
    private JSONArray words;
    private ReviewSession session;
    private boolean revealed,completed,changing,active=true,practice,taskDone,aiBusy;
    private String selectionId;
    private SpeechManager speech;
    /** The word group's own name, so the session reads as this task rather than a generic drill. */
    private String taskTitle="复习";
    private final Feedback interaction=new Feedback();
    private StringBuilder answers=new StringBuilder();
    private View cardView;
    private java.util.concurrent.Future<?> pendingAi;

    // Session chrome (kept while cards change; rebuilt after the summary or layout switch).
    private boolean sessionChrome,builtImmersive;
    private String builtProgressStyle=CardStyle.PROGRESS_LINE;
    private int safeTop,safeBottom,safeLeft,safeRight;
    private View immersiveTopChrome,immersiveBottomHost;
    private ProgressLine progress;
    private TextView counter,forgetCue;
    private ImageButton speakTop;
    private FrameLayout deck;
    /** Horizontal side the next card enters from: -1 left, 1 right, 0 rises in place. */
    private int enterFrom;

    // Current card parts.
    private JSONObject currentWord;
    private boolean currentZh;
    private String currentExample="";
    private View hairline,categoryTag,masteryRow,relationsRow;
    private TextView back,sentence;
    private boolean gesturePromptShown;
    private CardStyle cardStyle=CardStyle.defaults();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        interaction.prepare(this);
        ui=new WordUi(this);
        speech=new SpeechManager(this);
        cardStyle=CardStyleStore.load(this);
        id=getIntent().getLongExtra("taskId",0);
        selectionId=getIntent().getStringExtra("selectionId");
        practice=getIntent().getBooleanExtra("practice",false)||selectionId!=null||(state!=null&&state.getBoolean("practice"));
        forceMode=getIntent().hasExtra("forceMode")?getIntent().getIntExtra("forceMode",-1):(state==null?-1:state.getInt("forceMode",-1));
        try{
            JSONObject task=id==0?null:Store.find(this,id);
            if(selectionId!=null){
                JSONObject selection=TrainingStore.selection(this,selectionId);
                if(selection==null)throw new Exception("训练选词已失效");
                words=selection.getJSONArray("words");
            }else{
                if(task==null)throw new Exception("任务不存在");
                words=task.getJSONArray("words");
            }
            if(words.length()==0)throw new Exception("词组为空");
            if(task!=null){
                String title=task.optString("title").trim();
                if(!title.isEmpty())taskTitle=title;
                taskDone=task.optBoolean("done");
                expectedStage=state==null?task.optInt("stage"):state.getInt("stage");
            }else{
                taskTitle=practice?"任意复习":"复习";
                expectedStage=0;
            }
            mode=Store.prefs(this).getInt("reviewMode",0);
            seed=state==null?System.nanoTime():state.getLong("seed");
            session=new ReviewSession(words.length(),selectionId==null&&Store.prefs(this).getBoolean("shuffleWords",false),seed);
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

    @Override protected void onResume(){
        super.onResume();
        if(isFinishing())return;
        active=true;
        CardStyle latest=CardStyleStore.load(this);
        if(!latest.equals(cardStyle)){
            boolean chromeChanged=latest.isImmersiveLayout()!=builtImmersive||!latest.progressStyle.equals(builtProgressStyle);
            cardStyle=latest;
            if(chromeChanged)sessionChrome=false;
            if(session!=null&&!session.ended()&&!changing)show();
        }
    }

    @Override protected void onPause(){
        active=false;
        if(speech!=null)speech.stop();
        super.onPause();
    }

    @Override protected void onDestroy(){
        AiClient.cancel(pendingAi);
        if(speech!=null)speech.shutdown();
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

    // ── Direction & speech ────────────────────────────────────────────────────

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
        if(english==null||english.trim().isEmpty()||speech==null)return;
        if(automatic)lastAutoSpoken=session.answered();
        String extra=example==null?"":example.trim();
        speech.speak(english.trim()+(extra.isEmpty()?"":". "+extra),"review");
    }

    // ── Session page ──────────────────────────────────────────────────────────

    /** Minimal top strip (本批 x/y · progress · 🔊 · ⋯) for classic framed mode, or full-bleed edge-to-edge deck for immersive mode. */
    private void buildSession(){
        builtImmersive=cardStyle.isImmersiveLayout();
        builtProgressStyle=cardStyle.progressStyle;
        immersiveTopChrome=null;
        immersiveBottomHost=null;

        if(builtImmersive){
            FrameLayout root=new FrameLayout(this);
            root.setBackgroundColor(Theme.BG);
            deck=new FrameLayout(this);
            deck.setClipChildren(false);
            root.addView(deck,new FrameLayout.LayoutParams(-1,-1));
            root.setOnApplyWindowInsetsListener((v,insets)->{
                int top=insets.getSystemWindowInsetTop();
                if(android.os.Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){
                    top=Math.max(top,insets.getDisplayCutout().getSafeInsetTop());
                }
                safeTop=top;
                safeBottom=insets.getSystemWindowInsetBottom();
                safeLeft=insets.getSystemWindowInsetLeft();
                safeRight=insets.getSystemWindowInsetRight();
                applyImmersiveInsets();
                return insets;
            });
            setContentView(root);
            root.requestApplyInsets();
            progress=null;
            counter=null;
            speakTop=null;
            Notice.setBottomOffset(this,28);
        }else{
            ui.startModal("",R.drawable.ic_ellipsis,"更多",v->more());
            LinearLayout batchGroup=ui.row();
            TextView batchLabel=ui.type("本批",Theme.T_CAPTION);
            batchLabel.setTextColor(Theme.INK_3);
            batchLabel.setTypeface(Theme.MEDIUM);
            LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);
            blp.rightMargin=ui.dp(Theme.SPACE_6);
            batchGroup.addView(batchLabel,blp);

            counter=ui.type("",Theme.T_CALLOUT);
            counter.setTypeface(Theme.MEDIUM);
            counter.setTextColor(Theme.INK_2);
            counter.setFontFeatureSettings("tnum");
            batchGroup.addView(counter,new LinearLayout.LayoutParams(-2,-2));

            boolean isRing=CardStyle.PROGRESS_RING.equals(builtProgressStyle);
            progress=new ProgressLine(this).style(builtProgressStyle).colors(Theme.PRIMARY,Theme.SUNK);
            progress.setStep(0,session==null?1:session.total(),false);
            if(isRing){
                LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20));
                rlp.leftMargin=ui.dp(Theme.SPACE_8);
                batchGroup.addView(progress,rlp);
            }
            if(ui.bar!=null&&ui.bar.getChildCount()>=1){
                ui.bar.removeViewAt(0);
                LinearLayout.LayoutParams bgLp=new LinearLayout.LayoutParams(0,-2,1);
                ui.bar.addView(batchGroup,0,bgLp);
            }

            // Quiet borderless speaker icon in the top bar (48dp touch target).
            speakTop=ui.iconButton(R.drawable.ic_volume_2,"朗读单词",v->{
                if(currentWord!=null)speak(currentWord.optString("english"),false);
            });
            speakTop.setImageTintList(ColorStateList.valueOf(Theme.INK_2));
            if(ui.bar!=null&&ui.bar.getChildCount()>=2){
                ui.bar.addView(speakTop,ui.bar.getChildCount()-1,new LinearLayout.LayoutParams(ui.dp(Theme.TOUCH),ui.dp(Theme.TOUCH)));
            }

            ui.body.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_2),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_20));
            ui.pin(); // Explicitly hide footer for full-stage card.

            if(!isRing){
                ui.body.addView(progress,WordUi.lp());
            }

            deck=new FrameLayout(this);
            deck.setClipChildren(false);
            LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,0,1);
            dp.topMargin=ui.dp(isRing?Theme.SPACE_8:Theme.SPACE_16);
            ui.body.addView(deck,dp);
            Notice.setBottomOffset(this,20);
        }

        forgetCue=ui.type("↓ 下滑标记忘记",Theme.T_CALLOUT);
        forgetCue.setTypeface(Theme.MEDIUM);
        forgetCue.setGravity(Gravity.CENTER);
        forgetCue.setPadding(ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_8),ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_8));
        forgetCue.setAlpha(0f);

        sessionChrome=true;
        enterFrom=0;
        maybeShowFirstRunGuide();
    }

    private void applyImmersiveInsets(){
        if(!builtImmersive)return;
        int minTop=ui.dp(Theme.SPACE_8);
        int topPad=safeTop+minTop;
        int sideLeft=Math.max(safeLeft,ui.dp(Theme.SPACE_12));
        int sideRight=Math.max(safeRight,ui.dp(Theme.SPACE_12));
        if(immersiveTopChrome!=null){
            immersiveTopChrome.setPadding(sideLeft,topPad,sideRight,ui.dp(Theme.SPACE_4));
        }
        if(immersiveBottomHost!=null){
            immersiveBottomHost.setPadding(
                Math.max(safeLeft,ui.dp(Theme.SPACE_20)),
                0,
                Math.max(safeRight,ui.dp(Theme.SPACE_20)),
                safeBottom+ui.dp(Theme.SPACE_16)
            );
        }
        if(forgetCue!=null&&forgetCue.getLayoutParams() instanceof FrameLayout.LayoutParams){
            FrameLayout.LayoutParams cueLp=(FrameLayout.LayoutParams)forgetCue.getLayoutParams();
            cueLp.topMargin=safeTop+ui.dp(Theme.TOP_BAR+Theme.SPACE_8);
            forgetCue.setLayoutParams(cueLp);
        }
    }

    /** Shown once on the very first flashcard session so the card itself stays completely text-free. */
    private void maybeShowFirstRunGuide(){
        if(gesturePromptShown||Store.prefs(this).getBoolean("reviewGestureHintSeen",false))return;
        gesturePromptShown=true;
        View anchor=deck!=null?deck:ui.body;
        if(anchor==null)return;
        anchor.post(()->{
            if(isFinishing()||isDestroyed())return;
            showGestureGuide(true);
        });
    }

    private void showGestureGuide(boolean markSeen){
        LinearLayout list=ui.col();
        guideRow(list,"轻点一次","查看中文释义、例句与相关词");
        guideRow(list,"再次轻点","标记「记得」并进入下一张");
        guideRow(list,"向下滑动","标记「忘记」（翻面前后均可）");
        guideRow(list,"长按卡片","编辑当前单词卡");
        guideRow(list,"手机返回键","退出本轮复习");
        Sheet sheet=new Sheet(this)
            .title("背词手势说明")
            .message("背单词卡片不设按钮，专注看词与手势操作。")
            .content(list)
            .primary("知道了",()->{
                if(markSeen)Store.prefs(this).edit().putBoolean("reviewGestureHintSeen",true).apply();
            });
        sheet.onDismiss(()->{
            if(markSeen)Store.prefs(this).edit().putBoolean("reviewGestureHintSeen",true).apply();
        });
        sheet.show();
    }

    private void guideRow(LinearLayout parent,String gesture,String effect){
        LinearLayout row=ui.row();
        row.setPadding(0,ui.dp(Theme.SPACE_8),0,ui.dp(Theme.SPACE_8));
        TextView g=ui.type(gesture,Theme.T_LABEL);
        g.setTextColor(Theme.PRIMARY);
        row.addView(g,new LinearLayout.LayoutParams(ui.dp(88),-2));
        TextView e=ui.type(effect,Theme.T_CALLOUT);
        e.setTextColor(Theme.INK);
        row.addView(e,WordUi.weight());
        parent.addView(row,WordUi.lp());
    }

    private void show(){
        if(session.ended()){summary();return;}
        boolean fresh=!sessionChrome;
        if(fresh)buildSession();

        JSONObject word=words.optJSONObject(session.current());
        boolean zh=chineseFirst(word);
        currentWord=word;
        currentZh=zh;
        currentExample=word.optString("example");

        View card=buildCard(word,zh);
        if(counter!=null){
            counter.setText(counterText());
            counter.setContentDescription("本批第 "+(session.answered()+1)+" 张，共 "+session.total()+" 张");
        }
        if(progress!=null){
            progress.setStep(session.answered(),session.total(),!fresh);
        }
        updateSpeakVisibility();

        deck.removeAllViews();
        resetForgetCue();
        FrameLayout.LayoutParams cueLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        cueLp.topMargin=builtImmersive?(safeTop+ui.dp(Theme.TOP_BAR+Theme.SPACE_8)):ui.dp(Theme.SPACE_12);
        deck.addView(forgetCue,cueLp);
        deck.addView(card,new FrameLayout.LayoutParams(-1,-1));
        cardView=card;
        if(builtImmersive)applyImmersiveInsets();

        Motion.enter(card,enterFrom);
        enterFrom=0;

        if(revealed)speakWordThenExample(word.optString("english"),currentExample,true);
        else if(!zh)speak(word.optString("english"),true);
    }

    private void updateSpeakVisibility(){
        if(speakTop==null)return;
        boolean canSpeak=!currentZh||revealed;
        speakTop.setVisibility(canSpeak?View.VISIBLE:View.INVISIBLE);
        speakTop.setEnabled(canSpeak);
    }

    private void resetForgetCue(){
        if(forgetCue==null)return;
        forgetCue.animate().cancel();
        forgetCue.setAlpha(0f);
        forgetCue.setTranslationY(0f);
        forgetCue.setText("↓ 下滑标记忘记");
        forgetCue.setTextColor(Theme.INK_3);
        forgetCue.setBackground(Theme.shape(this,Theme.SUNK,18));
    }

    private String counterText(){return (session.answered()+1)+" / "+session.total();}

    /**
     * Pure immersive paper card with zero buttons and zero permanent hint copy.
     * Supports custom background, bottom slot, dark mode, long text, and compact/landscape/large-font layouts.
     */
    private View buildCard(JSONObject word,boolean zh){
        boolean nightMode=CardStyleStore.isNightMode(this);
        boolean immersive=builtImmersive;
        int screenH=getResources().getDisplayMetrics().heightPixels;
        int screenW=getResources().getDisplayMetrics().widthPixels;
        float fontScale=getResources().getConfiguration().fontScale;
        boolean landscape=screenW>screenH;
        boolean compact=landscape||screenH<ui.dp(620)||fontScale>=1.25f;

        CardSurfaceView card=new CardSurfaceView(this,immersive?0:24);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        if(!immersive){
            int minH=Math.min(ui.dp(420),Math.max(ui.dp(compact?220:300),screenH-ui.dp(compact?148:180)));
            card.setMinimumHeight(minH);
        }
        int padH=ui.dp(compact?Theme.SPACE_20:Theme.SPACE_24);
        int padV=ui.dp(compact?Theme.SPACE_16:Theme.SPACE_24);
        if(immersive)card.setPadding(0,0,0,0);
        else card.setPadding(padH,padV,padH,padV);
        card.applyStyle(cardStyle,nightMode);
        CardSurfaceView.Palette palette=card.palette();

        if(immersive){
            WordUi.edgeToEdgeSystemBars(this,palette.darkSurface);
            LinearLayout topChrome=ui.col();
            LinearLayout topRow=ui.row();
            topRow.setMinimumHeight(ui.dp(Theme.TOP_BAR));

            LinearLayout batchBox=ui.row();
            batchBox.setGravity(Gravity.CENTER_VERTICAL);
            if(CardStyle.BG_IMAGE.equals(cardStyle.backgroundMode)){
                batchBox.setBackground(Theme.shape(this,palette.chipFill,16));
                batchBox.setPadding(ui.dp(Theme.SPACE_12),ui.dp(Theme.SPACE_6),ui.dp(Theme.SPACE_12),ui.dp(Theme.SPACE_6));
            }else{
                batchBox.setPadding(ui.dp(Theme.SPACE_8),0,ui.dp(Theme.SPACE_8),0);
            }
            TextView batchLabel=ui.type("本批",Theme.T_CAPTION);
            batchLabel.setTextColor(palette.inkMuted);
            batchLabel.setTypeface(Theme.MEDIUM);
            LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);
            blp.rightMargin=ui.dp(Theme.SPACE_6);
            batchBox.addView(batchLabel,blp);

            counter=ui.type(counterText(),Theme.T_CALLOUT);
            counter.setTypeface(Theme.MEDIUM);
            counter.setTextColor(palette.ink);
            counter.setFontFeatureSettings("tnum");
            batchBox.addView(counter,new LinearLayout.LayoutParams(-2,-2));

            boolean isRing=CardStyle.PROGRESS_RING.equals(builtProgressStyle);
            progress=new ProgressLine(this).style(builtProgressStyle).colors(palette.accent,palette.hairline);
            if(isRing){
                LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20));
                rlp.leftMargin=ui.dp(Theme.SPACE_8);
                batchBox.addView(progress,rlp);
            }
            LinearLayout leftWrap=ui.row();
            leftWrap.addView(batchBox,new LinearLayout.LayoutParams(-2,-2));
            topRow.addView(leftWrap,WordUi.weight());

            speakTop=cardActionIcon(R.drawable.ic_volume_2,"朗读单词",palette,v->{
                if(currentWord!=null)speak(currentWord.optString("english"),false);
            });
            topRow.addView(speakTop,new LinearLayout.LayoutParams(ui.dp(Theme.TOUCH),ui.dp(Theme.TOUCH)));

            ImageButton moreBtn=cardActionIcon(R.drawable.ic_ellipsis,"更多",palette,v->more());
            topRow.addView(moreBtn,new LinearLayout.LayoutParams(ui.dp(Theme.TOUCH),ui.dp(Theme.TOUCH)));

            topChrome.addView(topRow,WordUi.lp());
            if(!isRing){
                LinearLayout.LayoutParams plp=WordUi.lp();
                plp.leftMargin=ui.dp(Theme.SPACE_8);
                plp.rightMargin=ui.dp(Theme.SPACE_8);
                plp.topMargin=ui.dp(Theme.SPACE_4);
                topChrome.addView(progress,plp);
            }
            immersiveTopChrome=topChrome;
            card.addView(topChrome,WordUi.lp());
        }

        if(session.attemptsForCurrent()>0){
            TextView retryTag=ui.type("再看一次",Theme.T_OVERLINE);
            retryTag.setTextColor(palette.inkMuted);
            retryTag.setGravity(Gravity.CENTER);
            card.addView(retryTag,WordUi.lp());
        }

        LinearLayout content=ui.col();
        content.setGravity(Gravity.CENTER);
        int contentPadV=ui.dp(compact?Theme.SPACE_8:Theme.SPACE_16);
        int contentPadH=immersive?padH:0;
        content.setPadding(contentPadH,contentPadV,contentPadH,contentPadV);

        String frontText=zh?word.optString("chinese"):word.optString("english");
        TextView front=new TextView(this);
        front.setText(frontText);
        front.setGravity(Gravity.CENTER);
        front.setTextColor(palette.ink);
        if(zh){
            front.setTypeface(Theme.MEDIUM);
            int zhSize=frontText.length()>28?18:(frontText.length()>18?21:25);
            front.setTextSize(compact?Math.max(17,zhSize-3):zhSize);
            front.setLineSpacing(0,1.28f);
            front.setMaxLines(compact?4:6);
            front.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }else{
            front.setTypeface(Theme.SERIF);
            int enBase=compact?32:40;
            front.setTextSize(headwordSize(frontText,enBase));
            front.setLetterSpacing(-0.01f);
            front.setLineSpacing(0,1.1f);
            front.setMaxLines(3);
            front.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        content.addView(front,WordUi.lp());

        hairline=new View(this);
        hairline.setBackgroundColor(palette.hairline);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(ui.dp(36),Math.max(1,ui.dp(1)));
        hp.topMargin=ui.dp(compact?Theme.SPACE_12:Theme.SPACE_24);
        hp.bottomMargin=ui.dp(compact?Theme.SPACE_12:Theme.SPACE_24);
        content.addView(hairline,hp);

        String backText=zh?word.optString("english"):word.optString("chinese","");
        back=new TextView(this);
        back.setText(backText);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(palette.ink);
        if(zh){
            back.setTypeface(Theme.SERIF);
            back.setTextSize(headwordSize(backText,compact?26:32));
            back.setLineSpacing(0,1.1f);
            back.setMaxLines(3);
            back.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }else{
            back.setTypeface(Theme.REGULAR);
            int defSize=backText.length()>40?17:(backText.length()>22?19:21);
            back.setTextSize(compact?Math.max(15,defSize-2):defSize);
            back.setLineSpacing(0,1.32f);
            back.setMaxLines(compact?4:6);
            back.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        content.addView(back,WordUi.lp());

        // Example sentence: tap to pronounce without flipping or advancing the card (PRD VO-04).
        sentence=new TextView(this);
        sentence.setText(currentExample);
        sentence.setGravity(Gravity.CENTER);
        sentence.setTypeface(Theme.SERIF_ITALIC);
        sentence.setTextSize(compact?13:15);
        sentence.setTextColor(palette.inkSecondary);
        sentence.setLineSpacing(0,1.32f);
        sentence.setMaxLines(compact?3:5);
        sentence.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sentence.setPadding(ui.dp(Theme.SPACE_12),ui.dp(compact?Theme.SPACE_4:Theme.SPACE_8),ui.dp(Theme.SPACE_12),ui.dp(compact?Theme.SPACE_4:Theme.SPACE_8));
        sentence.setContentDescription("朗读例句："+currentExample);
        sentence.setClickable(true);
        sentence.setFocusable(true);
        Theme.applyRipple(sentence,Theme.shape(this,Color.TRANSPARENT,Theme.RADIUS_CHIP),palette.ripple);
        Motion.press(sentence,.98f);
        sentence.setOnClickListener(v->{
            if(!currentExample.isEmpty()&&speech!=null)speech.speak(currentExample,"review_example");
        });
        sentence.setOnLongClickListener(v->true);
        content.addView(sentence,ui.lpMargin(compact?Theme.SPACE_8:Theme.SPACE_12,0));

        String category=word.optString("category","").trim();
        TextView catBadge=ui.chip(category,palette.chipFill,palette.chipInk);
        LinearLayout.LayoutParams catLp=new LinearLayout.LayoutParams(-2,-2);
        catLp.topMargin=ui.dp(compact?Theme.SPACE_8:Theme.SPACE_12);
        catLp.gravity=Gravity.CENTER_HORIZONTAL;
        catBadge.setVisibility(category.isEmpty()?View.GONE:(revealed?View.VISIBLE:View.INVISIBLE));
        content.addView(catBadge,catLp);
        categoryTag=catBadge;

        int level=Mastery.level(word);
        LinearLayout mastery=ui.row();
        mastery.setGravity(Gravity.CENTER);
        mastery.addView(new StageDots(this,5,level,false).ringNext(false).color(palette.dotActive).offColor(palette.dotInactive));
        TextView masteryLabel=ui.type("熟练度 "+level+"/5",Theme.T_CAPTION);
        masteryLabel.setTextColor(palette.inkMuted);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
        mp.leftMargin=ui.dp(Theme.SPACE_8);
        mastery.addView(masteryLabel,mp);
        content.addView(mastery,ui.lpMargin(compact?Theme.SPACE_12:Theme.SPACE_20,0));
        masteryRow=mastery;

        // Quiet inline relation link (no pill button chrome); tap opens interactive mind map.
        String relationLabel="";
        try{relationLabel=WordRelations.studyLabel(this,Words.stableId(word));}catch(Exception ignored){}
        boolean hasRelations=relationLabel!=null&&!relationLabel.isEmpty();
        LinearLayout relStrip=ui.row();
        relStrip.setGravity(Gravity.CENTER);
        relStrip.setPadding(ui.dp(Theme.SPACE_12),ui.dp(compact?Theme.SPACE_6:Theme.SPACE_8),ui.dp(Theme.SPACE_12),ui.dp(compact?Theme.SPACE_6:Theme.SPACE_8));
        relStrip.setBackground(Theme.shape(this,Color.TRANSPARENT,Theme.RADIUS_CHIP));
        ImageView relIcon=ui.icon(R.drawable.ic_layers,palette.accent);
        LinearLayout.LayoutParams rip=new LinearLayout.LayoutParams(ui.dp(15),ui.dp(15));
        rip.rightMargin=ui.dp(Theme.SPACE_6);
        relStrip.addView(relIcon,rip);
        TextView relText=ui.type(hasRelations?relationLabel:"",Theme.T_CAPTION);
        relText.setTextColor(palette.accent);
        relText.setTypeface(Theme.MEDIUM);
        relText.setSingleLine(true);
        relText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int maxRelWidth=Math.max(ui.dp(160),Math.min(ui.dp(420),screenW-ui.dp(148)));
        relText.setMaxWidth(maxRelWidth);
        relStrip.addView(relText);
        ImageView relArrow=ui.icon(R.drawable.ic_chevron_right,palette.accent);
        LinearLayout.LayoutParams rap=new LinearLayout.LayoutParams(ui.dp(14),ui.dp(14));
        rap.leftMargin=ui.dp(2);
        relStrip.addView(relArrow,rap);
        relStrip.setClickable(true);
        relStrip.setFocusable(true);
        relStrip.setContentDescription("查看相关词关联图："+relationLabel);
        Theme.applyRipple(relStrip,relStrip.getBackground(),palette.ripple);
        Motion.press(relStrip,.97f);
        relStrip.setOnClickListener(v->openRelations(word));
        relStrip.setOnLongClickListener(v->true);
        relStrip.setVisibility(hasRelations?(revealed?View.VISIBLE:View.INVISIBLE):View.GONE);
        LinearLayout.LayoutParams relationLp=new LinearLayout.LayoutParams(-2,-2);
        relationLp.topMargin=ui.dp(compact?Theme.SPACE_8:Theme.SPACE_12);
        relationLp.gravity=Gravity.CENTER_HORIZONTAL;
        content.addView(relStrip,relationLp);
        relationsRow=relStrip;

        card.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if(immersive){
            LinearLayout bottomHost=ui.col();
            CardSurfaceView.attachBottomSlot(this,bottomHost,cardStyle,palette,compact);
            immersiveBottomHost=bottomHost;
            card.addView(bottomHost,WordUi.lp());
        }else{
            CardSurfaceView.attachBottomSlot(this,card,cardStyle,palette,compact);
        }

        int answerState=revealed?View.VISIBLE:View.INVISIBLE;
        hairline.setVisibility(answerState);
        back.setVisibility(answerState);
        sentence.setVisibility(currentExample.isEmpty()?View.GONE:answerState);
        masteryRow.setVisibility(answerState);
        bindCard(card,palette.ripple,immersive?0:24);
        return card;
    }

    /** 48dp accessible icon button integrated directly into immersive card chrome without conflicting with card gestures. */
    private ImageButton cardActionIcon(int drawableRes,String description,CardSurfaceView.Palette palette,View.OnClickListener click){
        ImageButton b=new ImageButton(this);
        b.setImageResource(drawableRes);
        b.setImageTintList(ColorStateList.valueOf(palette.ink));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(description);
        b.setMinimumWidth(ui.dp(Theme.TOUCH));
        b.setMinimumHeight(ui.dp(Theme.TOUCH));
        if(CardStyle.BG_IMAGE.equals(cardStyle.backgroundMode)){
            Theme.applyRipple(b,Theme.oval(palette.chipFill),palette.ripple);
        }else{
            Theme.applyBorderlessRipple(b,palette.ripple);
        }
        b.setOnClickListener(click);
        b.setOnLongClickListener(v->true);
        Motion.press(b,.90f);
        return b;
    }

    /**
     * Card gestures:
     *   - Tap 1 reveals, Tap 2 marks “记得”
     *   - Swipe down marks “忘记” (with finger-follow translation, live terracotta cue & haptic)
     *   - Long-press opens word editor
     */
    private void bindCard(View card,int rippleColor,float cornerRadiusDp){
        card.setClickable(true);
        card.setFocusable(true);
        card.setLongClickable(false);
        card.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(rippleColor),null,Theme.shape(this,Color.WHITE,cornerRadiusDp)));
        final int slop=ViewConfiguration.get(this).getScaledTouchSlop();
        final int timeout=ViewConfiguration.getLongPressTimeout();
        final float threshold=ui.dp(76);
        final float flingDistance=ui.dp(42);
        final float flingVelocity=ui.dp(560);
        final float[] down=new float[2];
        final boolean[] held={false},dragging={false},crossed={false};
        final VelocityTracker[] tracker={null};

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
                held[0]=false;
                dragging[0]=false;
                crossed[0]=false;
                down[0]=event.getRawX();
                down[1]=event.getRawY();
                if(tracker[0]!=null)tracker[0].recycle();
                tracker[0]=VelocityTracker.obtain();
                tracker[0].addMovement(event);
                card.removeCallbacks(longPress);
                if(!changing)Feedback.press(view);
                if(view.isEnabled()&&Motion.enabled(view.getContext()))
                    view.animate().scaleX(.99f).scaleY(.99f).setStartDelay(0).setDuration(90).setInterpolator(Motion.EASE).start();
                card.postDelayed(longPress,timeout);
                return false;
            }
            if(tracker[0]!=null)tracker[0].addMovement(event);

            if(action==MotionEvent.ACTION_MOVE){
                float dx=event.getRawX()-down[0];
                float dy=event.getRawY()-down[1];
                if(Math.hypot(dx,dy)>slop)card.removeCallbacks(longPress);
                if(!dragging[0]&&!held[0]&&!changing&&dy>slop*1.2f&&dy>Math.abs(dx)*1.2f){
                    dragging[0]=true;
                    view.setPressed(false);
                    Feedback.releasePress(view);
                    view.animate().cancel();
                    if(view.getParent()!=null)view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if(dragging[0]){
                    float dragY=Math.max(0f,dy);
                    float progressRatio=Math.min(1f,dragY/threshold);
                    view.setTranslationY(dragY*0.86f);
                    float s=1f-progressRatio*0.025f;
                    view.setScaleX(s);
                    view.setScaleY(s);
                    boolean nowReady=dragY>=threshold;
                    if(nowReady!=crossed[0]){
                        crossed[0]=nowReady;
                        if(nowReady)Feedback.tick(view);
                        updateForgetCueStyle(nowReady);
                    }
                    if(forgetCue!=null){
                        forgetCue.setAlpha(Math.min(1f,dragY/(threshold*0.5f)));
                        forgetCue.setTranslationY(Math.min(ui.dp(12),dragY*0.14f));
                    }
                    return true;
                }
                return false;
            }

            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                card.removeCallbacks(longPress);
                Feedback.releasePress(view);
                float dy=event.getRawY()-down[1];
                float vy=0f;
                if(tracker[0]!=null){
                    tracker[0].computeCurrentVelocity(1000);
                    vy=tracker[0].getYVelocity();
                    tracker[0].recycle();
                    tracker[0]=null;
                }
                if(dragging[0]){
                    dragging[0]=false;
                    boolean commit=action==MotionEvent.ACTION_UP&&!changing
                        &&(dy>=threshold||(dy>=flingDistance&&vy>=flingVelocity));
                    if(commit){
                        if(forgetCue!=null)forgetCue.animate().alpha(0f).setDuration(100).start();
                        answer(false,session.answered(),view,true);
                    }else{
                        if(Motion.enabled(view.getContext())){
                            view.animate().translationY(0).scaleX(1).scaleY(1).setStartDelay(0).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
                            if(forgetCue!=null)forgetCue.animate().alpha(0f).translationY(0f).setDuration(160).withEndAction(this::resetForgetCue).start();
                        }else{
                            view.setTranslationY(0);view.setScaleX(1);view.setScaleY(1);
                            resetForgetCue();
                        }
                    }
                    return true;
                }
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
            else answer(true,session.answered(),v,false);
        });
    }

    private void updateForgetCueStyle(boolean ready){
        if(forgetCue==null)return;
        if(ready){
            forgetCue.setText("松手标记忘记");
            forgetCue.setTextColor(Theme.DANGER);
            forgetCue.setBackground(Theme.shape(this,Theme.DANGER_SOFT,18));
        }else{
            forgetCue.setText("↓ 下滑标记忘记");
            forgetCue.setTextColor(Theme.INK_3);
            forgetCue.setBackground(Theme.shape(this,Theme.SUNK,18));
        }
    }

    /** Long headwords step down so they keep to one or two calm lines. */
    private static int headwordSize(String value,int base){
        int n=value==null?0:value.length();
        if(n>22)return base-12;
        if(n>13)return base-6;
        return base;
    }

    private void revealAnswer(View source){
        if(revealed||changing)return;
        interaction.reveal(source);
        revealed=true;
        updateSpeakVisibility();

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
        if(categoryTag!=null&&categoryTag.getVisibility()!=View.GONE){
            categoryTag.setVisibility(View.VISIBLE);
            Motion.enter(categoryTag,0,delay);
            delay+=20;
        }
        masteryRow.setVisibility(View.VISIBLE);
        Motion.enter(masteryRow,0,delay);
        if(relationsRow!=null&&relationsRow.getVisibility()!=View.GONE){
            relationsRow.setVisibility(View.VISIBLE);
            Motion.enter(relationsRow,0,delay);
        }
        cardView.setScaleX(1);
        cardView.setScaleY(1);

        lastAutoSpoken=-1;
        speakWordThenExample(currentWord.optString("english"),currentExample,true);
    }

    private void answer(boolean known,int expected,View source,boolean swipeDown){
        if(changing||session.ended()||session.answered()!=expected)return;
        if(known&&!revealed)return;
        boolean firstAttempt=session.attemptsForCurrent()==0;
        JSONObject word=words.optJSONObject(session.current());
        long sourceTask=word==null?id:word.optLong("_taskId",id);
        int sourceIndex=word!=null&&word.has("_wordIndex")?word.optInt("_wordIndex"):session.current();
        String wordId=word==null?null:Words.stableId(word);
        try{
            if(!known&&sourceTask>0)WrongBook.record(this,sourceTask,sourceIndex,practice?0:expectedStage+1,words,"review:"+seed+":"+expected+":"+wordId,wordId);
            if(!practice&&sourceTask>0)Mastery.applyAnswer(this,sourceTask,sourceIndex,known,firstAttempt,words,wordId);
        }catch(Exception e){
            ui.error("结果保存失败，尚未提交本题："+e.getMessage());
            if(swipeDown&&cardView!=null){
                cardView.animate().translationY(0).scaleX(1).scaleY(1).setDuration(180).start();
                resetForgetCue();
            }
            return;
        }
        interaction.respond(source,known?Feedback.KNOWN:Feedback.RETRY);
        changing=true;
        if(speech!=null)speech.stop();
        session.answer(known);
        answers.append(known?'1':'0');
        revealed=false;

        progress.set(session.answered()/(float)session.total(),true);
        if(!session.ended())counter.setText(counterText());

        Runnable next=()->{
            if(isFinishing()||isDestroyed())return;
            changing=false;
            show();
        };
        if(swipeDown){
            enterFrom=0;
            Motion.leaveDown(cardView,next);
        }else{
            int toward=known?1:-1;
            enterFrom=-toward;
            Motion.leave(cardView,-toward,next);
        }
    }

    // ── Card menu & edit ──────────────────────────────────────────────────────

    private void more(){
        if(changing||currentWord==null)return;
        JSONObject word=currentWord;
        Sheet sheet=new Sheet(this)
            .item(R.drawable.ic_pen_line,"编辑这张卡","修改英文、中文、类别或例句",()->edit(word))
            .item(R.drawable.ic_layers,"相关词","近义、扩、同根、反义与关系图",()->openRelations(word))
            .item(R.drawable.ic_sparkles,"卡片外观",cardStyle.summary(),()->Motion.open(this,new Intent(this,CardStyleActivity.class)))
            .item(R.drawable.ic_circle_alert,"手势说明","轻点翻面 / 记得，下滑忘记，长按编辑",()->showGestureGuide(false));
        if(ConnectionImages.exists(this,word.optString("connectionImage")))sheet.item(R.drawable.ic_images,"查看附带图片",()->Motion.open(this,new Intent(this,ConnectionImageActivity.class).putExtra("path",word.optString("connectionImage")).putExtra("english",word.optString("english"))));
        sheet.show();
    }

    private void openRelations(JSONObject word){
        if(word==null)return;
        String wid=Words.stableId(word);
        Motion.open(this,new Intent(this,RelationActivity.class).putExtra("wordId",wid).putExtra("english",word.optString("english")).putExtra("chinese",word.optString("chinese")));
    }

    private void edit(JSONObject word){
        LinearLayout form=ui.col();
        EditText en=formField(form,"英文","",word.optString("english"));
        EditText zh=formField(form,"中文","",word.optString("chinese"));
        EditText cat=formField(form,"所属类别","可留空，如“优秀的”相关",word.optString("category"));
        cat.setSingleLine(true);
        java.util.LinkedHashSet<String> cats=new java.util.LinkedHashSet<>();
        if(words!=null)for(int i=0;i<words.length();i++){
            JSONObject w=words.optJSONObject(i);
            String c=w==null?"":w.optString("category","").trim();
            if(!c.isEmpty())cats.add(c);
        }
        if(!cats.isEmpty()){
            LinearLayout chipRow=ui.row();
            for(String existing:cats){
                TextView chip=ui.chip(existing,Theme.PRIMARY_SOFT,Theme.PRIMARY);
                chip.setPadding(ui.dp(10),ui.dp(5),ui.dp(10),ui.dp(5));
                chip.setClickable(true);chip.setFocusable(true);
                Theme.applyRipple(chip,chip.getBackground(),Theme.RIPPLE_ACCENT);
                Motion.press(chip,.95f);
                chip.setOnClickListener(v->{cat.setText(existing);cat.setSelection(existing.length());Feedback.tick(v);});
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
                if(chipRow.getChildCount()>0)cp.leftMargin=ui.dp(6);
                chipRow.addView(chip,cp);
            }
            HorizontalScrollView hsv=new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(chipRow);
            form.addView(hsv,ui.lpMargin(Theme.SPACE_8,0));
        }
        EditText ex=formField(form,"例句","可留空",word.optString("example"));
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
                Object oldCategory=word.opt("category");
                Object oldExample=word.opt("example");
                try{
                    long targetId=word.optLong("_taskId",id);
                    int hint=word.has("_wordIndex")?word.optInt("_wordIndex"):session.current();
                    String wordId=Words.stableId(word);
                    JSONObject current=Store.find(this,targetId);
                    if(current==null)throw new Exception("任务不存在");
                    JSONArray stored=current.optJSONArray("words");
                    int located=Words.locate(stored,hint,wordId);
                    if(located<0)throw new Exception("单词卡已不存在");
                    JSONObject target=stored.getJSONObject(located);
                    Words.stableId(target);
                    String nextCat=cat.getText().toString().trim();
                    target.put("english",en.getText().toString().trim())
                        .put("chinese",zh.getText().toString().trim())
                        .put("category",nextCat)
                        .put("example",ex.getText().toString().trim());
                    word.put("english",target.optString("english")).put("chinese",target.optString("chinese")).put("category",nextCat).put("example",target.optString("example")).put("wordId",Words.stableId(target));
                    Store.updateCard(this,targetId,located,target);
                    if(selectionId!=null)TrainingStore.updateWord(this,selectionId,word);
                }catch(Exception e){
                    restore(word,"english",oldEnglish);
                    restore(word,"chinese",oldChinese);
                    restore(word,"category",oldCategory);
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
        builtImmersive=false;
        immersiveTopChrome=null;
        immersiveBottomHost=null;
        cardView=null;
        speakTop=null;
        ui.startModal(practice?"任意复习":taskTitle);
        LinearLayout body=ui.body;

        JSONObject task=Store.find(this,id);
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
        TextView sentenceLine=ui.type("共 "+words.length()+" 个单词，标记“忘记” "+session.repeats()+" 次",Theme.T_CALLOUT);
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
            session=new ReviewSession(words.length(),selectionId==null&&Store.prefs(this).getBoolean("shuffleWords",false),seed);
            lastAutoSpoken=-1;
            answers.setLength(0);
            revealed=false;
            show();
        };
        LinearLayout links=ui.row();
        links.setGravity(Gravity.CENTER);
        Button moreOptions=ui.textButton("更多选项",v->summaryMore());
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

    private String completeLabel(){
        SharedPreferences prefs=Store.prefs(this);
        if(!prefs.getBoolean("spellingConfigured",false)||prefs.getBoolean("askSpellingMode",false))return "完成本轮并选择拼写";
        int saved=prefs.getInt("spellingMode",SpellingPlan.NONE);
        if(saved==SpellingPlan.ALL)return "完成并拼写";
        if(saved==SpellingPlan.WRONG_ONLY)return "完成并拼写错词";
        if(prefs.getBoolean("spellForgottenWords",false)&&session.repeats()>0)return "完成并拼写忘记的词";
        return "完成本轮";
    }

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

    private RadioButton choiceRow(String label,int id){return ui.choice(label,null,id);}

    private static void paintChoices(RadioGroup group){
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            if(child instanceof RadioButton)((RadioButton)child).setTypeface(((RadioButton)child).isChecked()?Theme.MEDIUM:Theme.REGULAR);
        }
    }

    private CheckBox checkBox(String label,boolean checked){return ui.check(label,checked);}

    private void forward(Intent next){Motion.replace(this,next);}

    private void complete(int spellingMode){
        if(completed)return;
        try{
            JSONObject task=Store.find(this,id);
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
            LocalStore.inTransaction(this,()->{
                LocalStore.writeTaskLocked(LocalStore.database(),task,-1);
                if(!Store.prefs(this).edit().putInt("spellingMode",spellingMode).commit())throw new IllegalStateException("复习结果未能写入设备");
            });
            Alarms.cancel(this,id);
            if(!task.optBoolean("done"))Alarms.schedule(this,task);
            completed=true;
            interaction.confirm(getWindow().getDecorView());

            JSONArray storedWords=task.optJSONArray("words");
            int wordTotal=storedWords==null?words.length():storedWords.length();
            ArrayList<Integer> wrongHits=new ArrayList<>();
            JSONArray wrongSource=storedWords!=null?storedWords:words;
            for(int i=0;i<wrongSource.length();i++){
                JSONObject word=wrongSource.optJSONObject(i);
                if(word!=null&&WrongBook.inBook(WrongBook.stateForWord(this,word)))wrongHits.add(i);
            }
            int[] wrongIndices=new int[wrongHits.size()];
            for(int i=0;i<wrongHits.size();i++)wrongIndices[i]=wrongHits.get(i);
            int[] indices=SpellingPlan.indices(spellingMode,wordTotal,wrongIndices);
            int[] forgotten=session.missedIndices();
            if(Store.prefs(this).getBoolean("spellForgottenWords",false))indices=SpellingPlan.include(indices,forgotten,words.length());
            int[] examples=Store.prefs(this).getBoolean("addExamplesForForgotten",false)?ExampleActivity.missingExampleIndices(storedWords,forgotten):new int[0];

            if(indices.length>0){
                String selection=TrainingStore.forTask(this,id,indices);
                Intent nextStep=new Intent(this,SpellingActivity.class)
                    .putExtra("taskId",id)
                    .putExtra("selectionId",selection)
                    .putExtra("round",next)
                    .putExtra("indices",indices)
                    .putExtra("historyAt",now)
                    .putExtra("exampleIndices",examples)
                    .putExtra("wrongMode",spellingMode==SpellingPlan.WRONG_ONLY)
                    .putExtra("sessionId",UUID.randomUUID().toString())
                    .putExtra("attemptId",UUID.randomUUID().toString());
                forward(nextStep);
            }else if(examples.length>0){
                forward(new Intent(this,ExampleActivity.class).putExtra("taskId",id).putExtra("indices",examples).putExtra("notice","第 "+next+" 轮已记录"));
            }else{
                String msg=task.optBoolean("done")?"已完成全部 "+next+" 轮":"已完成第 "+next+" 轮 · 下次 "+when(task.optLong("due"));
                finish();
                ui.toast(msg);
            }
        }catch(Exception e){
            ui.error("保存失败："+e.getMessage());
        }
    }
}
