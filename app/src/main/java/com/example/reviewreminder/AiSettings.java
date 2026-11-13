package com.example.reviewreminder;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Named OpenAI-compatible configs. A config is listed only after a connection check has passed
 * ({@link #commitVerified}). Every AI call uses the active config's base URL, key and model.
 */
final class AiSettings {
    static final int PROVIDER_DEEPSEEK=0, PROVIDER_MIMO=1, PROVIDER_CUSTOM=2;
    static final String DEEPSEEK_URL="https://api.deepseek.com";
    static final String MIMO_URL="https://api.xiaomimimo.com/v1";
    static final String DEEPSEEK_MODEL="deepseek-flash";
    static final String MIMO_MODEL="mimo-v2.6-flash";
    private static final String CONFIGS="aiConfigs";
    private static final String ACTIVE="aiActiveId";
    private static final Object LOCK=new Object();

    private AiSettings() {}

    static SharedPreferences prefs(Context c){return Store.prefs(c);}
    static boolean enabled(Context c){return prefs(c).getBoolean("aiEnabled",false);}
    static boolean setEnabled(Context c,boolean enabled){return prefs(c).edit().putBoolean("aiEnabled",enabled).commit();}

    static int provider(Context c){
        JSONObject active=active(c);
        return active==null?PROVIDER_DEEPSEEK:clamp(active.optInt("provider",PROVIDER_DEEPSEEK));
    }
    static String apiKey(Context c){
        JSONObject active=active(c);
        return active==null?"":AiSecrets.readApiKey(c,active.optString("id"));
    }
    static String storedBaseUrl(Context c){
        JSONObject active=active(c);
        return active==null?"":active.optString("baseUrl","").trim();
    }
    static String storedModel(Context c){
        JSONObject active=active(c);
        return active==null?"":active.optString("model","").trim();
    }
    static String baseUrl(Context c){return storedBaseUrl(c);}
    static String model(Context c){return storedModel(c);}
    static boolean ready(Context c){
        if(!enabled(c))return false;
        JSONObject active=active(c);
        return active!=null&&active.optBoolean("verified",false)&&!model(c).isEmpty()&&!apiKey(c).isEmpty();
    }
    static String notReadyMessage(Context c){
        if(!enabled(c))return "请先在「我的 > AI」开启 AI 功能";
        if(active(c)==null)return "请先在「我的 > AI」添加并通过连接检测的配置";
        if(apiKey(c).isEmpty())return "请先在「我的 > AI」填写 API Key";
        if(model(c).isEmpty())return "请先在「我的 > AI」选择模型";
        return "AI 尚未配置完成";
    }
    static String summary(Context c){
        JSONObject active=active(c);
        if(active==null)return enabled(c)?"已开启 · 尚未添加可用配置":"未开启 · DeepSeek / 小米 MiMo / 自定义";
        return (enabled(c)?"已开启":"未开启")+" · "+active.optString("name")+" · "+modelOf(active);
    }

    static String presetUrl(int provider){
        if(provider==PROVIDER_MIMO)return MIMO_URL;
        if(provider==PROVIDER_CUSTOM)return "";
        return DEEPSEEK_URL;
    }
    static String presetModel(int provider){
        if(provider==PROVIDER_MIMO)return MIMO_MODEL;
        if(provider==PROVIDER_CUSTOM)return "";
        return DEEPSEEK_MODEL;
    }
    static String defaultName(int provider){
        if(provider==PROVIDER_MIMO)return "小米 MiMo";
        if(provider==PROVIDER_CUSTOM)return "自定义";
        return "DeepSeek";
    }
    static String providerLabel(JSONObject config){
        int provider=clamp(config.optInt("provider",PROVIDER_CUSTOM));
        if(provider==PROVIDER_DEEPSEEK)return "DeepSeek";
        if(provider==PROVIDER_MIMO)return "小米 MiMo";
        String host=hostOf(config.optString("baseUrl"));
        return host.isEmpty()?"自定义":"自定义 · "+host;
    }
    static String modelOf(JSONObject config){
        String model=config.optString("model","").trim();
        return model.isEmpty()?"未选模型":model;
    }
    static String describe(JSONObject config){return providerLabel(config)+" · "+modelOf(config);}
    static String statusText(JSONObject config){
        String detail=config.optString("detail","").trim();
        if("fail".equals(config.optString("status")))return detail.isEmpty()?"连接失败":detail;
        if("尚未重新检测".equals(detail))return "已保存 · 尚未重新检测";
        return "已连接";
    }
    static boolean failed(JSONObject config){return "fail".equals(config.optString("status"));}

    static String normalizeBase(String url){
        String value=url==null?"":url.trim();
        while(value.endsWith("/"))value=value.substring(0,value.length()-1);
        if(value.endsWith("/chat/completions"))value=value.substring(0,value.length()-"/chat/completions".length());
        while(value.endsWith("/"))value=value.substring(0,value.length()-1);
        if(value.endsWith("/models"))value=value.substring(0,value.length()-"/models".length());
        while(value.endsWith("/"))value=value.substring(0,value.length()-1);
        return value;
    }

    /** Model-list URLs for a base that may or may not already include {@code /v1}. */
    static List<String> modelUrls(String base){
        String root=normalizeBase(base);
        LinkedHashSet<String> urls=new LinkedHashSet<>();
        if(root.isEmpty())return new ArrayList<>();
        if(versioned(root)){
            urls.add(root+"/models");
            int cut=root.lastIndexOf('/');
            if(cut>"https://".length())urls.add(root.substring(0,cut)+"/models");
        }else{
            urls.add(root+"/models");
            urls.add(root+"/v1/models");
        }
        return new ArrayList<>(urls);
    }

    static String validateBase(String baseUrl){
        String root=normalizeBase(baseUrl);
        if(!root.startsWith("https://")&&!root.startsWith("http://"))return "地址无效，请以 https:// 开头";
        try{
            URL url=new URL(root);
            if(url.getHost()==null||url.getHost().isEmpty())return "地址无效";
        }catch(Exception e){return "地址无效";}
        return null;
    }

    static String chatUrlFor(String base){return normalizeBase(base)+"/chat/completions";}
    static String chatUrl(Context c){return chatUrlFor(baseUrl(c));}

    /** Verified configs, oldest first. */
    static JSONArray configs(Context c){
        synchronized(LOCK){
            JSONArray all=load(c),out=new JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject config=all.optJSONObject(i);
                if(config!=null&&config.optBoolean("verified",false))out.put(config);
            }
            return out;
        }
    }

    static JSONObject active(Context c){
        synchronized(LOCK){
            JSONArray all=load(c);
            String id=prefs(c).getString(ACTIVE,"");
            JSONObject first=null;
            for(int i=0;i<all.length();i++){
                JSONObject config=all.optJSONObject(i);
                if(config==null||!config.optBoolean("verified",false))continue;
                if(first==null)first=config;
                if(id.equals(config.optString("id")))return config;
            }
            if(first!=null)prefs(c).edit().putString(ACTIVE,first.optString("id")).commit();
            return first;
        }
    }

    static String activeId(Context c){JSONObject active=active(c);return active==null?"":active.optString("id");}

    static boolean setActive(Context c,String id){
        synchronized(LOCK){
            JSONArray all=load(c);
            for(int i=0;i<all.length();i++){
                JSONObject config=all.optJSONObject(i);
                if(config!=null&&id.equals(config.optString("id"))&&config.optBoolean("verified",false))
                    return prefs(c).edit().putString(ACTIVE,id).commit();
            }
            return false;
        }
    }

    static boolean delete(Context c,String id){
        synchronized(LOCK){
            JSONArray all=load(c),next=new JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject config=all.optJSONObject(i);
                if(config!=null&&!id.equals(config.optString("id")))next.put(config);
            }
            String active=prefs(c).getString(ACTIVE,"");
            if(id.equals(active))active=next.length()==0?"":next.optJSONObject(0).optString("id");
            AiSecrets.deleteApiKey(c,id);
            return prefs(c).edit().putString(CONFIGS,next.toString()).putString(ACTIVE,active).commit();
        }
    }

    /** Records the latest connection check on a config that was already added. */
    static boolean markCheck(Context c,String id,boolean ok,String detail){
        synchronized(LOCK){
            JSONArray all=load(c);
            boolean found=false;
            for(int i=0;i<all.length();i++){
                JSONObject config=all.optJSONObject(i);
                if(config==null||!id.equals(config.optString("id")))continue;
                try{
                    config.put("status",ok?"ok":"fail").put("detail",detail==null?"":detail).put("checkedAt",System.currentTimeMillis()).put("verified",true);
                }catch(JSONException e){return false;}
                found=true;
            }
            if(!found)return false;
            return prefs(c).edit().putString(CONFIGS,all.toString()).commit();
        }
    }

    /**
     * Persists a config after the caller has already seen a successful connection check.
     * A blank key on an existing config keeps the stored key. A new config requires a key.
     * {@code makeActive} selects it for later AI calls.
     */
    static boolean commitVerified(Context c,String idOrNull,String name,int provider,String baseUrl,String model,String apiKeyOrBlank,String detail,boolean makeActive){
        synchronized(LOCK){return commitUnlocked(c,load(c),idOrNull,name,provider,normalizeBase(baseUrl),model==null?"":model.trim(),apiKeyOrBlank,detail,makeActive,null);}
    }

    /**
     * Programmatic upsert used by tests and migration-style callers. Marks the config verified
     * without a network round-trip; the in-app add flow must go through a real check first.
     */
    static boolean save(Context c,boolean enabled,int provider,String baseUrl,String model,String apiKeyOrBlank){
        synchronized(LOCK){
            provider=clamp(provider);
            String url=normalizeBase(baseUrl);
            JSONArray all=load(c);
            String id=null,name=defaultName(provider);
            for(int i=0;i<all.length();i++){
                JSONObject config=all.optJSONObject(i);
                if(config==null)continue;
                if(config.optInt("provider")==provider&&normalizeBase(config.optString("baseUrl")).equals(url)){
                    id=config.optString("id");
                    name=config.optString("name",name);
                    break;
                }
            }
            return commitUnlocked(c,all,id,name,provider,url,model==null?"":model.trim(),apiKeyOrBlank,"已连接",true,enabled);
        }
    }

    static void reset(Context c){
        synchronized(LOCK){
            prefs(c).edit().putBoolean("aiEnabled",false).remove(ACTIVE).putString(CONFIGS,"[]").remove("aiBaseUrl").remove("aiModel").remove("aiProvider").commit();
            AiSecrets.clear(c);
        }
    }

    private static boolean commitUnlocked(Context c,JSONArray all,String idOrNull,String name,int provider,String baseUrl,String model,String apiKeyOrBlank,String detail,boolean makeActive,Boolean enabled){
        provider=clamp(provider);
        boolean fresh=idOrNull==null||idOrNull.isEmpty();
        String typedKey=apiKeyOrBlank==null?"":apiKeyOrBlank.trim();
        if(fresh&&typedKey.isEmpty())return false;
        if(model.isEmpty()||baseUrl.isEmpty())return false;
        String id=fresh?newId():idOrNull;
        String unique=uniqueName(all,name==null||name.trim().isEmpty()?defaultName(provider):name.trim(),id);
        JSONObject config;
        try{
            config=new JSONObject().put("id",id).put("name",unique).put("provider",provider).put("baseUrl",baseUrl).put("model",model)
                .put("verified",true).put("status","ok").put("detail",detail==null||detail.isEmpty()?"已连接":detail).put("checkedAt",System.currentTimeMillis());
        }catch(JSONException e){return false;}
        JSONArray next=new JSONArray();
        boolean replaced=false;
        for(int i=0;i<all.length();i++){
            JSONObject old=all.optJSONObject(i);
            if(old!=null&&id.equals(old.optString("id"))){next.put(config);replaced=true;}
            else if(old!=null)next.put(old);
        }
        if(!replaced)next.put(config);
        String active=prefs(c).getString(ACTIVE,"");
        boolean activeStillThere=false;
        for(int i=0;i<next.length();i++)if(active.equals(next.optJSONObject(i).optString("id")))activeStillThere=true;
        if(makeActive||!activeStillThere)active=id;
        SharedPreferences.Editor edit=prefs(c).edit().putString(CONFIGS,next.toString()).putString(ACTIVE,active);
        if(enabled!=null)edit.putBoolean("aiEnabled",enabled);
        if(!edit.commit())return false;
        if(!typedKey.isEmpty()){
            try{AiSecrets.writeApiKey(c,id,typedKey);}catch(Exception e){return false;}
        }
        return true;
    }

    private static JSONArray load(Context c){
        String raw=prefs(c).getString(CONFIGS,null);
        if(raw!=null){
            try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}
        }
        JSONArray migrated=new JSONArray();
        String key=AiSecrets.readLegacyApiKey(c);
        if(!key.isEmpty()){
            int provider=clamp(prefs(c).getInt("aiProvider",PROVIDER_DEEPSEEK));
            String base=prefs(c).getString("aiBaseUrl","").trim();
            String model=prefs(c).getString("aiModel","").trim();
            if(base.isEmpty())base=provider==PROVIDER_MIMO?MIMO_URL:DEEPSEEK_URL;
            if(model.isEmpty())model=provider==PROVIDER_MIMO?MIMO_MODEL:DEEPSEEK_MODEL;
            String id=newId();
            try{
                migrated.put(new JSONObject().put("id",id).put("name",defaultName(provider)).put("provider",provider).put("baseUrl",normalizeBase(base)).put("model",model.trim())
                    .put("verified",true).put("status","ok").put("detail","尚未重新检测").put("checkedAt",0));
                AiSecrets.writeApiKey(c,id,key);
                if(!prefs(c).edit().putString(CONFIGS,migrated.toString()).putString(ACTIVE,id).remove("aiBaseUrl").remove("aiModel").remove("aiProvider").commit()){
                    AiSecrets.deleteApiKey(c,id);
                    return new JSONArray();
                }
                AiSecrets.deleteLegacy(c);
                return migrated;
            }catch(Exception e){return new JSONArray();}
        }
        prefs(c).edit().putString(CONFIGS,"[]").remove("aiBaseUrl").remove("aiModel").remove("aiProvider").commit();
        return migrated;
    }

    private static String uniqueName(JSONArray configs,String name,String exceptId){
        String base=name.trim().isEmpty()?"未命名":name.trim();
        if(!nameTaken(configs,base,exceptId))return base;
        for(int n=2;n<100;n++){
            String candidate=base+" "+n;
            if(!nameTaken(configs,candidate,exceptId))return candidate;
        }
        return base;
    }
    private static boolean nameTaken(JSONArray configs,String name,String exceptId){
        for(int i=0;i<configs.length();i++){
            JSONObject config=configs.optJSONObject(i);
            if(config!=null&&name.equals(config.optString("name"))&&!config.optString("id").equals(exceptId))return true;
        }
        return false;
    }
    private static boolean versioned(String root){
        String lower=root.toLowerCase(Locale.US);
        return lower.endsWith("/v1")||lower.endsWith("/v1beta")||lower.endsWith("/openai");
    }
    private static String hostOf(String url){
        try{
            String host=new URL(normalizeBase(url)).getHost();
            return host==null?"":host;
        }catch(Exception e){return "";}
    }
    private static int clamp(int provider){return Math.max(0,Math.min(2,provider));}
    private static String newId(){return "c"+Long.toString(System.nanoTime(),36);}
}
