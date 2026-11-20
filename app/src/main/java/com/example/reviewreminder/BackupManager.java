package com.example.reviewreminder;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Versioned, key-free backup with in-memory validation and one-shot SQLite transaction restore.
 * Portable JSON is used exclusively for export/import interchange; runtime persistence lives in
 * normalized SQLite tables ({@link LocalStore}).
 */
final class BackupManager {
    static final int VERSION=4,MAX_BYTES=128*1024*1024;
    private static final Set<String> SECRET_KEYS=new HashSet<>(Arrays.asList(
        "apikey","api_key","api-key","apikeyenc","api_key_enc","apikeyencrypted","speechkey","voicekey","voiceapikey",
        "mimokey","deepseekkey","authorization","authorizationheader","bearer","token","accesstoken","refreshtoken",
        "password","secret","clientsecret","privatekey","credential","credentials"
    ));
    private BackupManager(){}

    static final class Backup {
        int version;long exportedAt;
        JSONArray tasks=new JSONArray(),attempts=new JSONArray(),translationExercises=new JSONArray(),voiceProfiles=new JSONArray(),trainingOrder=new JSONArray(),relations=new JSONArray(),relationGroups=new JSONArray(),relationMembers=new JSONArray(),aiMistakes=new JSONArray();
        JSONObject wrongStates=new JSONObject(),trainingSelections=new JSONObject(),activeSessions=new JSONObject(),voiceSettings=new JSONObject(),images=new JSONObject(),audio=new JSONObject();
    }

    static final class Result {
        int tasksAdded,wordsAdded,exercises,profiles,images,sessions,wrongStates,relations;boolean replaced;String warning="";
        String summary(){return replaced?"已替换数据：":"已合并数据：";}
    }

    static JSONObject exportData(Context c,boolean includeAudio,boolean includeImages)throws Exception{
        JSONObject out=new JSONObject()
            .put("version",VERSION).put("exportedAt",System.currentTimeMillis()).put("app","com.example.reviewreminder")
            .put("tasks",cleanArray(Store.tasks(c)))
            .put("wrongStates",cleanObject(WrongBook.allStates(c)))
            .put("spellingAttempts",cleanArray(WrongBook.attempts(c,null)))
            .put("trainingSelections",cleanObject(TrainingStore.allSelections(c)))
            .put("trainingSelectionOrder",cleanArray(TrainingStore.selectionOrder(c)))
            .put("spellingSessions",cleanObject(TrainingStore.allSpelling(c)))
            .put("translationExercises",cleanArray(TranslationStore.all(c)))
            .put("aiMistakes",cleanArray(AiNotes.all(c)));
        JSONArray profiles=cleanProfiles(VoiceStore.profiles(c));out.put("voiceProfiles",profiles);
        out.put("voiceSettings",new JSONObject()
            .put("provider",VoiceStore.provider(c)).put("voiceId",VoiceStore.voiceId(c)).put("speechRate",Store.prefs(c).getInt("speechRate",2))
            .put("speechVoice",Store.prefs(c).getString("speechVoice","")).put("mimoSpeechBase",VoiceStore.baseUrl(c)).put("mimoSpeechModel",VoiceStore.model(c)));
        JSONObject selections=out.getJSONObject("trainingSelections");
        JSONObject active=out.getJSONObject("spellingSessions");
        Iterator<String> sessionKeys=active.keys();
        while(sessionKeys.hasNext()){
            JSONObject session=active.optJSONObject(sessionKeys.next());if(session==null)continue;
            String selection=session.optString("selectionId");if(!selection.isEmpty()&&!selections.has(selection)){JSONObject saved=TrainingStore.selection(c,selection);if(saved!=null)selections.put(selection,cleanObject(saved));}
        }
        if(includeImages){
            JSONObject images=new JSONObject();JSONArray tasks=out.optJSONArray("tasks");
            for(int i=0;tasks!=null&&i<tasks.length();i++){
                JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray words=task.optJSONArray("words");if(words==null)continue;
                for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null)continue;String path=word.optString("connectionImage");if(!path.isEmpty()&&!images.has(path)&&ConnectionImages.exists(c,path))try{images.put(path,ConnectionImages.base64(c,path));}catch(Exception ignored){}}
            }
            out.put("images",images);
        }else out.put("images",new JSONObject());
        if(includeAudio){
            JSONObject audio=new JSONObject();JSONArray profilesOut=out.optJSONArray("voiceProfiles");
            for(int i=0;profilesOut!=null&&i<profilesOut.length();i++){
                JSONObject profile=profilesOut.optJSONObject(i);if(profile==null)continue;String id=profile.optString("id");
                JSONObject original=VoiceStore.profile(c,id);if(original==null)continue;
                try{byte[] bytes=VoiceStore.sample(c,original);audio.put(id,new JSONObject().put("mime",original.optString("mime")).put("data",Base64Compat.encode(bytes)));}catch(Exception ignored){}
            }
            out.put("audio",audio);
        }else out.put("audio",new JSONObject());
        JSONObject graph=WordRelations.export(c);
        out.put("relations",graph.optJSONArray("links")==null?new JSONArray():graph.optJSONArray("links"));
        out.put("relationGroups",graph.optJSONArray("groups")==null?new JSONArray():graph.optJSONArray("groups"));
        out.put("relationMembers",graph.optJSONArray("members")==null?new JSONArray():graph.optJSONArray("members"));
        return out;
    }

    static String readJson(InputStream in)throws Exception{
        if(in==null)throw new IllegalArgumentException("无法读取备份文件");
        try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n,total=0;
            while((n=source.read(buffer))!=-1){total+=n;if(total>MAX_BYTES)throw new IllegalArgumentException("备份文件不能超过 128 MB");out.write(buffer,0,n);}
            String value=out.toString("UTF-8");return value.startsWith("\uFEFF")?value.substring(1):value;
        }
    }

    static Backup inspect(String json)throws Exception{
        if(json==null||json.trim().isEmpty())throw new IllegalArgumentException("备份文件为空");
        JSONObject root=(JSONObject)clean(new JSONObject(json));
        int version=root.optInt("version",0);if(version<1||version>VERSION)throw new IllegalArgumentException("不支持的备份版本："+version);
        Backup backup=new Backup();backup.version=version;backup.exportedAt=root.optLong("exportedAt",0);
        backup.tasks=array(root,"tasks",true);backup.attempts=array(root,"spellingAttempts",false);backup.translationExercises=array(root,"translationExercises",false);backup.trainingOrder=array(root,"trainingSelectionOrder",false);backup.voiceProfiles=array(root,"voiceProfiles",false);backup.aiMistakes=array(root,"aiMistakes",false);
        backup.wrongStates=object(root,"wrongStates");backup.trainingSelections=object(root,"trainingSelections");backup.activeSessions=object(root,"spellingSessions");backup.voiceSettings=object(root,"voiceSettings");backup.images=object(root,"images");backup.audio=object(root,"audio");
        backup.relations=array(root,"relations",false);backup.relationGroups=array(root,"relationGroups",false);backup.relationMembers=array(root,"relationMembers",false);
        validateTasks(backup.tasks);validateSelections(backup.trainingSelections);validateProfiles(backup.voiceProfiles);
        validateImages(backup.images);validateAudio(backup.audio);validateGraph(backup.relations,backup.relationGroups,backup.relationMembers);
        return backup;
    }

    static Result apply(Context c,Backup backup,boolean replace)throws Exception{
        Result result=new Result();result.replaced=replace;
        JSONArray currentTasks=Store.tasks(c),nextTasks=new JSONArray();Map<Long,Long> idMap=new HashMap<>();long max=0;Set<Long> used=new HashSet<>();
        if(!replace){for(int i=0;i<currentTasks.length();i++){JSONObject task=currentTasks.optJSONObject(i);if(task==null)continue;nextTasks.put(new JSONObject(task.toString()));max=Math.max(max,task.optLong("id"));used.add(task.optLong("id"));}}
        for(int i=0;i<backup.tasks.length();i++){
            JSONObject incoming=cleanObject(backup.tasks.getJSONObject(i));long oldId=incoming.optLong("id");long target=oldId;
            JSONObject existing=replace?null:find(nextTasks,oldId);
            if(existing!=null&&samePlan(existing,incoming)){
                idMap.put(oldId,oldId);result.wordsAdded+=mergeWords(existing,incoming);mergeHistory(existing,incoming);continue;
            }
            if(replace&&used.contains(target))target=++max;
            else if(existing!=null||(used.contains(target)&&target!=oldId))target=++max;
            else if(used.contains(target)&&!replace)target=++max;
            incoming.put("id",target);used.add(target);max=Math.max(max,target);if(oldId!=target)idMap.put(oldId,target);if(!idMap.containsKey(oldId))idMap.put(oldId,oldId);
            nextTasks.put(incoming);result.tasksAdded++;JSONArray incomingWords=incoming.optJSONArray("words");if(incomingWords!=null)result.wordsAdded+=incomingWords.length();
        }

        JSONObject wrongStates=replace?new JSONObject():WrongBook.allStates(c);
        Iterator<String> states=backup.wrongStates.keys();
        while(states.hasNext()){String id=states.next();JSONObject incoming=backup.wrongStates.optJSONObject(id);if(incoming==null)continue;JSONObject current=wrongStates.optJSONObject(id);wrongStates.put(id,current==null?incoming:mergeState(current,incoming));result.wrongStates++;}
        JSONArray attempts=replace?new JSONArray():WrongBook.attempts(c,null);
        mergeAttempts(attempts,backup.attempts,idMap);

        JSONObject selections=replace?new JSONObject():TrainingStore.allSelections(c);
        Iterator<String> selectionKeys=backup.trainingSelections.keys();
        while(selectionKeys.hasNext()){String id=selectionKeys.next();JSONObject selection=backup.trainingSelections.optJSONObject(id);if(selection==null)continue;if(!selections.has(id)){JSONObject copy=cleanObject(selection);remapSelection(copy,idMap);selections.put(id,copy);}}

        JSONObject active=new JSONObject();if(!replace){JSONObject current=TrainingStore.allSpelling(c);Iterator<String> keys=current.keys();while(keys.hasNext()){String key=keys.next();active.put(key,current.optJSONObject(key));}}
        Iterator<String> activeKeys=backup.activeSessions.keys();
        while(activeKeys.hasNext()){
            String sessionId=activeKeys.next();JSONObject session=backup.activeSessions.optJSONObject(sessionId);if(session==null||active.has(sessionId))continue;
            JSONObject copy=cleanObject(session);remapObject(copy,idMap);String selectionId=copy.optString("selectionId");
            if(!selectionId.isEmpty()&&!selections.has(selectionId))continue;
            active.put(sessionId,copy);result.sessions++;
        }

        JSONArray exercises=replace?new JSONArray():TranslationStore.all(c);
        for(int i=0;i<backup.translationExercises.length();i++){JSONObject exercise=cleanObject(backup.translationExercises.getJSONObject(i));if(!containsExercise(exercises,exercise.optString("id"))){exercises.put(exercise);result.exercises++;}}
        while(exercises.length()>200)exercises.remove(0);

        JSONArray mistakes=replace?new JSONArray():AiNotes.all(c);
        for(int i=0;i<backup.aiMistakes.length();i++){JSONObject m=backup.aiMistakes.optJSONObject(i);if(m!=null)mistakes.put(cleanObject(m));}
        while(mistakes.length()>80)mistakes.remove(0);

        JSONArray currentProfiles=replace?new JSONArray():VoiceStore.profiles(c);
        JSONArray profiles=new JSONArray();if(!replace)for(int i=0;i<currentProfiles.length();i++){JSONObject p=currentProfiles.optJSONObject(i);if(p!=null)profiles.put(new JSONObject(p.toString()));}
        List<PendingFile> pending=new ArrayList<>();boolean committed=false;
        final long finalMax=max;
        try{
            for(int i=0;i<backup.voiceProfiles.length();i++){
                JSONObject p=cleanObject(backup.voiceProfiles.getJSONObject(i));String id=p.optString("id");if(id.isEmpty()||findProfile(profiles,id)!=null)continue;
                File target=voiceFile(c,id,p.optString("mime"));JSONObject audio=backup.audio.optJSONObject(id);
                if(audio!=null){PendingFile staged=stageVoice(c,target,audio);if(staged==null)continue;pending.add(staged);p.put("path",staged.target.getAbsolutePath());}
                else if(target!=null&&target.isFile())p.put("path",target.getAbsolutePath());
                else continue;
                profiles.put(p);result.profiles++;
            }
            for(Iterator<String> keys=backup.images.keys();keys.hasNext();){String path=keys.next();String data=backup.images.optString(path,"");if(data.isEmpty())continue;ConnectionImages.StagedFile staged=ConnectionImages.stage(c,path,data);pending.add(new PendingFile(staged.temp,staged.target));result.images++;}
            LocalStore.inTransaction(c,()->{
                SQLiteDatabase db=LocalStore.database();
                LocalStore.writeAllTasksLocked(db,nextTasks);
                db.delete("wrong_state",null,null);
                for(Iterator<String> k=wrongStates.keys();k.hasNext();){String id=k.next();JSONObject st=wrongStates.optJSONObject(id);if(st!=null)LocalStore.upsertWrongStateLocked(db,id,st);}
                db.delete("spelling_attempt",null,null);
                for(int i=0;i<attempts.length();i++){JSONObject a=attempts.optJSONObject(i);if(a!=null)LocalStore.insertAttemptLocked(db,a);}
                db.delete("training_selection",null,null);
                for(Iterator<String> k=selections.keys();k.hasNext();){String id=k.next();JSONObject sel=selections.optJSONObject(id);if(sel!=null)LocalStore.upsertSelectionLocked(db,id,sel.optLong("created",System.currentTimeMillis()),sel.optJSONArray("words"));}
                db.delete("spelling_session",null,null);
                for(Iterator<String> k=active.keys();k.hasNext();){String id=k.next();JSONObject s=active.optJSONObject(id);if(s!=null)LocalStore.upsertSpellingSessionLocked(db,id,s);}
                db.delete("translation_exercise",null,null);
                for(int i=0;i<exercises.length();i++){JSONObject ex=exercises.optJSONObject(i);if(ex!=null)LocalStore.upsertTranslationLocked(db,ex);}
                db.delete("voice_profile",null,null);
                for(int i=0;i<profiles.length();i++){JSONObject p=profiles.optJSONObject(i);if(p!=null)LocalStore.upsertVoiceProfileLocked(db,p);}
                db.delete("ai_mistake",null,null);
                for(int i=0;i<mistakes.length();i++){JSONObject m=mistakes.optJSONObject(i);if(m!=null)LocalStore.insertAiMistakeLocked(db,m.optString("english"),m.optString("sentence"),m.optString("feedback"),m.optLong("at",System.currentTimeMillis()));}
                android.content.SharedPreferences prefs=Store.prefs(c);
                android.content.SharedPreferences.Editor edit=prefs.edit()
                    .putLong("nextId",Math.max(finalMax,prefs.getLong("nextId",0))).remove(WrongBook.LEGACY_MIGRATED);
                if(replace){
                    JSONObject voice=backup.voiceSettings;String provider=voice.optString("provider",VoiceStore.SYSTEM);String voiceId=voice.optString("voiceId","");if(!VoiceStore.SYSTEM.equals(provider)&&!VoiceStore.preset(voiceId)&&findProfile(profiles,voiceId)==null){provider=VoiceStore.SYSTEM;voiceId="";}
                    edit.putString("voiceProvider",provider).putString("voiceId",voiceId).putInt("speechRate",Math.max(0,Math.min(4,voice.optInt("speechRate",2)))).putString("mimoSpeechModel",voice.optString("mimoSpeechModel","mimo-v2.5-tts"));
                    if(voice.has("speechVoice"))edit.putString("speechVoice",voice.optString("speechVoice",""));
                }
                if(!edit.commit())throw new IllegalStateException("备份数据未能写入设备");
                WordRelations.restore(c,backup.relations,backup.relationGroups,backup.relationMembers,replace);
            });
            committed=true;result.relations=backup.relations.length();publishFiles(pending);reschedule(c,result,currentTasks,nextTasks,replace);return result;
        }catch(Exception e){
            if(!committed){discardFiles(pending);throw e;}
            result.warning="数据已写入，但部分附件未能恢复："+(e.getMessage()==null?"写入失败":e.getMessage());
            reschedule(c,result,currentTasks,nextTasks,replace);return result;
        }
    }

    private static void reschedule(Context c,Result result,JSONArray previous,JSONArray next,boolean replace){
        try{
            if(replace){for(int i=0;i<previous.length();i++){JSONObject task=previous.optJSONObject(i);if(task!=null)Alarms.cancel(c,task.optLong("id"));}}
            for(int i=0;i<next.length();i++){JSONObject task=next.optJSONObject(i);if(task==null)continue;boolean fresh=replace||!findLong(previous,task.optLong("id"));if(fresh&&!task.optBoolean("done")&&!task.optBoolean("archived"))Alarms.schedule(c,task);}
        }catch(Exception e){String note="数据已写入，但提醒未能全部重新安排："+(e.getMessage()==null?"系统限制":e.getMessage());result.warning=result.warning==null||result.warning.isEmpty()?note:result.warning+" "+note;}
    }

    private static JSONArray cleanProfiles(JSONArray old)throws Exception{JSONArray out=new JSONArray();for(int i=0;i<old.length();i++){JSONObject p=old.optJSONObject(i);if(p==null)continue;JSONObject copy=cleanObject(p);copy.remove("path");out.put(copy);}return out;}
    private static JSONArray array(JSONObject root,String key,boolean required){Object value=root.opt(key);if(value==null&&!required)return new JSONArray();if(!(value instanceof JSONArray))throw new IllegalArgumentException("备份字段格式错误："+key);return (JSONArray)value;}
    private static JSONObject object(JSONObject root,String key){Object value=root.opt(key);return value instanceof JSONObject?(JSONObject)value:new JSONObject();}

    private static void validateTasks(JSONArray tasks)throws Exception{
        Set<Long> ids=new HashSet<>();if(tasks.length()>1000)throw new IllegalArgumentException("计划数量超出限制");
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)throw new IllegalArgumentException("计划格式无效");long id=task.optLong("id");if(id<=0||!ids.add(id))throw new IllegalArgumentException("计划 ID 重复或无效");if(task.optString("title").trim().isEmpty())throw new IllegalArgumentException("计划名称不能为空");if(task.has("words")&&task.opt("words")!=null&&task.opt("words")!=JSONObject.NULL){JSONArray words=task.optJSONArray("words");if(words==null)throw new IllegalArgumentException("计划单词列表格式无效");for(int j=0;j<words.length();j++){JSONObject word=words.optJSONObject(j);if(word==null||word.optString("english").trim().isEmpty())throw new IllegalArgumentException("单词卡缺少英文");}}}
    }
    private static void validateSelections(JSONObject selections)throws Exception{Iterator<String> keys=selections.keys();while(keys.hasNext()){String id=keys.next();JSONObject selection=selections.optJSONObject(id);if(selection==null||selection.optJSONArray("words")==null)throw new IllegalArgumentException("训练选词格式无效");}}
    private static void validateProfiles(JSONArray profiles)throws Exception{for(int i=0;i<profiles.length();i++){JSONObject p=profiles.optJSONObject(i);if(p==null||p.optString("id").isEmpty())throw new IllegalArgumentException("音色配置格式无效");}}
    private static void validateImages(JSONObject images)throws Exception{Iterator<String> keys=images.keys();while(keys.hasNext()){String path=keys.next();if(path.contains("/")||path.contains("\\")||path.startsWith("."))throw new IllegalArgumentException("连接图路径无效");byte[] data=Base64Compat.decode(images.optString(path,""));if(data.length==0)throw new IllegalArgumentException("连接图内容为空");}}
    private static void validateAudio(JSONObject audio)throws Exception{Iterator<String> keys=audio.keys();while(keys.hasNext()){JSONObject sample=audio.optJSONObject(keys.next());if(sample==null||sample.optString("mime").isEmpty())throw new IllegalArgumentException("参考音频格式无效");byte[] data=Base64Compat.decode(sample.optString("data",""));if(data.length==0)throw new IllegalArgumentException("参考音频内容为空");}}
    private static void validateGraph(JSONArray links,JSONArray groups,JSONArray members)throws Exception{
        if(links.length()>20000||groups.length()>5000||members.length()>20000)throw new IllegalArgumentException("词汇关系数量超出限制");
        JSONObject incoming=new JSONObject().put("links",links).put("groups",groups).put("members",members);
        WordGraph.merge(WordGraph.empty(),incoming,true);
    }

    private static Object clean(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject source=(JSONObject)value,out=new JSONObject();Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();if(isSecret(key))continue;out.put(key,clean(source.opt(key)));}return out;}
        if(value instanceof JSONArray){JSONArray source=(JSONArray)value,out=new JSONArray();for(int i=0;i<source.length();i++)out.put(clean(source.opt(i)));return out;}
        return value;
    }
    private static JSONObject cleanObject(JSONObject value)throws Exception{return (JSONObject)clean(new JSONObject(value.toString()));}
    private static JSONArray cleanArray(JSONArray value)throws Exception{return (JSONArray)clean(new JSONArray(value.toString()));}
    private static boolean isSecret(String key){String lower=key.toLowerCase(Locale.ROOT);return SECRET_KEYS.contains(lower)||lower.contains("apikey")||lower.contains("api_key")||lower.contains("api-key")||lower.contains("authorization")||lower.contains("password")||lower.contains("secret")||lower.contains("credential")||lower.endsWith("token")||lower.equals("bearer");}
    private static JSONObject find(JSONArray tasks,long id){for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task!=null&&task.optLong("id")==id)return task;}return null;}
    private static boolean findLong(JSONArray tasks,long id){return find(tasks,id)!=null;}
    static boolean samePlan(JSONObject a,JSONObject b){return PlanIdentity.same(a,b);}
    private static int mergeWords(JSONObject existing,JSONObject incoming)throws Exception{JSONArray source=incoming.optJSONArray("words");if(source==null)return 0;JSONArray target=existing.optJSONArray("words");if(target==null){target=new JSONArray();existing.put("words",target);}Set<String> ids=new HashSet<>();for(int i=0;i<target.length();i++){JSONObject word=target.optJSONObject(i);if(word!=null)ids.add(Words.stableId(word));}int added=0;for(int i=0;i<source.length();i++){JSONObject word=source.optJSONObject(i);if(word==null)continue;String id=Words.stableId(word);if(ids.add(id)){target.put(new JSONObject(word.toString()));added++;}}return added;}
    private static void mergeHistory(JSONObject existing,JSONObject incoming)throws Exception{JSONArray target=existing.optJSONArray("history"),source=incoming.optJSONArray("history");if(target==null){target=new JSONArray();existing.put("history",target);}if(source==null)return;Set<Long> seen=new HashSet<>();for(int i=0;i<target.length();i++){JSONObject h=target.optJSONObject(i);if(h!=null)seen.add(h.optLong("at"));}for(int i=0;i<source.length();i++){JSONObject h=source.optJSONObject(i);if(h!=null&&seen.add(h.optLong("at")))target.put(new JSONObject(h.toString()));}}
    private static JSONObject mergeState(JSONObject old,JSONObject incoming)throws Exception{JSONObject out=new JSONObject(old.toString());out.put("wrongCount",Math.max(old.optInt("wrongCount"),incoming.optInt("wrongCount"))).put("wrongAt",Math.max(old.optLong("wrongAt"),incoming.optLong("wrongAt"))).put("bookmarked",old.optBoolean("bookmarked")||incoming.optBoolean("bookmarked"));int streak=Math.max(old.optInt("streak"),incoming.optInt("streak"));out.put("streak",streak).put("mastered",streak>=2);out.put("wrongRounds",unionNumbers(old.optJSONArray("wrongRounds"),incoming.optJSONArray("wrongRounds"))).put("eventIds",unionStrings(old.optJSONArray("eventIds"),incoming.optJSONArray("eventIds"),40));return out;}
    private static JSONArray unionNumbers(JSONArray a,JSONArray b){JSONArray out=new JSONArray();Set<Integer> seen=new HashSet<>();for(JSONArray list:new JSONArray[]{a,b})if(list!=null)for(int i=0;i<list.length();i++)seen.add(list.optInt(i));for(int n:seen)out.put(n);return out;}
    private static JSONArray unionStrings(JSONArray a,JSONArray b,int limit){JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();for(JSONArray list:new JSONArray[]{a,b})if(list!=null)for(int i=0;i<list.length();i++){String s=list.optString(i);if(!s.isEmpty()&&seen.add(s))out.put(s);}while(out.length()>limit)out.remove(0);return out;}
    private static void mergeAttempts(JSONArray target,JSONArray source,Map<Long,Long> idMap)throws Exception{Set<String> seen=new HashSet<>();for(int i=0;i<target.length();i++)seen.add(attemptKey(target.optJSONObject(i)));for(int i=0;i<source.length();i++){JSONObject a=source.optJSONObject(i);if(a==null)continue;String key=attemptKey(a);if(!seen.add(key))continue;long old=a.optLong("taskId");long mapped=idMap.containsKey(old)?idMap.get(old):old;if(mapped!=old)a.put("taskId",mapped);target.put(a);}while(target.length()>500)target.remove(0);}
    private static String attemptKey(JSONObject a){if(a==null)return "";String event=a.optString("eventId");return event.isEmpty()?a.optString("wordId")+"|"+a.optLong("at")+"|"+a.optString("type")+"|"+a.optLong("taskId"):"event:"+event;}
    private static void remapSelection(JSONObject selection,Map<Long,Long> idMap)throws Exception{JSONArray words=selection.optJSONArray("words");if(words==null)return;for(int i=0;i<words.length();i++){JSONObject word=words.optJSONObject(i);if(word==null)continue;long old=word.optLong("_taskId");if(idMap.containsKey(old))word.put("_taskId",idMap.get(old));}}
    private static void remapObject(JSONObject value,Map<Long,Long> idMap){long old=value.optLong("taskId");if(idMap.containsKey(old))try{value.put("taskId",idMap.get(old));}catch(Exception ignored){}}
    private static boolean containsExercise(JSONArray array,String id){for(int i=0;i<array.length();i++){JSONObject item=array.optJSONObject(i);if(item!=null&&id.equals(item.optString("id")))return true;}return false;}
    private static JSONObject findProfile(JSONArray profiles,String id){for(int i=0;i<profiles.length();i++){JSONObject p=profiles.optJSONObject(i);if(p!=null&&id.equals(p.optString("id")))return p;}return null;}
    private static File voiceFile(Context c,String id,String mime){String safe=id.replaceAll("[^A-Za-z0-9_-]","");if(safe.isEmpty())return null;String extension="audio/wav".equals(mime)?".wav":".mp3";return new File(new File(c.getFilesDir(),"voices"),safe+extension);}
    private static final class PendingFile {final File temp,target;PendingFile(File temp,File target){this.temp=temp;this.target=target;}}
    private static PendingFile stageVoice(Context c,File target,JSONObject sample)throws Exception{if(sample==null||target==null)return null;byte[] bytes=Base64Compat.decode(sample.optString("data",""));if(bytes.length==0)throw new IllegalArgumentException("参考音频为空");File dir=target.getParentFile();if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("无法创建音色目录");File tmp=new File(dir,target.getName()+".part");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);out.getFD().sync();}return new PendingFile(tmp,target);}
    private static void publishFiles(List<PendingFile> pending)throws Exception{for(PendingFile file:pending)if(!ConnectionImages.publish(file.temp,file.target))throw new IllegalStateException("备份附件未能写入设备");}
    private static void discardFiles(List<PendingFile> pending){for(PendingFile file:pending)if(file.temp.exists()&&!file.temp.equals(file.target))file.temp.delete();}
}
