package com.example.reviewreminder;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

final class CropView extends View {
    Bitmap bitmap;private final Paint p=new Paint(3);private final RectF frame=new RectF();
    private float l=.06f,t=.08f,r=.94f,b=.92f;private int handle=-1;
    CropView(Context c,Bitmap image){super(c);bitmap=image;setContentDescription("照片裁切区域，拖动四角调整");}
    @Override protected void onDraw(Canvas c){
        float s=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());float w=bitmap.getWidth()*s,h=bitmap.getHeight()*s;frame.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
        c.drawColor(0xFF172522);c.drawBitmap(bitmap,null,frame,p);float x1=frame.left+l*w,y1=frame.top+t*h,x2=frame.left+r*w,y2=frame.top+b*h;
        p.setColor(0x99000000);c.drawRect(frame.left,frame.top,frame.right,y1,p);c.drawRect(frame.left,y2,frame.right,frame.bottom,p);c.drawRect(frame.left,y1,x1,y2,p);c.drawRect(x2,y1,frame.right,y2,p);
        p.setColor(0xFF8BF4C3);p.setStrokeWidth(3);p.setStyle(Paint.Style.STROKE);c.drawRect(x1,y1,x2,y2,p);p.setStyle(Paint.Style.FILL);for(float x:new float[]{x1,x2})for(float y:new float[]{y1,y2})c.drawCircle(x,y,9,p);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(frame.width()==0)return true;float x=Math.max(0,Math.min(1,(e.getX()-frame.left)/frame.width())),y=Math.max(0,Math.min(1,(e.getY()-frame.top)/frame.height()));
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){handle=(x<(l+r)/2?0:1)+(y<(t+b)/2?0:2);getParent().requestDisallowInterceptTouchEvent(true);}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(handle%2==0)l=Math.min(x,r-.08f);else r=Math.max(x,l+.08f);if(handle<2)t=Math.min(y,b-.06f);else b=Math.max(y,t+.06f);invalidate();}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    Bitmap cropped(){int x=(int)(l*bitmap.getWidth()),y=(int)(t*bitmap.getHeight());Bitmap part=Bitmap.createBitmap(bitmap,x,y,Math.max(1,(int)((r-l)*bitmap.getWidth())),Math.max(1,(int)((b-t)*bitmap.getHeight())));return part==bitmap?part.copy(Bitmap.Config.ARGB_8888,false):part;}
    void rotate(){Matrix m=new Matrix();m.postRotate(90);Bitmap old=bitmap;bitmap=Bitmap.createBitmap(old,0,0,old.getWidth(),old.getHeight(),m,true);if(old!=bitmap)old.recycle();invalidate();}
    @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();if(!bitmap.isRecycled())bitmap.recycle();}
}
