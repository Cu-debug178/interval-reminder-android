package com.example.reviewreminder;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/**
 * Shared component kit for the "Paper & Ink" language. Every screen builds from these parts so
 * spacing, type, colour and press feedback stay identical everywhere.
 *
 * Page anatomy (from {@link #start}):
 *   top bar (back · title · optional trailing icon)
 *   scrolling {@link #body}
 *   optional pinned {@link #footer} for the page's primary action
 */
final class WordUi {
    /** @deprecated Prefer Theme.*; kept as aliases for call sites. */
    static final int INK=Theme.INK,GREEN=Theme.PRIMARY,BG=Theme.BG,MUTED=Theme.INK_3;
    static final int PAPER=Theme.SURFACE,LINE=Theme.HAIRLINE,ACCENT=Theme.PRIMARY,SOFT=Theme.PRIMARY_SOFT;

    final Activity a;
    /** Scrolling page content. */
    LinearLayout body;
    /** Pinned bottom action area; hidden until something is added. */
    LinearLayout footer;
    /** Top bar title (may be updated in place). */
    TextView barTitle;
    LinearLayout bar;
    ScrollView scroll;
    FrameLayout frame;

    WordUi(Activity a){this.a=a;}
    int dp(float n){return Theme.dp(a,n);}
    GradientDrawable bg(int color,int r){return Theme.shape(a,color,r);}

    // ── Text ──────────────────────────────────────────────────────────────────
    TextView text(String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Theme.REGULAR);t.setLineSpacing(0,1.2f);return t;}
    /** Text with a named role from {@link Theme#style}: Theme.T_TITLE, T_BODY, T_CAPTION … */
    TextView type(String value,int role){TextView t=new TextView(a);t.setText(value);return Theme.style(t,role);}
    LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void gap(LinearLayout l,int n){l.addView(new View(a),new LinearLayout.LayoutParams(1,dp(n)));}
    static LinearLayout.LayoutParams lp(){return new LinearLayout.LayoutParams(-1,-2);}
    static LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    LinearLayout.LayoutParams lpMargin(int top,int bottom){LinearLayout.LayoutParams p=lp();p.topMargin=dp(top);p.bottomMargin=dp(bottom);return p;}

    // ── Page ──────────────────────────────────────────────────────────────────
    void start(String title){start(title,R.drawable.ic_arrow_left,"返回",0,null,null);}
    /** Back-arrow page with a trailing icon action (e.g. ⋯ more). */
    void start(String title,int trailingIcon,String trailingDescription,View.OnClickListener trailing){start(title,R.drawable.ic_arrow_left,"返回",trailingIcon,trailingDescription,trailing);}
    /** Modal flow page (review, quiz): leading ✕ instead of back. */
    void startModal(String title){start(title,R.drawable.ic_x,"关闭",0,null,null);}
    void startModal(String title,int trailingIcon,String trailingDescription,View.OnClickListener trailing){start(title,R.drawable.ic_x,"关闭",trailingIcon,trailingDescription,trailing);}

    void start(String title,int leadingIcon,String leadingDescription,int trailingIcon,String trailingDescription,View.OnClickListener trailing){
        LinearLayout screen=col();screen.setBackgroundColor(Theme.BG);
        bar=pageChrome(title,leadingIcon,leadingDescription,trailingIcon,trailingDescription,trailing);
        screen.addView(bar);
        scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body=col();body.setPadding(dp(Theme.PAGE_PAD),dp(Theme.SPACE_8),dp(Theme.PAGE_PAD),dp(Theme.SPACE_32));
        scroll.addView(body);
        // Hairline under the bar appears only once content scrolls beneath it.
        View shadow=new View(a);shadow.setBackgroundColor(Theme.HAIRLINE);shadow.setAlpha(0);
        frame=new FrameLayout(a);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));frame.addView(shadow,new FrameLayout.LayoutParams(-1,Math.max(1,dp(.75f))));
        scroll.getViewTreeObserver().addOnScrollChangedListener(()->shadow.setAlpha(Math.min(1f,scroll.getScrollY()/(float)dp(12))));
        screen.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        footer=col();footer.setPadding(dp(Theme.PAGE_PAD),dp(Theme.SPACE_12),dp(Theme.PAGE_PAD),dp(Theme.SPACE_16));footer.setBackgroundColor(Theme.BG);footer.setVisibility(View.GONE);
        screen.addView(footer,lp());
        a.setContentView(screen);
        systemBars(a);
    }

    /** Pins views to the bottom of the page (primary actions stay reachable while content scrolls). */
    void pin(View... views){
        footer.removeAllViews();
        for(int i=0;i<views.length;i++){if(i>0)gap(footer,Theme.SPACE_8);footer.addView(views[i],lp());}
        footer.setVisibility(views.length==0?View.GONE:View.VISIBLE);
    }
    /** Two actions side by side in the footer: secondary left, primary right. */
    void pinPair(View secondary,View primary){
        footer.removeAllViews();LinearLayout r=row();
        LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-2,1);l.rightMargin=dp(Theme.SPACE_8);r.addView(secondary,l);
        r.addView(primary,new LinearLayout.LayoutParams(0,-2,1.4f));footer.addView(r,lp());footer.setVisibility(View.VISIBLE);
    }

    static void systemBars(Activity a){
        a.getWindow().setStatusBarColor(Theme.BG);
        a.getWindow().setNavigationBarColor(Theme.BG);
        int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(android.os.Build.VERSION.SDK_INT>=26)flags|=0x10;
        a.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    LinearLayout pageChrome(String title){return pageChrome(title,R.drawable.ic_arrow_left,"返回",0,null,null);}
    LinearLayout pageChrome(String title,int leadingIcon,String leadingDescription,int trailingIcon,String trailingDescription,View.OnClickListener trailing){
        LinearLayout header=row();header.setMinimumHeight(dp(Theme.TOP_BAR));
        header.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_4),dp(Theme.SPACE_4),dp(Theme.SPACE_4));
        header.addView(iconButton(leadingIcon,leadingDescription,v->a.onBackPressed()),new LinearLayout.LayoutParams(dp(Theme.TOUCH),dp(Theme.TOUCH)));
        barTitle=type(title,Theme.T_HEADLINE);barTitle.setSingleLine(true);barTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(0,-2,1);heading.leftMargin=dp(Theme.SPACE_4);
        header.addView(barTitle,heading);
        if(trailingIcon!=0)header.addView(iconButton(trailingIcon,trailingDescription,trailing),new LinearLayout.LayoutParams(dp(Theme.TOUCH),dp(Theme.TOUCH)));
        else header.addView(new View(a),new LinearLayout.LayoutParams(dp(Theme.SPACE_12),1));
        return header;
    }

    /** Large page title shown at the top of a page body (for root-level pages). */
    TextView largeTitle(LinearLayout parent,String title,String subtitle){
        if(subtitle!=null&&!subtitle.isEmpty()){TextView s=type(subtitle,Theme.T_CAPTION);s.setTextColor(Theme.INK_2);parent.addView(s);gap(parent,Theme.SPACE_4);}
        TextView t=type(title,Theme.T_DISPLAY);parent.addView(t);return t;
    }

    // ── Sections & surfaces ──────────────────────────────────────────────────
    /** Small section heading above a group; optional trailing caption on the right. */
    LinearLayout section(LinearLayout parent,String label,String trailing){
        LinearLayout r=row();r.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_24),dp(Theme.SPACE_4),dp(Theme.SPACE_8));
        TextView t=type(label,Theme.T_LABEL);t.setTextSize(Theme.CALLOUT_SIZE);t.setTextColor(Theme.INK_2);r.addView(t,weight());
        if(trailing!=null&&!trailing.isEmpty()){TextView tr=type(trailing,Theme.T_CAPTION);r.addView(tr);}
        parent.addView(r,lp());return r;
    }

    TextView sectionLabel(String label){TextView t=type(label,Theme.T_LABEL);t.setTextSize(Theme.CALLOUT_SIZE);t.setTextColor(Theme.INK_2);return t;}

    /** Footnote under a group — explains, never shouts. */
    TextView note(LinearLayout parent,String value){TextView t=type(value,Theme.T_CAPTION);t.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_8),dp(Theme.SPACE_4),0);parent.addView(t,lp());return t;}

    /** White surface with soft corners and no border/shadow; content padded. */
    LinearLayout card(){
        LinearLayout card=col();
        card.setPadding(dp(Theme.SPACE_20),dp(Theme.SPACE_20),dp(Theme.SPACE_20),dp(Theme.SPACE_20));
        card.setBackground(Theme.shape(a,Theme.SURFACE,Theme.RADIUS_CARD));
        card.setElevation(0);
        return card;
    }

    /** Inset grouped list container (rows added with {@link #item} get hairline dividers automatically). */
    LinearLayout group(){LinearLayout g=col();g.setBackground(Theme.shape(a,Theme.SURFACE,Theme.RADIUS_GROUP));g.setClipToOutline(true);return g;}

    /** Adds a hairline divider if the group already has rows; inset to align with the text column. */
    void divide(LinearLayout group,int insetDp){
        if(group.getChildCount()==0)return;
        View d=new View(a);d.setBackgroundColor(Theme.HAIRLINE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.75f)));p.leftMargin=dp(insetDp);group.addView(d,p);
    }

    /**
     * A list row: [icon] title / subtitle … trailing [›]. Clickable rows get ripple + press.
     * Returns the row so callers can add extra trailing views.
     */
    LinearLayout item(LinearLayout group,int icon,String title,String subtitle,String trailing,boolean chevron,View.OnClickListener click){
        divide(group,icon!=0?Theme.ROW_PAD+Theme.ICON+Theme.SPACE_16:Theme.ROW_PAD);
        LinearLayout r=row();r.setMinimumHeight(dp(Theme.ROW_HEIGHT));r.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_12),dp(chevron?Theme.SPACE_12:Theme.ROW_PAD),dp(Theme.SPACE_12));
        if(icon!=0){ImageView i=icon(icon,Theme.INK_2);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(Theme.ICON),dp(Theme.ICON));ip.rightMargin=dp(Theme.SPACE_16);r.addView(i,ip);}
        LinearLayout copy=col();TextView t=type(title,Theme.T_BODY);t.setTypeface(Theme.MEDIUM);copy.addView(t);
        if(subtitle!=null&&!subtitle.isEmpty()){TextView s=type(subtitle,Theme.T_CAPTION);s.setTextColor(Theme.INK_3);LinearLayout.LayoutParams sp=lp();sp.topMargin=dp(2);copy.addView(s,sp);}
        r.addView(copy,weight());
        if(trailing!=null&&!trailing.isEmpty()){TextView tr=type(trailing,Theme.T_CALLOUT);tr.setTextColor(Theme.INK_3);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=dp(Theme.SPACE_12);r.addView(tr,tp);}
        if(chevron){ImageView c=icon(R.drawable.ic_chevron_right,Theme.INK_3);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(Theme.ICON_SMALL),dp(Theme.ICON_SMALL));cp.leftMargin=dp(Theme.SPACE_4);r.addView(c,cp);}
        if(click!=null){r.setClickable(true);r.setFocusable(true);r.setOnClickListener(click);Theme.applyRipple(r,null,Theme.RIPPLE_LIGHT);Motion.press(r,.985f);}
        group.addView(r,lp());return r;
    }

    /**
     * A switch row inside a group. The Switch itself carries the label (and an optional smaller
     * second line) so it stays one accessible, tappable target.
     */
    Switch switchRow(LinearLayout group,String label,String detail,boolean checked){
        divide(group,Theme.ROW_PAD);
        Switch s=new Switch(a);
        CharSequence text=label;
        if(detail!=null&&!detail.isEmpty()){
            SpannableString sp=new SpannableString(label+"\n"+detail);int st=label.length()+1;
            sp.setSpan(new AbsoluteSizeSpan(Theme.CAPTION_SIZE,true),st,sp.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(Theme.INK_3),st,sp.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text=sp;
        }
        s.setText(text);s.setTextSize(Theme.BODY_SIZE);s.setTextColor(Theme.INK);s.setTypeface(Theme.REGULAR);s.setLineSpacing(dp(2),1f);
        s.setMinHeight(dp(Theme.ROW_HEIGHT));s.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_12),dp(Theme.ROW_PAD),dp(Theme.SPACE_12));
        s.setSwitchPadding(dp(Theme.SPACE_16));s.setChecked(checked);
        Theme.applyRipple(s,null,Theme.RIPPLE_LIGHT);
        s.setOnCheckedChangeListener((b,on)->Feedback.tick(b));
        group.addView(s,lp());return s;
    }

    /** Switch row outside a group (wraps itself in a one-row group). */
    Switch switchRow(String label,String detail,boolean checked){LinearLayout g=group();Switch s=switchRow(g,label,detail,checked);body.addView(g,lp());gap(body,Theme.SPACE_8);return s;}

    // ── Buttons ───────────────────────────────────────────────────────────────
    /** Backward-compatible: primary=true → filled; false → quiet secondary. */
    Button button(String title,boolean primary,View.OnClickListener action){return primary?primaryButton(title,action):secondaryButton(title,action);}

    Button primaryButton(String title,View.OnClickListener action){return styled(title,Theme.PRIMARY,Color.WHITE,Theme.RIPPLE_DARK,0,action);}
    Button primaryButton(String title,int icon,View.OnClickListener action){return styled(title,Theme.PRIMARY,Color.WHITE,Theme.RIPPLE_DARK,icon,action);}
    /** Quiet filled button on paper (sunk fill, ink text). */
    Button secondaryButton(String title,View.OnClickListener action){return styled(title,Theme.SUNK,Theme.INK,Theme.RIPPLE_LIGHT,0,action);}
    Button secondaryButton(String title,int icon,View.OnClickListener action){return styled(title,Theme.SUNK,Theme.INK,Theme.RIPPLE_LIGHT,icon,action);}
    /** Pine-tinted button for positive secondary actions. */
    Button tonalButton(String title,int icon,View.OnClickListener action){return styled(title,Theme.PRIMARY_SOFT,Theme.PRIMARY,Theme.RIPPLE_ACCENT,icon,action);}
    /** Outline button for use on white surfaces. */
    Button ghostButton(String title,View.OnClickListener action){
        Button b=styled(title,Theme.SURFACE,Theme.INK,Theme.RIPPLE_LIGHT,0,action);
        Theme.applyRipple(b,Theme.shape(a,Theme.SURFACE,Theme.RADIUS_BUTTON,Theme.HAIRLINE,1),Theme.RIPPLE_LIGHT);
        return b;
    }
    /** Destructive confirmation (filled terracotta). */
    Button dangerButton(String title,View.OnClickListener action){return styled(title,Theme.DANGER,Color.WHITE,Theme.RIPPLE_DARK,0,action);}

    Button textButton(String title,View.OnClickListener action){
        Button b=new Btn(a);
        b.setText(title);b.setAllCaps(false);b.setTextSize(Theme.BODY_SIZE);b.setTextColor(Theme.PRIMARY);b.setTypeface(Theme.MEDIUM);
        b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(Theme.SPACE_12),0,dp(Theme.SPACE_12),0);
        b.setBackground(Theme.shape(a,Color.TRANSPARENT,Theme.RADIUS_CHIP));Theme.applyRipple(b,b.getBackground(),Theme.RIPPLE_ACCENT);
        b.setStateListAnimator(null);b.setElevation(0);
        b.setOnClickListener(action);Motion.press(b,.96f);return b;
    }
    Button dangerTextButton(String title,View.OnClickListener action){Button b=textButton(title,action);b.setTextColor(Theme.DANGER);Theme.applyRipple(b,Theme.shape(a,Color.TRANSPARENT,Theme.RADIUS_CHIP),0x22B0503A);return b;}

    /** Compact tonal pill (36dp) for inline row actions like “任意复习”. */
    Button pill(String title,int icon,View.OnClickListener action){
        Button b=styled(title,Theme.PRIMARY_SOFT,Theme.PRIMARY,Theme.RIPPLE_ACCENT,icon,action);
        b.setTextSize(Theme.CALLOUT_SIZE);b.setMinHeight(dp(Theme.BUTTON_SMALL));b.setMinimumHeight(dp(Theme.BUTTON_SMALL));
        b.setPadding(dp(Theme.SPACE_12),0,dp(icon==0?Theme.SPACE_12:Theme.SPACE_16),0);
        Theme.applyRipple(b,Theme.shape(a,Theme.PRIMARY_SOFT,Theme.BUTTON_SMALL/2f),Theme.RIPPLE_ACCENT);
        if(icon!=0)setIcon(b,icon,Theme.PRIMARY,16);
        return b;
    }
    /** Neutral pill (sunk fill, ink text). */
    Button quietPill(String title,int icon,View.OnClickListener action){
        Button b=pill(title,icon,action);b.setTextColor(Theme.INK);
        Theme.applyRipple(b,Theme.shape(a,Theme.SUNK,Theme.BUTTON_SMALL/2f),Theme.RIPPLE_LIGHT);
        if(icon!=0)setIcon(b,icon,Theme.INK_2,16);
        return b;
    }

    private Button styled(String title,int fill,int fg,int ripple,int icon,View.OnClickListener action){
        Button b=new Btn(a);
        b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(fg);b.setTypeface(Theme.MEDIUM);b.setLetterSpacing(.01f);
        b.setGravity(Gravity.CENTER);b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(dp(Theme.SPACE_20),0,dp(Theme.SPACE_20),0);
        b.setMinHeight(dp(Theme.BUTTON_HEIGHT));b.setMinimumHeight(dp(Theme.BUTTON_HEIGHT));b.setMinWidth(0);b.setMinimumWidth(0);
        Theme.applyRipple(b,Theme.shape(a,fill,Theme.RADIUS_BUTTON),ripple);
        b.setStateListAnimator(null);b.setElevation(0);
        b.setOnClickListener(action);
        if(icon!=0)setIcon(b,icon,fg,Theme.ICON_SMALL);
        Motion.press(b);
        return b;
    }

    /** Adds a leading icon; {@link Btn} keeps icon+label centred as one unit. */
    void setIcon(TextView b,int icon,int color,int sizeDp){
        Drawable d=a.getDrawable(icon).mutate();d.setTint(color);int size=dp(sizeDp);d.setBounds(0,0,size,size);
        b.setCompoundDrawables(d,null,null,null);b.setCompoundDrawablePadding(dp(Theme.SPACE_8));
    }

    /** Disabled controls fade back instead of changing colour. */
    static void enable(View v,boolean on){v.setEnabled(on);v.setAlpha(on?1f:.38f);}

    /** 48dp borderless icon button with circular ripple. */
    ImageButton iconButton(int icon,String description,View.OnClickListener click){
        ImageButton b=new ImageButton(a);b.setImageResource(icon);b.setContentDescription(description);
        b.setImageTintList(ColorStateList.valueOf(Theme.INK));b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(13),dp(13),dp(13),dp(13));Theme.applyBorderlessRipple(b,Theme.RIPPLE_LIGHT);
        b.setOnClickListener(click);Motion.press(b,.92f);return b;
    }

    ImageView icon(int res,int color){ImageView i=new ImageView(a);i.setImageResource(res);i.setImageTintList(ColorStateList.valueOf(color));return i;}

    // ── Small parts ──────────────────────────────────────────────────────────
    /** Status chip: compact, quiet label (e.g. “第 2 轮”, “已到期”). */
    TextView chip(String value,int fill,int fg){
        TextView t=type(value,Theme.T_CAPTION);t.setTextColor(fg);t.setTypeface(Theme.MEDIUM);
        t.setPadding(dp(Theme.SPACE_8),dp(3),dp(Theme.SPACE_8),dp(3));t.setBackground(Theme.shape(a,fill,Theme.RADIUS_CHIP));return t;
    }

    /** Inline banner for state the user should act on (warning) or know about (info). */
    LinearLayout banner(int icon,String message,boolean warning,View.OnClickListener click){
        LinearLayout b=row();b.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12));
        int fill=warning?Theme.WARN_SOFT:Theme.PRIMARY_SOFT,fg=warning?Theme.WARN:Theme.PRIMARY;
        b.setBackground(Theme.shape(a,fill,Theme.RADIUS_GROUP));
        if(icon!=0){ImageView i=icon(icon,fg);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(Theme.ICON_SMALL),dp(Theme.ICON_SMALL));ip.rightMargin=dp(Theme.SPACE_12);b.addView(i,ip);}
        TextView t=type(message,Theme.T_CALLOUT);t.setTextColor(fg);b.addView(t,weight());
        if(click!=null){ImageView c=icon(R.drawable.ic_chevron_right,fg);b.addView(c,new LinearLayout.LayoutParams(dp(Theme.ICON_SMALL),dp(Theme.ICON_SMALL)));b.setClickable(true);b.setOnClickListener(click);Theme.applyRipple(b,b.getBackground(),Theme.alpha(fg,.14f));Motion.press(b,.985f);}
        return b;
    }

    /** Calm empty state: icon in a soft circle, one line, one helper line, optional action. */
    LinearLayout empty(int icon,String title,String detail,String action,View.OnClickListener run){
        LinearLayout box=col();box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(Theme.SPACE_24),dp(Theme.SPACE_32),dp(Theme.SPACE_24),dp(Theme.SPACE_24));
        box.setBackground(Theme.shape(a,Theme.SURFACE,Theme.RADIUS_CARD));
        if(icon!=0){FrameLayout halo=new FrameLayout(a);halo.setBackground(Theme.oval(Theme.PRIMARY_SOFT));ImageView i=icon(icon,Theme.PRIMARY);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER);halo.addView(i,ip);box.addView(halo,new LinearLayout.LayoutParams(dp(52),dp(52)));gap(box,Theme.SPACE_16);}
        TextView h=type(title,Theme.T_HEADLINE);h.setGravity(Gravity.CENTER);box.addView(h);gap(box,Theme.SPACE_6);
        TextView d=type(detail,Theme.T_CALLOUT);d.setGravity(Gravity.CENTER);d.setTextColor(Theme.INK_3);box.addView(d);
        if(action!=null){gap(box,Theme.SPACE_16);box.addView(pill(action,0,run),new LinearLayout.LayoutParams(-2,-2));}
        return box;
    }

    /** Segmented control for 2–4 short, mutually exclusive options. */
    Segmented segmented(String[] labels,int selected,Segmented.OnPick change){Segmented s=new Segmented(a,labels,selected);s.setOnPick(change);return s;}

    // ── Fields ────────────────────────────────────────────────────────────────
    /** Labelled field appended to {@link #body}. */
    EditText field(String label,String value,int lines){
        TextView l=type(label,Theme.T_CALLOUT);l.setTypeface(Theme.MEDIUM);l.setTextColor(Theme.INK_2);l.setPadding(dp(Theme.SPACE_4),0,0,0);
        body.addView(l);gap(body,Theme.SPACE_6);
        EditText e=input("",value);e.setMinLines(lines);e.setMaxLines(Math.max(4,lines));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setGravity(lines>1?Gravity.TOP|Gravity.START:Gravity.CENTER_VERTICAL|Gravity.START);
        body.addView(e,lp());gap(body,Theme.SPACE_20);return e;
    }
    /** Standalone styled input (white fill, hairline, pine focus ring). */
    EditText input(String hint,String value){return input(a,hint,value);}
    static EditText input(Context c,String hint,String value){
        EditText e=new EditText(c);e.setText(value);e.setHint(hint);e.setTextSize(16);e.setTextColor(Theme.INK);e.setHintTextColor(Theme.INK_3);e.setTypeface(Theme.REGULAR);
        int p=Theme.dp(c,14);e.setPadding(p,Theme.dp(c,12),p,Theme.dp(c,12));e.setMinHeight(Theme.dp(c,Theme.BUTTON_HEIGHT));e.setMinimumHeight(Theme.dp(c,Theme.BUTTON_HEIGHT));
        e.setBackground(Theme.fieldBackground(c,Theme.SURFACE));e.setLineSpacing(0,1.2f);
        return e;
    }

    // ── Messages ──────────────────────────────────────────────────────────────
    void toast(String message){Notice.show(a,message);}
    void error(String message){Notice.error(a,message);}

    /**
     * Button whose leading compound icon stays centred together with its label, instead of the
     * platform default that pins the icon to the far edge.
     */
    static final class Btn extends Button {
        Btn(Context c){super(c);}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            super.onLayout(changed,l,t,r,b);
            Drawable d=getCompoundDrawables()[0];if(d==null)return;
            int base=(int)(getResources().getDisplayMetrics().density*12);
            float tw=getPaint().measureText(getText().toString());
            int content=(int)(d.getBounds().width()+getCompoundDrawablePadding()+tw+.5f);
            int pad=Math.max(base,(getWidth()-content)/2-(int)(getResources().getDisplayMetrics().density*2));
            if(Math.abs(pad-getPaddingLeft())>1)setPadding(pad,getPaddingTop(),pad,getPaddingBottom());
        }
    }

    static ViewGroup.LayoutParams wrap(){return new ViewGroup.LayoutParams(-2,-2);}
}
