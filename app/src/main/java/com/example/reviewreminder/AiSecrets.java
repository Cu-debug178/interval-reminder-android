package com.example.reviewreminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts AI API keys with Android Keystore. Never log plaintext keys. */
final class AiSecrets {
    private static final String PREF="ai_secrets";
    private static final String KEY_ALIAS="interval_reminder_ai_key";
    private static final String ENC_KEY="apiKeyEnc";
    private static final String PLAIN_LEGACY="aiApiKey";

    private AiSecrets() {}

    static String readApiKey(Context c){
        SharedPreferences secrets=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String enc=secrets.getString(ENC_KEY,"").trim();
        if(!enc.isEmpty()){
            try{return decrypt(enc);}catch(Exception ignored){return "";}
        }
        String legacy=Store.prefs(c).getString(PLAIN_LEGACY,"").trim();
        if(!legacy.isEmpty()){
            try{writeApiKey(c,legacy);Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();}catch(Exception ignored){}
            return legacy;
        }
        return "";
    }

    static void writeApiKey(Context c,String key)throws Exception{
        SharedPreferences.Editor edit=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit();
        String value=key==null?"":key.trim();
        if(value.isEmpty())edit.remove(ENC_KEY);
        else edit.putString(ENC_KEY,encrypt(value));
        if(!edit.commit())throw new IllegalStateException("无法安全保存 API Key");
        Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();
    }

    static void clear(Context c){
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(ENC_KEY).apply();
        Store.prefs(c).edit().remove(PLAIN_LEGACY).apply();
    }

    static String mask(String key){
        if(key==null||key.isEmpty())return "";
        if(key.length()<=8)return "••••";
        return key.substring(0,4)+"…"+key.substring(key.length()-4);
    }

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
