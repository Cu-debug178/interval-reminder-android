package com.example.reviewreminder;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public final class VoiceSettingsActivity extends ScreenActivity {
    private static final int PICK_AUDIO=41;
    private WordUi ui;private SpeechManager speech;private Spinner voice,rate;private EditText key,base,model,previewText;private final ArrayList<Option> options=new ArrayList<>();
    private static final class Option{String provider,id,label;Option(String provider,String id,String label){this.provider=provider;this.id=id;this.label=label;}}

    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);speech=new SpeechManager(this);show();}
    private void show(){
        ui.start("朗读声音与语速");ui.body.addView(ui.text("单词、例句和答后朗读共用这里的默认音色。系统音色可离线使用。",13,WordUi.MUTED));ui.gap(ui.body,16);
        voice=new Spinner(this);voice.setMinimumHeight(ui.dp(52));buildOptions();ui.body.addView(voice);ui.gap(ui.body,8);ui.body.addView(ui.ghostButton("选择系统英文声音",v->SpeechSettings.show(this)));ui.gap(ui.body,12);
        rate=new Spinner(this);rate.setMinimumHeight(ui.dp(52));rate.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SpeechSettings.RATE_LABELS));rate.setSelection(SpeechSettings.rateIndex(Store.prefs(this).getInt("speechRate",2)));ui.body.addView(rate);ui.gap(ui.body,16);
        ui.body.addView(ui.sectionLabel("MiMo 语音服务"));key=field("API Key（独立加密保存，留空保留）","",true);base=field("Base URL",VoiceStore.baseUrl(this),false);model=field("预置音色模型",VoiceStore.model(this),false);ui.gap(ui.body,8);
        previewText=field("英文试听文本","Learning a little every day makes a difference.",false,2);ui.body.addView(ui.primaryButton("试听并保存为默认",v->{if(save())speech.speak(previewText.getText().toString(),"preview");}));ui.gap(ui.body,8);ui.body.addView(ui.secondaryButton("保存设置",v->{if(save())ui.toast("朗读设置已保存");}));ui.gap(ui.body,16);
        ui.body.addView(ui.secondaryButton("添加自定义克隆音色",v->pickAudio()));ui.gap(ui.body,8);ui.body.addView(ui.text("仅导入你拥有使用授权的 MP3 / WAV；Base64 编码后不得超过 10 MB。参考音频会随请求上传至小米 MiMo，不会在服务端形成永久音色 ID。",12,WordUi.MUTED));ui.gap(ui.body,16);profiles();ui.gap(ui.body,16);
        ui.body.addView(ui.ghostButton("清空语音缓存（"+String.format(java.util.Locale.CHINA,"%.1f",SpeechManager.cacheSize(this)/1024f/1024f)+" MB）",v->{long freed=SpeechManager.clearCache(this);ui.toast("已释放 "+String.format(java.util.Locale.CHINA,"%.1f",freed/1024f/1024f)+" MB");show();}));ui.gap(ui.body,8);ui.body.addView(ui.dangerTextButton("清除 MiMo 语音 Key",v->{AiSecrets.clearVoice(this);key.setText("");ui.toast("语音 Key 已清除");}));
    }

    private EditText field(String label,String value,boolean password){return field(label,value,password,1);}
    private EditText field(String label,String value,boolean password,int lines){
        ui.body.addView(ui.text(label,12,WordUi.MUTED));ui.gap(ui.body,4);
        EditText e=new EditText(this);e.setText(value);e.setTextSize(15);e.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));e.setBackground(Theme.shape(this,Theme.SURFACE,Theme.RADIUS_BUTTON,Theme.BORDER,1));if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(lines>1){e.setSingleLine(false);e.setMinLines(lines);e.setMaxLines(lines);e.setGravity(android.view.Gravity.TOP|android.view.Gravity.START);}else e.setSingleLine(true);
        ui.body.addView(e,new LinearLayout.LayoutParams(-1,ui.dp(lines>1?76:52)));ui.gap(ui.body,8);return e;
    }
    private void buildOptions(){
        options.clear();options.add(new Option(VoiceStore.SYSTEM,"","系统英文声音"));options.add(new Option(VoiceStore.MIMO,"Mia","MiMo · Mia（英文女声）"));options.add(new Option(VoiceStore.MIMO,"Chloe","MiMo · Chloe（英文女声）"));options.add(new Option(VoiceStore.MIMO,"Milo","MiMo · Milo（英文男声）"));options.add(new Option(VoiceStore.MIMO,"Dean","MiMo · Dean（英文男声）"));JSONArray profiles=VoiceStore.profiles(this);for(int i=0;i<profiles.length();i++){JSONObject p=profiles.optJSONObject(i);if(p!=null)options.add(new Option(VoiceStore.MIMO,p.optString("id"),"克隆 · "+p.optString("name")));}
        ArrayList<String> labels=new ArrayList<>();int selected=0;for(int i=0;i<options.size();i++){Option o=options.get(i);labels.add(o.label);if(o.provider.equals(VoiceStore.provider(this))&&(VoiceStore.SYSTEM.equals(o.provider)||o.id.equals(VoiceStore.voiceId(this))))selected=i;}voice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));voice.setSelection(selected);
    }
    private boolean save(){Option o=options.get(voice.getSelectedItemPosition());boolean ok=VoiceStore.saveSelection(this,o.provider,o.id,rate.getSelectedItemPosition(),base.getText().toString(),model.getText().toString(),key.getText().toString());if(!ok)ui.toast("设置未能保存");return ok;}
    private void profiles(){JSONArray all=VoiceStore.profiles(this);if(all.length()==0)return;ui.body.addView(ui.sectionLabel("我的克隆音色"));for(int i=0;i<all.length();i++){JSONObject p=all.optJSONObject(i);if(p==null)continue;LinearLayout card=ui.card();card.addView(ui.text(p.optString("name"),16,WordUi.INK));card.addView(ui.text(p.optString("mime"),12,WordUi.MUTED));ui.gap(card,8);LinearLayout row=ui.row();row.addView(ui.secondaryButton("试听",v->{select(p);speech.speak(previewText.getText().toString(),"preview");}),new LinearLayout.LayoutParams(0,ui.dp(48),1));LinearLayout.LayoutParams deleteLp=new LinearLayout.LayoutParams(0,ui.dp(48),1);deleteLp.leftMargin=ui.dp(8);row.addView(ui.ghostButton("删除",v->confirmDelete(p)),deleteLp);card.addView(row);ui.body.addView(card);ui.gap(ui.body,8);}}
    private void select(JSONObject p){Store.prefs(this).edit().putString("voiceProvider",VoiceStore.MIMO).putString("voiceId",p.optString("id")).apply();buildOptions();}
    private void confirmDelete(JSONObject p){Sheet.confirm(this,"删除克隆音色？","将删除本机参考音频和配置，不影响已经生成的缓存。","取消","删除",true,()->{try{VoiceStore.delete(this,p.optString("id"));show();}catch(Exception e){ui.toast(e.getMessage());}});}
    private void pickAudio(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");startActivityForResult(intent,PICK_AUDIO);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK_AUDIO||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();LinearLayout form=ui.col();EditText name=ui.input("音色名称","");name.setSingleLine(true);form.addView(name);CheckBox authorized=ui.check("我确认拥有此参考音频的使用与克隆授权",false);form.addView(authorized,WordUi.lp());new Sheet(this).title("添加自定义音色").message("仅导入你拥有使用授权的音频。").content(form).secondary("取消",null).primaryIf("导入",()->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("请输入音色名称");name.requestFocus();return false;}if(!authorized.isChecked()){ui.toast("请先确认音频授权");return false;}try{String mime=getContentResolver().getType(uri);if(mime==null){String path=uri.toString().toLowerCase(java.util.Locale.US);mime=path.endsWith(".wav")?"audio/wav":"audio/mpeg";}VoiceStore.importAudio(this,uri,value,mime);ui.toast("音色已保存，可试听并设为默认");show();return true;}catch(Exception e){ui.toast(e.getMessage());return false;}}).show();}
    @Override protected void onPause(){speech.stop();super.onPause();}
    @Override protected void onDestroy(){speech.shutdown();super.onDestroy();}
}
