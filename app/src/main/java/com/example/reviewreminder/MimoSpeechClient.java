package com.example.reviewreminder;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class MimoSpeechClient {
    private MimoSpeechClient(){}

    static byte[] synthesize(Context c,String text,String voiceId,JSONObject profile,float rate)throws Exception{
        String key=VoiceStore.speechKey(c);if(key.isEmpty())throw new IllegalStateException("请先在音色管理中填写 MiMo API Key");
        boolean clone=profile!=null;String model=clone?"mimo-v2.5-tts-voiceclone":VoiceStore.model(c);String voice=voiceId;
        if(clone){byte[] sample=VoiceStore.sample(c,profile);voice="data:"+profile.getString("mime")+";base64,"+Base64.encodeToString(sample,Base64.NO_WRAP);}
        JSONObject audio=new JSONObject().put("format","wav").put("voice",voice);
        JSONObject body=new JSONObject().put("model",model).put("stream",false).put("audio",audio).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","Read only the assistant text in clear English. Use a natural learning pace ("+rate+"x).")).put(new JSONObject().put("role","assistant").put("content",text)));
        IOException last=null;
        for(int attempt=0;attempt<3;attempt++){
            try{return request(AiSettings.normalizeBase(VoiceStore.baseUrl(c))+"/chat/completions",key,body.toString());}catch(IOException e){last=e;if(!retryable(e)||attempt==2)throw e;try{Thread.sleep(500L*(1<<attempt));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw e;}}
        }
        throw last==null?new IOException("语音生成失败"):last;
    }

    private static byte[] request(String endpoint,String key,String json)throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(endpoint).openConnection();conn.setConnectTimeout(10000);conn.setReadTimeout(90000);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setRequestProperty("api-key",key);byte[] bytes=json.getBytes(StandardCharsets.UTF_8);conn.setFixedLengthStreamingMode(bytes.length);
        try{
            try(OutputStream out=conn.getOutputStream()){out.write(bytes);}int code=conn.getResponseCode();String response=read(code>=200&&code<300?conn.getInputStream():conn.getErrorStream());if(code<200||code>=300)throw new HttpError(code,message(code,response));JSONObject root=new JSONObject(response);JSONObject choice=root.optJSONArray("choices")==null?null:root.optJSONArray("choices").optJSONObject(0);JSONObject message=choice==null?null:choice.optJSONObject("message");JSONObject audio=message==null?null:message.optJSONObject("audio");String data=audio==null?"":audio.optString("data","");if(data.isEmpty())throw new IOException("MiMo 未返回音频数据");return Base64.decode(data,Base64.DEFAULT);
        }finally{conn.disconnect();}
    }

    private static boolean retryable(IOException e){if(e instanceof HttpError){int code=((HttpError)e).code;return code==429||code==500||code==502||code==503||code==504;}return true;}
    private static String message(int code,String body){try{JSONObject error=new JSONObject(body).optJSONObject("error");if(error!=null&&!error.optString("message").isEmpty())return "语音错误 "+code+"："+error.optString("message");}catch(Exception ignored){}return code==401?"语音 Key 无效或已过期（401）":code==403?"当前账号无语音模型权限（403）":code==404?"语音接口或模型不存在，请检查 Base URL（404）":"语音请求失败 HTTP "+code;}
    private static String read(InputStream in)throws IOException{if(in==null)return "";try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=source.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static final class HttpError extends IOException{final int code;HttpError(int code,String message){super(message);this.code=code;}}
}
