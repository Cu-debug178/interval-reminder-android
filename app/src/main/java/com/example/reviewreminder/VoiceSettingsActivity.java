package com.example.reviewreminder;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/**
 * System TTS, MiMo presets and cloned reference voices share one page.
 * Cloned audio stays on device and is sent only when that voice is synthesized.
 */
public final class VoiceSettingsActivity extends ScreenActivity {
    private static final int PICK_AUDIO=41;
    private static final String[] RATE_SHORT={"0.6×","0.75×","0.85×","1.0×","1.15×"};
    private WordUi ui;
    private SpeechManager speech;
    private Segmented rate;
    private TextView rateCaption;
    private EditText key,base,model,previewText;
    private int voiceIndex;
    private final ArrayList<Option> options=new ArrayList<>();
    private static final class Option {
        final String provider,id,label,detail;
        Option(String provider,String id,String label,String detail){this.provider=provider;this.id=id;this.label=label;this.detail=detail;}
    }

    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);speech=new SpeechManager(this);show();}

    private void show(){
        ui.start("朗读声音与语速");
        ui.note(ui.body,"单词、例句和复习朗读共用这里的音色。系统声音可以离线使用；MiMo 与克隆音色需要语音 Key。");
        buildOptions();
        Option current=options.get(voiceIndex);
        ui.section(ui.body,"默认音色",null);
        LinearLayout voice=ui.group();
        ui.item(voice,R.drawable.ic_volume_2,"当前音色",current.detail,current.label,true,v->pickVoice());
        if(VoiceStore.SYSTEM.equals(current.provider)){
            ui.item(voice,0,"系统英文声音","从这台设备已安装的语音里选",null,true,v->SpeechSettings.show(this));
        }
        ui.body.addView(voice,WordUi.lp());
        ui.section(ui.body,"朗读速度",null);
        int savedRate=SpeechSettings.rateIndex(Store.prefs(this).getInt("speechRate",2));
        rate=ui.segmented(RATE_SHORT,savedRate,index->rateCaption.setText(SpeechSettings.RATE_LABELS[SpeechSettings.rateIndex(index)]));
        ui.body.addView(rate,WordUi.lp());
        rateCaption=ui.type(SpeechSettings.RATE_LABELS[savedRate],Theme.T_CAPTION);
        rateCaption.setPadding(ui.dp(Theme.SPACE_4),ui.dp(Theme.SPACE_8),ui.dp(Theme.SPACE_4),0);
        ui.body.addView(rateCaption,WordUi.lp());

        ui.section(ui.body,"我的克隆音色",null);
        JSONArray profiles=VoiceStore.profiles(this);
        if(profiles.length()==0){
            ui.body.addView(ui.empty(R.drawable.ic_user_round,"还没有克隆音色","导入一段你有权使用的 MP3 或 WAV。参考音频只存在这台设备上，朗读时才会随请求发给 MiMo。",null,null));
        }else{
            LinearLayout list=ui.group();
            for(int i=0;i<profiles.length();i++){
                JSONObject profile=profiles.optJSONObject(i);
                if(profile==null)continue;
                final JSONObject item=profile;
                boolean selected=VoiceStore.MIMO.equals(VoiceStore.provider(this))&&profile.optString("id").equals(VoiceStore.voiceId(this));
                ui.item(list,R.drawable.ic_user_round,profile.optString("name"),profile.optString("mime")+(selected?" · 当前默认":""),null,true,v->profileActions(item));
            }
            ui.body.addView(list,WordUi.lp());
        }
        ui.gap(ui.body,Theme.SPACE_12);
        ui.body.addView(ui.secondaryButton("添加克隆音色",v->pickAudio()));
        ui.note(ui.body,"仅导入你拥有使用授权的音频，Base64 后不超过 10 MB。MiMo 不会把这段音频保存成永久音色 ID。");

        ui.section(ui.body,"MiMo 语音服务",null);
        key=ui.field("API Key（加密保存在本机，留空则保留）","",1);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);
        base=ui.field("Base URL",VoiceStore.baseUrl(this),1);
        base.setSingleLine(true);
        model=ui.field("预置音色模型",VoiceStore.model(this),1);
        model.setSingleLine(true);
        previewText=ui.field("英文试听文本","Learning a little every day makes a difference.",2);
        ui.note(ui.body,"预置音色是 Mia、Chloe、Milo、Dean。克隆音色使用 mimo-v2.5-tts-voiceclone，并带上本机参考音频。");

        ui.gap(ui.body,Theme.SPACE_8);
        ui.body.addView(ui.textButton("清空语音缓存（"+String.format(java.util.Locale.CHINA,"%.1f",SpeechManager.cacheSize(this)/1024f/1024f)+" MB）",v->{
            long freed=SpeechManager.clearCache(this);
            ui.toast("已释放 "+String.format(java.util.Locale.CHINA,"%.1f",freed/1024f/1024f)+" MB");
            show();
        }));
        ui.body.addView(ui.dangerTextButton("清除 MiMo 语音 Key",v->{AiSecrets.clearVoice(this);key.setText("");ui.toast("语音 Key 已清除");}));
        ui.pinPair(ui.secondaryButton("试听",v->preview()),ui.primaryButton("保存",v->{if(save())ui.toast("朗读设置已保存");}));
    }

    private void buildOptions(){
        options.clear();
        options.add(new Option(VoiceStore.SYSTEM,"","系统英文声音","离线，使用系统文字转语音"));
        options.add(new Option(VoiceStore.MIMO,"Mia","Mia","MiMo 英文女声"));
        options.add(new Option(VoiceStore.MIMO,"Chloe","Chloe","MiMo 英文女声"));
        options.add(new Option(VoiceStore.MIMO,"Milo","Milo","MiMo 英文男声"));
        options.add(new Option(VoiceStore.MIMO,"Dean","Dean","MiMo 英文男声"));
        JSONArray profiles=VoiceStore.profiles(this);
        for(int i=0;i<profiles.length();i++){
            JSONObject profile=profiles.optJSONObject(i);
            if(profile!=null)options.add(new Option(VoiceStore.MIMO,profile.optString("id"),profile.optString("name"),"克隆音色 · "+profile.optString("mime")));
        }
        voiceIndex=0;
        String provider=VoiceStore.provider(this),id=VoiceStore.voiceId(this);
        for(int i=0;i<options.size();i++){
            Option option=options.get(i);
            if(option.provider.equals(provider)&&(VoiceStore.SYSTEM.equals(option.provider)||option.id.equals(id)))voiceIndex=i;
        }
    }

    private void pickVoice(){
        String[] labels=new String[options.size()];
        for(int i=0;i<options.size();i++)labels[i]=options.get(i).label+" · "+options.get(i).detail;
        Sheet.pick(this,"默认音色",labels,voiceIndex,index->{
            voiceIndex=index;
            Option option=options.get(index);
            int rateIndex=rate==null?Store.prefs(this).getInt("speechRate",2):rate.getSelectedItemPosition();
            String baseUrl=base==null?VoiceStore.baseUrl(this):base.getText().toString();
            String modelName=model==null?VoiceStore.model(this):model.getText().toString();
            String keyText=key==null?"":key.getText().toString();
            if(!VoiceStore.saveSelection(this,option.provider,option.id,rateIndex,baseUrl,modelName,keyText)){ui.error("音色未能保存");return;}
            ui.toast("已改为「"+option.label+"」");
            show();
        });
    }

    private void profileActions(JSONObject profile){
        new Sheet(this).title(profile.optString("name")).message(profile.optString("mime"))
            .item(R.drawable.ic_volume_2,"试听",()->previewProfile(profile))
            .item(R.drawable.ic_check,"设为默认",()->{
                selectOption(VoiceStore.MIMO,profile.optString("id"));
                if(save()){ui.toast("已设为默认音色");show();}
            })
            .dangerItem(R.drawable.ic_trash_2,"删除",()->confirmDelete(profile))
            .secondary("关闭",null).show();
    }

    private void selectOption(String provider,String id){
        for(int i=0;i<options.size();i++){
            Option option=options.get(i);
            if(option.provider.equals(provider)&&option.id.equals(id==null?"":id)){voiceIndex=i;return;}
        }
    }

    private void preview(){
        if(!save())return;
        Option option=options.get(voiceIndex);
        speech.speak(previewText.getText().toString(),"preview",option.provider,option.id,rate.getSelectedItemPosition());
    }

    private void previewProfile(JSONObject profile){
        speech.speak(previewText==null?"Learning a little every day makes a difference.":previewText.getText().toString(),"preview",VoiceStore.MIMO,profile.optString("id"),rate==null?Store.prefs(this).getInt("speechRate",2):rate.getSelectedItemPosition());
    }

    private boolean save(){
        Option option=options.get(Math.max(0,Math.min(voiceIndex,options.size()-1)));
        boolean ok=VoiceStore.saveSelection(this,option.provider,option.id,rate.getSelectedItemPosition(),base.getText().toString(),model.getText().toString(),key.getText().toString());
        if(!ok)ui.error("设置未能保存");
        else key.setText("");
        return ok;
    }

    private void confirmDelete(JSONObject profile){
        Sheet.confirm(this,"删除克隆音色？","将删除本机参考音频和配置。已经生成的缓存还在，可以另行清空。","取消","删除",true,()->{
            try{VoiceStore.delete(this,profile.optString("id"));show();}catch(Exception e){ui.error(e.getMessage());}
        });
    }

    private void pickAudio(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        try{startActivityForResult(intent,PICK_AUDIO);}catch(Exception e){ui.error("没有可用的音频选择器");}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_AUDIO||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        LinearLayout form=ui.col();
        EditText name=ui.input("音色名称","");
        name.setSingleLine(true);
        form.addView(name);
        CheckBox authorized=ui.check("我确认拥有此参考音频的使用与克隆授权",false);
        form.addView(authorized,WordUi.lp());
        new Sheet(this).title("添加克隆音色").message("只导入你有权使用的 MP3 或 WAV。").content(form)
            .secondary("取消",null)
            .primaryIf("导入",()->{
                String value=name.getText().toString().trim();
                if(value.isEmpty()){name.setError("请输入音色名称");name.requestFocus();return false;}
                if(!authorized.isChecked()){ui.toast("请先确认音频授权");return false;}
                try{
                    String mime=getContentResolver().getType(uri);
                    if(mime==null){
                        String path=uri.toString().toLowerCase(java.util.Locale.US);
                        mime=path.endsWith(".wav")?"audio/wav":"audio/mpeg";
                    }
                    VoiceStore.importAudio(this,uri,value,mime);
                    ui.toast("音色已保存，可试听并设为默认");
                    show();
                    return true;
                }catch(Exception e){ui.error(e.getMessage()==null?"导入失败":e.getMessage());return false;}
            }).show();
    }

    @Override protected void onPause(){speech.stop();super.onPause();}
    @Override protected void onDestroy(){speech.shutdown();super.onDestroy();}
}
