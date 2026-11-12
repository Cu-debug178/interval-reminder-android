package com.example.reviewreminder;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * Restrained motion vocabulary. Everything is short (≤280 ms), eased with the standard
 * "emphasized decelerate" curve, and fully disabled by the in-app switch or the system
 * animator duration scale.
 */
final class Motion {
    private Motion() {}
    static final PathInterpolator EASE=new PathInterpolator(.2f,0,0,1);
    static final PathInterpolator EASE_OUT=new PathInterpolator(.05f,.7f,.1f,1);
    static final PathInterpolator EASE_IN=new PathInterpolator(.3f,0,.8f,.15f);

    static boolean enabled(Context c){return Store.prefs(c).getBoolean("motion",true)&&Settings.Global.getFloat(c.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;}

    /** Fade + small rise (direction 0) or horizontal slide (±1). */
    static void enter(View v){enter(v,0);}
    static void enter(View v,int direction){enter(v,direction,0);}
    static void enter(View v,int direction,int delay){
        v.animate().cancel();v.setAlpha(1);v.setTranslationX(0);v.setTranslationY(0);
        if(!enabled(v.getContext()))return;
        float d=v.getResources().getDisplayMetrics().density;v.setAlpha(0);
        if(direction==0)v.setTranslationY(8*d);else v.setTranslationX(direction*20*d);
        v.animate().alpha(1).translationX(0).translationY(0).setStartDelay(delay).setDuration(Theme.ENTER_MS).setInterpolator(EASE_OUT).withEndAction(null).start();
    }

    /** Children rise in one after another — used once per page render, capped so long pages stay fast. */
    static void stagger(ViewGroup parent){
        if(!enabled(parent.getContext()))return;
        int n=Math.min(parent.getChildCount(),10);
        for(int i=0;i<n;i++){View c=parent.getChildAt(i);if(c.getVisibility()!=View.VISIBLE)continue;enter(c,0,i*28);}
    }

    static void leave(View v,int direction,Runnable next){
        if(!enabled(v.getContext())){next.run();return;}
        float d=v.getResources().getDisplayMetrics().density;
        v.animate().cancel();v.animate().alpha(0).translationX(-direction*16*d).setStartDelay(0).setDuration(Theme.LEAVE_MS).setInterpolator(EASE_IN).withEndAction(next).start();
    }

    // ViewPropertyAnimator keeps the last start delay (enter()/stagger() set one), so every later
    // animate() chain below resets it to stay immediate.
    /** Pressed state: ripple + a gentle 3% squeeze that springs back on release. */
    static void press(View v){press(v,.97f);}
    static void press(View v,float scale){
        if(!(v.getBackground() instanceof android.graphics.drawable.RippleDrawable))Theme.applyRipple(v,v.getBackground());
        v.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN&&view.isEnabled()&&enabled(view.getContext()))
                view.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(90).setInterpolator(EASE).start();
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1).scaleY(1).setStartDelay(0).setDuration(enabled(view.getContext())?Theme.PRESS_MS+60:0).setInterpolator(EASE_OUT).start();
            return false;
        });
    }

    /** Animated bounds/fade for in-place layout changes (expand, collapse, insert, remove). */
    static void layout(ViewGroup parent){if(enabled(parent.getContext())){TransitionSet t=new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER).addTransition(new ChangeBounds()).addTransition(new Fade());t.setDuration(Theme.ENTER_MS-20).setInterpolator(EASE);TransitionManager.beginDelayedTransition(parent,t);}}

    /** Rotates a chevron to show expanded/collapsed state. */
    static void rotate(View v,float degrees){if(enabled(v.getContext()))v.animate().rotation(degrees).setStartDelay(0).setDuration(Theme.ENTER_MS).setInterpolator(EASE).start();else v.setRotation(degrees);}

    /** Counts a number up for statistics; respects reduced motion. */
    static void count(android.widget.TextView t,int to,String suffix){
        if(!enabled(t.getContext())||to<=0){t.setText(to+suffix);return;}
        ValueAnimator a=ValueAnimator.ofInt(0,to);a.setDuration(Math.min(700,260+to*12));a.setInterpolator(EASE_OUT);a.addUpdateListener(x->t.setText(x.getAnimatedValue()+suffix));a.start();
    }

    static void open(Activity a,android.content.Intent intent){a.startActivity(intent);if(enabled(a))a.overridePendingTransition(R.anim.page_in,R.anim.page_out);else a.overridePendingTransition(0,0);}
    /**
     * Forward step that replaces the current screen (review → spelling → example). finish() on a
     * ScreenActivity issues the "back" transition, so the forward one is applied after it and wins.
     */
    static void replace(Activity a,android.content.Intent intent){a.startActivity(intent);a.finish();if(enabled(a))a.overridePendingTransition(R.anim.page_in,R.anim.page_out);else a.overridePendingTransition(0,0);}
    static void close(Activity a){if(enabled(a))a.overridePendingTransition(R.anim.page_return,R.anim.page_close);else a.overridePendingTransition(0,0);}
}
