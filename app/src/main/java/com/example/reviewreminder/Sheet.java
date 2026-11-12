package com.example.reviewreminder;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;

/**
 * Bottom sheet that replaces stock AlertDialogs everywhere: menus, confirmations, pickers and
 * small forms share one shape, one type scale and one motion.
 *
 * <pre>
 * new Sheet(activity).title("删除任务？").message("…")
 *     .secondary("保留",null).danger("删除",()->delete(id)).show();
 * new Sheet(activity).title("更多").item(R.drawable.ic_pencil,"编辑这张卡",()->edit()).show();
 * </pre>
 */
final class Sheet {
    interface Check { boolean ok(); }
    interface OnPick { void pick(int index); }

    final Activity a;
    final Dialog dialog;
    final LinearLayout panel;
    /** Custom content goes here (between the header and the buttons). */
    final LinearLayout body;
    private final LinearLayout header,list,buttons;
    private TextView titleView,messageView;
    private Button primary,secondary;
    private boolean stacked;

    Sheet(Activity a){
        this.a=a;
        dialog=new Dialog(a,R.style.SheetDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Theme.topRounded(a,Theme.SURFACE,Theme.RADIUS_SHEET));
        panel.setPadding(0,dp(8),0,dp(12));
        View handle=new View(a);handle.setBackground(Theme.shape(a,Theme.HAIRLINE,2));
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(36),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(8);panel.addView(handle,hp);
        header=col();header.setPadding(dp(24),dp(12),dp(24),dp(4));header.setVisibility(View.GONE);panel.addView(header);
        ScrollView scroll=new ScrollView(a);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout inner=col();
        body=col();body.setPadding(dp(24),dp(8),dp(24),0);body.setVisibility(View.GONE);inner.addView(body);
        list=col();list.setPadding(dp(8),dp(4),dp(8),0);list.setVisibility(View.GONE);inner.addView(list);
        scroll.addView(inner);
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        buttons=new LinearLayout(a);buttons.setOrientation(LinearLayout.HORIZONTAL);buttons.setPadding(dp(20),dp(16),dp(20),dp(4));buttons.setVisibility(View.GONE);panel.addView(buttons);
        // Weighted scroll would collapse in WRAP_CONTENT; a max-height wrapper keeps long sheets scrollable.
        dialog.setContentView(new MaxHeight(a,panel));
        dialog.setCanceledOnTouchOutside(true);
        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.32f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if(!Motion.enabled(a))w.setWindowAnimations(0);
            if(android.os.Build.VERSION.SDK_INT>=26)w.setNavigationBarColor(Theme.SURFACE);
        }
    }

    private int dp(float n){return Theme.dp(a,n);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}

    Sheet title(String value){
        if(titleView==null){titleView=new TextView(a);Theme.style(titleView,Theme.T_HEADLINE);titleView.setTextSize(19);header.addView(titleView,0);}
        titleView.setText(value);header.setVisibility(View.VISIBLE);return this;
    }
    Sheet message(String value){
        if(messageView==null){messageView=new TextView(a);Theme.style(messageView,Theme.T_CALLOUT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(6);header.addView(messageView,p);}
        messageView.setText(value);header.setVisibility(View.VISIBLE);return this;
    }
    TextView titleView(){return titleView;}

    /** Custom content (forms, pickers). */
    Sheet content(View v){body.addView(v,new LinearLayout.LayoutParams(-1,-2));body.setVisibility(View.VISIBLE);return this;}
    Sheet clearContent(){body.removeAllViews();return this;}

    /** Menu row; tapping dismisses the sheet, then runs the action. */
    Sheet item(int icon,String label,Runnable action){return addItem(icon,label,null,false,false,action);}
    Sheet item(String label,Runnable action){return addItem(0,label,null,false,false,action);}
    Sheet item(int icon,String label,String detail,Runnable action){return addItem(icon,label,detail,false,false,action);}
    Sheet dangerItem(int icon,String label,Runnable action){return addItem(icon,label,null,true,false,action);}

    private Sheet addItem(int icon,String label,String detail,boolean danger,boolean checked,Runnable action){
        LinearLayout r=new LinearLayout(a);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));r.setPadding(dp(16),dp(10),dp(16),dp(10));
        int fg=danger?Theme.DANGER:Theme.INK;
        if(icon!=0){ImageView i=new ImageView(a);i.setImageResource(icon);i.setImageTintList(android.content.res.ColorStateList.valueOf(danger?Theme.DANGER:Theme.INK_2));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(22),dp(22));ip.rightMargin=dp(16);r.addView(i,ip);}
        LinearLayout copy=col();TextView t=new TextView(a);t.setText(label);Theme.style(t,Theme.T_BODY);t.setTextSize(16);t.setTextColor(fg);copy.addView(t);
        if(detail!=null&&!detail.isEmpty()){TextView d=new TextView(a);d.setText(detail);Theme.style(d,Theme.T_CAPTION);copy.addView(d);}
        r.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        if(checked){ImageView c=new ImageView(a);c.setImageResource(R.drawable.ic_check);c.setImageTintList(android.content.res.ColorStateList.valueOf(Theme.PRIMARY));r.addView(c,new LinearLayout.LayoutParams(dp(20),dp(20)));t.setTextColor(Theme.PRIMARY);t.setTypeface(Theme.MEDIUM);}
        r.setClickable(true);r.setFocusable(true);
        r.setBackground(Theme.shape(a,Color.TRANSPARENT,Theme.RADIUS_FIELD));Theme.applyRipple(r,r.getBackground(),Theme.RIPPLE_LIGHT);Motion.press(r,.985f);
        r.setOnClickListener(v->{dialog.dismiss();if(action!=null)action.run();});
        list.addView(r,new LinearLayout.LayoutParams(-1,-2));list.setVisibility(View.VISIBLE);return this;
    }

    /** Single-choice list with a check on the current option. */
    Sheet choices(String[] options,int selected,OnPick pick){
        for(int i=0;i<options.length;i++){final int index=i;addItem(0,options[i],null,false,i==selected,()->pick.pick(index));}
        return this;
    }

    /** Filled primary action; dismisses then runs. */
    Sheet primary(String label,Runnable action){return primaryIf(label,()->{if(action!=null)action.run();return true;});}
    /** Filled primary action that stays open when {@code check} returns false (validation). */
    Sheet primaryIf(String label,Check check){primary=addButton(label,Theme.PRIMARY,Color.WHITE,Theme.RIPPLE_DARK,check,true);return this;}
    /** Destructive filled action. */
    Sheet danger(String label,Runnable action){primary=addButton(label,Theme.DANGER,Color.WHITE,Theme.RIPPLE_DARK,()->{if(action!=null)action.run();return true;},true);return this;}
    /** Quiet action (cancel / keep / back); null action simply closes. */
    Sheet secondary(String label,Runnable action){return secondaryIf(label,()->{if(action!=null)action.run();return true;});}
    Sheet secondaryIf(String label,Check check){secondary=addButton(label,Theme.SUNK,Theme.INK,Theme.RIPPLE_LIGHT,check,false);return this;}
    /** Stack buttons vertically (long labels). Call before adding buttons. */
    Sheet stacked(){stacked=true;buttons.setOrientation(LinearLayout.VERTICAL);return this;}

    Button primaryButton(){return primary;}
    Button secondaryButton(){return secondary;}

    private Button addButton(String label,int fill,int fg,int ripple,Check check,boolean isPrimary){
        WordUi.Btn b=new WordUi.Btn(a);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(Theme.MEDIUM);b.setTextColor(fg);b.setGravity(Gravity.CENTER);b.setMaxLines(1);
        b.setMinHeight(dp(Theme.BUTTON_HEIGHT));b.setMinimumHeight(dp(Theme.BUTTON_HEIGHT));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(12),0,dp(12),0);b.setStateListAnimator(null);b.setElevation(0);
        Theme.applyRipple(b,Theme.shape(a,fill,Theme.RADIUS_BUTTON),ripple);
        b.setOnClickListener(v->{if(check==null||check.ok())dialog.dismiss();});
        Motion.press(b);
        LinearLayout.LayoutParams p=stacked?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);
        if(stacked){if(buttons.getChildCount()>0)p.topMargin=dp(8);}
        else if(buttons.getChildCount()>0)p.leftMargin=dp(10);
        // Secondary sits left (or below when stacked), primary right (or on top).
        if(!stacked&&!isPrimary){buttons.addView(b,0,p);if(buttons.getChildCount()>1){((LinearLayout.LayoutParams)buttons.getChildAt(1).getLayoutParams()).leftMargin=dp(10);p.leftMargin=0;}}
        else if(stacked&&isPrimary){buttons.addView(b,0,p);if(buttons.getChildCount()>1){((LinearLayout.LayoutParams)buttons.getChildAt(1).getLayoutParams()).topMargin=dp(8);p.topMargin=0;}}
        else buttons.addView(b,p);
        buttons.setVisibility(View.VISIBLE);return b;
    }

    Sheet cancelable(boolean on){dialog.setCancelable(on);dialog.setCanceledOnTouchOutside(on);return this;}
    Sheet onDismiss(Runnable r){dialog.setOnDismissListener(d->r.run());return this;}
    Sheet show(){if(!a.isFinishing())dialog.show();return this;}
    void dismiss(){dialog.dismiss();}
    boolean isShowing(){return dialog.isShowing();}

    /** Convenience: single-choice picker. */
    static Sheet pick(Activity a,String title,String[] options,int selected,OnPick pick){return new Sheet(a).title(title).choices(options,selected,pick).show();}
    /** Convenience: confirmation with a quiet cancel and a primary (or destructive) action. */
    static Sheet confirm(Activity a,String title,String message,String cancel,String ok,boolean destructive,Runnable action){
        Sheet s=new Sheet(a).title(title);if(message!=null&&!message.isEmpty())s.message(message);
        s.secondary(cancel,null);if(destructive)s.danger(ok,action);else s.primary(ok,action);return s.show();
    }

    /** Limits the sheet to 88% of the screen so long content scrolls inside it. */
    private static final class MaxHeight extends FrameLayout {
        MaxHeight(Activity a,View child){super(a);addView(child,new FrameLayout.LayoutParams(-1,-2));}
        @Override protected void onMeasure(int w,int h){
            int max=(int)(getResources().getDisplayMetrics().heightPixels*.88f);
            int mode=MeasureSpec.getMode(h),size=MeasureSpec.getSize(h);
            int limit=mode==MeasureSpec.UNSPECIFIED?max:Math.min(max,size);
            super.onMeasure(w,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));
        }
    }
}
