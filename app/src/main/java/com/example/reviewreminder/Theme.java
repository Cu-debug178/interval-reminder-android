package com.example.reviewreminder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/**
 * Design tokens for the "Paper & Ink" visual language.
 *
 * Warm paper background, near-black ink, one pine accent. Hierarchy comes from type weight,
 * size and spacing — not from borders or shadows. Every colour/size used by the UI lives here.
 */
final class Theme {
    // ── Colour ────────────────────────────────────────────────────────────────
    static final int BG=0xFFF6F5F1;           // paper
    static final int SURFACE=0xFFFFFFFF;      // raised sheet / grouped list
    static final int SUNK=0xFFEFEEE9;         // inset fills: secondary buttons, segmented track, fields on paper
    static final int HAIRLINE=0xFFE6E4DE;     // dividers, field outlines
    static final int INK=0xFF1B2220;          // primary text
    static final int INK_2=0xFF59625E;        // secondary text
    static final int INK_3=0xFF959B97;        // tertiary text, placeholders, inactive icons
    static final int PRIMARY=0xFF1F5A4C;      // pine — the only accent
    static final int PRIMARY_PRESSED=0xFF184A3E;
    static final int PRIMARY_SOFT=0xFFE4EEE9; // tonal fills, selected states
    static final int PRIMARY_TINT=0xFF6E9C8F; // heatmap mid, progress track hints
    static final int DANGER=0xFFB0503A;       // terracotta
    static final int DANGER_SOFT=0xFFF6E8E3;
    static final int WARN=0xFF8A5F17;
    static final int WARN_SOFT=0xFFF7EEDB;
    static final int SCRIM=0x66141A18;

    /** @deprecated aliases kept for older call sites. */
    static final int MUTED=INK_3, BORDER=HAIRLINE;

    // ── Space (dp) ────────────────────────────────────────────────────────────
    static final int SPACE_2=2,SPACE_4=4,SPACE_6=6,SPACE_8=8,SPACE_12=12,SPACE_16=16,SPACE_20=20,SPACE_24=24,SPACE_32=32,SPACE_40=40,SPACE_48=48;
    static final int PAGE_PAD=20;
    static final int ROW_PAD=16;

    // ── Shape (dp) ────────────────────────────────────────────────────────────
    static final int RADIUS_CARD=18,RADIUS_GROUP=16,RADIUS_BUTTON=14,RADIUS_FIELD=12,RADIUS_CHIP=10,RADIUS_SHEET=24;

    // ── Size (dp) ─────────────────────────────────────────────────────────────
    static final int BUTTON_HEIGHT=52,BUTTON_SMALL=36,ROW_HEIGHT=56,TOP_BAR=56,ICON=22,ICON_SMALL=18,TOUCH=48;

    // ── Type (sp) ─────────────────────────────────────────────────────────────
    static final int DISPLAY_SIZE=30,TITLE_SIZE=24,HEADLINE_SIZE=17,BODY_SIZE=15,CALLOUT_SIZE=14,CAPTION_SIZE=12;

    // ── Motion (ms) ───────────────────────────────────────────────────────────
    static final int PRESS_MS=150;
    static final int ENTER_MS=240;
    static final int LEAVE_MS=120;
    static final int SHEET_MS=280;

    private Theme(){}

    static int dp(Context c,float n){return (int)(c.getResources().getDisplayMetrics().density*n+.5f);}

    // ── Type faces ────────────────────────────────────────────────────────────
    static final Typeface REGULAR=Typeface.create("sans-serif",Typeface.NORMAL);
    static final Typeface MEDIUM=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    /** English headwords: a serif face gives flashcards a dictionary-like calm. */
    static final Typeface SERIF=Typeface.create(Typeface.SERIF,Typeface.NORMAL);
    static final Typeface SERIF_ITALIC=Typeface.create(Typeface.SERIF,Typeface.ITALIC);

    /** Applies a named text style. Kept as one place so every screen shares the same scale. */
    static TextView style(TextView t,int role){
        switch(role){
            case T_DISPLAY: t.setTextSize(DISPLAY_SIZE);t.setTypeface(MEDIUM);t.setTextColor(INK);t.setLetterSpacing(-0.01f);t.setLineSpacing(0,1.08f);break;
            case T_TITLE: t.setTextSize(TITLE_SIZE);t.setTypeface(MEDIUM);t.setTextColor(INK);t.setLetterSpacing(-0.005f);t.setLineSpacing(0,1.1f);break;
            case T_HEADLINE: t.setTextSize(HEADLINE_SIZE);t.setTypeface(MEDIUM);t.setTextColor(INK);t.setLineSpacing(0,1.15f);break;
            case T_BODY: t.setTextSize(BODY_SIZE);t.setTypeface(REGULAR);t.setTextColor(INK);t.setLineSpacing(0,1.25f);break;
            case T_CALLOUT: t.setTextSize(CALLOUT_SIZE);t.setTypeface(REGULAR);t.setTextColor(INK_2);t.setLineSpacing(0,1.25f);break;
            case T_CAPTION: t.setTextSize(CAPTION_SIZE);t.setTypeface(REGULAR);t.setTextColor(INK_3);t.setLineSpacing(0,1.25f);break;
            case T_OVERLINE: t.setTextSize(CAPTION_SIZE);t.setTypeface(MEDIUM);t.setTextColor(INK_3);t.setLetterSpacing(0.06f);break;
            case T_LABEL: t.setTextSize(BODY_SIZE);t.setTypeface(MEDIUM);t.setTextColor(INK);break;
            default: break;
        }
        return t;
    }
    static final int T_DISPLAY=0,T_TITLE=1,T_HEADLINE=2,T_BODY=3,T_CALLOUT=4,T_CAPTION=5,T_OVERLINE=6,T_LABEL=7;

    // ── Drawables ─────────────────────────────────────────────────────────────
    static GradientDrawable shape(Context c,int color,float radiusDp){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c,radiusDp));
        return d;
    }

    static GradientDrawable shape(Context c,int color,float radiusDp,int strokeColor,float strokeDp){
        GradientDrawable d=shape(c,color,radiusDp);
        if(strokeDp>0)d.setStroke(Math.max(1,dp(c,strokeDp)),strokeColor);
        return d;
    }

    static GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}

    /** Rounded top corners only (sheets). */
    static GradientDrawable topRounded(Context c,int color,float radiusDp){
        GradientDrawable d=new GradientDrawable();d.setColor(color);float r=dp(c,radiusDp);d.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});return d;
    }

    /** Field background: hairline at rest, pine outline when focused. */
    static Drawable fieldBackground(Context c,int fill){
        StateListDrawable s=new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},shape(c,fill,RADIUS_FIELD,PRIMARY,1.5f));
        s.addState(new int[]{},shape(c,fill,RADIUS_FIELD,HAIRLINE,1));
        return s;
    }

    /** Ripple colours: ink wash on light surfaces, white wash on filled pine/terracotta. */
    static final int RIPPLE_LIGHT=0x161B2220, RIPPLE_DARK=0x38FFFFFF, RIPPLE_ACCENT=0x241F5A4C;

    static void applyRipple(View v,Drawable background){applyRipple(v,background,RIPPLE_LIGHT);}

    static void applyRipple(View v,Drawable background,int rippleColor){
        if(background instanceof RippleDrawable){((RippleDrawable)background).setColor(ColorStateList.valueOf(rippleColor));return;}
        Drawable mask;
        if(background==null||background instanceof ColorDrawable&&((ColorDrawable)background).getColor()==Color.TRANSPARENT){
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),null,null));
            return;
        }
        mask=background.getConstantState()==null?new ColorDrawable(Color.WHITE):background.getConstantState().newDrawable().mutate();
        mask.setTint(Color.WHITE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),background,mask));
    }

    /** Borderless circular ripple for icon buttons. */
    static void applyBorderlessRipple(View v,int rippleColor){
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),null,oval(Color.WHITE)));
    }

    static int alpha(int color,float a){return (color&0x00FFFFFF)|(((int)(a*255)&0xFF)<<24);}
}
