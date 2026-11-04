package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImportActivity extends ScreenActivity {
    private WordUi ui;private File cameraPhoto,currentPhoto;private final ExecutorService worker=Executors.newSingleThreadExecutor();private boolean busy;private EditText recognized;private String rawResult;
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    @Override public void onCreate(Bundle state){super.onCreate(state);ui=new WordUi(this);if(state!=null){String camera=state.getString("camera"),photo=state.getString("photo");if(camera!=null)cameraPhoto=new File(camera);if(photo!=null)currentPhoto=new File(photo);rawResult=state.getString("rawResult");}if(currentPhoto!=null&&rawResult!=null)result(rawResult,currentPhoto.getAbsolutePath());else if(currentPhoto!=null)prepare(currentPhoto);else home();}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(cameraPhoto!=null)out.putString("camera",cameraPhoto.getAbsolutePath());if(currentPhoto!=null)out.putString("photo",currentPhoto.getAbsolutePath());if(rawResult!=null)out.putString("rawResult",recognized==null?rawResult:recognized.getText().toString());}
    private void home(){
        busy=false;currentPhoto=null;rawResult=null;recognized=null;ui.start("照片录词");ui.body.addView(ui.button("拍摄单词照片",true,v->camera()));ui.gap(ui.body,12);ui.body.addView(ui.button("从相册选择照片",false,v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,10);}catch(Exception e){ui.toast("没有可用的图片选择器");}}));ui.gap(ui.body,24);
        ui.body.addView(ui.button("继续校对草稿",false,v->edit()));ui.gap(ui.body,10);
        ui.body.addView(ui.button("打开 Lesson 1 待校对词组",false,v->{new AlertDialog.Builder(this).setTitle("打开 Lesson 1？").setMessage("将用你提供的两页照片整理出的 42 张卡替换当前草稿，不影响已经创建的任务。此词表为人工整理，仍请校对。").setNegativeButton("取消",null).setPositiveButton("打开",(d,w)->{try{Words.lesson(this);edit();}catch(Exception e){ui.toast(e.getMessage());}}).show();}));
        ui.gap(ui.body,10);ui.body.addView(ui.button("手动添加一组单词",false,v->edit()));ui.gap(ui.body,18);ui.body.addView(ui.text("照片仅在设备上识别和保存。识别结果需要校对，不会自动补写释义或例句。",13,WordUi.MUTED));Motion.enter(ui.body);
    }
    private void edit(){Motion.open(this,new Intent(this,WordEditorActivity.class));}
    private File newPhoto(String prefix) throws IOException {File folder=new File(getFilesDir(),"photos");if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法保存照片");return new File(folder,prefix+UUID.randomUUID()+".jpg");}
    private void camera(){try{cameraPhoto=newPhoto("capture-");Uri uri=PhotoProvider.uri(cameraPhoto);Intent capture=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);capture.setClipData(ClipData.newRawUri("照片",uri));startActivityForResult(capture,11);}catch(Exception e){ui.toast("无法打开相机，可从相册选图："+e.getMessage());}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK)return;
        if(request==11){if(cameraPhoto!=null&&cameraPhoto.length()>0)prepare(cameraPhoto);else ui.toast("没有收到拍摄照片");}
        else if(request==10&&data!=null&&data.getData()!=null){Uri uri=data.getData();loading("正在读取照片…");worker.execute(()->{try{File local=newPhoto("photo-");try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){if(in==null)throw new IOException("无法读取照片");byte[] b=new byte[65536];int n;long size=0;while((n=in.read(b))!=-1){size+=n;if(size>40*1024*1024)throw new IOException("请选取小于 40 MB 的照片");out.write(b,0,n);}}runOnUiThread(()->prepare(local));}catch(Exception e){runOnUiThread(()->{home();ui.toast(e.getMessage());});}});}
    }
    private void loading(String label){ui.start(label);ui.body.addView(new ProgressBar(this));busy=true;}
    private void prepare(File photo){if(!alive())return;currentPhoto=photo;rawResult=null;recognized=null;loading("正在准备照片…");worker.execute(()->{try{Bitmap image=Ocr.decode(photo);runOnUiThread(()->{if(!alive()){image.recycle();return;}busy=false;crop(image);});}catch(Exception e){runOnUiThread(()->{if(!alive())return;busy=false;home();ui.toast("照片读取失败："+e.getMessage());});}});}
    private void crop(Bitmap image){
        ui.start("选择需要识别的区域");CropView crop=new CropView(this,image);ui.body.addView(crop,new LinearLayout.LayoutParams(-1,ui.dp(400)));ui.gap(ui.body,12);
        ui.body.addView(ui.button("识别选中区域",true,v->{if(busy)return;Bitmap part=crop.cropped();File original=currentPhoto;loading("正在离线识别…");worker.execute(()->{try{String raw=Ocr.recognize(getApplicationContext(),part);runOnUiThread(()->{if(!alive())return;busy=false;result(raw,original.getAbsolutePath());});}catch(Throwable e){runOnUiThread(()->{if(!alive())return;busy=false;ui.toast("识别失败，可重试或手动录入："+e.getMessage());prepare(original);});}finally{part.recycle();}});}));ui.gap(ui.body,10);
        LinearLayout buttons=ui.row();buttons.addView(ui.button("旋转 90°",false,v->crop.rotate()),new LinearLayout.LayoutParams(0,-2,1));buttons.addView(ui.button("换一张",false,v->home()),new LinearLayout.LayoutParams(0,-2,1));ui.body.addView(buttons);Motion.enter(ui.body);
    }
    private void result(String raw,String photo){
        rawResult=raw;ui.start("识别结果");EditText source=ui.field("识别原文",raw,7);recognized=source;source.setGravity(android.view.Gravity.TOP|android.view.Gravity.START);source.setMaxLines(30);ui.body.addView(ui.text("识别结果待校对",13,WordUi.MUTED));ui.gap(ui.body,14);
        ui.body.addView(ui.button("生成单词卡并校对",true,v->{try{JSONObject draft=Words.draft(this);JSONArray found=Words.array(WordParser.parse(source.getText().toString()));if(found.length()==0){ui.toast("未识别出单词，请调整区域或手动添加");return;}JSONArray words=draft.getJSONArray("words");for(int i=0;i<found.length();i++)words.put(found.getJSONObject(i));JSONArray photos=draft.optJSONArray("photos");if(photos==null)photos=new JSONArray();photos.put(photo);draft.put("photos",photos).put("rawOcr",source.getText().toString()).put("source","离线 OCR 草稿，待校对");Words.writeDraft(this,draft);currentPhoto=null;edit();home();}catch(Exception e){ui.toast("保存失败："+e.getMessage());}}));ui.gap(ui.body,10);ui.body.addView(ui.button("重新裁切",false,v->prepare(new File(photo))));Motion.enter(ui.body);
    }
    @Override public void onBackPressed(){if(busy){ui.toast("正在处理，请稍候");return;}if(currentPhoto!=null)home();else super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
