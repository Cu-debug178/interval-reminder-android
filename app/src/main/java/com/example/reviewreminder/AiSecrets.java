package com.example.reviewreminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts AI API keys with Android Keystore. Never log or display a full key after it is saved. */
final class AiSecrets {
    private static final String PREF="ai_secrets";
    private static final String KEY_ALIAS="interval_reminder_ai_key";
    private static final String ENC_KEY="apiKeyEnc";
    private static final String PLAIN_LEGACY="aiApiKey";

    private AiSecrets() {}

    /** Legacy single-slot key, used only to migrate installs from before named configs. */
    static String readLegacyApiKey(Context c){
        SharedPreferences secrets=secrets(c);
        String enc=secrets.getString(ENC_KEY,"").trim();
        if(!enc.isEmpty()){
            try{return decrypt(enc);}catch(Exception ignored){return "";}
        }
        String legacy=Store.prefs(c).getString(PLAIN_LEGACY,"").trim();
        if(!legacy.isEmpty()){
            try{writeApiKey(c,null,legacy);Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();}catch(Exception ignored){}
            return legacy;
        }
        return "";
    }

    static String readApiKey(Context c,String configId){
        if(configId==null||configId.isEmpty())return readLegacyApiKey(c);
        String enc=secrets(c).getString(slot(configId),"").trim();
        if(enc.isEmpty())return "";
        try{return decrypt(enc);}catch(Exception ignored){return "";}
    }

    static void writeApiKey(Context c,String key)throws Exception{writeApiKey(c,null,key);}

    static void writeApiKey(Context c,String configId,String key)throws Exception{
        SharedPreferences.Editor edit=secrets(c).edit();
        String value=key==null?"":key.trim();
        String name=configId==null||configId.isEmpty()?ENC_KEY:slot(configId);
        if(value.isEmpty())edit.remove(name);
        else edit.putString(name,encrypt(value));
        if(!edit.commit())throw new IllegalStateException("无法安全保存 API Key");
        Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();
    }

    static void deleteApiKey(Context c,String configId){
        if(configId==null||configId.isEmpty()){deleteLegacy(c);return;}
        secrets(c).edit().remove(slot(configId)).commit();
    }

    static void deleteLegacy(Context c){
        secrets(c).edit().remove(ENC_KEY).commit();
        Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();
    }

    static void clear(Context c){
        SharedPreferences secrets=secrets(c);
        SharedPreferences.Editor edit=secrets.edit();
        for(Map.Entry<String,?> entry:secrets.getAll().entrySet()){
            if(entry.getKey()!=null&&entry.getKey().startsWith(ENC_KEY))edit.remove(entry.getKey());
        }
        edit.commit();
        Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();
    }

    /** Short hint for a saved key. Never returns the full secret. */
    static String mask(String key){
        if(key==null||key.isEmpty())return "";
        if(key.length()<=8)return "••••";
        return key.substring(0,4)+"…"+key.substring(key.length()-4);
    }

    private static SharedPreferences secrets(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private static String slot(String configId){return ENC_KEY+"."+configId.replaceAll("[^A-Za-z0-9_\\-]","");}

    private static SecretKey secret()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if(store.containsAlias(KEY_ALIAS)){
            KeyStore.SecretKeyEntry entry=(KeyStore.SecretKeyEntry)store.getEntry(KEY_ALIAS,null);
            return entry.getSecretKey();
        }
        if(Build.VERSION.SDK_INT<23)throw new IllegalStateException("设备过旧，无法加密保存 Key");
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return gen.generateKey();
    }

    private static String encrypt(String plain)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,secret());
        byte[] iv=cipher.getIV();
        byte[] sealed=cipher.doFinal(plain.getBytes("UTF-8"));
        ByteBuffer buf=ByteBuffer.allocate(4+iv.length+sealed.length);
        buf.putInt(iv.length).put(iv).put(sealed);
        return Base64.encodeToString(buf.array(),Base64.NO_WRAP);
    }

    private static String decrypt(String packed)throws Exception{
        byte[] all=Base64.decode(packed,Base64.NO_WRAP);
        ByteBuffer buf=ByteBuffer.wrap(all);
        int ivLen=buf.getInt();
        if(ivLen<=0||ivLen>32)throw new IllegalStateException("损坏的密钥数据");
        byte[] iv=new byte[ivLen];buf.get(iv);
        byte[] sealed=new byte[buf.remaining()];buf.get(sealed);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,iv));
        return new String(cipher.doFinal(sealed),"UTF-8");
    }
}
