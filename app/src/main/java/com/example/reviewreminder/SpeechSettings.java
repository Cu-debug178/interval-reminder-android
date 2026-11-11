package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

final class SpeechSettings {
    private static final float[] RATES={0.60f,0.75f,0.85f,1.0f,1.15f};
    private static final String[] RATE_LABELS={"很慢 · 0.60×","较慢 · 0.75×","舒缓 · 0.85×（默认）","正常 · 1.00×","较快 · 1.15×"};
    private final Activity activity;
    private final Spinner voices,rate;
    private final TextView status;
    private final AlertDialog dialog;
    private final ArrayList<Voice> choices=new ArrayList<>();
    private TextToSpeech engine;
    private boolean ready,closed;

    private SpeechSettings(Activity activity){
        this.activity=activity;
        float d=activity.getResources().getDisplayMetrics().density;
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding((int)(24*d),0,(int)(24*d),0);
        TextView voiceLabel=new TextView(activity);voiceLabel.setText("英文声音");form.addView(voiceLabel);
        voices=new Spinner(activity);voices.setMinimumHeight((int)(48*d));form.addView(voices);
        status=new TextView(activity);status.setText("正在读取设备语音…");form.addView(status);
        TextView rateLabel=new TextView(activity);rateLabel.setText("朗读速度");rateLabel.setPadding(0,(int)(14*d),0,0);form.addView(rateLabel);
        rate=new Spinner(activity);rate.setMinimumHeight((int)(48*d));rate.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,RATE_LABELS));rate.setSelection(rateIndex(Store.prefs(activity).getInt("speechRate",2)));form.addView(rate);
        Button preview=new Button(activity);preview.setText("试听：remember");preview.setMinHeight((int)(48*d));preview.setOnClickListener(v->preview());Motion.press(preview);form.addView(preview);
        Button system=new Button(activity);system.setText("系统语音引擎设置");system.setMinHeight((int)(48*d));system.setOnClickListener(v->{try{activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception e){toast("请在系统设置中打开文字转语音设置");}});Motion.press(system);form.addView(system);
        dialog=new AlertDialog.Builder(activity).setTitle("朗读声音与语速").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            int selected=voices.getSelectedItemPosition();
            String voice=ready?(selected>0&&selected<=choices.size()?choices.get(selected-1).getName():""):Store.prefs(activity).getString("speechVoice","");
            if(Store.prefs(activity).edit().putString("speechVoice",voice).putInt("speechRate",rateIndex(rate.getSelectedItemPosition())).commit()){dialog.dismiss();toast("朗读设置已保存");}else toast("设置未保存，请检查设备存储");
        }));
        dialog.setOnDismissListener(v->{closed=true;if(engine!=null){engine.stop();engine.shutdown();engine=null;}});
        dialog.show();
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
        if(name!=null&&!name.isEmpty()&&engine.getVoices()!=null)for(Voice voice:engine.getVoices())if(name.equals(voice.getName())&&voice.getLocale()!=null&&"en".equals(voice.getLocale().getLanguage())){if(engine.setVoice(voice)!=TextToSpeech.SUCCESS&&fallback!=null)engine.setVoice(fallback);break;}
        engine.setSpeechRate(rate(rate));return true;
    }
    private void initialized(int result){
        if(closed)return;
        if(result!=TextToSpeech.SUCCESS||engine==null||!configure(engine,"",2)){status.setText("没有可用的英文语音，请检查系统语音引擎。");return;}
        ready=true;
        if(engine.getVoices()!=null)for(Voice voice:engine.getVoices())if(voice.getLocale()!=null&&"en".equals(voice.getLocale().getLanguage()))choices.add(voice);
        Collections.sort(choices,new Comparator<Voice>(){public int compare(Voice a,Voice b){int locale=a.getLocale().toLanguageTag().compareTo(b.getLocale().toLanguageTag());return locale!=0?locale:a.getName().compareTo(b.getName());}});
        ArrayList<String> labels=new ArrayList<>();labels.add("系统默认英文声音");String saved=Store.prefs(activity).getString("speechVoice","");int selected=0;
        for(int i=0;i<choices.size();i++){Voice voice=choices.get(i);labels.add(voice.getLocale().toLanguageTag()+" · "+voice.getName()+(voice.isNetworkConnectionRequired()?"（需联网）":"（无需联网）"));if(voice.getName().equals(saved))selected=i+1;}
        voices.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));voices.setSelection(selected);
        status.setText(!saved.isEmpty()&&selected==0?"已选声音不可用，当前回退到系统默认；保存将更新选择。":"声音由系统语音引擎提供，联网声音可能不可用。");
    }
    private void preview(){if(!ready||engine==null){toast("英文语音尚不可用");return;}int index=voices.getSelectedItemPosition();String name=index>0&&index<=choices.size()?choices.get(index-1).getName():"";if(configure(engine,name,rate.getSelectedItemPosition()))engine.speak("remember",TextToSpeech.QUEUE_FLUSH,null,"preview");}
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();}
}
