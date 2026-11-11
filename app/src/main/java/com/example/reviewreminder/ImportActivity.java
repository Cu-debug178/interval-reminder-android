package com.example.reviewreminder;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.EditText;
import android.widget.ProgressBar;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ImportActivity extends ScreenActivity {
    private WordUi ui;private EditText source;private Future<?> pending;private boolean busy;
    private File cameraPhoto;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);
        if(state!=null){String camera=state.getString("camera");if(camera!=null)cameraPhoto=new File(camera);}
        home();
    }
    @Override public void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        if(cameraPhoto!=null)out.putString("camera",cameraPhoto.getAbsolutePath());
    }
    @Override protected void onDestroy(){AiClient.cancel(pending);worker.shutdownNow();super.onDestroy();}

    private void home(){
        busy=false;source=null;ui.start("录词");
        ui.body.addView(ui.text("可拍照/选图走云端识词，或粘贴词表后用 AI 整理；也支持本地解析与手动添加。",13,WordUi.MUTED));ui.gap(ui.body,14);
        ui.body.addView(ui.button("拍摄单词照片（云端识别）",true,v->camera()));ui.gap(ui.body,10);
        ui.body.addView(ui.button("从相册选择照片（云端识别）",false,v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            try{startActivityForResult(i,10);}catch(Exception e){ui.toast("没有可用的图片选择器");}
        }));ui.gap(ui.body,18);
        source=ui.field("词表原文","",8);
        source.setHint("例如：\nambition n. 野心\nconquer v. 征服\n或一行一个英文单词");
        ui.body.addView(ui.button("AI 整理成单词卡",true,v->aiImport()));ui.gap(ui.body,10);
        ui.body.addView(ui.button("本地解析并校对",false,v->localImport()));ui.gap(ui.body,18);
        if(Words.hasDraft(this)){ui.body.addView(ui.button("继续校对草稿",false,v->edit()));ui.gap(ui.body,10);}
        ui.body.addView(ui.button("打开 Lesson 1 词组",false,v->{new AlertDialog.Builder(this).setTitle("打开 Lesson 1？").setMessage("将用内置 42 张卡替换当前草稿，不影响已创建的任务。仍建议校对。").setNegativeButton("取消",null).setPositiveButton("打开",(d,w)->{try{Words.lesson(this);edit();}catch(Exception e){ui.toast(e.getMessage());}}).show();}));
        ui.gap(ui.body,10);ui.body.addView(ui.button("手动添加一组单词",false,v->edit()));
        ui.gap(ui.body,18);
        String tip=AiSettings.ready(this)
            ?(AiSettings.provider(this)==AiSettings.PROVIDER_MIMO||AiSettings.model(this).toLowerCase().contains("mimo")
                ?"AI 已配置。照片识词建议用小米 MiMo（支持看图）。"
                :"AI 已配置。照片识词需看图模型（推荐小米 MiMo）；纯文本整理可用当前模型。")
            :"未开启 AI 时仍可用本地解析或手动添加。照片识词请到「我的 > AI」配置小米 MiMo。";
        ui.body.addView(ui.text(tip,13,WordUi.MUTED));
        Motion.enter(ui.body);
    }

    private void edit(){
        String[] modes={"快速确认（推荐）","逐项编辑"};
        new AlertDialog.Builder(this).setTitle("选择校对方式").setItems(modes,(dialog,which)->
                Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("quickReview",which==0)))
                .show();
    }

    private File newPhoto(String prefix) throws IOException {
        File folder=new File(getFilesDir(),"photos");
        if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法保存照片");
        return new File(folder,prefix+UUID.randomUUID()+".jpg");
    }

    private void camera(){
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        try{
            cameraPhoto=newPhoto("capture-");
            Uri uri=PhotoProvider.uri(this,cameraPhoto);
            Intent capture=new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT,uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            capture.setClipData(ClipData.newRawUri("照片",uri));
            startActivityForResult(capture,11);
        }catch(Exception e){ui.toast("无法打开相机，可从相册选图："+e.getMessage());}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK)return;
        if(request==11){
            if(cameraPhoto!=null&&cameraPhoto.length()>0)cloudRecognize(cameraPhoto);
            else ui.toast("没有收到拍摄照片");
        }else if(request==10&&data!=null&&data.getData()!=null){
            if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
            Uri uri=data.getData();
            loading("正在读取照片…");
            worker.execute(()->{
                try{
                    File local=newPhoto("photo-");
                    try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){
                        if(in==null)throw new IOException("无法读取照片");
                        byte[] b=new byte[65536];int n;long size=0;
                        while((n=in.read(b))!=-1){size+=n;if(size>40*1024*1024)throw new IOException("请选取小于 40 MB 的照片");out.write(b,0,n);}
                    }
                    runOnUiThread(()->cloudRecognize(local));
                }catch(Exception e){runOnUiThread(()->{home();ui.toast(e.getMessage());});}
            });
        }
    }

    private void loading(String label){ui.start(label);ui.body.addView(new ProgressBar(this));busy=true;}

    private void cloudRecognize(File photo){
        if(!alive())return;
        if(!AiSettings.ready(this)){home();ui.toast(AiSettings.notReadyMessage(this));return;}
        loading("云端识别中");
        ui.body.addView(ui.text("正在把照片发给模型识词，请稍候…",14,WordUi.MUTED));
        worker.execute(()->{
            try{
                String base64=encodeJpegBase64(photo);
                runOnUiThread(()->{
                    if(!alive())return;
                    pending=AiClient.chatVisionJson(this,AiPrompts.systemPhotoImport(),AiPrompts.userPhotoImport(),"image/jpeg",base64,new AiClient.Callback(){
                        @Override public void onResult(JSONObject json){
                            if(!alive())return;busy=false;
                            try{applyAiWords(json,photo.getAbsolutePath(),"云端识图草稿，待校对");}
                            catch(Exception e){home();ui.toast("识别失败："+e.getMessage());}
                        }
                        @Override public void onError(String message){if(!alive())return;busy=false;home();ui.toast(message);}
                    });
                });
            }catch(Exception e){runOnUiThread(()->{if(!alive())return;busy=false;home();ui.toast("照片处理失败："+e.getMessage());});}
        });
    }

    private static String encodeJpegBase64(File photo)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(photo.getAbsolutePath(),bounds);
        int max=Math.max(bounds.outWidth,bounds.outHeight);int sample=1;
        while(max/sample>1600)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=Math.max(1,sample);
        Bitmap bitmap=BitmapFactory.decodeFile(photo.getAbsolutePath(),opts);
        if(bitmap==null)throw new IOException("无法解码照片");
        Bitmap scaled=null;
        try{
            int w=bitmap.getWidth(),h=bitmap.getHeight(),side=Math.max(w,h);
            scaled=bitmap;
            if(side>1600){
                float scale=1600f/side;
                scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),true);
            }
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(!scaled.compress(Bitmap.CompressFormat.JPEG,78,out))throw new IOException("照片压缩失败");
            return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
        }finally{
            if(scaled!=null&&scaled!=bitmap)scaled.recycle();
            bitmap.recycle();
        }
    }

    private void localImport(){
        try{
            String raw=source==null?"":source.getText().toString();
            JSONArray found=Words.array(WordParser.parse(raw));
            if(found.length()==0){ui.toast("未解析出单词，可改用 AI 整理或手动添加");return;}
            saveDraft(found,"","本地解析草稿，待校对");edit();
        }catch(Exception e){ui.toast("解析失败："+e.getMessage());}
    }

    private void aiImport(){
        if(busy)return;
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        String raw=source==null?"":source.getText().toString().trim();
        if(raw.isEmpty()){ui.toast("请先粘贴或输入词表");return;}
        busy=true;ui.start("AI 整理中");ui.body.addView(new ProgressBar(this));ui.body.addView(ui.text("正在调用模型整理单词卡…",14,WordUi.MUTED));
        pending=AiClient.chatJson(this,AiPrompts.systemImport(),AiPrompts.userImport(raw),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                if(!alive())return;busy=false;
                try{applyAiWords(json,"","AI 整理草稿，待校对");}
                catch(Exception e){home();ui.toast("整理失败："+e.getMessage());}
            }
            @Override public void onError(String message){if(!alive())return;busy=false;home();ui.toast(message);}
        });
    }

    private void applyAiWords(JSONObject json,String photoPath,String sourceLabel)throws Exception{
        JSONArray words=json.optJSONArray("words");
        if(words==null||words.length()==0)throw new Exception("模型没有返回单词卡");
        JSONArray cleaned=new JSONArray();
        for(int i=0;i<words.length();i++){
            JSONObject w=words.optJSONObject(i);if(w==null)continue;
            String en=w.optString("english","").trim();if(en.isEmpty())continue;
            JSONObject card=new JSONObject().put("english",en).put("chinese",w.optString("chinese","").trim()).put("example",w.optString("example","").trim());
            JSONArray senses=w.optJSONArray("senses");
            if(senses!=null&&senses.length()>0)card.put("senses",senses);
            else{try{Senses.ensure(card);}catch(Exception ignored){}}
            cleaned.put(card);
        }
        if(cleaned.length()==0)throw new Exception("模型返回的卡片无效");
        saveDraft(cleaned,photoPath,sourceLabel);
        ui.toast("已生成 "+cleaned.length()+" 张单词卡");home();edit();
    }

    private void saveDraft(JSONArray words,String photoPath,String sourceLabel)throws Exception{
        JSONObject draft=Words.draft(this);
        draft.put("words",words).put("rawOcr","").put("source",sourceLabel);
        JSONArray photos=new JSONArray();
        if(photoPath!=null&&!photoPath.isEmpty())photos.put(photoPath);
        draft.put("photos",photos);
        if(draft.optString("title","").trim().isEmpty())draft.put("title","");
        Words.writeDraft(this,draft);
    }

    @Override public void onBackPressed(){if(busy){ui.toast("正在处理，请稍候");return;}super.onBackPressed();}
}
