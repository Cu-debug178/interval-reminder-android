package com.example.reviewreminder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class AiClient {
    interface Callback { void onResult(JSONObject json); void onError(String message); }

    private static final ExecutorService WORKER=Executors.newCachedThreadPool();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    private AiClient() {}

    static Future<?> chatJson(Context context,String system,String user,Callback callback){
        return chat(context,system,user,null,null,callback);
    }

    static Future<?> chatVisionJson(Context context,String system,String user,String mime,String base64,Callback callback){
        return chat(context,system,user,mime,base64,callback);
    }

    private static Future<?> chat(Context context,String system,String user,String mime,String base64,Callback callback){
        Context app=context.getApplicationContext();
        AtomicBoolean cancelled=new AtomicBoolean(false);
        return WORKER.submit(()->{
            try{
                if(!AiSettings.ready(app))throw new IllegalStateException(AiSettings.notReadyMessage(app));
                Object userContent=user;
                if(base64!=null&&!base64.isEmpty()){
                    String type=mime==null||mime.isEmpty()?"image/jpeg":mime;
                    userContent=new JSONArray()
                        .put(new JSONObject().put("type","text").put("text",user))
                        .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:"+type+";base64,"+base64)));
                }
                JSONObject body=new JSONObject()
                    .put("model",AiSettings.model(app))
                    .put("temperature",0.2)
                    .put("messages",new JSONArray()
                        .put(new JSONObject().put("role","system").put("content",system))
                        .put(new JSONObject().put("role","user").put("content",userContent)));
                String raw=post(AiSettings.chatUrl(app),AiSettings.apiKey(app),body.toString());
                if(cancelled.get())return;
                JSONObject parsed=extractJson(raw);
                MAIN.post(()->{if(!cancelled.get())callback.onResult(parsed);});
            }catch(Exception e){
                String message=e.getMessage()==null?"AI 请求失败":e.getMessage();
                MAIN.post(()->{if(!cancelled.get())callback.onError(message);});
            }
        });
    }

    static void cancel(Future<?> future){if(future!=null)future.cancel(true);}

    private static String post(String endpoint,String apiKey,String json)throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(endpoint).openConnection();
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        conn.setRequestProperty("Authorization","Bearer "+apiKey);
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
        int code=conn.getResponseCode();
        InputStream stream=code>=200&&code<300?conn.getInputStream():conn.getErrorStream();
        String response=read(stream);
        if(code<200||code>=300)throw new IOException(errorMessage(code,response));
        JSONObject root=new JSONObject(response);
        JSONArray choices=root.optJSONArray("choices");
        if(choices==null||choices.length()==0)throw new IOException("模型没有返回内容");
        String content=choices.getJSONObject(0).optJSONObject("message").optString("content","").trim();
        if(content.isEmpty())throw new IOException("模型返回空内容");
        return content;
    }

    private static String errorMessage(int code,String body){
        try{
            JSONObject err=new JSONObject(body).optJSONObject("error");
            if(err!=null){String msg=err.optString("message","").trim();if(!msg.isEmpty())return "AI 错误 "+code+"："+msg;}
        }catch(Exception ignored){}
        if(body!=null&&!body.trim().isEmpty()&&body.length()<240)return "AI 错误 "+code+"："+body.trim();
        return "AI 错误 HTTP "+code;
    }

    static JSONObject extractJson(String content)throws Exception{
        String text=content==null?"":content.trim();
        if(text.startsWith("```")){
            int start=text.indexOf('\n');
            int end=text.lastIndexOf("```");
            if(start>=0&&end>start)text=text.substring(start+1,end).trim();
        }
        int obj=text.indexOf('{'), arr=text.indexOf('[');
        int begin=obj<0?arr:arr<0?obj:Math.min(obj,arr);
        if(begin>0)text=text.substring(begin);
        if(text.startsWith("[")){
            try{return new JSONObject().put("items",new JSONArray(text));}
            catch(Exception e){return new JSONObject().put("ok",true).put("message",content.trim());}
        }
        if(text.startsWith("{")){
            try{return new JSONObject(text);}
            catch(Exception e){return new JSONObject().put("ok",true).put("message",content.trim());}
        }
        // Some models (esp. brief pings) ignore JSON-only instructions.
        return new JSONObject().put("ok",true).put("message",text);
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;while((n=source.read(buf))!=-1)out.write(buf,0,n);
            return out.toString("UTF-8");
        }
    }
}
