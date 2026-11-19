package com.example.reviewreminder;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Focused device tests for versioned backup and connection-image storage. */
public final class P2SmokeTest extends Instrumentation {
    private File output;private final StringBuilder log=new StringBuilder();
    @Override public void onCreate(Bundle state){super.onCreate(state);start();}
    @Override public void onStart(){
        output=new File(getTargetContext().getExternalFilesDir(null),"qa");output.mkdirs();
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{runTests();line("ALL PASSED");}catch(Throwable e){code=Activity.RESULT_CANCELED;try{java.io.StringWriter trace=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(trace));line("FAIL "+trace);}catch(Exception ignored){}}
        result.putString("stream",log.toString());finish(code,result);
    }
    private void line(String message)throws Exception{log.append(message).append('\n');File file=new File(output,"p2-results.txt");try(OutputStream out=new FileOutputStream(file)){out.write(log.toString().getBytes("UTF-8"));}android.util.Log.i("ReviewQA",file.getAbsolutePath());}
    private void check(boolean ok,String message)throws Exception{if(!ok)throw new AssertionError(message);line("PASS "+message);}
    private void runTests()throws Exception{
        Context c=getTargetContext();line("START P2 device QA");clean(c);
        Bitmap bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(44,93,78));
        String image=ConnectionImages.saveBitmap(c,bitmap);bitmap.recycle();
        check(ConnectionImages.exists(c,image),"connection image is saved privately");check(ConnectionImages.decode(c,image,400).getWidth()>0,"connection image decodes");check(image.startsWith("ci_")&&image.endsWith(".jpg"),"connection image receives a generated name");
        check(!ConnectionImages.exists(c,"../outside.jpg"),"connection image rejects path traversal");check(ConnectionImages.file(c,"sub/evil.jpg")==null,"connection image rejects nested paths");
        check(ConnectionImages.base64(c,image).length()>0,"connection image can be encoded for backup");ConnectionImages.delete(c,image);check(!ConnectionImages.exists(c,image),"connection image deletion removes the file");
        clean(c);testMechanical(c);
        clean(c);Bitmap restoredImageBitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);restoredImageBitmap.eraseColor(Color.rgb(44,93,78));image=ConnectionImages.saveBitmap(c,restoredImageBitmap);restoredImageBitmap.recycle();JSONObject fixture=fixture(c,image);seedVoice(c);
        JSONObject withoutImages=BackupManager.exportData(c,false,false);
        check(withoutImages.getInt("version")==BackupManager.VERSION,"backup declares schema version 2");check(withoutImages.getJSONObject("images").length()==0,"connection images are omitted when unchecked");
        check(!containsSecret(withoutImages),"export recursively removes API keys and secret-like fields");check(!withoutImages.toString().contains("do-not-export"),"injected secret value is absent from export");
        JSONObject withImages=BackupManager.exportData(c,false,true);
        check(withImages.getJSONObject("images").length()==1&&withImages.getJSONObject("images").has(image),"connection images are included when selected");
        BackupManager.Backup parsed=BackupManager.inspect(withImages.toString());
        check(parsed.tasks.length()==1&&parsed.wrongStates.length()==1,"inspect accepts a valid backup and counts data");

        clean(c);BackupManager.Result restored=BackupManager.apply(c,parsed,true);
        check(Store.tasks(c).length()==1,"replace restores the plan");check(!containsSecret(Store.tasks(c)),"restored data remains free of secret fields");
        JSONObject restoredWord=Store.tasks(c).getJSONObject(0).getJSONArray("words").getJSONObject(0);
        check(restoredWord.optString("connectionImage").equals(image),"restored word keeps its connection image reference");check(ConnectionImages.exists(c,image),"replace recreates connection image bytes");
        check(Store.prefs(c).getString("wrongWordStates","{}").contains("成对"),"replace restores wrong-word state");check(Store.prefs(c).getString("trainingSelectionOrder","[]").contains("selection-1"),"replace restores selected-words order");
        check(Store.prefs(c).getString("spellingActive:session-1","").contains("selection-1"),"replace restores resumable spelling session");
        check(Store.prefs(c).getString("translationExercises","[]").contains("练习"),"replace restores translation exercises");
        check(restored.tasksAdded==1&&restored.images==1,"replace reports task and image counts");

        seedVoice(c);JSONObject withAudio=BackupManager.exportData(c,true,true);
        check(withAudio.getJSONObject("audio").has("voice-test"),"reference audio is exported only when checked");
        clean(c);BackupManager.Backup audioBackup=BackupManager.inspect(withAudio.toString());
        BackupManager.apply(c,audioBackup,true);
        JSONObject voice=VoiceStore.profile(c,"voice-test");check(voice!=null,"referenced voice profile returns without a stale absolute path");check(voice!=null&&new File(voice.optString("path")).isFile(),"reference audio file is recreated in private storage");check(VoiceStore.voiceId(c).equals("voice-test"),"voice selection is restored when its profile exists");

        clean(c);
        JSONObject current=new JSONObject().put("id",7L).put("title","当前计划").put("created",10L).put("done",true).put("words",new JSONArray().put(new JSONObject().put("english","local").put("chinese","本地").put("example","")));
        Store.save(c,new JSONArray().put(current));
        JSONObject incoming=new JSONObject().put("id",7L).put("title","备份计划").put("created",20L).put("done",true).put("words",new JSONArray().put(new JSONObject().put("english","backup").put("chinese","备份").put("example","")));
        JSONObject selection=new JSONObject().put("id","selection-1").put("created",20L).put("words",new JSONArray().put(new JSONObject().put("english","backup").put("chinese","备份").put("_taskId",7L).put("_wordIndex",0)));
        JSONObject active=new JSONObject().put("taskId",7L).put("selectionId","selection-1").put("sessionId","session-1").put("snapshot","x").put("updatedAt",20L);
        JSONObject backup=new JSONObject().put("version",2).put("exportedAt",20L).put("tasks",new JSONArray().put(incoming)).put("wrongStates",new JSONObject()).put("spellingAttempts",new JSONArray().put(new JSONObject().put("eventId","a1").put("taskId",7L).put("wordId","backup").put("at",20L).put("type","review"))).put("trainingSelections",new JSONObject().put("selection-1",selection)).put("trainingSelectionOrder",new JSONArray().put("selection-1")).put("spellingSessions",new JSONObject().put("session-1",active)).put("translationExercises",new JSONArray()).put("voiceProfiles",new JSONArray()).put("voiceSettings",new JSONObject()).put("images",new JSONObject()).put("audio",new JSONObject());
        BackupManager.Backup mergeBackup=BackupManager.inspect(backup.toString());BackupManager.apply(c,mergeBackup,false);
        JSONArray merged=Store.tasks(c);check(merged.length()==2,"merge preserves the current plan and adds a colliding backup plan");
        long newId=merged.getJSONObject(1).getLong("id");check(newId!=7,"colliding task id is remapped");
        JSONObject remappedSelection=Store.prefs(c).getString("trainingSelections","{}").length()>0?new JSONObject(Store.prefs(c).getString("trainingSelections","{}")).getJSONObject("selection-1"):new JSONObject();
        check(remappedSelection.getJSONArray("words").getJSONObject(0).getLong("_taskId")==newId,"selection snapshot follows the remapped task id");
        check(Store.prefs(c).getString("spellingActive:session-1","").contains("\"taskId\":"+newId),"resumable session follows the remapped task id");
        check(Store.prefs(c).getString("spellingAttempts","[]").contains("\"taskId\":"+newId),"attempt history follows the remapped task id");

        JSONArray before=Store.tasks(c);try{BackupManager.inspect("{\"version\":99,\"tasks\":[]}");throw new AssertionError("unsupported version was accepted");}catch(IllegalArgumentException expected){check(true,"unsupported backup version is rejected");}
        check(Store.tasks(c).length()==before.length(),"invalid backup does not clear existing data");
        String invalidTask=withImages.toString().replace("\"id\":1", "\"id\":0");try{BackupManager.inspect(invalidTask);throw new AssertionError("invalid task id was accepted");}catch(IllegalArgumentException expected){check(true,"invalid task id is rejected during inspection");}
        check(Store.tasks(c).length()==before.length(),"rejected backup leaves current data intact");
    }
    private void testMechanical(Context c)throws Exception{
        SpellingSession empty=new SpellingSession(java.util.Collections.emptyList(),false);check(empty.ended()&&empty.initialTotal()==0,"empty spelling queue ends immediately");
        SpellingSession one=new SpellingSession(java.util.Collections.singletonList(new SpellingSession.Item("solo",0)),false);SpellingSession.Result first=one.submit("solo",true,false);check(first.accepted&&one.ended(),"single word queue accepts one independent answer and finishes");
        java.util.List<SpellingSession.Item> hundred=new java.util.ArrayList<>();for(int i=0;i<100;i++)hundred.add(new SpellingSession.Item("hundred-"+i,0));SpellingSession many=new SpellingSession(hundred,false);for(int i=0;i<100;i++)many.submit("hundred-"+i,true,false);check(many.ended()&&many.finishedCount()==100,"100-word queue completes exactly once per word");
        java.util.List<SpellingSession.Item> spacingItems=new java.util.ArrayList<>();for(int i=0;i<5;i++)spacingItems.add(new SpellingSession.Item("spacing-"+i,0));SpellingSession spacing=new SpellingSession(spacingItems,false);spacing.submit("spacing-0",false,false);check(spacing.current().equals("spacing-1"),"wrong word waits behind another word");java.util.List<String> spacingQueue=spacing.queueIds();check(spacingQueue.subList(0,3).indexOf("spacing-0")<0,"wrong word keeps at least three cards of spacing");spacing.submit("spacing-1",true,false);spacing.submit("spacing-2",true,false);spacing.submit("spacing-3",true,false);check(spacing.current().equals("spacing-0"),"wrong word returns after the minimum safe interval");
        try{SpellingSession.restore("not-base64");throw new AssertionError("corrupt spelling snapshot accepted");}catch(IllegalArgumentException expected){check(true,"corrupt spelling snapshot fails safely");}

        JSONObject book=new JSONObject().put("english","book").put("chinese","n. 书；v. 预订");
        JSONObject same=new JSONObject().put("english","book").put("chinese","n. 书；v. 预订");
        JSONObject other=new JSONObject().put("english","book").put("chinese","v. 预订");
        check(Words.stableId(book).equals(Words.stableId(same)),"same English and meaning share a stable word id");check(!Words.stableId(book).equals(Words.stableId(other)),"same English with another meaning keeps a distinct stable id");

        JSONObject aiPrefix=AiClient.extractJson("说明文字：{\"ok\":true,\"note\":\"brace } in string\"} 后记");
        check(aiPrefix.optBoolean("ok")&&aiPrefix.optString("note").contains("brace"),"AI JSON ignores surrounding explanation and respects braces inside strings");
        JSONObject aiArray=AiClient.extractJson("结果如下\n[{\"english\":\"a\"},{\"english\":\"b\"}]\n请核对");
        check(aiArray.optJSONArray("items")!=null&&aiArray.optJSONArray("items").length()==2,"AI array payload is wrapped in items even with trailing text");
        JSONObject aiEmpty=AiClient.extractJson("{\"items\":[]}");check(aiEmpty.optJSONArray("items")!=null&&aiEmpty.optJSONArray("items").length()==0,"empty AI items array remains valid");
        JSONObject aiMissing=AiClient.extractJson("{\"unexpected\":1}");check(aiMissing.optInt("unexpected")==1,"missing expected fields do not throw at parse time");
        StringBuilder longItem=new StringBuilder("{\"items\":[\"");
        for(int i=0;i<20000;i++)longItem.append(i%10==0?'x':'a');
        longItem.append("\"]}");JSONObject aiLong=AiClient.extractJson(longItem.toString());check(aiLong.optJSONArray("items").length()==1,"large AI item still parses without truncation");

        Store.prefs(c).edit().putInt("speechRate",2).commit();
        java.lang.reflect.Method cache=SpeechManager.class.getDeclaredMethod("cacheFile",Context.class,String.class,String.class,JSONObject.class,String.class,String.class);cache.setAccessible(true);
        JSONObject profileV1=new JSONObject().put("id","v").put("version","1"),profileV2=new JSONObject().put("id","v").put("version","2");
        File base=(File)cache.invoke(null,c,"mimo","voice-v",profileV1,"hello","word");
        File sameKey=(File)cache.invoke(null,c,"mimo","voice-v",profileV1,"hello","word");
        File text=(File)cache.invoke(null,c,"mimo","voice-v",profileV1,"world","word");
        File voice=(File)cache.invoke(null,c,"mimo","voice-w",profileV1,"hello","word");
        File provider=(File)cache.invoke(null,c,"system","voice-v",profileV1,"hello","word");
        File version=(File)cache.invoke(null,c,"mimo","voice-v",profileV2,"hello","word");
        File context=(File)cache.invoke(null,c,"mimo","voice-v",profileV1,"hello","example");
        check(base.getName().equals(sameKey.getName()),"same speech inputs hit the same cache key");
        check(!base.getName().equals(text.getName())&&!base.getName().equals(voice.getName())&&!base.getName().equals(provider.getName())&&!base.getName().equals(version.getName())&&!base.getName().equals(context.getName()),"text, voice, provider, sample version and context each change the speech cache key");
        Store.prefs(c).edit().putInt("speechRate",3).commit();File rate=(File)cache.invoke(null,c,"mimo","voice-v",profileV1,"hello","word");check(!base.getName().equals(rate.getName()),"speech rate changes the speech cache key");
        Store.prefs(c).edit().putInt("speechRate",2).commit();

        JSONArray words=new JSONArray().put(new JSONObject().put("english","book").put("chinese","n. 书；v. 预订"));
        JSONObject task=new JSONObject().put("id",20L).put("title","边界").put("created",1L).put("done",true).put("words",words);Store.save(c,new JSONArray().put(task));
        JSONObject migrated=WrongBook.stateForWord(c,words.getJSONObject(0));check(migrated.optInt("wrongCount",0)==0&&!migrated.optBoolean("mastered",false),"no legacy wrong count does not fake mastery");
        JSONObject firstWrong=WrongBook.record(c,20L,0,1,words,"mech-1");check(firstWrong.optInt("wrongCount")==1,"wrong event increments once");
        WrongBook.record(c,20L,0,1,words,"mech-1");check(WrongBook.state(c,Words.stableId(words.getJSONObject(0))).optInt("wrongCount")==1,"replayed event id never increments twice");
        WrongBook.independent(c,20L,0,words,"mech-2");check(WrongBook.state(c,Words.stableId(words.getJSONObject(0))).optInt("streak")==1,"first independent answer starts learning");
        WrongBook.independent(c,20L,0,words,"mech-3");check(WrongBook.state(c,Words.stableId(words.getJSONObject(0))).optBoolean("mastered"),"second independent answer masters the word");
        WrongBook.record(c,20L,0,1,words,"mech-4");check(!WrongBook.state(c,Words.stableId(words.getJSONObject(0))).optBoolean("mastered"),"a new mistake resets mastery");
    }
    private void seedVoice(Context c)throws Exception{
        File voiceDir=new File(c.getFilesDir(),"voices");if(!voiceDir.exists()&&!voiceDir.mkdirs())throw new AssertionError("cannot create voice dir");
        File sample=new File(voiceDir,"voice-test.wav");try(FileOutputStream out=new FileOutputStream(sample)){out.write(new byte[]{82,73,70,70,1,2,3,4,87,65,86,69,102,109,116,32});out.getFD().sync();}
        JSONArray profiles=new JSONArray().put(new JSONObject().put("id","voice-test").put("name","测试音色").put("path",sample.getAbsolutePath()).put("mime","audio/wav").put("version",1L).put("created",1L));
        Store.prefs(c).edit().putString("voiceProfiles",profiles.toString()).putString("voiceProvider",VoiceStore.MIMO).putString("voiceId","voice-test").putString("mimoSpeechBase","https://example.invalid/v1").putString("mimoSpeechModel","test-model").putInt("speechRate",2).commit();
    }
    private JSONObject fixture(Context c,String image)throws Exception{
        JSONObject word=new JSONObject().put("english","pair").put("chinese","n. 一对；成对").put("example","a pair of shoes").put("connectionImage",image).put("apiKey","do-not-export").put("wrongCount",2).put("wrongRounds",new JSONArray().put(1)).put("wrongAt",20L);
        JSONObject task=new JSONObject().put("id",1L).put("title","备份计划").put("created",10L).put("done",true).put("stage",1).put("intervals",new JSONArray().put(5L)).put("words",new JSONArray().put(word)).put("history",new JSONArray());
        Store.save(c,new JSONArray().put(task));
        JSONObject state=new JSONObject().put("wrongCount",2).put("wrongRounds",new JSONArray().put(1)).put("wrongAt",20L).put("streak",1).put("token","secret-token");
        Store.prefs(c).edit().putString("wrongWordStates",new JSONObject().put("pair|||n. 一对；成对",state).toString())
            .putString("spellingAttempts",new JSONArray().put(new JSONObject().put("eventId","e1").put("taskId",1L).put("wordId","pair").put("at",20L).put("type","review")).toString())
            .putString("trainingSelections",new JSONObject().put("selection-1",new JSONObject().put("id","selection-1").put("created",10L).put("words",new JSONArray().put(new JSONObject().put("english","pair").put("chinese","n. 一对；成对").put("_taskId",1L).put("_wordIndex",0)))).toString())
            .putString("trainingSelectionOrder",new JSONArray().put("selection-1").toString())
            .putString("spellingActive:session-1",new JSONObject().put("taskId",1L).put("selectionId","selection-1").put("sessionId","session-1").put("snapshot","x").put("updatedAt",20L).toString())
            .putString("translationExercises",new JSONArray().put(new JSONObject().put("id","ex1").put("title","练习")).toString())
            .commit();
        return task;
    }
    private boolean containsSecret(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();String lower=key.toLowerCase(java.util.Locale.ROOT);if(lower.contains("apikey")||lower.contains("api_key")||lower.contains("authorization")||lower.contains("token")||lower.contains("secret")||lower.contains("password"))return true;if(containsSecret(object.opt(key)))return true;}}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)if(containsSecret(array.opt(i)))return true;}
        return false;
    }
    private void clean(Context c){Store.prefs(c).edit().clear().commit();Store.save(c,new JSONArray());File images=new File(c.getFilesDir(),"connection-images");deleteTree(images);File voices=new File(c.getFilesDir(),"voices");deleteTree(voices);}
    private void deleteTree(File file){if(file==null||!file.exists())return;if(file.isDirectory())for(File child:file.listFiles())deleteTree(child);file.delete();}
}
