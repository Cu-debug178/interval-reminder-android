package com.example.reviewreminder;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class WordUi {
    static final int INK=0xFF1D3531,GREEN=0xFF184E45,BG=0xFFF4F8F6,MUTED=0xFF677B76;
    final Activity a;LinearLayout body;
    WordUi(Activity a){this.a=a;}
    int dp(float n){return (int)(a.getResources().getDisplayMetrics().density*n+.5f);}
    GradientDrawable bg(int color,int r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(r));return d;}
    TextView text(String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(1);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(a);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void start(String title){LinearLayout screen=col();screen.setBackgroundColor(BG);LinearLayout header=row();header.setPadding(dp(10),dp(8),dp(20),dp(8));ImageButton back=new ImageButton(a);back.setImageResource(R.drawable.ic_arrow_left);back.setContentDescription("返回");back.setBackground(bg(0xFFDEF2EA,16));back.setPadding(dp(13),dp(13),dp(13),dp(13));back.setOnClickListener(v->a.onBackPressed());Motion.press(back);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView h=text(title,22,INK);h.setTypeface(null,1);LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(0,-2,1);heading.leftMargin=dp(12);header.addView(h,heading);screen.addView(header);ScrollView s=new ScrollView(a);s.setFillViewport(true);body=col();body.setPadding(dp(22),dp(12),dp(22),dp(28));s.addView(body);screen.addView(s,new LinearLayout.LayoutParams(-1,0,1));a.setContentView(screen);a.getWindow().setStatusBarColor(GREEN);a.getWindow().setNavigationBarColor(BG);}
    void gap(LinearLayout l,int n){l.addView(new View(a),new LinearLayout.LayoutParams(1,dp(n)));}
    Button button(String title,boolean primary,View.OnClickListener action){Button b=new Button(a);b.setText(title);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?Color.WHITE:GREEN);b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setMinimumHeight(dp(52));b.setMinHeight(dp(52));b.setBackground(bg(primary?GREEN:0xFFDEF2EA,16));b.setStateListAnimator(null);b.setOnClickListener(action);UiIcons.apply(b,primary?Color.WHITE:GREEN);Motion.press(b);return b;}
    EditText field(String label,String value,int lines){body.addView(text(label,13,MUTED));gap(body,5);EditText e=new EditText(a);e.setText(value);e.setTextSize(18);e.setTextColor(INK);e.setPadding(dp(14),dp(10),dp(14),dp(10));e.setBackground(bg(Color.WHITE,14));e.setMinHeight(dp(54));e.setMinLines(lines);e.setMaxLines(Math.max(4,lines));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);body.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(body,16);return e;}
    void toast(String message){Toast.makeText(a,message,Toast.LENGTH_LONG).show();}
}
