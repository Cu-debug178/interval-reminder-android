package com.example.reviewreminder;

import android.app.Activity;
import android.content.Context;
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
        if(direction==0)v.setTranslationY(6*d);else v.setTranslationX(direction*14*d);
        v.animate().alpha(1).translationX(0).translationY(0).setDuration(Theme.ENTER_MS).setInterpolator(EASE).withEndAction(null).start();
    }
    static void leave(View v,int direction,Runnable next){
        if(!enabled(v.getContext())){next.run();return;}
        v.animate().cancel();v.animate().alpha(0).translationX(-direction*10*v.getResources().getDisplayMetrics().density).setDuration(Theme.LEAVE_MS).setInterpolator(EASE).withEndAction(next).start();
    }
    static void press(View v){
        Theme.applyRipple(v,v.getBackground());
        v.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN&&enabled(view.getContext()))
                view.animate().scaleX(.978f).scaleY(.978f).setDuration(Theme.PRESS_MS/2).setInterpolator(EASE).start();
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1).scaleY(1).setDuration(enabled(view.getContext())?Theme.PRESS_MS:0).setInterpolator(EASE).start();
            return false;
        });
    }
    static void layout(ViewGroup parent){if(enabled(parent.getContext())){TransitionSet t=new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER).addTransition(new ChangeBounds()).addTransition(new Fade());t.setDuration(Theme.ENTER_MS-20).setInterpolator(EASE);TransitionManager.beginDelayedTransition(parent,t);}}
    static void open(Activity a,android.content.Intent intent){a.startActivity(intent);if(enabled(a))a.overridePendingTransition(R.anim.page_in,R.anim.page_out);else a.overridePendingTransition(0,0);}
    static void close(Activity a){if(enabled(a))a.overridePendingTransition(R.anim.page_return,R.anim.page_close);else a.overridePendingTransition(0,0);}
}
