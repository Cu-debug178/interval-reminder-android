package com.example.reviewreminder;

import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 录词 — bring new words in: photos (camera / gallery, recognised in the cloud), a pasted word
 * list (AI organise or local parse) or manual entry. Every path ends in a draft that the user
 * proofreads in {@link WordEditorActivity} before it becomes a review task.
 *
 * Screen states:
 *   landing  — lead copy, optional "continue draft" banner, photo tiles, paste field
 *   +photos  — the same page with a thumbnail strip and a pinned "开始云端识别" primary
 *   working  — calm centred progress while photos are read / recognised or text is organised
 */
public final class ImportActivity extends ScreenActivity {
    private static final int REQUEST_GALLERY=10;
    private static final int REQUEST_CAMERA=11;
    private static final int THUMB_DP=84;

    private WordUi ui;
    private EditText source;
    private Future<?> pending;
    private boolean busy;
    private File cameraPhoto;
    private final List<File> pendingPhotos=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    /** Thumbnails decode off the main thread so a dozen large photos never stall the page. */
    private final ExecutorService thumbWorker=Executors.newSingleThreadExecutor();
    private final Map<String,Bitmap> thumbs=new HashMap<>();

    /** Pasted text survives re-renders (adding photos, failed AI calls) until it is imported. */
    private String sourceText="";

    // Live parts of the landing page, updated in place.
    private LinearLayout draftSlot;
    private LinearLayout photoStrip;
    private TextView photoCount;
    private Button recognizeButton;
    private TextView lineCount;

    private boolean alive(){return !isFinishing()&&!isDestroyed();}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        if(state!=null){
            String camera=state.getString("camera");
            if(camera!=null)cameraPhoto=new File(camera);
            ArrayList<String> paths=state.getStringArrayList("pendingPhotos");
            if(paths!=null)for(String path:paths)pendingPhotos.add(new File(path));
            String text=state.getString("sourceText");
            if(text!=null)sourceText=text;
        }
        home();
    }

    @Override public void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        if(cameraPhoto!=null)out.putString("camera",cameraPhoto.getAbsolutePath());
        ArrayList<String> paths=new ArrayList<>();
        for(File photo:pendingPhotos)paths.add(photo.getAbsolutePath());
        out.putStringArrayList("pendingPhotos",paths);
        keepSource();
        out.putString("sourceText",sourceText);
    }

    @Override protected void onRestart(){
        super.onRestart();
        // Coming back from the editor: the draft may have been committed or changed.
        if(!busy)renderDraft(true);
    }

    @Override protected void onDestroy(){
        AiClient.cancel(pending);
        worker.shutdownNow();
        thumbWorker.shutdownNow();
        super.onDestroy();
    }

    // ── Landing ──────────────────────────────────────────────────────────────

    private void keepSource(){if(source!=null)sourceText=source.getText().toString();}

    private void home(){
        keepSource();
        busy=false;
        source=null;
        draftSlot=null;
        photoStrip=null;
        photoCount=null;
        recognizeButton=null;
        lineCount=null;
        ui.start("录词");
        LinearLayout body=ui.body;
        boolean hasPhotos=!pendingPhotos.isEmpty();
        boolean aiReady=AiSettings.ready(this);

        // Lead: what this page does, in one calm sentence.
        TextView title=ui.type("收录新单词",Theme.T_TITLE);
        body.addView(title,ui.lpMargin(Theme.SPACE_8,0));
        TextView lead=ui.type("拍下课本或词表，或直接粘贴文字。整理好的单词卡会先交给你校对，确认后再排进复习计划。",Theme.T_CALLOUT);
        body.addView(lead,ui.lpMargin(Theme.SPACE_8,0));

        // Pick up where you left off.
        draftSlot=ui.col();
        body.addView(draftSlot,ui.lpMargin(Theme.SPACE_20,0));
        renderDraft(false);

        // Selected photos waiting for recognition.
        if(hasPhotos)photoSection(body);

        // Photo entry tiles.
        ui.section(body,hasPhotos?"继续添加":"拍照识词",null);
        body.addView(tile(R.drawable.ic_camera,"拍摄单词照片","对准课本或词表，可连拍多张",v->camera()),ui.lp());
        ui.gap(body,Theme.SPACE_12);
        body.addView(tile(R.drawable.ic_images,"从相册选择（可多选）","一次最多 12 张",v->gallery()),ui.lp());
        if(aiReady){
            ui.note(body,"多图识词建议使用小米 MiMo 模型。");
        }else{
            ui.gap(body,Theme.SPACE_12);
            body.addView(ui.banner(R.drawable.ic_sparkles,"照片识词与 AI 整理需先在「我的 › AI」中开启。也可以用本地解析或手动添加。",true,null),ui.lp());
        }

        // Paste a word list.
        pasteSection(body,hasPhotos);

        if(hasPhotos){
            recognizeButton=ui.primaryButton(recognizeLabel(),v->cloudRecognizeAll());
            ui.pin(recognizeButton);
            Notice.setBottomOffset(this,64);
        }else{
            Notice.setBottomOffset(this,0);
        }
        Motion.stagger(body);
    }

    /** Draft banner: shown only while a draft with at least one English card exists. */
    private void renderDraft(boolean animate){
        if(draftSlot==null||!alive())return;
        boolean has=Words.hasDraft(this);
        if(animate&&ui.body!=null)Motion.layout(ui.body);
        draftSlot.removeAllViews();
        LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)draftSlot.getLayoutParams();
        p.topMargin=has?ui.dp(Theme.SPACE_20):0;
        draftSlot.setLayoutParams(p);
        if(has)draftSlot.addView(draftBanner(),ui.lp());
    }

    private View draftBanner(){
        String name="";
        int count=0;
        try{
            JSONObject draft=Words.draft(this);
            name=draft.optString("title","").trim();
            JSONArray words=draft.optJSONArray("words");
            if(words!=null)for(int i=0;i<words.length();i++){
                JSONObject w=words.optJSONObject(i);
                if(w!=null&&!w.optString("english","").trim().isEmpty())count++;
            }
        }catch(Exception ignored){}
        String detail=(name.isEmpty()?"未命名词组":name)+(count>0?" · "+count+" 个词":"");

        LinearLayout row=ui.row();
        row.setMinimumHeight(ui.dp(76));
        row.setPadding(ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_12),ui.dp(Theme.SPACE_16));
        row.setBackground(Theme.shape(this,Theme.PRIMARY_SOFT,Theme.RADIUS_CARD));
        row.addView(halo(R.drawable.ic_list_checks,Color.WHITE,Theme.PRIMARY),iconParams());

        LinearLayout copy=ui.col();
        TextView label=ui.type("继续校对草稿",Theme.T_LABEL);
        label.setTextSize(16);
        label.setTextColor(Theme.PRIMARY);
        copy.addView(label);
        TextView sub=ui.type(detail,Theme.T_CALLOUT);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp=ui.lp();
        sp.topMargin=ui.dp(2);
        copy.addView(sub,sp);
        row.addView(copy,WordUi.weight());

        ImageView chevron=ui.icon(R.drawable.ic_chevron_right,Theme.PRIMARY);
        row.addView(chevron,new LinearLayout.LayoutParams(ui.dp(Theme.ICON_SMALL),ui.dp(Theme.ICON_SMALL)));
        clickable(row,Theme.RIPPLE_ACCENT,v->edit());
        return row;
    }

    /** Large entry tile: icon in a soft pine circle, headline label, one caption line. */
    private View tile(int icon,String label,String caption,View.OnClickListener click){
        LinearLayout row=ui.row();
        row.setMinimumHeight(ui.dp(80));
        row.setPadding(ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16));
        row.setBackground(Theme.shape(this,Theme.SURFACE,Theme.RADIUS_CARD));
        row.addView(halo(icon,Theme.PRIMARY_SOFT,Theme.PRIMARY),iconParams());

        LinearLayout copy=ui.col();
        TextView t=ui.type(label,Theme.T_HEADLINE);
        copy.addView(t);
        TextView c=ui.type(caption,Theme.T_CAPTION);
        LinearLayout.LayoutParams cp=ui.lp();
        cp.topMargin=ui.dp(3);
        copy.addView(c,cp);
        row.addView(copy,WordUi.weight());

        clickable(row,Theme.RIPPLE_LIGHT,click);
        return row;
    }

    private FrameLayout halo(int icon,int fill,int tint){
        FrameLayout halo=new FrameLayout(this);
        halo.setBackground(Theme.oval(fill));
        ImageView i=ui.icon(icon,tint);
        halo.addView(i,new FrameLayout.LayoutParams(ui.dp(Theme.ICON),ui.dp(Theme.ICON),Gravity.CENTER));
        return halo;
    }

    private LinearLayout.LayoutParams iconParams(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44));
        p.rightMargin=ui.dp(Theme.SPACE_16);
        return p;
    }

    /** Hand-built clickable surface: ripple on its own shape plus the shared press squeeze. */
    private void clickable(View v,int ripple,View.OnClickListener click){
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(click);
        Theme.applyRipple(v,v.getBackground(),ripple);
        Motion.press(v,.985f);
    }

    // ── Pending photos ───────────────────────────────────────────────────────

    private void photoSection(LinearLayout body){
        LinearLayout head=ui.row();
        head.setPadding(ui.dp(Theme.SPACE_4),ui.dp(Theme.SPACE_16),0,ui.dp(Theme.SPACE_4));
        TextView label=ui.sectionLabel("已选照片");
        head.addView(label);
        photoCount=ui.type("",Theme.T_CAPTION);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);
        cp.leftMargin=ui.dp(Theme.SPACE_8);
        head.addView(photoCount,cp);
        Button clear=ui.textButton("清空照片",v->{
            int n=pendingPhotos.size();
            pendingPhotos.clear();
            thumbs.clear();
            home();
            ui.toast("已清空 "+n+" 张照片");
        });
        clear.setTextColor(Theme.INK_2);
        Theme.applyRipple(clear,clear.getBackground(),Theme.RIPPLE_LIGHT);
        head.addView(clear);
        body.addView(head,ui.lp());

        HorizontalScrollView scroller=new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setClipToPadding(false);
        photoStrip=ui.row();
        photoStrip.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        for(int i=0;i<pendingPhotos.size();i++)photoStrip.addView(thumb(pendingPhotos.get(i)),thumbParams(i));
        scroller.addView(photoStrip);
        body.addView(scroller,ui.lp());
        ui.note(body,"识别完成后会生成草稿，进入校对。");
        updatePhotoCount();
    }

    private LinearLayout.LayoutParams thumbParams(int index){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ui.dp(THUMB_DP),ui.dp(THUMB_DP));
        if(index>0)p.leftMargin=ui.dp(Theme.SPACE_8);
        return p;
    }

    private View thumb(File photo){
        FrameLayout frame=new FrameLayout(this);
        frame.setTag(photo);

        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Theme.shape(this,Theme.SUNK,Theme.RADIUS_FIELD));
        image.setClipToOutline(true);
        image.setContentDescription("单词照片");
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        loadThumb(photo,image);

        // Remove affordance: a small ink disc in the corner, with a comfortable touch area.
        FrameLayout remove=new FrameLayout(this);
        remove.setContentDescription("移除这张照片");
        View disc=new View(this);
        disc.setBackground(Theme.oval(0xB31B2220));
        FrameLayout.LayoutParams discLp=new FrameLayout.LayoutParams(ui.dp(22),ui.dp(22),Gravity.TOP|Gravity.END);
        discLp.topMargin=ui.dp(6);
        discLp.rightMargin=ui.dp(6);
        remove.addView(disc,discLp);
        ImageView x=ui.icon(R.drawable.ic_x,Color.WHITE);
        FrameLayout.LayoutParams xLp=new FrameLayout.LayoutParams(ui.dp(12),ui.dp(12),Gravity.TOP|Gravity.END);
        xLp.topMargin=ui.dp(11);
        xLp.rightMargin=ui.dp(11);
        remove.addView(x,xLp);
        remove.setClickable(true);
        remove.setFocusable(true);
        Theme.applyBorderlessRipple(remove,Theme.RIPPLE_DARK);
        remove.setOnClickListener(v->removePhoto(photo,frame));
        Motion.press(remove,.9f);
        frame.addView(remove,new FrameLayout.LayoutParams(ui.dp(Theme.TOUCH),ui.dp(Theme.TOUCH),Gravity.TOP|Gravity.END));
        return frame;
    }

    private void removePhoto(File photo,View tile){
        if(busy)return;
        pendingPhotos.remove(photo);
        thumbs.remove(photo.getAbsolutePath());
        Feedback.tick(tile);
        if(pendingPhotos.isEmpty()||photoStrip==null){
            home();
            ui.toast("已移除照片");
            return;
        }
        Motion.layout(photoStrip);
        photoStrip.removeView(tile);
        for(int i=0;i<photoStrip.getChildCount();i++)photoStrip.getChildAt(i).setLayoutParams(thumbParams(i));
        updatePhotoCount();
    }

    private void updatePhotoCount(){
        if(photoCount!=null)photoCount.setText(pendingPhotos.size()+" 张");
        if(recognizeButton!=null)recognizeButton.setText(recognizeLabel());
    }

    private String recognizeLabel(){return "开始云端识别 · "+pendingPhotos.size()+" 张";}

    private void loadThumb(File photo,ImageView target){
        String key=photo.getAbsolutePath();
        Bitmap cached=thumbs.get(key);
        if(cached!=null){target.setImageBitmap(cached);return;}
        int size=ui.dp(THUMB_DP);
        try{
            thumbWorker.execute(()->{
                Bitmap bitmap=decodeThumb(photo,size);
                if(bitmap==null)return;
                runOnUiThread(()->{
                    if(!alive())return;
                    thumbs.put(key,bitmap);
                    if(target.getParent()==null)return;
                    target.setImageBitmap(bitmap);
                    if(Motion.enabled(this)){
                        target.setAlpha(0f);
                        target.animate().alpha(1f).setDuration(Theme.ENTER_MS-60).setInterpolator(Motion.EASE_OUT).start();
                    }
                });
            });
        }catch(Exception ignored){}
    }

    private static Bitmap decodeThumb(File photo,int size){
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(photo.getAbsolutePath(),bounds);
            int min=Math.min(bounds.outWidth,bounds.outHeight);
            if(min<=0)return null;
            int sample=1;
            while(min/(sample*2)>=size)sample*=2;
            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inSampleSize=sample;
            return BitmapFactory.decodeFile(photo.getAbsolutePath(),opts);
        }catch(Throwable e){return null;}
    }

    // ── Paste a list ─────────────────────────────────────────────────────────

    private void pasteSection(LinearLayout body,boolean hasPhotos){
        LinearLayout head=ui.section(body,"粘贴词表",null);
        lineCount=ui.type("",Theme.T_CAPTION);
        head.addView(lineCount);

        source=ui.input("例如：\nambition n. 野心\nconquer v. 征服",sourceText);
        source.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        source.setMinLines(5);
        source.setMaxLines(12);
        source.setGravity(Gravity.TOP|Gravity.START);
        source.setTextSize(Theme.BODY_SIZE);
        source.setPadding(ui.dp(Theme.SPACE_16),ui.dp(14),ui.dp(Theme.SPACE_16),ui.dp(14));
        source.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
            @Override public void afterTextChanged(Editable s){updateLineCount();}
        });
        body.addView(source,ui.lp());
        updateLineCount();
        ui.note(body,"任意格式都可以：带音标、词性或编号都没关系。");
        ui.gap(body,Theme.SPACE_16);

        // With photos waiting, recognition is the page's primary; text organising steps back.
        Button organise=hasPhotos
            ?ui.tonalButton("AI 整理成单词卡",0,v->aiImport())
            :ui.primaryButton("AI 整理成单词卡",v->aiImport());
        body.addView(organise,ui.lp());
        ui.gap(body,Theme.SPACE_4);
        Button more=ui.textButton("更多录入方式",v->moreImportOptions());
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
        mp.gravity=Gravity.CENTER_HORIZONTAL;
        body.addView(more,mp);
    }

    private void updateLineCount(){
        if(lineCount==null||source==null)return;
        int lines=0;
        for(String line:source.getText().toString().split("\n"))if(!line.trim().isEmpty())lines++;
        lineCount.setText(lines==0?"":lines+" 行");
    }

    private void moreImportOptions(){
        new Sheet(this)
            .title("更多录入方式")
            .item(R.drawable.ic_file_text,"本地解析并校对","按行识别上方词表，不经过 AI",this::localImport)
            .item(R.drawable.ic_pen_line,"手动添加一组单词","逐张填写英文与释义",this::edit)
            .secondary("取消",null)
            .show();
    }

    private void edit(){
        new Sheet(this)
            .title("选择校对方式")
            .item(R.drawable.ic_list_checks,"快速确认（推荐）","逐张翻看，有问题再修改",
                ()->Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("quickReview",true)))
            .item(R.drawable.ic_pen_line,"逐项编辑","逐张修改英文、释义和例句",
                ()->Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("quickReview",false)))
            .show();
    }

    // ── Capture ──────────────────────────────────────────────────────────────

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
            startActivityForResult(capture,REQUEST_CAMERA);
        }catch(Exception e){ui.toast("无法打开相机，可从相册选图："+e.getMessage());}
    }

    private void gallery(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(i,REQUEST_GALLERY);}
        catch(Exception e){ui.toast("没有可用的图片选择器");}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK)return;
        if(request==REQUEST_CAMERA){
            if(cameraPhoto!=null&&cameraPhoto.length()>0){
                pendingPhotos.add(cameraPhoto);
                cameraPhoto=null;
                home();
                Feedback.tick(ui.body);
                ui.toast("已加入照片，共 "+pendingPhotos.size()+" 张");
            }else ui.toast("没有收到拍摄照片");
        }else if(request==REQUEST_GALLERY&&data!=null){
            if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
            List<Uri> uris=new ArrayList<>();
            if(data.getClipData()!=null){
                ClipData clip=data.getClipData();
                for(int i=0;i<clip.getItemCount();i++)uris.add(clip.getItemAt(i).getUri());
            }else if(data.getData()!=null)uris.add(data.getData());
            if(uris.isEmpty()){ui.toast("没有选中照片");return;}
            if(uris.size()>12){ui.toast("一次最多 12 张，请分批选择");return;}
            loading(R.drawable.ic_images,"正在读取 "+uris.size()+" 张照片…","照片只保存在本机，稍后用于识别");
            worker.execute(()->{
                try{
                    List<File> copied=new ArrayList<>();
                    for(Uri uri:uris){
                        File local=newPhoto("photo-");
                        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){
                            if(in==null)throw new IOException("无法读取照片");
                            byte[] b=new byte[65536];
                            int n;
                            long size=0;
                            while((n=in.read(b))!=-1){
                                size+=n;
                                if(size>40*1024*1024)throw new IOException("请选取小于 40 MB 的照片");
                                out.write(b,0,n);
                            }
                        }
                        copied.add(local);
                    }
                    runOnUiThread(()->{
                        pendingPhotos.addAll(copied);
                        if(!alive())return;
                        home();
                        Feedback.tick(ui.body);
                        ui.toast("已加入 "+copied.size()+" 张，共 "+pendingPhotos.size()+" 张");
                    });
                }catch(Exception e){
                    runOnUiThread(()->{
                        if(!alive())return;
                        home();
                        ui.error("读取照片失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
                    });
                }
            });
        }
    }

    // ── Working state ────────────────────────────────────────────────────────

    /** Replaces the page with a calm centred status while work runs; back is held until it ends. */
    private void loading(int icon,String headline,String detail){
        keepSource();
        source=null;
        draftSlot=null;
        photoStrip=null;
        photoCount=null;
        recognizeButton=null;
        lineCount=null;
        ui.start("录词");
        Notice.setBottomOffset(this,0);
        LinearLayout body=ui.body;
        body.setGravity(Gravity.CENTER_HORIZONTAL|Gravity.CENTER_VERTICAL);
        body.setPadding(ui.dp(Theme.SPACE_40),0,ui.dp(Theme.SPACE_40),ui.dp(96));

        LinearLayout box=ui.col();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(halo(icon,Theme.PRIMARY_SOFT,Theme.PRIMARY),new LinearLayout.LayoutParams(ui.dp(64),ui.dp(64)));
        ui.gap(box,Theme.SPACE_24);
        TextView h=ui.type(headline,Theme.T_HEADLINE);
        h.setGravity(Gravity.CENTER);
        h.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(h,ui.lp());
        ui.gap(box,Theme.SPACE_8);
        TextView d=ui.type(detail,Theme.T_CALLOUT);
        d.setTextColor(Theme.INK_3);
        d.setGravity(Gravity.CENTER);
        box.addView(d,ui.lp());
        ui.gap(box,Theme.SPACE_24);
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Theme.PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.SUNK));
        box.addView(bar,new LinearLayout.LayoutParams(ui.dp(140),ui.dp(4)));

        body.addView(box,new LinearLayout.LayoutParams(-1,-2));
        Motion.enter(box);
        busy=true;
    }

    // ── Recognise / organise ─────────────────────────────────────────────────

    private void cloudRecognizeAll(){
        if(!alive()||pendingPhotos.isEmpty())return;
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        final List<File> photos=new ArrayList<>(pendingPhotos);
        loading(R.drawable.ic_sparkles,"正在识别 "+photos.size()+" 张照片…","通常需要十几秒，请留在此页面");
        worker.execute(()->{
            try{
                List<String> encoded=new ArrayList<>();
                for(File photo:photos)encoded.add(encodeJpegBase64(photo));
                String[] images=encoded.toArray(new String[0]);
                JSONArray paths=new JSONArray();
                for(File photo:photos)paths.put(photo.getAbsolutePath());
                runOnUiThread(()->{
                    if(!alive())return;
                    pending=AiClient.chatVisionJson(this,AiPrompts.systemPhotoImport(),AiPrompts.userPhotoImportMany(photos.size()),"image/jpeg",images,new AiClient.Callback(){
                        @Override public void onResult(JSONObject json){
                            if(!alive())return;
                            busy=false;
                            try{
                                JSONArray cleaned=cleanAiWords(json);
                                saveDraft(cleaned,paths,"云端识图草稿，待校对");
                                pendingPhotos.clear();
                                thumbs.clear();
                                drafted(cleaned.length());
                            }catch(Exception e){home();ui.error("识别失败："+e.getMessage());}
                        }
                        @Override public void onError(String message){
                            if(!alive())return;
                            busy=false;
                            home();
                            ui.error(message);
                        }
                    });
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(!alive())return;
                    busy=false;
                    home();
                    ui.error("照片处理失败："+e.getMessage());
                });
            }
        });
    }

    private static String encodeJpegBase64(File photo)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(photo.getAbsolutePath(),bounds);
        int max=Math.max(bounds.outWidth,bounds.outHeight);
        int sample=1;
        while(max/sample>1600)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inSampleSize=Math.max(1,sample);
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
            keepSource();
            String raw=source==null?"":source.getText().toString();
            JSONArray found=Words.array(WordParser.parse(raw));
            if(found.length()==0){ui.error("未解析出单词，可改用 AI 整理或手动添加");return;}
            saveDraft(found,new JSONArray(),"本地解析草稿，待校对");
            renderDraft(true);
            ui.toast("已解析 "+found.length()+" 个单词");
            edit();
        }catch(Exception e){ui.error("解析失败："+e.getMessage());}
    }

    private void aiImport(){
        if(busy)return;
        if(!AiSettings.ready(this)){ui.toast(AiSettings.notReadyMessage(this));return;}
        String raw=source==null?"":source.getText().toString().trim();
        if(raw.isEmpty()){
            ui.toast("请先粘贴或输入词表");
            if(source!=null)source.requestFocus();
            return;
        }
        loading(R.drawable.ic_sparkles,"正在整理单词卡…","AI 会补全释义与例句，稍后交给你校对");
        pending=AiClient.chatJson(this,AiPrompts.systemImport(),AiPrompts.userImport(raw),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                if(!alive())return;
                busy=false;
                try{
                    JSONArray cleaned=cleanAiWords(json);
                    saveDraft(cleaned,new JSONArray(),"AI 整理草稿，待校对");
                    sourceText="";
                    drafted(cleaned.length());
                }catch(Exception e){home();ui.error("整理失败："+e.getMessage());}
            }
            @Override public void onError(String message){
                if(!alive())return;
                busy=false;
                home();
                ui.error(message);
            }
        });
    }

    /** Validates and normalises the model's cards; throws when nothing usable came back. */
    private JSONArray cleanAiWords(JSONObject json)throws Exception{
        JSONArray words=json.optJSONArray("words");
        if(words==null||words.length()==0)throw new Exception("模型没有返回单词卡");
        JSONArray cleaned=new JSONArray();
        for(int i=0;i<words.length();i++){
            JSONObject w=words.optJSONObject(i);
            if(w==null)continue;
            String en=w.optString("english","").trim();
            if(en.isEmpty())continue;
            JSONObject card=new JSONObject()
                .put("english",en)
                .put("chinese",w.optString("chinese","").trim())
                .put("example",w.optString("example","").trim());
            JSONArray senses=w.optJSONArray("senses");
            if(senses!=null&&senses.length()>0)card.put("senses",senses);
            else{try{Senses.ensure(card);}catch(Exception ignored){}}
            cleaned.put(card);
        }
        if(cleaned.length()==0)throw new Exception("模型返回的卡片无效");
        return cleaned;
    }

    private void applyAiWords(JSONObject json,JSONArray photoPaths,String sourceLabel)throws Exception{
        JSONArray cleaned=cleanAiWords(json);
        saveDraft(cleaned,photoPaths==null?new JSONArray():photoPaths,sourceLabel);
        drafted(cleaned.length());
    }

    private void applyAiWords(JSONObject json,String photoPath,String sourceLabel)throws Exception{
        JSONArray photos=new JSONArray();
        if(photoPath!=null&&!photoPath.isEmpty())photos.put(photoPath);
        applyAiWords(json,photos,sourceLabel);
    }

    /** Draft saved: back to the landing (now showing the draft), confirm, then choose how to proofread. */
    private void drafted(int count){
        home();
        Feedback.tick(ui.body);
        ui.toast("已生成 "+count+" 张单词卡");
        edit();
    }

    private void saveDraft(JSONArray words,JSONArray photos,String sourceLabel)throws Exception{
        JSONObject draft=Words.draft(this);
        draft.put("words",words).put("rawOcr","").put("source",sourceLabel).put("photos",photos==null?new JSONArray():photos);
        if(draft.optString("title","").trim().isEmpty())draft.put("title","");
        Words.writeDraft(this,draft);
    }

    @Override public void onBackPressed(){
        if(busy){ui.toast("正在处理，请稍候");return;}
        super.onBackPressed();
    }
}
