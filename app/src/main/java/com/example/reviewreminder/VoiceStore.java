package com.example.reviewreminder;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

final class VoiceStore {
    static final String SYSTEM="system",MIMO="mimo";
    /** Built-in MiMo English voices. They are not stored as cloned profiles. */
    private static final String[] PRESETS={"Mia","Chloe","Milo","Dean"};
    static final int MAX_BASE64_BYTES=10*1024*1024;
    private VoiceStore(){}

    static JSONArray profiles(Context c){try{return new JSONArray(Store.prefs(c).getString("voiceProfiles","[]"));}catch(Exception e){return new JSONArray();}}
    static JSONObject profile(Context c,String id){JSONArray all=profiles(c);for(int i=0;i<all.length();i++){JSONObject p=all.optJSONObject(i);if(p!=null&&p.optString("id").equals(id))return p;}return null;}
    static String provider(Context c){return Store.prefs(c).getString("voiceProvider",SYSTEM);}
    static String voiceId(Context c){return Store.prefs(c).getString("voiceId","");}
    static boolean preset(String voiceId){if(voiceId==null)return false;for(String name:PRESETS)if(name.equals(voiceId))return true;return false;}
    static String baseUrl(Context c){String value=Store.prefs(c).getString("mimoSpeechBase",AiSettings.MIMO_URL).trim();return value.isEmpty()?AiSettings.MIMO_URL:value;}
    static String model(Context c){String value=Store.prefs(c).getString("mimoSpeechModel","mimo-v2.5-tts").trim();return value.isEmpty()?"mimo-v2.5-tts":value;}
    static String speechKey(Context c){
        String key=AiSecrets.readVoiceApiKey(c);
        if(!key.isEmpty())return key;
        return AiSettings.provider(c)==AiSettings.PROVIDER_MIMO?AiSettings.apiKey(c):"";
    }

    static boolean saveSelection(Context c,String provider,String voiceId,int rate,String baseUrl,String model,String keyOrBlank){
        if(!Store.prefs(c).edit().putString("voiceProvider",provider).putString("voiceId",voiceId==null?"":voiceId).putInt("speechRate",SpeechSettings.rateIndex(rate)).putString("mimoSpeechBase",baseUrl==null?AiSettings.MIMO_URL:baseUrl.trim()).putString("mimoSpeechModel",model==null?"mimo-v2.5-tts":model.trim()).commit())return false;
        if(keyOrBlank!=null&&!keyOrBlank.trim().isEmpty())try{AiSecrets.writeVoiceApiKey(c,keyOrBlank.trim());}catch(Exception e){return false;}return true;
    }

    static JSONObject importAudio(Context c,Uri uri,String displayName,String mime)throws Exception{
        String normalized=mime==null?"":mime.toLowerCase(java.util.Locale.US);if(normalized.contains("wav"))normalized="audio/wav";else if(normalized.contains("mpeg")||normalized.contains("mp3"))normalized="audio/mpeg";else throw new IllegalArgumentException("仅支持 MP3 或 WAV 参考音频");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null)throw new IllegalArgumentException("无法读取参考音频");byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1){total+=n;if(4*((total+2)/3)>MAX_BASE64_BYTES)throw new IllegalArgumentException("参考音频 Base64 编码后不能超过 10 MB");bytes.write(buffer,0,n);}}
        byte[] data=bytes.toByteArray();if(data.length<12)throw new IllegalArgumentException("参考音频内容无效");
        String id=UUID.randomUUID().toString(),extension="audio/wav".equals(normalized)?".wav":".mp3";File dir=new File(c.getFilesDir(),"voices");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("无法创建音色目录");File target=new File(dir,id+extension);try(FileOutputStream out=new FileOutputStream(target)){out.write(data);out.getFD().sync();}
        JSONObject profile=new JSONObject().put("id",id).put("name",displayName.trim()).put("path",target.getAbsolutePath()).put("mime",normalized).put("version",System.currentTimeMillis()).put("created",System.currentTimeMillis());JSONArray all=profiles(c);all.put(profile);if(!Store.prefs(c).edit().putString("voiceProfiles",all.toString()).commit()){target.delete();throw new IllegalStateException("音色配置未能保存");}return profile;
    }

    static byte[] sample(Context c,JSONObject profile)throws Exception{
        File file=new File(profile.getString("path"));if(!file.isFile())throw new IllegalStateException("参考音频已丢失，请重新导入");try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);byte[] data=out.toByteArray();if(4*((data.length+2)/3)>MAX_BASE64_BYTES)throw new IllegalStateException("参考音频超过 10 MB 限制");return data;}
    }

    static void delete(Context c,String id)throws Exception{
        JSONArray old=profiles(c),next=new JSONArray();String path="";for(int i=0;i<old.length();i++){JSONObject p=old.optJSONObject(i);if(p==null)continue;if(id.equals(p.optString("id")))path=p.optString("path");else next.put(p);}boolean selected=id.equals(voiceId(c));android.content.SharedPreferences.Editor edit=Store.prefs(c).edit().putString("voiceProfiles",next.toString());if(selected)edit.putString("voiceProvider",SYSTEM).putString("voiceId","");if(!edit.commit())throw new IllegalStateException("音色删除失败");if(!path.isEmpty())new File(path).delete();
    }
}
