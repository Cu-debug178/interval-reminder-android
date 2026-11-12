package com.example.reviewreminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin rounded progress track (3dp) whose fill eases to each new value. */
final class ProgressLine extends View {
    private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;private final RectF rect=new RectF();private ValueAnimator anim;

    ProgressLine(Context c){super(c);track.setColor(Theme.SUNK);fill.setColor(Theme.PRIMARY);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}

    ProgressLine color(int c){fill.setColor(c);invalidate();return this;}

    void set(float target,boolean animate){
        target=Math.max(0,Math.min(1,target));
        if(anim!=null)anim.cancel();
        if(!animate||!Motion.enabled(getContext())){value=target;invalidate();return;}
        anim=ValueAnimator.ofFloat(value,target);anim.setDuration(Theme.ENTER_MS+120);anim.setInterpolator(Motion.EASE);
        anim.addUpdateListener(a->{value=(float)a.getAnimatedValue();invalidate();});anim.start();
    }

    @Override protected void onMeasure(int w,int h){setMeasuredDimension(getDefaultSize(0,w),resolveSize(Theme.dp(getContext(),3),h));}

    @Override protected void onDraw(Canvas c){
        float h=getHeight(),r=h/2;rect.set(0,0,getWidth(),h);c.drawRoundRect(rect,r,r,track);
        if(value<=0)return;rect.set(0,0,Math.max(h,getWidth()*value),h);c.drawRoundRect(rect,r,r,fill);
    }
}
