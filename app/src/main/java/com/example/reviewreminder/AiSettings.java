package com.example.reviewreminder;

import android.content.Context;
import android.content.SharedPreferences;

final class AiSettings {
    static final int PROVIDER_DEEPSEEK=0, PROVIDER_MIMO=1, PROVIDER_CUSTOM=2;
    static final String DEEPSEEK_URL="https://api.deepseek.com";
    static final String MIMO_URL="https://api.xiaomimimo.com/v1";
    static final String DEEPSEEK_MODEL="deepseek-flash";
    static final String MIMO_MODEL="mimo-v2.6-flash";

    private AiSettings() {}

    static SharedPreferences prefs(Context c){return Store.prefs(c);}
    static boolean enabled(Context c){return prefs(c).getBoolean("aiEnabled",false);}
    static int provider(Context c){return Math.max(0,Math.min(2,prefs(c).getInt("aiProvider",PROVIDER_DEEPSEEK)));}
    static String apiKey(Context c){return AiSecrets.readApiKey(c);}
    static String storedBaseUrl(Context c){return prefs(c).getString("aiBaseUrl","").trim();}
    static String storedModel(Context c){return prefs(c).getString("aiModel","").trim();}
    static String baseUrl(Context c){
        int p=provider(c);
        String stored=storedBaseUrl(c);
        if(p==PROVIDER_CUSTOM)return normalizeBase(stored.isEmpty()?DEEPSEEK_URL:stored);
        if(p==PROVIDER_MIMO)return MIMO_URL;
        return DEEPSEEK_URL;
    }
    static String model(Context c){
        String stored=storedModel(c);
        if(!stored.isEmpty())return stored;
        return provider(c)==PROVIDER_MIMO?MIMO_MODEL:DEEPSEEK_MODEL;
    }
    static boolean ready(Context c){return enabled(c)&&!apiKey(c).isEmpty();}
    static String notReadyMessage(Context c){
        if(!enabled(c))return "请先在「我的 > AI」开启 AI 功能";
        if(apiKey(c).isEmpty())return "请先在「我的 > AI」填写 API Key";
        return "AI 尚未配置完成";
    }
    static void applyPreset(SharedPreferences.Editor edit,int provider){
        edit.putInt("aiProvider",provider);
        if(provider==PROVIDER_DEEPSEEK)edit.putString("aiBaseUrl",DEEPSEEK_URL).putString("aiModel",DEEPSEEK_MODEL);
        else if(provider==PROVIDER_MIMO)edit.putString("aiBaseUrl",MIMO_URL).putString("aiModel",MIMO_MODEL);
    }
    static String normalizeBase(String url){
        String value=url==null?"":url.trim();
        while(value.endsWith("/"))value=value.substring(0,value.length()-1);
        if(value.endsWith("/chat/completions"))value=value.substring(0,value.length()-"/chat/completions".length());
        return value;
    }
    static String chatUrl(Context c){return normalizeBase(baseUrl(c))+"/chat/completions";}

    /** Saves non-secret AI settings and optionally a new API key (blank keeps existing). */
    static boolean save(Context c,boolean enabled,int provider,String baseUrl,String model,String apiKeyOrBlank){
        SharedPreferences.Editor edit=prefs(c).edit()
            .putBoolean("aiEnabled",enabled)
            .putInt("aiProvider",provider)
            .putString("aiBaseUrl",baseUrl==null?"":baseUrl.trim())
            .putString("aiModel",model==null?"":model.trim());
        if(provider!=PROVIDER_CUSTOM)applyPreset(edit,provider);
        if(!edit.commit())return false;
        if(apiKeyOrBlank!=null&&!apiKeyOrBlank.trim().isEmpty()){
            try{AiSecrets.writeApiKey(c,apiKeyOrBlank.trim());}
            catch(Exception e){return false;}
        }
        return true;
    }
}
