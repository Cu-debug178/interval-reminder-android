package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** Voice profiles stored in the normalized SQLite {@code voice_profile} table. */
final class VoiceStore {
    static final String SYSTEM="system",MIMO="mimo";
    /** Built-in MiMo English voices. They are not stored as cloned profiles. */
    private static final String[] PRESETS={"Mia","Chloe","Milo","Dean"};
    static final int MAX_BASE64_BYTES=10*1024*1024;
    private VoiceStore(){}

    static JSONArray profiles(Context c){
        try{
            return LocalStore.inRead(c,()->{
                JSONArray out=new JSONArray();
                Cursor cur=LocalStore.database().rawQuery("SELECT id,name,path,mime,version,created FROM voice_profile ORDER BY created ASC",null);
                try{
                    while(cur.moveToNext()){
                        out.put(new JSONObject().put("id",cur.getString(0)).put("name",cur.getString(1)).put("path",cur.getString(2)).put("mime",cur.getString(3)).put("version",cur.getLong(4)).put("created",cur.getLong(5)));
                    }
                }finally{cur.close();}
                return out;
            });
        }catch(Exception e){return new JSONArray();}
    }

    static JSONObject profile(Context c,String id){
        if(id==null||id.isEmpty())return null;
        try{
            return LocalStore.inRead(c,()->{
                Cursor cur=LocalStore.database().rawQuery("SELECT id,name,path,mime,version,created FROM voice_profile WHERE id=?",new String[]{id});
                try{
                    if(!cur.moveToFirst())return null;
                    return new JSONObject().put("id",cur.getString(0)).put("name",cur.getString(1)).put("path",cur.getString(2)).put("mime",cur.getString(3)).put("version",cur.getLong(4)).put("created",cur.getLong(5));
                }finally{cur.close();}
            });
        }catch(Exception e){return null;}
    }

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
        JSONObject profile=new JSONObject().put("id",id).put("name",displayName.trim()).put("path",target.getAbsolutePath()).put("mime",normalized).put("version",System.currentTimeMillis()).put("created",System.currentTimeMillis());
        try{LocalStore.inTransaction(c,()->LocalStore.upsertVoiceProfileLocked(LocalStore.database(),profile));}
        catch(Exception e){target.delete();throw new IllegalStateException("音色配置未能保存",e);}
        return profile;
    }

    static byte[] sample(Context c,JSONObject profile)throws Exception{
        File file=new File(profile.getString("path"));if(!file.isFile())throw new IllegalStateException("参考音频已丢失，请重新导入");try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);byte[] data=out.toByteArray();if(4*((data.length+2)/3)>MAX_BASE64_BYTES)throw new IllegalArgumentException("参考音频超过 10 MB 限制");return data;}
    }

    static void delete(Context c,String id)throws Exception{
        JSONObject existing=profile(c,id);
        String path=existing==null?"":existing.optString("path");
        boolean selected=id.equals(voiceId(c));
        LocalStore.inTransaction(c,()->{
            LocalStore.database().delete("voice_profile","id=?",new String[]{id});
            if(selected&&!Store.prefs(c).edit().putString("voiceProvider",SYSTEM).putString("voiceId","").commit()){
                throw new IllegalStateException("音色删除失败");
            }
        });
        if(!path.isEmpty())new File(path).delete();
    }
}
