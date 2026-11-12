package com.example.reviewreminder;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * "朗读声音与语速" bottom sheet.
 *
 *   英文声音   [ current voice · 无需联网           › ]   → opens a checkmarked choice sheet
 *   朗读速度   [ 0.6× | 0.75× | 0.85× | 1.0× | 1.15× ]   segmented, with a one-line description
 *   (试听)                                   系统语音设置
 *   [ 取消 ]  [ 保存 ]
 *
 * Prefs keys ("speechVoice", "speechRate"), rate table and {@link #configure} are unchanged.
 */
final class SpeechSettings {
    private static final float[] RATES={0.60f,0.75f,0.85f,1.0f,1.15f};
    private static final String[] RATE_LABELS={"很慢 · 0.60×","较慢 · 0.75×","舒缓 · 0.85×（默认）","正常 · 1.00×","较快 · 1.15×"};
    private static final String[] RATE_SHORT={"0.6×","0.75×","0.85×","1.0×","1.15×"};
    private static final String DEFAULT_VOICE="系统默认英文声音";

    private final Activity activity;
    private final WordUi ui;
    private final Sheet sheet;
    /** The sheet's window (kept under the old field name). */
    private final Dialog dialog;
    /** Speech-rate selector; index semantics match the old Spinner (0…4). */
    private final Segmented rate;
    private final TextView status,rateDetail,voiceName,voiceDetail;
    private final LinearLayout voiceRow;
    private final Button save,cancel;
    private final ArrayList<Voice> choices=new ArrayList<>();
    /** 0 = system default English voice, i+1 = choices.get(i) — same as the old Spinner position. */
    private int voiceIndex;
    private TextToSpeech engine;
    private boolean ready,closed;

    private SpeechSettings(Activity activity){
        this.activity=activity;
        this.ui=new WordUi(activity);

        LinearLayout form=ui.col();

        // ── Voice ──
        form.addView(label("英文声音"));
        voiceRow=ui.row();
        voiceRow.setMinimumHeight(dp(Theme.ROW_HEIGHT));
        voiceRow.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_8),dp(Theme.SPACE_12),dp(Theme.SPACE_8));
        voiceRow.setBackground(Theme.shape(activity,Theme.SUNK,Theme.RADIUS_FIELD));
        LinearLayout voiceCopy=ui.col();
        voiceName=ui.type(DEFAULT_VOICE,Theme.T_BODY);
        voiceName.setSingleLine(true);
        voiceName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        voiceCopy.addView(voiceName);
        voiceDetail=ui.type("",Theme.T_CAPTION);
        voiceDetail.setVisibility(View.GONE);
        voiceCopy.addView(voiceDetail);
        voiceRow.addView(voiceCopy,WordUi.weight());
        ImageView chevron=ui.icon(R.drawable.ic_chevron_right,Theme.INK_3);
        LinearLayout.LayoutParams chevronLp=new LinearLayout.LayoutParams(dp(Theme.ICON_SMALL),dp(Theme.ICON_SMALL));
        chevronLp.leftMargin=dp(Theme.SPACE_8);
        voiceRow.addView(chevron,chevronLp);
        voiceRow.setClickable(true);
        voiceRow.setFocusable(true);
        voiceRow.setOnClickListener(v->pickVoice());
        Theme.applyRipple(voiceRow,voiceRow.getBackground(),Theme.RIPPLE_LIGHT);
        Motion.press(voiceRow,.985f);
        WordUi.enable(voiceRow,false);
        form.addView(voiceRow,WordUi.lp());

        status=ui.type("正在读取设备语音…",Theme.T_CAPTION);
        status.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_8),dp(Theme.SPACE_4),0);
        form.addView(status,WordUi.lp());

        // ── Rate ──
        TextView rateLabel=label("朗读速度");
        form.addView(rateLabel);
        ((LinearLayout.LayoutParams)rateLabel.getLayoutParams()).topMargin=dp(Theme.SPACE_24);
        int savedRate=rateIndex(Store.prefs(activity).getInt("speechRate",2));
        rate=ui.segmented(RATE_SHORT,savedRate,index->describeRate());
        form.addView(rate,WordUi.lp());
        rateDetail=ui.type("",Theme.T_CAPTION);
        rateDetail.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_8),dp(Theme.SPACE_4),0);
        form.addView(rateDetail,WordUi.lp());
        describeRate();

        // ── Preview & system settings ──
        LinearLayout tools=ui.row();
        Button preview=ui.quietPill("试听",R.drawable.ic_volume_2,v->preview());
        preview.setContentDescription("试听：remember");
        tools.addView(preview,new LinearLayout.LayoutParams(-2,-2));
        tools.addView(new View(activity),WordUi.weight());
        Button system=ui.textButton("系统语音设置",v->{
            try{
                activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
            }catch(Exception e){
                inline("请在系统设置中打开文字转语音设置");
            }
        });
        system.setTextSize(Theme.CALLOUT_SIZE);
        tools.addView(system,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams toolsLp=WordUi.lp();
        toolsLp.topMargin=dp(Theme.SPACE_20);
        form.addView(tools,toolsLp);

        sheet=new Sheet(activity).title("朗读声音与语速").content(form);
        sheet.secondary("取消",null);
        sheet.primaryIf("保存",this::persist);
        save=sheet.primaryButton();
        cancel=sheet.secondaryButton();
        dialog=sheet.dialog;
        sheet.onDismiss(()->{
            closed=true;
            if(engine!=null){
                engine.stop();
                engine.shutdown();
                engine=null;
            }
        });
        sheet.show();
        if(!sheet.isShowing()){
            // Activity is finishing: the sheet never appears, so its dismiss listener would never
            // release a TTS engine. Do not create one.
            closed=true;
            return;
        }
        engine=new TextToSpeech(activity,result->new Handler(Looper.getMainLooper()).post(()->initialized(result)));
    }

    static void show(Activity activity){new SpeechSettings(activity);}

    static int rateIndex(int value){return Math.max(0,Math.min(RATES.length-1,value));}

    static float rate(int value){return RATES[rateIndex(value)];}

    static boolean configure(TextToSpeech engine,String name,int rate){
        int language=engine.setLanguage(Locale.ENGLISH);
        if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED)return false;
        Voice fallback=engine.getDefaultVoice();
        if(fallback!=null&&fallback.getLocale()!=null&&"en".equals(fallback.getLocale().getLanguage()))engine.setVoice(fallback);
        if(name!=null&&!name.isEmpty()&&engine.getVoices()!=null){
            for(Voice voice:engine.getVoices()){
                if(name.equals(voice.getName())&&voice.getLocale()!=null&&"en".equals(voice.getLocale().getLanguage())){
                    if(engine.setVoice(voice)!=TextToSpeech.SUCCESS&&fallback!=null)engine.setVoice(fallback);
                    break;
                }
            }
        }
        engine.setSpeechRate(rate(rate));
        return true;
    }

    /** Save handler: same values, same commit check, same messages. Returning false keeps the sheet open. */
    private boolean persist(){
        int selected=voiceIndex;
        String voice=ready
                ?(selected>0&&selected<=choices.size()?choices.get(selected-1).getName():"")
                :Store.prefs(activity).getString("speechVoice","");
        if(Store.prefs(activity).edit().putString("speechVoice",voice).putInt("speechRate",rateIndex(rate.getSelectedItemPosition())).commit()){
            toast("朗读设置已保存");
            return true;
        }
        inline("设置未保存，请检查设备存储");
        return false;
    }

    private void initialized(int result){
        if(closed)return;
        if(result!=TextToSpeech.SUCCESS||engine==null||!configure(engine,"",2)){
            status.setText("没有可用的英文语音，请检查系统语音引擎。");
            status.setTextColor(Theme.DANGER);
            return;
        }
        ready=true;
        if(engine.getVoices()!=null){
            for(Voice voice:engine.getVoices())if(voice.getLocale()!=null&&"en".equals(voice.getLocale().getLanguage()))choices.add(voice);
        }
        Collections.sort(choices,new Comparator<Voice>(){
            public int compare(Voice a,Voice b){
                int locale=a.getLocale().toLanguageTag().compareTo(b.getLocale().toLanguageTag());
                return locale!=0?locale:a.getName().compareTo(b.getName());
            }
        });
        String saved=Store.prefs(activity).getString("speechVoice","");
        int selected=0;
        for(int i=0;i<choices.size();i++)if(choices.get(i).getName().equals(saved))selected=i+1;
        voiceIndex=selected;
        WordUi.enable(voiceRow,true);
        describeVoice();
        status.setTextColor(Theme.INK_3);
        status.setText(!saved.isEmpty()&&selected==0
                ?"已选声音不可用，当前回退到系统默认；保存将更新选择。"
                :"声音由系统语音引擎提供，联网声音可能不可用。");
    }

    /** Opens the checkmarked voice list; choosing one updates the row (saved only with 保存). */
    private void pickVoice(){
        if(!ready||closed)return;
        String[] labels=new String[choices.size()+1];
        labels[0]=DEFAULT_VOICE;
        for(int i=0;i<choices.size();i++)labels[i+1]=voiceLabel(choices.get(i));
        Sheet.pick(activity,"英文声音",labels,voiceIndex,index->{
            if(closed)return;
            voiceIndex=index;
            describeVoice();
            Feedback.tick(voiceRow);
        });
    }

    private void describeVoice(){
        if(voiceIndex>0&&voiceIndex<=choices.size()){
            Voice voice=choices.get(voiceIndex-1);
            voiceName.setText(voice.getName());
            voiceDetail.setText(voice.getLocale().toLanguageTag()+" · "+(voice.isNetworkConnectionRequired()?"需联网":"无需联网"));
            voiceDetail.setVisibility(View.VISIBLE);
        }else{
            voiceName.setText(DEFAULT_VOICE);
            voiceDetail.setVisibility(View.GONE);
        }
    }

    private void describeRate(){rateDetail.setText(RATE_LABELS[rateIndex(rate.getSelectedItemPosition())]);}

    private static String voiceLabel(Voice voice){
        return voice.getLocale().toLanguageTag()+" · "+voice.getName()+(voice.isNetworkConnectionRequired()?"（需联网）":"（无需联网）");
    }

    private void preview(){
        if(!ready||engine==null){
            inline("英文语音尚不可用");
            return;
        }
        int index=voiceIndex;
        String name=index>0&&index<=choices.size()?choices.get(index-1).getName():"";
        if(configure(engine,name,rate.getSelectedItemPosition()))engine.speak("remember",TextToSpeech.QUEUE_FLUSH,null,"preview");
    }

    private TextView label(String text){
        TextView t=ui.type(text,Theme.T_LABEL);
        t.setTextSize(Theme.CALLOUT_SIZE);
        t.setTextColor(Theme.INK_2);
        t.setGravity(Gravity.START);
        t.setPadding(dp(Theme.SPACE_4),0,dp(Theme.SPACE_4),dp(Theme.SPACE_8));
        t.setLayoutParams(WordUi.lp());
        return t;
    }

    private int dp(float n){return Theme.dp(activity,n);}

    private void toast(String text){Notice.show(activity,text);}

    /**
     * Messages raised while the sheet is open are written into its status line (a toast would sit
     * under the scrim); the line re-enters so the change is noticed.
     */
    private void inline(String text){
        if(closed||!sheet.isShowing()){Notice.error(activity,text);return;}
        status.setText(text);
        status.setTextColor(Theme.DANGER);
        Motion.enter(status);
    }
}
