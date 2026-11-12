package com.example.reviewreminder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/** Global design tokens and light drawing helpers for the UI redesign. */
final class Theme {
    static final int BG=0xFFF7F8F6;
    static final int SURFACE=0xFFFFFFFF;
    static final int PRIMARY=0xFF205247;
    static final int PRIMARY_SOFT=0xFFE5F1EA;
    static final int INK=0xFF203A33;
    static final int MUTED=0xFF788780;
    static final int DANGER=0xFFB56750;
    static final int DANGER_SOFT=0xFFF6EBE7;
    static final int BORDER=0xFFE5EAE6;
    static final int WARN=0xFF855111;
    static final int WARN_SOFT=0xFFFFF2D8;

    static final int SPACE_4=4,SPACE_8=8,SPACE_12=12,SPACE_16=16,SPACE_20=20,SPACE_24=24,SPACE_32=32;
    static final int PAGE_PAD=20;
    static final int RADIUS_CARD=16,RADIUS_BUTTON=13,RADIUS_CHIP=12;
    static final int BUTTON_HEIGHT=50;
    static final int TITLE_SIZE=26,BODY_SIZE=15,CAPTION_SIZE=13;

    static final int PRESS_MS=140;
    static final int ENTER_MS=200;
    static final int LEAVE_MS=90;

    private Theme(){}

    static int dp(Context c,float n){return (int)(c.getResources().getDisplayMetrics().density*n+.5f);}

    static GradientDrawable shape(Context c,int color,float radiusDp){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c,radiusDp));
        return d;
    }

    static GradientDrawable shape(Context c,int color,float radiusDp,int strokeColor,float strokeDp){
        GradientDrawable d=shape(c,color,radiusDp);
        if(strokeDp>0)d.setStroke(dp(c,strokeDp),strokeColor);
        return d;
    }

    static void applyRipple(View v,android.graphics.drawable.Drawable background){
        android.graphics.drawable.Drawable base=background==null?new android.graphics.drawable.ColorDrawable(SURFACE):background;
        android.graphics.drawable.Drawable mask=base.getConstantState()==null
            ?new android.graphics.drawable.ColorDrawable(Color.WHITE)
            :base.getConstantState().newDrawable().mutate();
        mask.setTint(Color.WHITE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33205247),base,mask));
    }
}
