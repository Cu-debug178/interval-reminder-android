package com.example.reviewreminder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

final class Feedback {
    static final int KNOWN=0, RETRY=1, COMPLETE=2;
    private static final long MAX_PENDING_PLAY_DELAY_MS=100;
    private SoundPool pool;
    private final int[] sounds=new int[3];
    private final boolean[] loaded=new boolean[3];
    private int pending=-1;
    private long pendingAt;

    void prepare(Context context) {
        if(pool!=null)return;
        try {
            pool=new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((source,id,status)->{
                for(int i=0;i<sounds.length;i++)if(sounds[i]==id){
                    loaded[i]=status==0;
                    if(pending==i){pending=-1;if(loaded[i]&&SystemClock.elapsedRealtime()-pendingAt<MAX_PENDING_PLAY_DELAY_MS)source.play(id,0.55f,0.55f,1,0,1);}
                    break;
                }
            });
            sounds[KNOWN]=pool.load(context,R.raw.feedback_known,1);
            sounds[RETRY]=pool.load(context,R.raw.feedback_retry,1);
            sounds[COMPLETE]=pool.load(context,R.raw.feedback_complete,1);
        } catch(RuntimeException ignored) { release(); }
    }
    void confirm(View view){respond(view,COMPLETE);}
    /**
     * Non-zero while a finger-down tick for the current gesture has not yet cleared.
     * Click and checked-change listeners run on the way up; they must not vibrate again.
     */
    private static int pressHold;
    /** Light tick at finger down. Honours the haptic preference; motion settings do not gate it. */
    static void press(View view){
        if(view==null||!view.isEnabled())return;
        if(!Store.prefs(view.getContext()).getBoolean("haptic",true))return;
        pressHold++;
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
    /** Clears the finger-down hold after this gesture's click listeners have run. */
    static void releasePress(View view){
        if(view==null)return;
        int token=pressHold;
        Runnable clear=()->{if(pressHold==token)pressHold=0;};
        if(view.isAttachedToWindow())view.post(clear);else clear.run();
    }
    /** Touch listener for controls that already vibrate but do not use the press squeeze. */
    static boolean touch(View view,MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)press(view);
        else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)releasePress(view);
        return false;
    }
    static boolean held(){return pressHold!=0;}
    /** Light selection tick (segment change, toggle). Haptic only, never sound; honours the haptic preference. */
    static void tick(View view){
        if(view!=null&&pressHold==0&&Store.prefs(view.getContext()).getBoolean("haptic",true))view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
    void reveal(View view){
        if(pressHold==0&&Store.prefs(view.getContext()).getBoolean("haptic",true))view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
    void respond(View view,int kind){
        Context context=view.getContext();
        if(pressHold==0&&Store.prefs(context).getBoolean("haptic",true))view.performHapticFeedback(kind==RETRY?HapticFeedbackConstants.LONG_PRESS:HapticFeedbackConstants.VIRTUAL_KEY);
        if(Store.prefs(context).getBoolean("feedbackSound",true))play(context,kind);
    }
    void preview(Context context,int kind){play(context,kind);}
    private void play(Context context,int kind){
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(audio==null||audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL)return;
        prepare(context);
        if(pool==null)return;
        if(loaded[kind]){pending=-1;pool.play(sounds[kind],0.55f,0.55f,1,0,1);}
        else {pending=kind;pendingAt=SystemClock.elapsedRealtime();}
    }
    void release(){pending=-1;if(pool!=null){pool.release();pool=null;}for(int i=0;i<loaded.length;i++)loaded[i]=false;}
}
