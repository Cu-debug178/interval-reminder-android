package com.example.reviewreminder;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Segmented control: a sunk track with a white thumb that glides to the selected option.
 * Each option is a clickable TextView, so labels remain findable and accessible.
 */
final class Segmented extends FrameLayout {
    interface OnPick { void pick(int index); }
    private final View thumb;
    private final LinearLayout labels;
    private final TextView[] items;
    private int selected;
    private OnPick onPick;

    Segmented(Context c,String[] options,int initial){
        super(c);
        int pad=Theme.dp(c,3);
        setPadding(pad,pad,pad,pad);
        setBackground(Theme.shape(c,Theme.SUNK,Theme.RADIUS_FIELD));
        thumb=new View(c);thumb.setBackground(Theme.shape(c,Theme.SURFACE,Theme.RADIUS_FIELD-3));thumb.setElevation(Theme.dp(c,1));
        addView(thumb,new FrameLayout.LayoutParams(0,-1));
        labels=new LinearLayout(c);labels.setOrientation(LinearLayout.HORIZONTAL);
        addView(labels,new FrameLayout.LayoutParams(-1,Theme.dp(c,36)));
        labels.setElevation(Theme.dp(c,2)); // labels must draw above the elevated thumb
        items=new TextView[options.length];
        selected=Math.max(0,Math.min(options.length-1,initial));
        for(int i=0;i<options.length;i++){
            final int index=i;TextView t=new TextView(c);t.setText(options[i]);t.setGravity(Gravity.CENTER);t.setTextSize(Theme.CALLOUT_SIZE);t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setPadding(Theme.dp(c,6),0,Theme.dp(c,6),0);
            t.setClickable(true);t.setFocusable(true);t.setOnClickListener(v->{if(index!=selected){setSelected(index,true);Feedback.tick(v);if(onPick!=null)onPick.pick(index);}});
            items[i]=t;labels.addView(t,new LinearLayout.LayoutParams(0,-1,1));
        }
        paint();
    }

    void setOnPick(OnPick p){onPick=p;}
    int getSelected(){return selected;}
    /** Spinner-compatible alias. */
    int getSelectedItemPosition(){return selected;}

    void setSelected(int index,boolean animate){
        selected=Math.max(0,Math.min(items.length-1,index));paint();
        int w=labels.getWidth()/items.length;
        if(w==0)return;
        if(animate&&Motion.enabled(getContext()))thumb.animate().translationX(w*selected).setDuration(Theme.ENTER_MS).setInterpolator(Motion.EASE).start();
        else thumb.setTranslationX(w*selected);
    }

    private void paint(){
        for(int i=0;i<items.length;i++){boolean on=i==selected;items[i].setTextColor(on?Theme.INK:Theme.INK_2);items[i].setTypeface(on?Theme.MEDIUM:Theme.REGULAR);items[i].setSelected(on);}
    }

    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        super.onLayout(changed,l,t,r,b);
        int w=labels.getWidth()/Math.max(1,items.length);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)thumb.getLayoutParams();
        if(p.width!=w){p.width=w;thumb.setLayoutParams(p);post(()->thumb.setTranslationX(w*selected));}
    }
}
