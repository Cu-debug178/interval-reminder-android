package com.example.reviewreminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A row of small dots that shows where a task sits in its spaced-repetition plan:
 * filled = rounds done, ring = the round that is next, faint = rounds still ahead.
 * Also used (with total 5) to show a word's mastery.
 */
final class StageDots extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int total,done;private boolean alert,ringNext=true;
    private final float dot,gap;
    private int onColor=Theme.PRIMARY;

    StageDots(Context c,int total,int done,boolean alert){
        super(c);this.total=Math.max(1,total);this.done=Math.max(0,Math.min(done,this.total));this.alert=alert;
        float d=c.getResources().getDisplayMetrics().density;dot=6*d;gap=5*d;
        setContentDescription("进度 "+this.done+" / "+this.total);
    }
    StageDots ringNext(boolean on){ringNext=on;invalidate();return this;}
    StageDots color(int c){onColor=c;invalidate();return this;}

    @Override protected void onMeasure(int w,int h){
        int width=(int)(total*dot+(total-1)*gap+getPaddingLeft()+getPaddingRight()+2);
        int height=(int)(dot+getPaddingTop()+getPaddingBottom()+2);
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h));
    }

    @Override protected void onDraw(Canvas c){
        float r=dot/2,y=getHeight()/2f,x=getPaddingLeft()+r+1;
        float stroke=getResources().getDisplayMetrics().density*1.4f;
        for(int i=0;i<total;i++){
            if(i<done){paint.setStyle(Paint.Style.FILL);paint.setColor(onColor);c.drawCircle(x,y,r,paint);}
            else if(i==done&&ringNext){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(alert?Theme.DANGER:onColor);c.drawCircle(x,y,r-stroke/2,paint);}
            else {paint.setStyle(Paint.Style.FILL);paint.setColor(Theme.HAIRLINE);c.drawCircle(x,y,r,paint);}
            x+=dot+gap;
        }
    }
}
