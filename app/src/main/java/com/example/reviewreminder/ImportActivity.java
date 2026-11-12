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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ImportActivity extends ScreenActivity {
    private WordUi ui;private EditText source;private Future<?> pending;private boolean busy;
    private File cameraPhoto;private final List<File> pendingPhotos=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);
        if(state!=null){
            String camera=state.getString("camera");if(camera!=null)cameraPhoto=new File(camera);
            ArrayList<String> paths=state.getStringArrayList("pendingPhotos");
            if(paths!=null)for(String path:paths)pendingPhotos.add(new File(path));
        }
        home();
    }
    @Override public void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        if(cameraPhoto!=null)out.putString("camera",cameraPhoto.getAbsolutePath());
        ArrayList<String> paths=new ArrayList<>();
        for(File photo:pendingPhotos)paths.add(photo.getAbsolutePath());
        out.putStringArrayList("pendingPhotos",paths);
    }
    @Override protected void onDestroy(){AiClient.cancel(pending);worker.shutdownNow();super.onDestroy();}

    private void home(){
        busy=false;source=null;ui.start("录词");
        ui.body.addView(ui.text("可多张拍照/选图云端识词，或粘贴词表后整理。",13,WordUi.MUTED));ui.gap(ui.body,14);
        if(!pendingPhotos.isEmpty()){
            ui.body.addView(ui.text("已选 "+pendingPhotos.size()+" 张照片，可继续添加或开始识别。",14,WordUi.GREEN));ui.gap(ui.body,10);
            LinearLayout batch=ui.row();
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=ui.dp(8);
            batch.addView(ui.button("开始云端识别",true,v->cloudRecognizeAll()),left);
            batch.addView(ui.button("清空照片",false,v->{pendingPhotos.clear();home();}),new LinearLayout.LayoutParams(0,-2,1));
            ui.body.addView(batch);ui.gap(ui.body,14);
        }
        ui.body.addView(ui.button("拍摄单词照片",true,v->camera()));ui.gap(ui.body,8);
        ui.body.addView(ui.ghostButton("从相册选择（可多选）",v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            try{startActivityForResult(i,10);}catch(Exception e){ui.toast("没有可用的图片选择器");}
        }));ui.gap(ui.body,20);
        source=ui.field("词表原文","",6);
        source.setHint("例如：\nambition n. 野心\nconquer v. 征服");
        ui.body.addView(ui.button("AI 整理成单词卡",false,v->aiImport()));ui.gap(ui.body,8);
        if(Words.hasDraft(this)){ui.body.addView(ui.button("继续校对草稿",false,v->edit()));ui.gap(ui.body,8);}
        ui.body.addView(ui.textButton("更多录入方式",v->moreImportOptions()));ui.gap(ui.body,12);
        String tip=AiSettings.ready(this)
            ?"AI 已配置。多图识词建议用小米 MiMo。"
            :"未开启 AI 时可用本地解析或手动添加。照片识词请到「我的 > AI」配置。";
        ui.body.addView(ui.text(tip,13,WordUi.MUTED));
        Motion.enter(ui.body);
    }

    private void moreImportOptions(){
        String[] items={"本地解析并校对","手动添加一组单词"};
        new AlertDialog.Builder(this).setTitle("更多录入方式").setItems(items,(d,which)->{
            if(which==0)localImport();else edit();
        }).setNegativeButton("取消",null).show();
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
            if(cameraPhoto!=null&&cameraPhoto.length()>0){
                pendingPhotos.add(cameraPhoto);cameraPhoto=null;home();
                ui.toast("已加入照片，共 "+pendingPhotos.size()+" 张");
            }else ui.toast("没有收到拍摄照片");
        }else if(request==10&&data!=null){
            if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
            List<Uri> uris=new ArrayList<>();
            if(data.getClipData()!=null){
                ClipData clip=data.getClipData();
                for(int i=0;i<clip.getItemCount();i++)uris.add(clip.getItemAt(i).getUri());
            }else if(data.getData()!=null)uris.add(data.getData());
            if(uris.isEmpty()){ui.toast("没有选中照片");return;}
            if(uris.size()>12){ui.toast("一次最多 12 张，请分批选择");return;}
            loading("正在读取 "+uris.size()+" 张照片…");
            worker.execute(()->{
                try{
                    List<File> copied=new ArrayList<>();
                    for(Uri uri:uris){
                        File local=newPhoto("photo-");
                        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){
                            if(in==null)throw new IOException("无法读取照片");
                            byte[] b=new byte[65536];int n;long size=0;
                            while((n=in.read(b))!=-1){size+=n;if(size>40*1024*1024)throw new IOException("请选取小于 40 MB 的照片");out.write(b,0,n);}
                        }
                        copied.add(local);
                    }
                    runOnUiThread(()->{pendingPhotos.addAll(copied);home();ui.toast("已加入 "+copied.size()+" 张，共 "+pendingPhotos.size()+" 张");});
                }catch(Exception e){runOnUiThread(()->{home();ui.toast(e.getMessage());});}
            });
        }
    }

    private void loading(String label){ui.start(label);ui.body.addView(new ProgressBar(this));busy=true;}

    private void cloudRecognizeAll(){
        if(!alive()||pendingPhotos.isEmpty())return;
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        final List<File> photos=new ArrayList<>(pendingPhotos);
        loading("云端识别中");
        ui.body.addView(ui.text("正在识别 "+photos.size()+" 张照片…",14,WordUi.MUTED));
        worker.execute(()->{
            try{
                List<String> encoded=new ArrayList<>();
                for(File photo:photos)encoded.add(encodeJpegBase64(photo));
                String[] images=encoded.toArray(new String[0]);
                JSONArray paths=new JSONArray();for(File photo:photos)paths.put(photo.getAbsolutePath());
                runOnUiThread(()->{
                    if(!alive())return;
                    pending=AiClient.chatVisionJson(this,AiPrompts.systemPhotoImport(),AiPrompts.userPhotoImportMany(photos.size()),"image/jpeg",images,new AiClient.Callback(){
                        @Override public void onResult(JSONObject json){
                            if(!alive())return;busy=false;
                            try{
                                applyAiWords(json,paths,"云端识图草稿，待校对");
                                pendingPhotos.clear();
                            }catch(Exception e){home();ui.toast("识别失败："+e.getMessage());}
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
            saveDraft(found,new JSONArray(),"本地解析草稿，待校对");edit();
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
                try{applyAiWords(json,new JSONArray(),"AI 整理草稿，待校对");}
                catch(Exception e){home();ui.toast("整理失败："+e.getMessage());}
            }
            @Override public void onError(String message){if(!alive())return;busy=false;home();ui.toast(message);}
        });
    }

    private void applyAiWords(JSONObject json,JSONArray photoPaths,String sourceLabel)throws Exception{
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
        saveDraft(cleaned,photoPaths==null?new JSONArray():photoPaths,sourceLabel);
        ui.toast("已生成 "+cleaned.length()+" 张单词卡");home();edit();
    }

    private void applyAiWords(JSONObject json,String photoPath,String sourceLabel)throws Exception{
        JSONArray photos=new JSONArray();
        if(photoPath!=null&&!photoPath.isEmpty())photos.put(photoPath);
        applyAiWords(json,photos,sourceLabel);
    }

    private void saveDraft(JSONArray words,JSONArray photos,String sourceLabel)throws Exception{
        JSONObject draft=Words.draft(this);
        draft.put("words",words).put("rawOcr","").put("source",sourceLabel).put("photos",photos==null?new JSONArray():photos);
        if(draft.optString("title","").trim().isEmpty())draft.put("title","");
        Words.writeDraft(this,draft);
    }

    @Override public void onBackPressed(){if(busy){ui.toast("正在处理，请稍候");return;}super.onBackPressed();}
}
