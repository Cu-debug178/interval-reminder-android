package com.example.reviewreminder;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class WordUi {
    /** @deprecated Prefer Theme.*; kept as aliases for call sites. */
    static final int INK=Theme.INK,GREEN=Theme.PRIMARY,BG=Theme.BG,MUTED=Theme.MUTED;
    static final int PAPER=Theme.SURFACE,LINE=Theme.BORDER,ACCENT=Theme.PRIMARY,SOFT=Theme.PRIMARY_SOFT;

    final Activity a;LinearLayout body;
    WordUi(Activity a){this.a=a;}
    int dp(float n){return Theme.dp(a,n);}
    GradientDrawable bg(int color,int r){return Theme.shape(a,color,r);}
    TextView text(String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(1);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(a);l.setGravity(Gravity.CENTER_VERTICAL);return l;}

    void start(String title){
        LinearLayout screen=col();screen.setBackgroundColor(Theme.BG);
        screen.addView(pageChrome(title));
        ScrollView s=new ScrollView(a);s.setFillViewport(true);
        body=col();body.setPadding(dp(Theme.PAGE_PAD),dp(Theme.SPACE_12),dp(Theme.PAGE_PAD),dp(Theme.SPACE_24));
        s.addView(body);screen.addView(s,new LinearLayout.LayoutParams(-1,0,1));
        a.setContentView(screen);
        a.getWindow().setStatusBarColor(Theme.BG);
        a.getWindow().setNavigationBarColor(Theme.BG);
        int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(android.os.Build.VERSION.SDK_INT>=26)flags|=0x10;
        a.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    LinearLayout pageChrome(String title){
        LinearLayout header=row();
        header.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_8),dp(Theme.PAGE_PAD),dp(Theme.SPACE_8));
        ImageButton back=new ImageButton(a);
        back.setImageResource(R.drawable.ic_arrow_left);
        back.setContentDescription("返回");
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Theme.PRIMARY));
        back.setBackground(Theme.shape(a,Theme.PRIMARY_SOFT,Theme.RADIUS_CHIP));
        back.setPadding(dp(12),dp(12),dp(12),dp(12));
        back.setOnClickListener(v->a.onBackPressed());
        Motion.press(back);
        header.addView(back,new LinearLayout.LayoutParams(dp(40),dp(40)));
        TextView h=text(title,20,Theme.INK);h.setTypeface(null,1);
        LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(0,-2,1);heading.leftMargin=dp(Theme.SPACE_12);
        header.addView(h,heading);
        return header;
    }

    void gap(LinearLayout l,int n){l.addView(new View(a),new LinearLayout.LayoutParams(1,dp(n)));}

    TextView sectionLabel(String label){
        TextView t=text(label,13,Theme.MUTED);t.setTypeface(null,1);t.setAllCaps(false);return t;
    }

    LinearLayout card(){
        LinearLayout card=col();
        card.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_16),dp(Theme.SPACE_16),dp(Theme.SPACE_16));
        card.setBackground(Theme.shape(a,Theme.SURFACE,Theme.RADIUS_CARD,Theme.BORDER,1));
        card.setElevation(0);
        return card;
    }

    /** Backward-compatible: primary=true → filled; false → soft secondary. */
    Button button(String title,boolean primary,View.OnClickListener action){
        return primary?primaryButton(title,action):secondaryButton(title,action);
    }

    Button primaryButton(String title,View.OnClickListener action){
        return styledButton(title,Theme.PRIMARY,Color.WHITE,true,action);
    }

    Button secondaryButton(String title,View.OnClickListener action){
        return styledButton(title,Theme.PRIMARY_SOFT,Theme.PRIMARY,true,action);
    }

    Button ghostButton(String title,View.OnClickListener action){
        Button b=styledButton(title,Theme.SURFACE,Theme.INK,false,action);
        b.setBackground(Theme.shape(a,Theme.SURFACE,Theme.RADIUS_BUTTON,Theme.BORDER,1));
        Theme.applyRipple(b,b.getBackground());
        return b;
    }

    Button textButton(String title,View.OnClickListener action){
        Button b=new Button(a);
        b.setText(title);b.setAllCaps(false);b.setTextSize(Theme.BODY_SIZE);b.setTextColor(Theme.PRIMARY);
        b.setTypeface(null,1);b.setMinHeight(dp(40));b.setMinimumHeight(dp(40));
        b.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_4),dp(Theme.SPACE_8),dp(Theme.SPACE_4));
        b.setBackground(null);b.setStateListAnimator(null);b.setElevation(0);
        b.setOnClickListener(action);Motion.press(b);return b;
    }

    Button dangerTextButton(String title,View.OnClickListener action){
        Button b=textButton(title,action);b.setTextColor(Theme.DANGER);return b;
    }

    private Button styledButton(String title,int bgColor,int fg,boolean withIcon,View.OnClickListener action){
        Button b=new Button(a);
        b.setText(title);b.setAllCaps(false);b.setTextSize(Theme.BODY_SIZE);b.setTextColor(fg);
        b.setTypeface(null,1);
        b.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_8),dp(Theme.SPACE_16),dp(Theme.SPACE_8));
        b.setMinHeight(dp(Theme.BUTTON_HEIGHT));b.setMinimumHeight(dp(Theme.BUTTON_HEIGHT));
        b.setBackground(Theme.shape(a,bgColor,Theme.RADIUS_BUTTON));
        b.setStateListAnimator(null);b.setElevation(0);
        b.setOnClickListener(action);
        if(withIcon)UiIcons.apply(b,fg);
        Motion.press(b);
        return b;
    }

    static LinearLayout.LayoutParams lp(){return new LinearLayout.LayoutParams(-1,-2);}

    EditText field(String label,String value,int lines){
        body.addView(text(label,Theme.CAPTION_SIZE,Theme.MUTED));gap(body,Theme.SPACE_4);
        EditText e=new EditText(a);e.setText(value);e.setTextSize(16);e.setTextColor(Theme.INK);
        e.setPadding(dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12));
        e.setBackground(Theme.shape(a,Theme.SURFACE,Theme.RADIUS_BUTTON,Theme.BORDER,1));
        e.setMinHeight(dp(Theme.BUTTON_HEIGHT));e.setMinLines(lines);e.setMaxLines(Math.max(4,lines));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(body,Theme.SPACE_16);return e;
    }

    void toast(String message){Toast.makeText(a,message,Toast.LENGTH_LONG).show();}
}
