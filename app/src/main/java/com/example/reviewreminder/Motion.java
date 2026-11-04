package com.example.reviewreminder;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionSet;
import android.transition.TransitionManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

final class Motion {
    private Motion() {}
    private static final PathInterpolator EASE=new PathInterpolator(.2f,0,0,1);
    static boolean enabled(Context c){return Store.prefs(c).getBoolean("motion",true)&&Settings.Global.getFloat(c.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;}
    static void enter(View v){enter(v,0);}
    static void enter(View v,int direction){
        v.animate().cancel();v.setAlpha(1);v.setTranslationX(0);v.setTranslationY(0);
        if(!enabled(v.getContext()))return;
        float d=v.getResources().getDisplayMetrics().density;v.setAlpha(0);
        if(direction==0)v.setTranslationY(6*d);else v.setTranslationX(direction*16*d);
        v.animate().alpha(1).translationX(0).translationY(0).setDuration(160).setInterpolator(EASE).withEndAction(null).start();
    }
    static void leave(View v,int direction,Runnable next){
        if(!enabled(v.getContext())){next.run();return;}
        v.animate().cancel();v.animate().alpha(0).translationX(-direction*12*v.getResources().getDisplayMetrics().density).setDuration(80).setInterpolator(EASE).withEndAction(next).start();
    }
    static void press(View v){
        android.graphics.drawable.Drawable background=v.getBackground();android.graphics.drawable.Drawable mask=background==null?new android.graphics.drawable.ColorDrawable(Color.WHITE):background.getConstantState().newDrawable().mutate();mask.setTint(Color.WHITE);v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25184E45),background,mask));
        v.setOnTouchListener((view,event)->{int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN&&enabled(view.getContext()))view.animate().scaleX(.975f).scaleY(.975f).setDuration(70).setInterpolator(EASE).start();else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)view.animate().scaleX(1).scaleY(1).setDuration(enabled(view.getContext())?110:0).setInterpolator(EASE).start();return false;});
    }
    static void layout(ViewGroup parent){if(enabled(parent.getContext())){TransitionSet t=new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER).addTransition(new ChangeBounds()).addTransition(new Fade());t.setDuration(170).setInterpolator(EASE);TransitionManager.beginDelayedTransition(parent,t);}}
    static void open(Activity a,android.content.Intent intent){a.startActivity(intent);if(enabled(a))a.overridePendingTransition(R.anim.page_in,R.anim.page_out);else a.overridePendingTransition(0,0);}
    static void close(Activity a){if(enabled(a))a.overridePendingTransition(R.anim.page_return,R.anim.page_close);else a.overridePendingTransition(0,0);}
}
