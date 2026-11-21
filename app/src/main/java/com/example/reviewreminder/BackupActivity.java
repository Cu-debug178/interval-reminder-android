package com.example.reviewreminder;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local JSON backup UI; no keys are exported and restore is validated before commit. */
public final class BackupActivity extends ScreenActivity {
    private static final int CREATE=30,OPEN=31;
    private WordUi ui;private CheckBox images,audio;private Button export,restore;private TextView status;private BackupManager.Backup loaded;private boolean busy;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);ui.start("数据备份与恢复");
        ui.body.addView(ui.text("导出版本化 JSON 备份，可在另一台设备或重装后恢复。备份不会包含任何 API Key。词汇关系会一并导出；参考音频默认不导出，旧的附带图片可单独选择。",13,WordUi.MUTED));ui.gap(ui.body,16);
        LinearLayout card=ui.card();card.addView(ui.text("包含内容",15,Theme.INK));ui.gap(card,4);card.addView(ui.text("计划与单词、复习进度、拼写历史、错题本、词汇关系、训练选词、AI 翻译记录和音色配置。",12,WordUi.MUTED));ui.gap(card,10);
        images=new CheckBox(this);images.setText("包含单词卡旧图片与卡片外观图片");images.setChecked(true);images.setMinHeight(ui.dp(48));card.addView(images);
        audio=new CheckBox(this);audio.setText("包含参考音频（可能包含你的声音，体积较大）");audio.setChecked(false);audio.setMinHeight(ui.dp(48));card.addView(audio);
        ui.body.addView(card);ui.gap(ui.body,16);
        export=ui.primaryButton("导出备份文件",v->chooseExport());ui.body.addView(export);ui.gap(ui.body,10);
        restore=ui.secondaryButton("从文件恢复",v->chooseImport());ui.body.addView(restore);ui.gap(ui.body,16);
        status=ui.text("尚未选择备份文件。",13,WordUi.MUTED);ui.body.addView(status);
        ui.body.addView(ui.text("恢复会先完整读取并校验文件，再一次性写入；失败时不会先清空现有数据。替换模式仍需二次确认。",12,WordUi.MUTED));
    }
    private void chooseExport(){
        if(busy)return;
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmm",Locale.CHINA).format(new Date());
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"interval-reminder-backup-"+stamp+".json");
        try{startActivityForResult(intent,CREATE);}catch(Exception e){ui.toast("没有可用的文件保存器");}
    }
    private void chooseImport(){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{startActivityForResult(intent,OPEN);}catch(Exception e){ui.toast("没有可用的文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();if(request==CREATE)write(uri);else if(request==OPEN)read(uri);
    }
    private void write(Uri uri){
        final boolean includeAudio=audio.isChecked(),includeImages=images.isChecked();
        setBusy(true,"正在生成备份…");
        worker.execute(()->{
            try{JSONObject json=BackupManager.exportData(getApplicationContext(),includeAudio,includeImages);String text=json.toString(2);
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IllegalStateException("无法写入目标文件");out.write(text.getBytes("UTF-8"));out.flush();}
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;setBusy(false,"备份已导出："+summary(json)+"\n未包含 API Key"+(includeAudio?"，包含参考音频":"，未包含参考音频")+(includeImages?"，包含旧图片":"，未包含旧图片"));});
            }catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;setBusy(false,"导出失败："+message(e));});}
        });
    }
    private void read(Uri uri){
        setBusy(true,"正在读取并校验备份…");
        worker.execute(()->{
            try{
                String json=BackupManager.readJson(open(uri));BackupManager.Backup backup=BackupManager.inspect(json);
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;loaded=backup;setBusy(false,"已校验，可恢复："+summary(backup)+"\n请选择合并或替换。");askMode(backup);});
            }catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;setBusy(false,"无法恢复："+message(e));});}
        });
    }
    private InputStream open(Uri uri)throws Exception{InputStream in=getContentResolver().openInputStream(uri);if(in==null)throw new IllegalStateException("无法读取备份文件");return in;}
    private void askMode(BackupManager.Backup backup){
        new Sheet(this).title("恢复这份备份？").message(summary(backup)+"\n\n合并会保留现有数据并按稳定 ID 合并历史。替换会用备份覆盖当前计划、复习进度、错题本、翻译记录和音色配置。")
            .item(R.drawable.ic_layers,"合并到现有数据",()->apply(backup,false))
            .dangerItem(R.drawable.ic_rotate_cw,"替换全部数据",()->confirmReplace(backup))
            .secondary("取消",null).show();
    }
    private void confirmReplace(BackupManager.Backup backup){
        Sheet.confirm(this,"确认替换全部数据？","当前设备上的计划、复习进度、错词历史、拼写进度、翻译题和音色配置将被备份内容替换。API Key、语音服务地址和提醒时段不会改动。此操作不能撤销。","取消","确认替换",true,()->apply(backup,true));
    }
    private void apply(BackupManager.Backup backup,boolean replace){
        if(busy)return;setBusy(true,replace?"正在替换数据…":"正在合并数据…");
        worker.execute(()->{
            try{BackupManager.Result result=BackupManager.apply(getApplicationContext(),backup,replace);
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;loaded=null;String detail=result.summary()+" "+result.tasksAdded+" 个新计划，"+result.wordsAdded+" 个新单词，"+result.wrongStates+" 条错词记录，"+result.relations+" 条词汇关系，"+result.exercises+" 道翻译练习，"+result.profiles+" 个音色，"+result.images+" 张旧图片。"+"\n已重新安排未完成计划的提醒。";if(result.warning!=null&&!result.warning.isEmpty())detail+="\n"+result.warning;setBusy(false,detail);ui.toast(result.warning==null||result.warning.isEmpty()?"备份恢复完成":"备份已写入，但有部分内容需要检查");});
            }catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;setBusy(false,"恢复失败，原有数据未被清空："+message(e));});}
        });
    }
    private void setBusy(boolean value,String message){busy=value;if(export!=null)export.setEnabled(!value);if(restore!=null)restore.setEnabled(!value);if(images!=null)images.setEnabled(!value);if(audio!=null)audio.setEnabled(!value);if(status!=null){status.setText(message);status.setTextColor(value?Theme.INK:Theme.MUTED);}}
    private String summary(JSONObject json){int tasks=json.optJSONArray("tasks")==null?0:json.optJSONArray("tasks").length();int words=0;JSONArray array=json.optJSONArray("tasks");for(int i=0;array!=null&&i<array.length();i++){JSONObject task=array.optJSONObject(i);if(task!=null&&task.optJSONArray("words")!=null)words+=task.optJSONArray("words").length();}return tasks+" 个计划、"+words+" 个单词、"+json.optJSONArray("translationExercises").length()+" 道翻译练习、"+json.optJSONObject("wrongStates").length()+" 条错词状态。";}
    private String summary(BackupManager.Backup backup){return backup.tasks.length()+" 个计划、"+countWords(backup.tasks)+" 个单词、"+backup.translationExercises.length()+" 道翻译练习、"+backup.wrongStates.length()+" 条错词状态。";}
    private int countWords(JSONArray tasks){int words=0;for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task!=null&&task.optJSONArray("words")!=null)words+=task.optJSONArray("words").length();}return words;}
    private String message(Exception e){String value=e.getMessage();return value==null||value.trim().isEmpty()?e.getClass().getSimpleName():value;}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
