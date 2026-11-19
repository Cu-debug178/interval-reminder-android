package com.example.reviewreminder;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One generation-aware speech path for system TTS, MiMo presets and cloned voices. */
final class SpeechManager {
    private static final ExecutorService WORKER=Executors.newCachedThreadPool();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final long CACHE_LIMIT=64*1024*1024;
    private final Context app;
    private TextToSpeech tts;private MediaPlayer player;private Future<?> pending;
    private boolean ttsReady,ttsStarting,active=true;private int generation;private String pendingText,currentText,pendingSystemVoice="",speakProvider=VoiceStore.SYSTEM;private int pendingGeneration,pendingRate=2,speakRate=2;

    SpeechManager(Context context){app=context.getApplicationContext();}

    void speak(String text,String context){speak(text,context,null,null,-1);}

    /**
     * Review playback uses the saved voice. A preview may pass a provider, voice id and rate index
     * that have not been saved yet; null / -1 keep the saved selection.
     */
    void speak(String text,String context,String providerOverride,String voiceOverride,int rateOverride){
        final String value=text==null?"":text.trim();if(value.isEmpty()||!active)return;
        stopCurrent();final int ticket=++generation;currentText=value;
        final String provider=providerOverride!=null?providerOverride:VoiceStore.provider(app);
        final String voiceId=providerOverride!=null?(voiceOverride==null?"":voiceOverride):VoiceStore.voiceId(app);
        final int rateIndex=rateOverride>=0?SpeechSettings.rateIndex(rateOverride):Store.prefs(app).getInt("speechRate",2);
        speakProvider=provider;speakRate=rateIndex;
        JSONObject profile=VoiceStore.MIMO.equals(provider)?VoiceStore.profile(app,voiceId):null;
        if(VoiceStore.MIMO.equals(provider)){
            File cached=cacheFile(app,provider,voiceId,profile,value,context,rateIndex);
            if(cached.isFile()){playFile(cached,ticket);return;}
            if(VoiceStore.speechKey(app).isEmpty()){toast("未配置 MiMo 语音 Key，已回退到系统英文声音");systemSpeak(value,ticket,provider,rateIndex);return;}
            toast("正在生成语音…");pending=WORKER.submit(()->{
                try{byte[] audio=MimoSpeechClient.synthesize(app,value,voiceId,profile,SpeechSettings.rate(rateIndex));writeCache(cached,audio);MAIN.post(()->{if(valid(ticket))playFile(cached,ticket);});}
                catch(Exception e){MAIN.post(()->{if(valid(ticket)){toast((e.getMessage()==null?"语音生成失败":e.getMessage())+"，已回退到系统英文声音");systemSpeak(value,ticket,provider,rateIndex);}});}
            });
        }else systemSpeak(value,ticket,provider,rateIndex);
    }

    private void systemSpeak(String text,int ticket,String provider,int rateIndex){
        if(!valid(ticket))return;
        String systemVoice=VoiceStore.SYSTEM.equals(provider)?Store.prefs(app).getString("speechVoice",""):"";
        if(ttsReady&&tts!=null){
            if(SpeechSettings.configure(tts,systemVoice,rateIndex))tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"learning-"+ticket);
            else toast("设备的英文朗读不可用");
            return;
        }
        pendingText=text;pendingGeneration=ticket;pendingRate=rateIndex;pendingSystemVoice=systemVoice;
        if(tts==null){
            ttsStarting=true;
            tts=new TextToSpeech(app,status->MAIN.post(()->{
                ttsStarting=false;
                if(!active||tts==null)return;
                ttsReady=status==TextToSpeech.SUCCESS&&SpeechSettings.configure(tts,pendingSystemVoice,pendingRate);
                if(ttsReady&&pendingGeneration==generation&&pendingText!=null){String value=pendingText;pendingText=null;tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,"learning-"+generation);}
                else if(!ttsReady&&pendingGeneration==generation)toast("设备的英文朗读不可用");
            }));
        }else if(!ttsStarting)toast("英文朗读尚未就绪或当前设备不可用");
    }

    private void playFile(File file,int ticket){
        if(!valid(ticket))return;releasePlayer();player=new MediaPlayer();
        try{player.setDataSource(file.getAbsolutePath());player.setOnPreparedListener(mp->{if(valid(ticket)&&player==mp)mp.start();});player.setOnCompletionListener(mp->{if(player==mp)releasePlayer();});player.setOnErrorListener((mp,what,extra)->{if(player==mp){releasePlayer();file.delete();if(valid(ticket))systemSpeak(currentText,ticket,speakProvider,speakRate);}return true;});player.prepareAsync();}
        catch(Exception e){releasePlayer();file.delete();if(valid(ticket)){toast("音频缓存无法播放，已回退到系统声音");systemSpeak(currentText,ticket,speakProvider,speakRate);}}
    }

    void stop(){if(!active)return;generation++;stopCurrent();}
    private void stopCurrent(){pendingText=null;if(pending!=null){pending.cancel(true);pending=null;}releasePlayer();if(tts!=null)tts.stop();}
    void shutdown(){active=false;generation++;stopCurrent();if(tts!=null){tts.shutdown();tts=null;}ttsReady=false;}
    private boolean valid(int ticket){return active&&ticket==generation;}
    private void releasePlayer(){if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}}
    private void toast(String text){MAIN.post(()->Toast.makeText(app,text,Toast.LENGTH_LONG).show());}

    static long clearCache(Context c){long freed=0;File[] files=cacheDir(c).listFiles();if(files!=null)for(File file:files)if(file.isFile()){freed+=file.length();file.delete();}return freed;}
    static long cacheSize(Context c){long size=0;File[] files=cacheDir(c).listFiles();if(files!=null)for(File file:files)if(file.isFile())size+=file.length();return size;}
    private static File cacheDir(Context c){File dir=new File(c.getCacheDir(),"speech");if(!dir.exists())dir.mkdirs();return dir;}
    private static File cacheFile(Context c,String provider,String voiceId,JSONObject profile,String text,String context,int rateIndex){
        String version=profile==null?"":profile.optString("version");String model=profile==null?VoiceStore.model(c):"mimo-v2.5-tts-voiceclone";String key=provider+"|"+model+"|"+voiceId+"|"+version+"|"+text+"|en-US|"+SpeechSettings.rate(rateIndex)+"|"+context;
        return new File(cacheDir(c),sha256(key)+".wav");
    }
    private static void writeCache(File target,byte[] audio)throws Exception{File tmp=new File(target.getParentFile(),target.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(audio);out.getFD().sync();}if(!tmp.renameTo(target)){tmp.delete();throw new IllegalStateException("语音缓存写入失败");}trimCache(target.getParentFile());}
    private static void trimCache(File dir){File[] files=dir.listFiles();if(files==null)return;Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));long total=0;for(File file:files)total+=file.length();for(File file:files){if(total<=CACHE_LIMIT)break;if(file.isFile()){long size=file.length();if(file.delete())total-=size;}}}
    private static String sha256(String value){try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=digest.digest(value.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b));return out.toString();}catch(Exception e){return Integer.toHexString(value.hashCode());}}
}
