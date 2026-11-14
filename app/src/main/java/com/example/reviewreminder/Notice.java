package com.example.reviewreminder;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * In-app confirmation / error message: a dark ink capsule that rises above the bottom edge,
 * stays briefly, then fades. Falls back to a system Toast when the activity is leaving, so a
 * saved/failed result is never lost.
 */
final class Notice {
    private Notice(){}
    private static final String TAG="app-notice";
    /** Extra bottom offset (dp) — set by screens with a bottom bar. */
    static int bottomOffsetDp(Activity a){Object v=a.getWindow().getDecorView().getTag(R.id.notice_offset);return v instanceof Integer?(Integer)v:0;}
    static void setBottomOffset(Activity a,int dp){a.getWindow().getDecorView().setTag(R.id.notice_offset,dp);}

    static void show(Activity a,String message){show(a,message,false);}
    static void error(Activity a,String message){show(a,message,true);}

    static void show(Activity a,String message,boolean error){
        if(message==null||message.isEmpty())return;
        if(a.isFinishing()||a.isDestroyed()){Toast.makeText(a.getApplicationContext(),message,Toast.LENGTH_LONG).show();return;}
        View content=a.findViewById(android.R.id.content);
        if(!(content instanceof FrameLayout)){Toast.makeText(a,message,Toast.LENGTH_LONG).show();return;}
        FrameLayout host=(FrameLayout)content;
        View old=host.findViewWithTag(TAG);if(old!=null){old.animate().cancel();host.removeView(old);}
        LinearLayout pill=new LinearLayout(a);pill.setTag(TAG);pill.setOrientation(LinearLayout.HORIZONTAL);pill.setGravity(Gravity.CENTER_VERTICAL);
        int p=Theme.dp(a,16);pill.setPadding(p,Theme.dp(a,12),Theme.dp(a,18),Theme.dp(a,12));
        pill.setBackground(Theme.shape(a,0xF21B2220,14));pill.setElevation(Theme.dp(a,6));
        ImageView icon=new ImageView(a);icon.setImageResource(error?R.drawable.ic_circle_alert:R.drawable.ic_circle_check);
        icon.setImageTintList(ColorStateList.valueOf(error?0xFFF2A28F:0xFF9FD3BF));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Theme.dp(a,18),Theme.dp(a,18));ip.rightMargin=Theme.dp(a,10);pill.addView(icon,ip);
        TextView t=new TextView(a);t.setText(message);t.setTextColor(0xFFF6F5F1);t.setTextSize(14);t.setTypeface(Theme.REGULAR);t.setLineSpacing(0,1.2f);t.setMaxLines(4);
        pill.addView(t,new LinearLayout.LayoutParams(-2,-2));
        pill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        int side=Theme.dp(a,20);lp.setMargins(side,0,side,Theme.dp(a,24+bottomOffsetDp(a)));
        host.addView(pill,lp);
        pill.setOnClickListener(v->dismiss(host,pill));
        boolean motion=Motion.enabled(a);
        if(motion){pill.setAlpha(0);pill.setTranslationY(Theme.dp(a,12));pill.animate().alpha(1).translationY(0).setDuration(Theme.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();}
        long hold=Math.min(5200,2200+message.length()*45L);
        pill.postDelayed(()->dismiss(host,pill),hold);
    }

    private static void dismiss(FrameLayout host,View pill){
        if(pill.getParent()!=host)return;
        if(!Motion.enabled(pill.getContext())){host.removeView(pill);return;}
        pill.animate().alpha(0).translationY(Theme.dp(pill.getContext(),8)).setDuration(Theme.LEAVE_MS+60).setInterpolator(Motion.EASE_IN).withEndAction(()->host.removeView(pill)).start();
    }
}
