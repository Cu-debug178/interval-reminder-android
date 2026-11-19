package com.example.reviewreminder;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen connection image with restrained, interruptible zoom transitions. */
public final class ConnectionImageActivity extends ScreenActivity {
    private WordUi ui;private ImageView view;private TextView scaleLabel;private Matrix matrix=new Matrix();private float scale=1f,minScale=1f,maxScale=4f;private final PointF last=new PointF();private ScaleGestureDetector zoom;private GestureDetector taps;private ValueAnimator matrixAnimator;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new WordUi(this);String path=getIntent().getStringExtra("path"),english=getIntent().getStringExtra("english");
        if(!ConnectionImages.exists(this,path)){ui.toast("连接图已丢失，仍可正常复习");finish();return;}
        LinearLayout root=ui.col();root.setBackgroundColor(Theme.BG);root.addView(ui.pageChrome(english==null||english.isEmpty()?"连接图":english));
        LinearLayout holder=ui.col();holder.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_8),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_16));
        FrameLayout canvas=new FrameLayout(this);view=new ImageView(this);view.setScaleType(ImageView.ScaleType.MATRIX);view.setBackground(Theme.shape(this,Theme.SURFACE,20,Theme.BORDER,1));view.setClipToOutline(true);view.setContentDescription("连接图，可双指缩放、双击放大并拖动查看");view.setOnTouchListener((v,e)->gesture(e));canvas.addView(view,new FrameLayout.LayoutParams(-1,-1));holder.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        ui.gap(holder,Theme.SPACE_12);LinearLayout controls=ui.row();controls.setGravity(Gravity.CENTER);
        Button out=ui.ghostButton("−",v->zoomBy(.75f));out.setContentDescription("缩小连接图");controls.addView(out,new LinearLayout.LayoutParams(ui.dp(52),ui.dp(48)));
        scaleLabel=ui.chip("100%",Theme.PRIMARY_SOFT,Theme.PRIMARY);scaleLabel.setGravity(Gravity.CENTER);LinearLayout.LayoutParams scaleLp=new LinearLayout.LayoutParams(ui.dp(76),ui.dp(40));scaleLp.leftMargin=ui.dp(Theme.SPACE_12);controls.addView(scaleLabel,scaleLp);
        Button in=ui.primaryButton("+",v->zoomBy(1.35f));in.setContentDescription("放大连接图");LinearLayout.LayoutParams inLp=new LinearLayout.LayoutParams(ui.dp(52),ui.dp(48));inLp.leftMargin=ui.dp(Theme.SPACE_12);controls.addView(in,inLp);
        Button fit=ui.textButton("适应屏幕",v->fit(true));LinearLayout.LayoutParams fitLp=new LinearLayout.LayoutParams(0,ui.dp(48),1);fitLp.leftMargin=ui.dp(Theme.SPACE_12);controls.addView(fit,fitLp);holder.addView(controls);
        TextView hint=ui.text("双指缩放 · 双击放大 · 拖动查看",12,Theme.MUTED);hint.setGravity(Gravity.CENTER);LinearLayout.LayoutParams hintLp=new LinearLayout.LayoutParams(-1,-2);hintLp.topMargin=ui.dp(Theme.SPACE_8);holder.addView(hint,hintLp);root.addView(holder,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);ui.systemBars();
        try{Bitmap bitmap=ConnectionImages.decode(this,path,2400);view.setImageBitmap(bitmap);}catch(Exception e){ui.toast(e.getMessage());finish();return;}
        zoom=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){cancelMatrixAnimation();float next=Math.max(minScale,Math.min(maxScale,scale*detector.getScaleFactor()));float factor=next/scale;matrix.postScale(factor,factor,detector.getFocusX(),detector.getFocusY());clamp(matrix);applyMatrix();return true;}});
        taps=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){float target=scale>minScale*1.45f?minScale:Math.min(maxScale,minScale*2.2f);animateTo(target,e.getX(),e.getY());return true;}});
        view.post(()->{fit(false);Motion.enter(view);});
    }
    private Matrix fittedMatrix(){if(view.getDrawable()==null)return null;int width=view.getWidth(),height=view.getHeight();BitmapDrawable drawable=(BitmapDrawable)view.getDrawable();int iw=drawable.getIntrinsicWidth(),ih=drawable.getIntrinsicHeight();if(width<=0||height<=0||iw<=0||ih<=0)return null;float fitScale=Math.min((float)width/iw,(float)height/ih);minScale=fitScale;maxScale=fitScale*4;Matrix fitted=new Matrix();fitted.postScale(fitScale,fitScale);fitted.postTranslate((width-iw*fitScale)/2f,(height-ih*fitScale)/2f);return fitted;}
    private void fit(boolean animate){Matrix fitted=fittedMatrix();if(fitted==null)return;if(animate)animateMatrix(fitted,minScale);else{matrix=fitted;applyMatrix();}}
    private void zoomBy(float factor){animateTo(Math.max(minScale,Math.min(maxScale,scale*factor)),view.getWidth()/2f,view.getHeight()/2f);}
    private void animateTo(float targetScale,float focusX,float focusY){Matrix target=new Matrix(matrix);float factor=targetScale/scale;target.postScale(factor,factor,focusX,focusY);clamp(target);animateMatrix(target,targetScale);}
    private void animateMatrix(Matrix target,float targetScale){
        cancelMatrixAnimation();if(!Motion.enabled(this)){matrix=target;scale=targetScale;applyMatrix();return;}
        final float[] from=new float[9],to=new float[9],current=new float[9];matrix.getValues(from);target.getValues(to);System.arraycopy(from,0,current,0,9);matrixAnimator=ValueAnimator.ofFloat(0,1);final ValueAnimator animator=matrixAnimator;animator.setDuration(Theme.ENTER_MS);animator.setInterpolator(new PathInterpolator(.2f,0,0,1));
        animator.addUpdateListener(animation->{float fraction=(Float)animation.getAnimatedValue();for(int i=0;i<9;i++)current[i]=from[i]+(to[i]-from[i])*fraction;matrix=new Matrix();matrix.setValues(current);scale=current[Matrix.MSCALE_X];view.setImageMatrix(matrix);updateScaleLabel();});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator animation){if(matrixAnimator!=animation)return;matrix=target;scale=targetScale;applyMatrix();matrixAnimator=null;}@Override public void onAnimationCancel(android.animation.Animator animation){if(matrixAnimator==animation)matrixAnimator=null;}});animator.start();
    }
    private boolean gesture(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)cancelMatrixAnimation();zoom.onTouchEvent(event);taps.onTouchEvent(event);if(!zoom.isInProgress()){switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:last.set(event.getX(),event.getY());return true;case MotionEvent.ACTION_MOVE:matrix.postTranslate(event.getX()-last.x,event.getY()-last.y);last.set(event.getX(),event.getY());clamp(matrix);applyMatrix();return true;default:break;}}return true;}
    private void applyMatrix(){float[] values=new float[9];matrix.getValues(values);scale=values[Matrix.MSCALE_X];view.setImageMatrix(matrix);updateScaleLabel();}
    private void updateScaleLabel(){if(scaleLabel!=null&&minScale>0)scaleLabel.setText(Math.round(scale/minScale*100)+"%");}
    private void cancelMatrixAnimation(){if(matrixAnimator!=null){matrixAnimator.cancel();matrixAnimator=null;}}
    private void clamp(Matrix target){float[] values=new float[9];target.getValues(values);float tx=values[Matrix.MTRANS_X],ty=values[Matrix.MTRANS_Y],current=values[Matrix.MSCALE_X];float iw=view.getDrawable()==null?0:view.getDrawable().getIntrinsicWidth()*current,ih=view.getDrawable()==null?0:view.getDrawable().getIntrinsicHeight()*current;float dx=iw<=view.getWidth()?(view.getWidth()-iw)/2f-tx:Math.max(view.getWidth()-iw-tx,Math.min(0,-tx));float dy=ih<=view.getHeight()?(view.getHeight()-ih)/2f-ty:Math.max(view.getHeight()-ih-ty,Math.min(0,-ty));target.postTranslate(dx,dy);}
    @Override protected void onDestroy(){cancelMatrixAnimation();super.onDestroy();}
}
