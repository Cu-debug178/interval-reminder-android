package com.example.reviewreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Lightweight freehand canvas used for optional connection sketches. */
final class DrawingView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Path> strokes=new ArrayList<>();
    private final List<Integer> colors=new ArrayList<>();
    private Path current;
    DrawingView(Context context){super(context);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Theme.dp(context,5));paint.setColor(Theme.INK);setBackgroundColor(Color.WHITE);setContentDescription("连接图手绘区域");}
    void setInk(int color){paint.setColor(color);}
    boolean hasStrokes(){return !strokes.isEmpty()||current!=null;}
    void undo(){if(strokes.isEmpty())return;strokes.remove(strokes.size()-1);colors.remove(colors.size()-1);invalidate();}
    void clear(){strokes.clear();colors.clear();current=null;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        for(int i=0;i<strokes.size();i++){paint.setColor(colors.get(i));canvas.drawPath(strokes.get(i),paint);}
        if(current!=null){paint.setColor(colors.isEmpty()?Theme.INK:colors.get(colors.size()-1));canvas.drawPath(current,paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                current=new Path();current.moveTo(event.getX(),event.getY());invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(current!=null){current.lineTo(event.getX(),event.getY());invalidate();}return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(current!=null){strokes.add(current);colors.add(paint.getColor());current=null;performClick();invalidate();}return true;
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    Bitmap export(){
        int width=Math.max(1,getWidth()),height=Math.max(1,getHeight());Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);for(int i=0;i<strokes.size();i++){paint.setColor(colors.get(i));canvas.drawPath(strokes.get(i),paint);}return bitmap;
    }
}
