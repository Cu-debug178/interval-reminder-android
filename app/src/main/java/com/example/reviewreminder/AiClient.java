package com.example.reviewreminder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

final class AiClient {
    interface Callback { void onResult(JSONObject json); void onError(String message); }

    private static final ExecutorService WORKER=Executors.newCachedThreadPool();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    private AiClient() {}

    static Future<?> chatJson(Context context,String system,String user,Callback callback){
        return chat(context,system,user,null,null,callback);
    }

    static Future<?> chatVisionJson(Context context,String system,String user,String mime,String base64,Callback callback){
        return chat(context,system,user,mime,base64==null||base64.isEmpty()?null:new String[]{base64},callback);
    }

    static Future<?> chatVisionJson(Context context,String system,String user,String mime,String[] base64Images,Callback callback){
        return chat(context,system,user,mime,base64Images,callback);
    }

    private static Future<?> chat(Context context,String system,String user,String mime,String[] base64Images,Callback callback){
        Context app=context.getApplicationContext();
        AtomicBoolean cancelled=new AtomicBoolean(false);
        return WORKER.submit(()->{
            try{
                if(!AiSettings.ready(app))throw new IllegalStateException(AiSettings.notReadyMessage(app));
                Object userContent=user;
                if(base64Images!=null&&base64Images.length>0){
                    String type=mime==null||mime.isEmpty()?"image/jpeg":mime;
                    JSONArray parts=new JSONArray().put(new JSONObject().put("type","text").put("text",user));
                    for(String base64:base64Images){
                        if(base64==null||base64.isEmpty())continue;
                        parts.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:"+type+";base64,"+base64)));
                    }
                    if(parts.length()==1)throw new IllegalStateException("没有可用的照片");
                    userContent=parts;
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

    /** Connection check or model list. {@code models} is empty when the provider has no list. */
    interface ProbeCallback { void onResult(boolean ok, String message, List<String> models); }

    /** Lists models, then sends a short chat so the chosen model is the one that actually answered. */
    static Future<?> probe(String baseUrl,String apiKey,String model,ProbeCallback callback){
        return WORKER.submit(()->{
            ProbeResult result=probeBlocking(baseUrl,apiKey,model);
            MAIN.post(()->callback.onResult(result.ok,result.message,result.models));
        });
    }

    /** GET …/models, trying both versioned and unversioned paths. Does not send a chat. */
    static Future<?> models(String baseUrl,String apiKey,ProbeCallback callback){
        return WORKER.submit(()->{
            ProbeResult result=modelsBlocking(baseUrl,apiKey);
            MAIN.post(()->callback.onResult(result.ok,result.message,result.models));
        });
    }

    static ProbeResult probeBlocking(String baseUrl,String apiKey,String model){
        if(model==null||model.trim().isEmpty())return ProbeResult.fail("请填写或选择模型");
        ProbeResult listed=modelsBlocking(baseUrl,apiKey);
        if(!listed.ok&&fatal(listed.message))return listed;
        ProbeResult pinged=ping(AiSettings.chatUrlFor(baseUrl),apiKey.trim(),model.trim());
        if(!pinged.ok)return pinged;
        List<String> models=listed.ok?listed.models:Collections.<String>emptyList();
        String message=listed.ok&&models.isEmpty()?"连接成功，该服务商未返回模型列表":"连接成功";
        return ProbeResult.ok(message,models);
    }

    static ProbeResult modelsBlocking(String baseUrl,String apiKey){
        String baseError=AiSettings.validateBase(baseUrl);
        if(baseError!=null)return ProbeResult.fail(baseError);
        if(apiKey==null||apiKey.trim().isEmpty())return ProbeResult.fail("请填写 API Key");
        String key=apiKey.trim();
        String lastError="没有拿到模型列表，请手动填写模型名";
        boolean sawMissing=false;
        for(String url:AiSettings.modelUrls(baseUrl)){
            HttpResult res=request("GET",url,key,null,12000,20000);
            if(res.error!=null)return ProbeResult.fail(explainNetwork(res.error));
            if(res.code==401||res.code==403)return ProbeResult.fail(explainHttp(res.code,res.body,key));
            if(res.code>=200&&res.code<300){
                List<String> models=parseModelIds(res.body);
                String message=models.isEmpty()?"服务商没有返回模型列表，请手动填写":"已获取 "+models.size()+" 个模型";
                return ProbeResult.ok(message,models);
            }
            if(res.code==404||res.code==405){sawMissing=true;continue;}
            lastError=explainHttp(res.code,res.body,key);
        }
        if(sawMissing)return ProbeResult.ok("没有拿到模型列表，请手动填写模型名",Collections.<String>emptyList());
        return ProbeResult.fail(lastError);
    }

    private static boolean fatal(String message){
        if(message==null)return false;
        return message.startsWith("密钥被拒绝")||message.startsWith("地址无效")||message.startsWith("请填写 API Key")
            ||message.startsWith("网络")||message.startsWith("连接超时")||message.startsWith("安全连接");
    }

    private static ProbeResult ping(String endpoint,String apiKey,String model){
        HttpResult first=request("POST",endpoint,apiKey,pingBody(model,true),15000,30000);
        if(first.error!=null)return ProbeResult.fail(explainNetwork(first.error));
        if(first.code>=200&&first.code<300){
            String contentError=chatContentError(first.body);
            return contentError==null?ProbeResult.ok("连接成功",Collections.<String>emptyList()):ProbeResult.fail(contentError);
        }
        if(first.code==401||first.code==403)return ProbeResult.fail(explainHttp(first.code,first.body,apiKey));
        if(first.code==400){
            HttpResult second=request("POST",endpoint,apiKey,pingBody(model,false),15000,30000);
            if(second.error!=null)return ProbeResult.fail(explainNetwork(second.error));
            if(second.code>=200&&second.code<300){
                String contentError=chatContentError(second.body);
                return contentError==null?ProbeResult.ok("连接成功",Collections.<String>emptyList()):ProbeResult.fail(contentError);
            }
            return ProbeResult.fail(explainHttp(second.code,second.body,apiKey));
        }
        return ProbeResult.fail(explainHttp(first.code,first.body,apiKey));
    }

    private static String pingBody(String model,boolean strict){
        try{
            JSONObject body=new JSONObject().put("model",model);
            JSONArray messages=new JSONArray();
            if(strict){
                body.put("temperature",0).put("max_tokens",16);
                messages.put(new JSONObject().put("role","system").put("content",AiPrompts.systemPing()));
            }
            messages.put(new JSONObject().put("role","user").put("content",AiPrompts.userPing()));
            return body.put("messages",messages).toString();
        }catch(Exception e){return "{\"model\":\""+model.replace("\"","")+"\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";}
    }

    static List<String> parseModelIds(String body){
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        try{
            String text=body==null?"":body.trim();
            if(text.startsWith("["))collectModels(ids,new JSONArray(text));
            else{
                JSONObject root=new JSONObject(text);
                collectModels(ids,root.optJSONArray("data"));
                if(ids.isEmpty())collectModels(ids,root.optJSONArray("models"));
            }
        }catch(Exception ignored){}
        List<String> list=new ArrayList<>(ids);
        Collections.sort(list,String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private static void collectModels(LinkedHashSet<String> ids,JSONArray data)throws Exception{
        if(data==null)return;
        for(int i=0;i<data.length();i++){
            Object item=data.get(i);
            String id="";
            if(item instanceof String)id=((String)item).trim();
            else if(item instanceof JSONObject){
                JSONObject object=(JSONObject)item;
                id=object.optString("id",object.optString("name","")).trim();
            }
            if(!id.isEmpty())ids.add(id);
        }
    }

    /** Short Chinese reason. The API key is never left in the text. */
    static String explainHttp(int code,String body,String apiKey){
        String msg="";
        try{
            JSONObject err=new JSONObject(body==null?"{}":body).optJSONObject("error");
            if(err!=null)msg=err.optString("message","").trim();
        }catch(Exception ignored){}
        if(msg.isEmpty())msg=clip(body);
        msg=redact(msg,apiKey);
        if(code==401||code==403)return msg.isEmpty()?"密钥被拒绝":"密钥被拒绝："+clip(msg);
        String lower=msg.toLowerCase(Locale.US);
        if((code==400||code==404)&&(lower.contains("model")||msg.contains("模型")))return "模型不可用，请检查模型名";
        if(code==404||code==405)return "接口地址不正确";
        if(!msg.isEmpty())return "连接失败："+clip(msg);
        return "连接失败（HTTP "+code+"）";
    }

    static String explainNetwork(Exception e){
        if(e instanceof IllegalArgumentException||e instanceof java.net.MalformedURLException)return "地址无效";
        if(e instanceof UnknownHostException)return "网络不可用或域名无法解析";
        if(e instanceof SocketTimeoutException)return "连接超时";
        if(e instanceof SSLException)return "安全连接失败";
        if(e instanceof ConnectException)return "网络连接失败";
        return "网络连接失败";
    }

    static String redact(String message,String secret){
        String value=message==null?"":message;
        if(secret!=null&&secret.length()>=4)value=value.replace(secret,"••••");
        return value.replaceAll("sk-[A-Za-z0-9_\\-]{8,}","••••");
    }

    private static String clip(String value){
        if(value==null)return "";
        String text=value.replace('\n',' ').replace('\r',' ').trim();
        String lower=text.toLowerCase(Locale.US);
        if(lower.contains("<html")||lower.contains("<!doctype"))return "";
        if(text.length()>80)text=text.substring(0,80)+"…";
        return text;
    }

    private static String chatContentError(String response){
        try{
            JSONObject root=new JSONObject(response==null?"{}":response);
            JSONArray choices=root.optJSONArray("choices");
            if(choices==null||choices.length()==0)return "模型没有返回内容";
            JSONObject message=choices.getJSONObject(0).optJSONObject("message");
            String content=message==null?"":message.optString("content","").trim();
            if(content.isEmpty())return "模型返回空内容";
            return null;
        }catch(Exception e){return "连接失败";}
    }

    private static HttpResult request(String method,String endpoint,String apiKey,String json,int connectMs,int readMs){
        HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)new URL(endpoint).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept","application/json");
            conn.setRequestProperty("Authorization","Bearer "+apiKey);
            if(json!=null){
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            }
            int code=conn.getResponseCode();
            InputStream stream=code>=200&&code<300?conn.getInputStream():conn.getErrorStream();
            return HttpResult.http(code,read(stream));
        }catch(Exception e){return HttpResult.fail(e);}
        finally{if(conn!=null)conn.disconnect();}
    }

    static final class ProbeResult {
        final boolean ok; final String message; final List<String> models;
        private ProbeResult(boolean ok,String message,List<String> models){this.ok=ok;this.message=message;this.models=models==null?Collections.<String>emptyList():models;}
        static ProbeResult ok(String message,List<String> models){return new ProbeResult(true,message,models);}
        static ProbeResult fail(String message){return new ProbeResult(false,message==null||message.isEmpty()?"连接失败":message,Collections.<String>emptyList());}
    }

    private static final class HttpResult {
        final int code; final String body; final Exception error;
        private HttpResult(int code,String body,Exception error){this.code=code;this.body=body;this.error=error;}
        static HttpResult http(int code,String body){return new HttpResult(code,body,null);}
        static HttpResult fail(Exception error){return new HttpResult(0,"",error);}
    }

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
