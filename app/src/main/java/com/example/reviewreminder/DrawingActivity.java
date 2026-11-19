package com.example.reviewreminder;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen sketch editor; returns a private relative path in RESULT_OK. */
public final class DrawingActivity extends ScreenActivity {
    private WordUi ui;private DrawingView canvas;private int ink=Theme.INK;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);
        LinearLayout root=ui.col();root.setBackgroundColor(Theme.BG);root.addView(ui.pageChrome("手绘连接图"));
        FrameLayout holder=new FrameLayout(this);holder.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_8),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_8));
        canvas=new DrawingView(this);canvas.setBackground(Theme.shape(this,Theme.SURFACE,Theme.RADIUS_CARD,Theme.BORDER,1));
        holder.addView(canvas,new FrameLayout.LayoutParams(-1,-1));root.addView(holder,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout tools=ui.col();tools.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_8),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_16));
        TextView hint=ui.text("用手指在白色区域绘制；保存后仅在复习卡需要时显示。",12,Theme.MUTED);tools.addView(hint);ui.gap(tools,8);
        LinearLayout colors=ui.row();int[] palette={Theme.INK,Theme.PRIMARY,Theme.DANGER,0xFF7A5DA8};String[] names={"黑色画笔","绿色画笔","陶红画笔","紫色画笔"};
        for(int i=0;i<palette.length;i++){
            final int color=palette[i];final String name=names[i];
            Button swatch=new Button(this);swatch.setText("");swatch.setContentDescription(name);swatch.setBackground(Theme.shape(this,color,Theme.RADIUS_CHIP));swatch.setMinWidth(ui.dp(48));swatch.setMinHeight(ui.dp(48));
            swatch.setOnClickListener(v->{ink=color;canvas.setInk(ink);});Motion.press(swatch);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));lp.rightMargin=ui.dp(8);colors.addView(swatch,lp);
        }
        tools.addView(colors);ui.gap(tools,8);
        LinearLayout actions=ui.row();
        actions.addView(ui.ghostButton("撤销",v->canvas.undo()),new LinearLayout.LayoutParams(0,ui.dp(48),1));
        LinearLayout.LayoutParams clearLp=new LinearLayout.LayoutParams(0,ui.dp(48),1);clearLp.leftMargin=ui.dp(8);actions.addView(ui.ghostButton("清空",v->{if(!canvas.hasStrokes()){ui.toast("画布已经是空的");return;}Sheet.confirm(this,"清空手绘？","已画的线条会被清掉。","取消","清空",true,()->canvas.clear());}),clearLp);
        tools.addView(actions);ui.gap(tools,10);
        tools.addView(ui.primaryButton("保存连接图",v->save()));root.addView(tools);
        setContentView(root);ui.systemBars();Motion.enter(holder);Motion.enter(tools);
    }
    private void save(){
        if(!canvas.hasStrokes()){ui.toast("请先绘制内容");return;}
        Bitmap bitmap=null;try{bitmap=canvas.export();String path=ConnectionImages.saveBitmap(this,bitmap);Intent result=new Intent().putExtra("path",path);setResult(RESULT_OK,result);ui.toast("连接图已保存");finish();}catch(Exception e){ui.toast("保存失败："+e.getMessage());}finally{if(bitmap!=null)bitmap.recycle();}
    }
    @Override public void onBackPressed(){if(!canvas.hasStrokes()){super.onBackPressed();return;}Sheet.confirm(this,"放弃手绘？","未保存的线条会丢失。","继续绘制","放弃",true,()->DrawingActivity.super.onBackPressed());}
}
