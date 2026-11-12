package com.example.reviewreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ScreenActivity {
    private static final int BG=Theme.BG, SURFACE=Theme.SURFACE;
    private static final int INK=Theme.INK, FOREST=Theme.PRIMARY;
    private static final int MUTED=Theme.MUTED, MINT=Theme.PRIMARY_SOFT;
    private static final int CORAL=Theme.DANGER, CORAL_PALE=Theme.DANGER_SOFT;
    private static final int LINE=Theme.BORDER, GOLD=Color.rgb(235,177,83);
    private LinearLayout root, content;
    private int currentPage=0;
    private int settingsCategory=-1;
    private int completedFilter=0;
    private boolean completedExpanded=true;
    private long calendarDate=System.currentTimeMillis();
    private final Feedback interaction=new Feedback();
    private boolean changingPage;
    private interface Check { boolean get(); }
    private Check saveSettings;
    private boolean exactStateKnown,lastExactAllowed,setupRuleChosen;
    private AlertDialog setupDialog,permissionDialog;
    private LinearLayout setupContainer;
    private int setupStep;
    private String setupWindows="";
    private boolean setupRespectWindows;
    private TimeWindowPicker setupPicker;
    private RadioGroup setupRule;
    private TextView setupNotificationStatus,setupExactStatus,permissionStatus;
    private TextView permissionExactStatus,settingsNotificationStatus,settingsExactStatus;
    private CheckBox setupDontRemind;
    private boolean setupDontShow;

    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);interaction.prepare(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int systemBars=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=26)systemBars|=0x10;
        getWindow().getDecorView().setSystemUiVisibility(systemBars);
        render();
        lastExactAllowed=Notifications.exactAllowed(this);exactStateKnown=true;
        if(!Store.prefs(this).getBoolean("setupComplete",false))getWindow().getDecorView().post(this::showSetupWizard);
    }
    @Override protected void onResume(){super.onResume();boolean exact=Notifications.exactAllowed(this);if(exactStateKnown&&!lastExactAllowed&&exact)RestoreReceiver.restore(this);lastExactAllowed=exact;exactStateKnown=true;if(setupNotificationStatus!=null)setupNotificationStatus.setText(notificationStatus());if(setupExactStatus!=null)setupExactStatus.setText(exactStatus());if(permissionStatus!=null)permissionStatus.setText(notificationStatus());if(permissionExactStatus!=null)permissionExactStatus.setText(exactStatus());if(settingsNotificationStatus!=null)settingsNotificationStatus.setText(notificationStatus());if(settingsExactStatus!=null)settingsExactStatus.setText(exactStatus());if(root!=null&&currentPage!=2)render();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);currentPage=0;render();}
    private String notificationStatus(){return Notifications.enabled(this)?"通知权限：已开启":"通知权限：未开启，到期时不会显示系统通知";}
    private String exactStatus(){if(!Notifications.exactPermissionNeeded(this))return "精确提醒：当前应用版本无需额外授权";return Notifications.exactAllowed(this)?"精确提醒权限：已开启":"精确提醒权限：未开启，系统可能延迟提醒";}
    private void showSetupWizard(){
        if(isFinishing()||setupDialog!=null&&setupDialog.isShowing()||Store.prefs(this).getBoolean("dontShowSetup",false))return;
        setupStep=0;setupRuleChosen=false;setupDontShow=false;setupWindows=Store.prefs(this).getString("windows","");setupRespectWindows=ReminderWindows.restricted(this);
        setupContainer=column();setupContainer.addView(setupPage());
        setupDialog=new AlertDialog.Builder(this).setTitle("设置你的提醒时间").setView(setupContainer).setNegativeButton("稍后设置",null).setPositiveButton("下一步",null).create();
        setupDialog.setOnShowListener(dialog->{setupDialog.getButton(-1).setOnClickListener(v->advanceSetup());setupDialog.getButton(-2).setOnClickListener(v->{if(setupStep==0)skipSetup();else{setupStep--;replaceSetupPage();setupDialog.setTitle("设置你的提醒时间");setupDialog.getButton(-1).setText(setupStep==2?"完成设置":"下一步");setupDialog.getButton(-2).setText(setupStep==0?"稍后设置":"上一步");}});});setupDialog.show();setupDialog.getButton(-1).setText("下一步");
    }
    private void replaceSetupPage(){setupContainer.removeAllViews();setupContainer.addView(setupPage());}
    private View setupPage(){
        ScrollView scroll=new ScrollView(this);LinearLayout page=column();page.setPadding(dp(20),dp(4),dp(20),dp(12));scroll.addView(page);
        page.addView(text("第 "+(setupStep+1)+" 步，共 3 步",12,MUTED));gap(page,8);
        if(setupStep==0){
            TextView question=text("你每天什么时候方便复习？",19,INK);question.setTypeface(null,1);page.addView(question);gap(page,5);page.addView(text("可以添加多个时段。点击开始和结束时间，用系统时间选择器设置。",13,MUTED));gap(page,12);
            setupPicker=new TimeWindowPicker(this,setupWindows,()->{});page.addView(setupPicker.view());gap(page,8);page.addView(text("不添加时段表示全天都可以提醒。跨午夜时段也可以设置。",12,MUTED));
            setupDontRemind=new CheckBox(this);setupDontRemind.setText("不再显示首次设置引导");setupDontRemind.setMinHeight(dp(48));setupDontRemind.setChecked(setupDontShow);setupDontRemind.setOnCheckedChangeListener((button,checked)->setupDontShow=checked);page.addView(setupDontRemind);
        }else if(setupStep==1){
            TextView question=text("如果复习到期时不在你选择的时段内，怎么办？",19,INK);question.setTypeface(null,1);page.addView(question);gap(page,10);
            setupRule=new RadioGroup(this);setupRule.setOrientation(RadioGroup.VERTICAL);RadioButton wait=new RadioButton(this);wait.setId(1);wait.setText("等到下一个可用时段再提醒");wait.setMinHeight(dp(56));RadioButton anytime=new RadioButton(this);anytime.setId(2);anytime.setText("全天都可提醒，不等待时段");anytime.setMinHeight(dp(56));setupRule.addView(wait);setupRule.addView(anytime);setupRule.check(setupRespectWindows?1:2);setupRule.setOnCheckedChangeListener((group,id)->setupRuleChosen=true);page.addView(setupRule);gap(page,8);page.addView(text("这项选择只影响之后新建的任务，不会改动已有任务。",12,MUTED));
        }else{
            TextView question=text("检查提醒权限",19,INK);question.setTypeface(null,1);page.addView(question);gap(page,6);page.addView(text("到期后需要通知权限才能显示提醒。精确提醒权限是否需要，由 Android 版本和应用版本决定。",13,MUTED));gap(page,12);
            setupNotificationStatus=text(notificationStatus(),15,INK);page.addView(setupNotificationStatus);if(!Notifications.enabled(this)){gap(page,7);page.addView(button("打开通知权限设置",1,v->Notifications.openNotifications(this)));}gap(page,10);
            setupExactStatus=text(exactStatus(),15,INK);page.addView(setupExactStatus);if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this)){gap(page,7);page.addView(button("开启精确提醒权限",1,v->Notifications.openExactAlarmSettings(this)));}
            gap(page,12);page.addView(text("拍照和选图使用系统相机、图片选择器及临时文件授权，不要求开放整部相册权限。",12,MUTED));
        }
        return scroll;
    }
    private void advanceSetup(){
        try{
            if(setupStep==0){setupWindows=setupPicker.value();if(!setupRuleChosen)setupRespectWindows=!setupWindows.isEmpty();setupStep=1;replaceSetupPage();setupDialog.getButton(-1).setText("下一步");setupDialog.getButton(-2).setText("上一步");}
            else if(setupStep==1){setupRespectWindows=setupRule.getCheckedRadioButtonId()==1;setupRuleChosen=true;if(setupRespectWindows&&setupWindows.isEmpty()){toast("请先添加至少一个提醒时段，或选择全天都可提醒");setupStep=0;replaceSetupPage();setupDialog.getButton(-1).setText("下一步");setupDialog.getButton(-2).setText("稍后设置");return;}setupStep=2;replaceSetupPage();setupDialog.getButton(-1).setText("完成设置");setupDialog.getButton(-2).setText("上一步");}
            else{saveSetup(true);}
        }catch(Exception e){toast("时间设置无效："+e.getMessage());}
    }
    private void saveSetup(boolean complete){if(!Store.prefs(this).edit().putString("windows",setupWindows).putBoolean("respectWindows",setupRespectWindows).putBoolean("setupComplete",complete).putBoolean("dontShowSetup",setupDontShow).commit()){toast("设置未能保存，请重试");return;}if(setupDialog!=null)setupDialog.dismiss();setupNotificationStatus=null;setupExactStatus=null;render();}
    private void skipSetup(){setupWindows=Store.prefs(this).getString("windows","");setupRespectWindows=ReminderWindows.restricted(this);setupDontShow=setupDontRemind!=null&&setupDontRemind.isChecked();saveSetup(setupDontShow);}
    private void showPermissionGuide(){
        if(Store.prefs(this).getBoolean("dontShowPermissionGuide",false))return;
        LinearLayout body=column();body.setPadding(dp(20),dp(2),dp(20),dp(4));permissionStatus=text(notificationStatus(),15,INK);body.addView(permissionStatus);body.addView(button("打开通知权限设置",1,v->Notifications.openNotifications(this)));gap(body,10);
        permissionExactStatus=text(exactStatus(),15,INK);body.addView(permissionExactStatus);if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this))body.addView(button("打开精确提醒设置",1,v->Notifications.openExactAlarmSettings(this)));
        CheckBox dontRemind=new CheckBox(this);dontRemind.setText("不再显示此权限说明");dontRemind.setMinHeight(dp(48));body.addView(dontRemind);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("提醒权限").setView(body).setPositiveButton("完成",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(dontRemind.isChecked())Store.prefs(this).edit().putBoolean("dontShowPermissionGuide",true).apply();dialog.dismiss();}));dialog.show();permissionDialog=dialog;dialog.setOnDismissListener(d->{permissionStatus=null;permissionExactStatus=null;permissionDialog=null;});
    }
    private GradientDrawable shape(int color,float radius,int strokeColor,float stroke) {
        return Theme.shape(this,color,radius,strokeColor,stroke);
    }
    private TextView text(String value,int size,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(true); return v;
    }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setOrientation(0); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(1); return v; }
    private void gap(LinearLayout p,int h) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private View divider() { View v=new View(this); v.setBackgroundColor(LINE); return v; }
    private Button button(String label,int style,View.OnClickListener click) {
        Button b=new Button(this); b.setText(label); b.setTextSize(Theme.BODY_SIZE); b.setTypeface(null,1); b.setAllCaps(false);
        b.setMinHeight(dp(Theme.BUTTON_HEIGHT)); b.setMinimumHeight(dp(Theme.BUTTON_HEIGHT)); b.setPadding(dp(16),0,dp(16),0); b.setStateListAnimator(null);b.setElevation(0);
        if(style==0) { b.setTextColor(Color.WHITE); b.setBackground(shape(FOREST,Theme.RADIUS_BUTTON,FOREST,0)); }
        else if(style==1) { b.setTextColor(FOREST); b.setBackground(shape(MINT,Theme.RADIUS_BUTTON,MINT,0)); }
        else { b.setTextColor(INK); b.setBackground(shape(SURFACE,Theme.RADIUS_BUTTON,LINE,1)); }
        b.setOnClickListener(click); if(style==0)UiIcons.apply(b,Color.WHITE);Motion.press(b); return b;
    }
    private Button textAction(String label,View.OnClickListener click){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(Theme.BODY_SIZE);b.setTextColor(FOREST);b.setTypeface(null,1);
        b.setMinHeight(dp(40));b.setMinimumHeight(dp(40));b.setPadding(dp(8),dp(4),dp(8),dp(4));
        b.setBackground(null);b.setStateListAnimator(null);b.setElevation(0);b.setOnClickListener(click);Motion.press(b);return b;
    }
    private void render() {
        saveSettings=null;
        root=column(); root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp=new LinearLayout.LayoutParams(-1,0,1); root.addView(scroll,scrollLp);
        content=column(); content.setPadding(dp(Theme.PAGE_PAD),dp(Theme.SPACE_16),dp(Theme.PAGE_PAD),dp(Theme.SPACE_24)); scroll.addView(content);
        if(currentPage==0) homePage(); else if(currentPage==1) calendarPage(); else settingsPage();
        root.addView(bottomBar(),new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        Motion.enter(content);
    }
    private void renderAtScroll(){int y=root!=null&&root.getChildAt(0) instanceof ScrollView?((ScrollView)root.getChildAt(0)).getScrollY():0;render();ScrollView scroll=(ScrollView)root.getChildAt(0);scroll.post(()->scroll.scrollTo(0,y));}
    private void pageHeading(String title,String subtitle) {
        LinearLayout brand=row();
        LinearLayout copy=column(); TextView name=text(title,Theme.TITLE_SIZE,INK); name.setTypeface(null,1); copy.addView(name);
        if(!subtitle.isEmpty()) { TextView sub=text(subtitle,Theme.CAPTION_SIZE,MUTED); LinearLayout.LayoutParams subLp=new LinearLayout.LayoutParams(-2,-2);subLp.topMargin=dp(Theme.SPACE_4);copy.addView(sub,subLp); }
        brand.addView(copy,new LinearLayout.LayoutParams(0,-2,1)); content.addView(brand); gap(content,Theme.SPACE_16);
    }
    private LinearLayout bottomBar() {
        LinearLayout host=column();
        View hairline=new View(this);hairline.setBackgroundColor(LINE);host.addView(hairline,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout bar=row(); bar.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_8),dp(Theme.SPACE_8),dp(Theme.SPACE_12));
        bar.setBackgroundColor(SURFACE);
        String[] names={"主页","日历","我的"};
        for(int i=0;i<3;i++) {
            final int page=i; boolean selected=i==currentPage;
            LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setFocusable(true); item.setClickable(true);
            item.setMinimumHeight(dp(52)); item.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_4),dp(Theme.SPACE_8),dp(Theme.SPACE_4));
            item.setBackground(shape(selected?MINT:Color.TRANSPARENT,Theme.RADIUS_CHIP,Color.TRANSPARENT,0));
            ImageView icon=new ImageView(this);icon.setImageResource(new int[]{R.drawable.ic_house,R.drawable.ic_calendar_days,R.drawable.ic_user_round}[i]);icon.setImageTintList(ColorStateList.valueOf(selected?FOREST:MUTED));item.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView label=text(names[i],12,selected?FOREST:MUTED); if(selected)label.setTypeface(null,1);
            LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(-2,-2); labelLp.topMargin=dp(2); item.addView(label,labelLp);
            item.setContentDescription(names[i]); item.setOnClickListener(v->goPage(page));
            Motion.press(item);
            bar.addView(item,new LinearLayout.LayoutParams(0,dp(52),1));
        }
        host.addView(bar); return host;
    }
    private void goPage(int page){
        if(changingPage)return;
        if(page==currentPage){if(page==2&&settingsCategory>=0)leaveSettingsCategory();return;}
        if(currentPage==2&&settingsCategory>=0){if(saveSettings!=null&&!saveSettings.get())return;settingsCategory=-1;}
        navigate(page);
    }
    private void navigate(int page){int direction=page>currentPage?1:-1;changingPage=true;Motion.leave(content,direction,()->{if(isFinishing()||isDestroyed())return;currentPage=page;if(page!=2)settingsCategory=-1;render();Motion.enter(content,direction);changingPage=false;});}
    private final class IconView extends View {
        private final int type,color; private final Paint p=new Paint(3);
        IconView(int type,int color){super(MainActivity.this);this.type=type;this.color=color;setContentDescription(type==0?"主页图标":type==1?"已完成图标":"设置图标");}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float s=Math.min(getWidth(),getHeight())/24f; c.save(); c.translate((getWidth()-24*s)/2,(getHeight()-24*s)/2); c.scale(s,s);
            p.setColor(color);p.setStrokeWidth(1.9f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
            if(type==0){Path a=new Path();a.moveTo(3.5f,10.5f);a.lineTo(12,3.8f);a.lineTo(20.5f,10.5f);c.drawPath(a,p);c.drawRoundRect(5.5f,9.5f,18.5f,20,2,2,p);c.drawRoundRect(10,14,14,20,1,1,p);}
            else if(type==1){c.drawCircle(12,12,8.7f,p);Path a=new Path();a.moveTo(7.8f,12.3f);a.lineTo(10.5f,15);a.lineTo(16.6f,8.8f);c.drawPath(a,p);}
            else {c.drawLine(5,7,19,7,p);c.drawLine(5,12,19,12,p);c.drawLine(5,17,19,17,p);p.setStyle(Paint.Style.FILL);c.drawCircle(10,7,2.5f,p);c.drawCircle(15,12,2.5f,p);c.drawCircle(8,17,2.5f,p);}
            c.restore();
        }
    }
    private long dayStart(long time) { Calendar c=Calendar.getInstance();c.setTimeInMillis(time);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis(); }
    private long shiftDay(long time,int days){Calendar c=Calendar.getInstance();c.setTimeInMillis(dayStart(time));c.add(Calendar.DAY_OF_YEAR,days);return c.getTimeInMillis();}
    private String date(long time) { return new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(time)); }
    private String todayLabel() { return new SimpleDateFormat("M月d日 EEEE",Locale.CHINA).format(new Date()); }
    private List<JSONObject> taskList(boolean completed) {
        JSONArray all=Store.tasks(this); List<JSONObject> list=new ArrayList<>();
        for(int i=0;i<all.length();i++){JSONObject t=all.optJSONObject(i);if(t!=null&&t.optBoolean("done")==completed)list.add(t);}
        Collections.sort(list,new Comparator<JSONObject>(){@Override public int compare(JSONObject a,JSONObject b){long x=completed?lastCompleted(a):a.optLong("due"), y=completed?lastCompleted(b):b.optLong("due");return Long.compare(completed?-x:x,completed?-y:y);}});
        return list;
    }
    private long lastCompleted(JSONObject t) { JSONArray h=t.optJSONArray("history");return h==null||h.length()==0?t.optLong("created"):h.optJSONObject(h.length()-1).optLong("at"); }
    private void homePage() {
        List<JSONObject> active=taskList(false); long start=dayStart(System.currentTimeMillis()), end=shiftDay(start,1); int today=0;
        for(JSONObject t:active)if(t.optLong("due")<end)today++;
        LinearLayout header=row();
        LinearLayout copy=column();
        TextView dateLine=text(todayLabel(),Theme.CAPTION_SIZE,MUTED);copy.addView(dateLine);
        TextView name=text("间隔提醒",Theme.TITLE_SIZE,INK);name.setTypeface(null,1);LinearLayout.LayoutParams nameLp=new LinearLayout.LayoutParams(-2,-2);nameLp.topMargin=dp(Theme.SPACE_4);copy.addView(name,nameLp);
        String dueLine=today==0?"今天没有待复习":today+" 项待复习";
        TextView dueHint=text(dueLine,Theme.BODY_SIZE,today==0?MUTED:FOREST);if(today>0)dueHint.setTypeface(null,1);
        LinearLayout.LayoutParams dueLp=new LinearLayout.LayoutParams(-2,-2);dueLp.topMargin=dp(Theme.SPACE_8);copy.addView(dueHint,dueLp);
        header.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(header);gap(content,Theme.SPACE_16);

        LinearLayout tools=row();
        tools.addView(button("录词",2,v->Motion.open(this,new Intent(this,ImportActivity.class))),new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams createLp=new LinearLayout.LayoutParams(0,dp(44),1);createLp.leftMargin=dp(Theme.SPACE_8);
        tools.addView(button("新建",2,v->create()),createLp);
        content.addView(tools);gap(content,Theme.SPACE_16);

        if(!Notifications.enabled(this)){
            LinearLayout warning=row();warning.setPadding(dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12));warning.setBackground(shape(Theme.WARN_SOFT,Theme.RADIUS_CHIP,0,0));
            TextView w=text("通知未开启 · 点此授权，到期才能收到提醒",Theme.CAPTION_SIZE,Theme.WARN);w.setTypeface(null,1);
            warning.addView(w,new LinearLayout.LayoutParams(0,-2,1));
            warning.setOnClickListener(v->showPermissionGuide());Motion.press(warning);content.addView(warning);gap(content,Theme.SPACE_16);
        }

        if(active.isEmpty()){
            emptyState("暂时没有复习任务","录词或打开课程后创建计划，也可随时练习已有词卡。","去录词",()->Motion.open(this,new Intent(this,ImportActivity.class)));
            gap(content,Theme.SPACE_24);
        } else {
            sectionTitle("待复习",today+" 项今天到期");
            boolean hasDue=false,hasFuture=false;
            for(JSONObject t:active){if(t.optLong("due")<end){addTask(t,false,true);hasDue=true;}else hasFuture=true;}
            if(!hasDue)content.addView(text("今天没有到期任务，可以任意复习已有词卡。",Theme.CAPTION_SIZE,MUTED));
            if(hasFuture){gap(content,Theme.SPACE_16);sectionTitle("之后安排",(active.size()-today)+" 项");for(JSONObject t:active)if(t.optLong("due")>=end)addTask(t,false,false);}
            gap(content,Theme.SPACE_24);
        }
        sectionTitle("课程","内置词表");
        addLessonCard();gap(content,Theme.SPACE_16);completedSection();
    }
    private void addLessonCard(){
        LinearLayout card=column();card.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_12),dp(Theme.SPACE_16),dp(Theme.SPACE_12));card.setBackground(shape(SURFACE,Theme.RADIUS_CARD,LINE,1));card.setElevation(0);
        LinearLayout top=row();
        LinearLayout copy=column();
        TextView title=text("Lesson 1",16,INK);title.setTypeface(null,1);copy.addView(title);
        copy.addView(text("42 张内置单词卡",Theme.CAPTION_SIZE,MUTED));
        top.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(textAction("打开校对",v->{
            new AlertDialog.Builder(this).setTitle("打开 Lesson 1？").setMessage("将用内置 42 张卡替换当前草稿，不影响已创建的任务。").setNegativeButton("取消",null).setPositiveButton("打开",(d,w)->{try{Words.lesson(this);Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("quickReview",true));}catch(Exception e){toast(e.getMessage());}}).show();
        }));
        card.addView(top);
        JSONObject lessonTask=findTaskByTitle("Lesson 1");
        if(lessonTask!=null&&lessonTask.optJSONArray("words")!=null&&lessonTask.optJSONArray("words").length()>0){
            long lessonId=lessonTask.optLong("id");
            gap(card,Theme.SPACE_4);
            LinearLayout practiceRow=row();practiceRow.setGravity(Gravity.END);
            practiceRow.addView(textAction("任意复习",v->Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",lessonId).putExtra("practice",true))));
            card.addView(practiceRow);
        }
        content.addView(card);
    }
    private JSONObject findTaskByTitle(String title){
        JSONArray all=Store.tasks(this);
        for(int i=0;i<all.length();i++){JSONObject t=all.optJSONObject(i);if(t!=null&&title.equals(t.optString("title")))return t;}
        return null;
    }
    private void completedSection(){
        List<JSONObject> done=taskList(true);Button toggle=button("已完成（"+done.size()+"）  "+(completedExpanded?"▴":"▾"),2,v->{completedExpanded=!completedExpanded;renderAtScroll();});content.addView(toggle);if(!completedExpanded)return;
        Spinner filter=new Spinner(this);filter.setMinimumHeight(dp(52));filter.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"今天完成","近 7 天","近 14 天","14 天以前"}));filter.setSelection(completedFilter);content.addView(filter);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(position!=completedFilter){completedFilter=position;renderAtScroll();}}});
        long today=dayStart(System.currentTimeMillis()),start=completedFilter==0?today:shiftDay(today,completedFilter==1?-6:-13),end=shiftDay(today,1);int count=0;
        for(JSONObject t:done){JSONArray h=t.optJSONArray("history");if(h==null||h.length()==0)continue;long at=lastCompleted(t);boolean match=completedFilter==3?at<start:at>=start&&at<end;if(match){addTask(t,true);count++;}}
        if(count==0){gap(content,8);content.addView(text("此时间范围没有已完成的任务",14,MUTED));}
    }
    private void calendarPage(){
        pageHeading("日历","查看计划与完成记录");LinearLayout calendar=column();content.addView(calendar,new LinearLayout.LayoutParams(-1,-2));gap(content,16);LinearLayout events=column();content.addView(events);showMonth(calendar,events);showCalendarEvents(events);
    }
    private void showMonth(LinearLayout grid,LinearLayout events){
        grid.removeAllViews();Calendar month=Calendar.getInstance();month.setTimeInMillis(calendarDate);month.set(Calendar.DAY_OF_MONTH,1);int offset=(month.get(Calendar.DAY_OF_WEEK)+5)%7,days=month.getActualMaximum(Calendar.DAY_OF_MONTH);
        LinearLayout head=row();ImageButton previous=calendarArrow(R.drawable.ic_arrow_left,"上个月");previous.setOnClickListener(v->{Calendar c=Calendar.getInstance();c.setTimeInMillis(calendarDate);c.set(Calendar.DAY_OF_MONTH,1);c.add(Calendar.MONTH,-1);calendarDate=c.getTimeInMillis();showMonth(grid,events);showCalendarEvents(events);});head.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text(new SimpleDateFormat("yyyy年 M月",Locale.CHINA).format(month.getTime()),18,INK);title.setGravity(Gravity.CENTER);title.setTypeface(null,1);head.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));ImageButton next=calendarArrow(R.drawable.ic_arrow_right,"下个月");next.setOnClickListener(v->{Calendar c=Calendar.getInstance();c.setTimeInMillis(calendarDate);c.set(Calendar.DAY_OF_MONTH,1);c.add(Calendar.MONTH,1);calendarDate=c.getTimeInMillis();showMonth(grid,events);showCalendarEvents(events);});head.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));grid.addView(head);gap(grid,8);
        LinearLayout weekdays=row();for(String label:new String[]{"一","二","三","四","五","六","日"}){TextView t=text(label,12,MUTED);t.setGravity(Gravity.CENTER);weekdays.addView(t,new LinearLayout.LayoutParams(0,dp(24),1));}grid.addView(weekdays);
        for(int week=0;week<6;week++){LinearLayout line=row();for(int day=0;day<7;day++){int number=week*7+day-offset+1;if(number<1||number>days){line.addView(new View(this),new LinearLayout.LayoutParams(0,dp(44),1));continue;}Calendar selected=(Calendar)month.clone();selected.set(Calendar.DAY_OF_MONTH,number);long value=selected.getTimeInMillis();boolean chosen=dayStart(value)==dayStart(calendarDate),today=dayStart(value)==dayStart(System.currentTimeMillis());LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.setMinimumHeight(dp(44));if(chosen||today)cell.setBackground(shape(chosen?FOREST:MINT,12,0,0));TextView num=text(String.valueOf(number),14,chosen?Color.WHITE:today?FOREST:INK);num.setGravity(Gravity.CENTER);cell.addView(num);View dot=new View(this);LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(4),dp(4));dotLp.topMargin=dp(2);dot.setBackground(shape(chosen?Color.WHITE:(dayHasPlans(value)?FOREST:Color.TRANSPARENT),2,0,0));cell.addView(dot,dotLp);cell.setContentDescription(new SimpleDateFormat("yyyy年M月d日",Locale.CHINA).format(selected.getTime()));cell.setOnClickListener(v->{calendarDate=value;showMonth(grid,events);showCalendarEvents(events);});line.addView(cell,new LinearLayout.LayoutParams(0,dp(44),1));}grid.addView(line);}
    }
    private ImageButton calendarArrow(int icon,String label){ImageButton b=new ImageButton(this);b.setImageResource(icon);b.setImageTintList(ColorStateList.valueOf(FOREST));b.setContentDescription(label);b.setBackground(shape(SURFACE,14,LINE,1));b.setPadding(dp(14),dp(14),dp(14),dp(14));Motion.press(b);return b;
    }
    private void showCalendarEvents(LinearLayout events){
        events.removeAllViews();long start=dayStart(calendarDate),end=shiftDay(start,1);TextView heading=text(new SimpleDateFormat("yyyy年M月d日 EEEE",Locale.CHINA).format(new Date(start)),18,INK);heading.setTypeface(null,1);events.addView(heading);gap(events,10);int count=0;
        JSONArray all=Store.tasks(this);for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task==null)continue;long id=task.optLong("id"),due=task.optLong("due");if(!task.optBoolean("done")&&due>=start&&due<end){calendarEvent(events,"计划  "+date(due),task.optString("title"),()->details(id));count++;}JSONArray history=task.optJSONArray("history");if(history!=null)for(int j=0;j<history.length();j++){JSONObject h=history.optJSONObject(j);if(h==null)continue;long at=h.optLong("at");if(at>=start&&at<end){calendarEvent(events,"完成第 "+h.optInt("stage")+" 轮  "+date(at),task.optString("title"),()->details(id));count++;}}}
        if(count==0)events.addView(text("当天没有计划或完成记录",14,MUTED));
    }
    private boolean dayHasPlans(long value){long start=dayStart(value),end=shiftDay(start,1);JSONArray all=Store.tasks(this);for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task==null)continue;if(!task.optBoolean("done")&&task.optLong("due")>=start&&task.optLong("due")<end)return true;JSONArray history=task.optJSONArray("history");if(history==null)continue;for(int j=0;j<history.length();j++){JSONObject h=history.optJSONObject(j);if(h!=null&&h.optLong("at")>=start&&h.optLong("at")<end)return true;}}return false;}
    private void calendarEvent(LinearLayout parent,String subtitle,String title,Runnable action){LinearLayout line=column();line.setPadding(dp(14),dp(10),dp(14),dp(10));line.setBackground(shape(SURFACE,Theme.RADIUS_CARD,LINE,1));line.setElevation(0);TextView name=text(title,15,INK);name.setTypeface(null,1);line.addView(name);line.addView(text(subtitle,13,MUTED));line.setMinimumHeight(dp(52));line.setOnClickListener(v->action.run());Motion.press(line);parent.addView(line);gap(parent,8);}
    private void statistics(){
        sectionTitle("复习统计","最近 12 周");int total=0,days=0;long today=dayStart(System.currentTimeMillis());java.util.Map<Long,Integer> counts=new java.util.HashMap<>();JSONArray all=Store.tasks(this);for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task==null)continue;JSONArray history=task.optJSONArray("history");if(history==null)continue;for(int j=0;j<history.length();j++){JSONObject entry=history.optJSONObject(j);if(entry==null||entry.optLong("at")<=0)continue;long day=dayStart(entry.optLong("at"));Integer old=counts.get(day);counts.put(day,old==null?1:old+1);}}for(int i=0;i<84;i++){Integer n=counts.get(shiftDay(today,-i));if(n!=null){total+=n;days++;}}
        TextView stat=text("完成 "+total+" 轮     活跃 "+days+" 天",16,INK);stat.setTypeface(null,1);content.addView(stat);gap(content,12);
        LinearLayout grid=column();for(int row=0;row<7;row++){LinearLayout cells=row();for(int col=0;col<12;col++){final long date=shiftDay(today,-(83-(col*7+row)));Integer value=counts.get(date);int n=value==null?0:value;TextView cell=text("",11,INK);cell.setGravity(Gravity.CENTER);cell.setBackground(shape(n==0?0xFFE8EFEB:n==1?0xFFBFE3D2:n<4?0xFF78BE9B:FOREST,4,0,0));cell.setContentDescription(new SimpleDateFormat("M月d日",Locale.CHINA).format(new Date(date))+" 完成 "+n+" 轮");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(24),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));cells.addView(cell,p);}grid.addView(cells);}content.addView(grid);gap(content,8);content.addView(text("浅到深表示当天完成轮次由少到多；删除任务也会删除其记录。",12,MUTED));gap(content,22);
    }
    private void sectionTitle(String label,String trailing) {
        LinearLayout r=row();TextView title=text(label,15,INK);title.setTypeface(null,1);r.addView(title,new LinearLayout.LayoutParams(0,-2,1));r.addView(text(trailing,12,MUTED));content.addView(r);gap(content,Theme.SPACE_8);
    }
    private void emptyState(String title,String subtitle,String action,Runnable run) {
        LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(Theme.SPACE_20),dp(Theme.SPACE_24),dp(Theme.SPACE_20),dp(Theme.SPACE_24));box.setBackground(shape(SURFACE,Theme.RADIUS_CARD,LINE,1));
        TextView heading=text(title,16,INK);heading.setTypeface(null,1);heading.setGravity(Gravity.CENTER);box.addView(heading);gap(box,Theme.SPACE_8);
        TextView detail=text(subtitle,Theme.CAPTION_SIZE,MUTED);detail.setGravity(Gravity.CENTER);box.addView(detail);
        if(action!=null){gap(box,Theme.SPACE_12);box.addView(textAction(action,v->run.run()));}content.addView(box);
    }
    private void addTask(JSONObject task,boolean completed){addTask(task,completed,!completed);}
    private void addTask(JSONObject task,boolean completed,boolean prominent) {
        long id=task.optLong("id"); int stage=task.optInt("stage"); JSONArray intervals=task.optJSONArray("intervals");
        long due=task.optLong("due"); boolean overdue=!completed&&due<=System.currentTimeMillis();
        boolean hasWords=task.optJSONArray("words")!=null&&task.optJSONArray("words").length()>0;
        if(!completed&&!prominent){
            LinearLayout row=row();row.setPadding(dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_8),dp(Theme.SPACE_12));row.setBackground(shape(SURFACE,Theme.RADIUS_CARD,LINE,1));
            LinearLayout copy=column();
            TextView title=text(task.optString("title"),15,INK);title.setTypeface(null,1);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(title);
            copy.addView(text("第 "+(stage+1)+" 次 · "+date(due),Theme.CAPTION_SIZE,MUTED));
            row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            if(hasWords)row.addView(textAction("任意复习",v->Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("practice",true))));
            row.setOnClickListener(v->details(id));Motion.press(row);content.addView(row);gap(content,Theme.SPACE_8);return;
        }
        LinearLayout card=column();card.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_12),dp(Theme.SPACE_16),dp(Theme.SPACE_12));card.setBackground(shape(SURFACE,Theme.RADIUS_CARD,LINE,1));card.setElevation(0);
        LinearLayout top=row();TextView title=text(task.optString("title"),16,INK);title.setTypeface(null,1);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text(completed?"完成":"第 "+(stage+1)+" 次",12,completed?FOREST:overdue?CORAL:FOREST);badge.setPadding(dp(8),dp(4),dp(8),dp(4));badge.setBackground(shape(completed?MINT:overdue?CORAL_PALE:MINT,Theme.RADIUS_CHIP,0,0));top.addView(badge);card.addView(top);gap(card,Theme.SPACE_8);
        String status=completed?"最后完成  "+date(lastCompleted(task)):overdue?"现在可以开始复习":"计划时间  "+date(due);
        card.addView(text(status,Theme.CAPTION_SIZE,completed?MUTED:overdue?CORAL:FOREST));
        int total=intervals==null?7:intervals.length();int shown=completed?total:Math.min(stage,total);
        gap(card,Theme.SPACE_4);card.addView(text("进度 "+shown+" / "+total+(hasWords?" · 掌握 "+String.format(java.util.Locale.CHINA,"%.1f",Mastery.average(task.optJSONArray("words")))+"/5":""),12,MUTED));
        gap(card,Theme.SPACE_12);
        LinearLayout actions=row();
        if(!completed){
            actions.addView(button(hasWords?"开始复习":"完成复习",0,v->{if(hasWords)Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id));else confirmComplete(id);}),new LinearLayout.LayoutParams(0,dp(Theme.BUTTON_HEIGHT),1));
            if(hasWords)actions.addView(textAction("任意复习",v->Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("practice",true))));
            else actions.addView(textAction("稍后",v->snooze(id)));
        } else if(hasWords) {
            actions.addView(textAction("任意复习",v->Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("practice",true))));
            actions.addView(textAction("记录",v->details(id)));
        } else {
            actions.addView(textAction("查看记录",v->details(id)));
        }
        card.addView(actions); if(!completed){card.setOnClickListener(v->details(id));card.setClickable(true);Motion.press(card);}
        content.addView(card);gap(content,Theme.SPACE_8);
    }
    private EditText input(String hint,String value,boolean numeric) {
        EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setText(value);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(Color.rgb(151,166,160));e.setPadding(dp(13),0,dp(13),0);
        e.setBackground(shape(SURFACE,14,LINE,1));if(numeric)e.setInputType(InputType.TYPE_CLASS_NUMBER);return e;
    }
    private void create() {
        LinearLayout form=column();form.setPadding(dp(20),0,dp(20),0);EditText title=input("例如：英语第十二课","",false);form.addView(title);gap(form,7);
        EditText description=input("备注（可选）","",false);form.addView(description);gap(form,10);
        Switch optional=new Switch(this);optional.setText("启用最后一轮（14 天，可在设置中修改）");optional.setTextColor(INK);optional.setTextSize(14);optional.setChecked(Store.prefs(this).getBoolean("optional",false));form.addView(optional);
        TextView hint=text("提醒时间窗和间隔使用当前默认设置。",12,MUTED);form.addView(hint);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("新建任务").setView(form).setNegativeButton("取消",null).setPositiveButton("创建任务",null).create();
        dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{
            String name=title.getText().toString().trim();if(name.isEmpty()){title.setError("请输入任务名称");return;}
            try {
                List<Long> plan=IntervalPlan.active(Store.prefs(this).getString("intervals",Store.DEFAULT_INTERVALS),optional.isChecked(),Store.prefs(this).getString("extraIntervals",""));JSONArray schedule=new JSONArray();
                for(long n:plan)schedule.put(n);
                String windows=ReminderWindows.active(this);long now=System.currentTimeMillis();long id=Store.prefs(this).getLong("nextId",0)+1;
                JSONObject task=new JSONObject().put("id",id).put("title",name).put("description",description.getText().toString().trim()).put("stage",0).put("intervals",schedule).put("windows",windows).put("created",now).put("due",Schedule.due(now,schedule.getLong(0),Store.windows(windows))).put("history",new JSONArray()).put("snoozes",0).put("done",false);
                JSONArray all=Store.tasks(this);all.put(task);Store.save(this,all);Store.prefs(this).edit().putLong("nextId",id).apply();boolean exact=Alarms.schedule(this,task);
                dialog.dismiss();currentPage=0;render();if(!exact)toast("精确提醒未获系统授权，系统可能延迟提醒");if(!Notifications.enabled(this))showPermissionGuide();
            }catch(Exception e){toast("创建失败："+e.getMessage());}
        }));dialog.show();
    }
    private void confirmComplete(long id) {
        JSONObject task=Store.find(Store.tasks(this),id);if(task==null)return;
        if(Store.prefs(this).getBoolean("skipEmptyTaskConfirm",false)){complete(id,task.optInt("stage"));return;}
        String message="下一次复习会从现在重新计算。"+(task.optLong("due")>System.currentTimeMillis()?"\n这次还没到计划时间，仍要标记完成吗？":"");
        LinearLayout body=column();body.setPadding(dp(20),0,dp(20),0);body.addView(text(message,15,INK));CheckBox dontRemind=new CheckBox(this);dontRemind.setText("以后直接完成空单词任务，不再询问");dontRemind.setMinHeight(dp(48));body.addView(dontRemind);
        new AlertDialog.Builder(this).setTitle("完成本次复习？").setView(body).setNegativeButton("继续复习",null).setPositiveButton("完成",(d,w)->{if(dontRemind.isChecked())Store.prefs(this).edit().putBoolean("skipEmptyTaskConfirm",true).apply();complete(id,task.optInt("stage"));}).show();
    }
    private void complete(long id,int expectedStage) {
        try {
            JSONArray all=Store.tasks(this);JSONObject task=Store.find(all,id);
            if(task==null||task.optBoolean("done")||task.optInt("stage")!=expectedStage)return;
            long now=System.currentTimeMillis();int nextStage=expectedStage+1;task.getJSONArray("history").put(new JSONObject().put("stage",nextStage).put("at",now));task.put("stage",nextStage).put("snoozes",0);Alarms.cancel(this,id);
            if(nextStage>=task.getJSONArray("intervals").length())task.put("done",true);
            else task.put("due",Schedule.due(now,task.getJSONArray("intervals").getLong(nextStage),Store.windows(task.optString("windows"))));
            Store.save(this,all);interaction.confirm(root);if(!task.optBoolean("done"))Alarms.schedule(this,task);render();
        }catch(Exception e){toast("保存失败："+e.getMessage());}
    }
    private void snooze(long id) {
        JSONObject task=Store.find(Store.tasks(this),id);if(task==null)return;
        if(task.optInt("snoozes")>=2){toast("本次已延期两次，请完成复习");return;}
        String[] options={"15 分钟后","30 分钟后","1 小时后","下一个允许时间段"};
        new AlertDialog.Builder(this).setTitle("稍后提醒").setItems(options,(dialog,which)->{
            try {
                JSONArray all=Store.tasks(this);JSONObject t=Store.find(all,id);if(t==null||t.optBoolean("done")||t.optInt("snoozes")>=2)return;
                long now=System.currentTimeMillis();long requested=now+(which==0?15:which==1?30:which==2?60:1)*60000L;List<int[]> windows=Store.windows(t.optString("windows"));
                if(which==3&&!windows.isEmpty()){requested=now+60000L;while(requested<now+2*86400000L&&Schedule.nextWindow(requested,windows)==requested)requested+=60000L;}
                long next=Schedule.nextWindow(requested,windows);t.put("due",next).put("snoozes",t.optInt("snoozes")+1);Store.save(this,all);Alarms.cancel(this,id);Alarms.schedule(this,t);render();toast("已安排到 "+date(next));
            }catch(Exception e){toast("延期失败："+e.getMessage());}
        }).show();
    }
    private void details(long id) {
        JSONObject task=Store.find(Store.tasks(this),id);if(task==null)return;StringBuilder body=new StringBuilder();
        if(task.optJSONArray("words")!=null&&task.optJSONArray("words").length()>0){new AlertDialog.Builder(this).setTitle(task.optString("title")+" · "+task.optJSONArray("words").length()+" 个单词").setItems(new String[]{"计划复习","任意复习（不计轮次）","词性义项练习","校对单词卡","更多…"},(d,which)->{if(which==0)Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id));else if(which==1)Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("practice",true));else if(which==2)Motion.open(this,new Intent(this,SenseQuizActivity.class).putExtra("taskId",id));else if(which==3)Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("taskId",id));else new AlertDialog.Builder(this).setTitle("更多").setItems(new String[]{"错词义项练习","稍后提醒","查看复习记录","删除任务"},(d2,w2)->{if(w2==0)Motion.open(this,new Intent(this,SenseQuizActivity.class).putExtra("taskId",id).putExtra("wrongOnly",true));else if(w2==1)snooze(id);else if(w2==2)history(task);else confirmDelete(id);}).setNegativeButton("关闭",null).show();}).setNegativeButton("关闭",null).show();return;}
        if(!task.optString("description").isEmpty())body.append(task.optString("description")).append("\n\n");
        body.append("创建时间  ").append(date(task.optLong("created"))).append("\n提醒时段  ").append(task.optString("windows").isEmpty()?"全天":task.optString("windows")).append("\n\n复习记录\n");
        JSONArray history=task.optJSONArray("history");if(history==null||history.length()==0)body.append("暂无记录");else for(int i=0;i<history.length();i++){JSONObject item=history.optJSONObject(i);body.append("第 ").append(item.optInt("stage")).append(" 次  ").append(date(item.optLong("at"))).append("\n");}
        new AlertDialog.Builder(this).setTitle(task.optString("title")).setMessage(body.toString()).setPositiveButton("关闭",null).setNeutralButton("删除任务",(d,w)->new AlertDialog.Builder(this).setTitle("删除任务？").setMessage("删除后无法恢复。到期提醒也会一并取消。").setNegativeButton("保留",null).setPositiveButton("删除",(d2,w2)->delete(id)).show()).show();
    }
    private void confirmDelete(long id){new AlertDialog.Builder(this).setTitle("删除任务？").setMessage("单词和复习记录将被删除，不能恢复。").setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->delete(id)).show();}
    private void history(JSONObject task){StringBuilder out=new StringBuilder();JSONArray h=task.optJSONArray("history");if(h==null||h.length()==0)out.append("暂无复习记录");else for(int i=0;i<h.length();i++)out.append("第 ").append(h.optJSONObject(i).optInt("stage")).append(" 次  ").append(date(h.optJSONObject(i).optLong("at"))).append('\n');new AlertDialog.Builder(this).setTitle("复习记录").setMessage(out.toString()).setPositiveButton("关闭",null).show();}
    private void delete(long id) { JSONArray old=Store.tasks(this),out=new JSONArray();for(int i=0;i<old.length();i++){JSONObject t=old.optJSONObject(i);if(t!=null&&t.optLong("id")!=id)out.put(t);}Store.save(this,out);Alarms.cancel(this,id);render(); }
    private String[] readIntervals() {
        String stored=Store.prefs(this).getString("intervals",Store.DEFAULT_INTERVALS).trim();String[] parts=stored.split(",");
        if(parts.length!=8)return Store.DEFAULT_INTERVALS.split(",");
        for(String part:parts)try{if(Long.parseLong(part)<1)return Store.DEFAULT_INTERVALS.split(",");}catch(Exception e){return Store.DEFAULT_INTERVALS.split(",");}
        return parts;
    }
    private String prettyMinutes(long minutes) {
        if(minutes<60)return minutes+" 分钟";if(minutes%1440==0)return (minutes/1440)+" 天";if(minutes%60==0)return (minutes/60)+" 小时";
        return (minutes/60)+" 小时 "+(minutes%60)+" 分钟";
    }
    private String[] settingNames(){return new String[]{"复习计划","复习与拼写","声音与触感","提醒时段","AI","权限管理"};}
    private void settingsPage(){
        if(settingsCategory<0){pageHeading("我的","统计与设置");statistics();content.addView(button("错题本",1,v->Motion.open(this,new Intent(this,WrongBookActivity.class))));gap(content,10);content.addView(button("创造力造句",1,v->Motion.open(this,new Intent(this,CreateActivity.class))));gap(content,16);sectionTitle("设置","按类别查看和修改");String[] names=settingNames(),notes={"默认间隔、自定义轮次","卡片显示、拼写偏好","朗读、音效与触感","可用提醒时间","DeepSeek / 小米 MiMo / 自定义","通知和精确提醒授权"};for(int i=0;i<names.length;i++)addSettingsEntry(names[i],notes[i],i);return;}
        String name=settingNames()[settingsCategory];
        LinearLayout back=row();back.setMinimumHeight(dp(44));back.setGravity(Gravity.CENTER_VERTICAL);back.setClickable(true);back.setFocusable(true);
        ImageView backIcon=new ImageView(this);backIcon.setImageResource(R.drawable.ic_arrow_left);backIcon.setImageTintList(ColorStateList.valueOf(FOREST));back.addView(backIcon,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView backLabel=text("返回设置目录",15,FOREST);backLabel.setTypeface(null,1);LinearLayout.LayoutParams backLp=new LinearLayout.LayoutParams(-2,-2);backLp.leftMargin=dp(6);back.addView(backLabel,backLp);
        back.setOnClickListener(v->leaveSettingsCategory());Motion.press(back);content.addView(back);gap(content,8);
        TextView categoryTitle=text(name,22,INK);categoryTitle.setTypeface(null,1);content.addView(categoryTitle);gap(content,16);
        if(settingsCategory==0)intervalSettings();else if(settingsCategory==1)reviewSettings();else if(settingsCategory==2)soundSettings();else if(settingsCategory==3)windowSettings();else if(settingsCategory==4)aiSettings();else permissionSettings();
    }
    private void aiSettings(){
        sectionTitle("AI 助手","可选，默认关闭");
        Switch enabled=new Switch(this);enabled.setText("启用 AI 功能");enabled.setMinHeight(dp(52));enabled.setChecked(AiSettings.enabled(this));content.addView(enabled);gap(content,8);
        Spinner provider=new Spinner(this);provider.setMinimumHeight(dp(52));provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"DeepSeek","小米 MiMo","自定义兼容端点"}));provider.setSelection(AiSettings.provider(this));content.addView(provider);gap(content,8);
        EditText base=input("Base URL",AiSettings.storedBaseUrl(this).isEmpty()?AiSettings.baseUrl(this):AiSettings.storedBaseUrl(this),false);content.addView(base);gap(content,8);
        String existingKey=AiSettings.apiKey(this);
        EditText key=input(existingKey.isEmpty()?"API Key":"API Key（留空则保留已保存："+AiSecrets.mask(existingKey)+"）","",false);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);content.addView(key);gap(content,8);
        EditText model=input("模型名",AiSettings.storedModel(this).isEmpty()?AiSettings.model(this):AiSettings.storedModel(this),false);content.addView(model);gap(content,8);
        provider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){
                if(position==AiSettings.PROVIDER_DEEPSEEK){base.setText(AiSettings.DEEPSEEK_URL);if(model.getText().toString().trim().isEmpty()||model.getText().toString().contains("mimo")||model.getText().toString().equals(AiSettings.MIMO_MODEL))model.setText(AiSettings.DEEPSEEK_MODEL);}
                else if(position==AiSettings.PROVIDER_MIMO){base.setText(AiSettings.MIMO_URL);if(model.getText().toString().trim().isEmpty()||model.getText().toString().contains("deepseek")||model.getText().toString().equals(AiSettings.DEEPSEEK_MODEL))model.setText(AiSettings.MIMO_MODEL);}
            }
        });
        content.addView(button("测试连接",1,v->{
            if(!AiSettings.save(this,true,provider.getSelectedItemPosition(),base.getText().toString(),model.getText().toString(),key.getText().toString())){toast("设置未能保存");return;}
            if(AiSettings.apiKey(this).isEmpty()){toast("请填写 API Key");return;}
            toast("正在测试…");
            AiClient.chatJson(this,AiPrompts.systemPing(),AiPrompts.userPing(),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){toast(json.optBoolean("ok",true)?"连接成功："+json.optString("message","ok"):"连接成功");}
                @Override public void onError(String message){toast(message);}
            });
        }));gap(content,8);
        content.addView(button("清除本机 API Key",2,v->{AiSecrets.clear(this);key.setText("");toast("已清除 Key");}));gap(content,8);
        content.addView(text("Key 经本机密钥库加密保存，不会上传或写入工程文件。文本整理/复盘可用 DeepSeek；照片识词请用小米 MiMo。MiMo 请用按量 sk- Key。未开启 AI 时仍可离线复习。",12,MUTED));
        saveSettings=()->{
            boolean ok=AiSettings.save(this,enabled.isChecked(),provider.getSelectedItemPosition(),base.getText().toString(),model.getText().toString(),key.getText().toString());
            if(!ok)toast("设置未能保存");
            return ok;
        };
    }
    private void addSettingsEntry(String title,String subtitle,int category){
        if(!(content.getChildAt(content.getChildCount()-1) instanceof LinearLayout)||!"settings-group".equals(String.valueOf(content.getChildAt(content.getChildCount()-1).getTag()))){
            LinearLayout group=column();group.setTag("settings-group");group.setBackground(shape(SURFACE,Theme.RADIUS_CARD,LINE,1));content.addView(group);
        }
        LinearLayout group=(LinearLayout)content.getChildAt(content.getChildCount()-1);
        if(group.getChildCount()>0)group.addView(divider(),new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout item=column();item.setPadding(dp(16),dp(12),dp(16),dp(12));item.setMinimumHeight(dp(56));
        TextView heading=text(title,16,INK);heading.setTypeface(null,1);item.addView(heading);
        TextView detail=text(subtitle,12,MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(2);item.addView(detail,p);
        item.setOnClickListener(v->openSettingsCategory(category));Motion.press(item);group.addView(item);
    }
    private void openSettingsCategory(int category){settingsCategory=category;render();}
    private void leaveSettingsCategory(){if(saveSettings!=null&&!saveSettings.get())return;settingsCategory=-1;render();}
    private void intervalSettings(){
        sectionTitle("默认复习间隔","分钟");String[] defaults=Store.DEFAULT_INTERVALS.split(","),values=readIntervals();EditText[] fields=new EditText[8];LinearLayout group=column();group.setPadding(dp(15),dp(3),dp(15),dp(3));group.setBackground(shape(SURFACE,19,LINE,1));String[] names={"第 1 轮","第 2 轮","第 3 轮","第 4 轮","第 5 轮","第 6 轮","第 7 轮","第 8 轮（可选）"};
        for(int i=0;i<8;i++){LinearLayout line=row();line.setMinimumHeight(dp(66));LinearLayout label=column();TextView stage=text(names[i],14,INK);stage.setTypeface(null,1);label.addView(stage);TextView helper=text("默认 "+prettyMinutes(Long.parseLong(defaults[i])),11,MUTED);label.addView(helper);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));fields[i]=input("分钟",values[i],true);fields[i].setGravity(Gravity.CENTER);line.addView(fields[i],new LinearLayout.LayoutParams(dp(96),dp(48)));group.addView(line);if(i<7)group.addView(divider(),new LinearLayout.LayoutParams(-1,dp(1)));}content.addView(group);gap(content,8);
        Switch useLast=new Switch(this);useLast.setText("启用第 8 轮");useLast.setMinHeight(dp(52));useLast.setChecked(Store.prefs(this).getBoolean("optional",false));content.addView(useLast);gap(content,14);
        List<EditText> extras=new ArrayList<>();LinearLayout extraBody=column();Button extraToggle=button("自定义间隔（"+IntervalPlan.parse(Store.prefs(this).getString("extraIntervals","")).size()+" 轮）  ▾",2,null);content.addView(extraToggle);content.addView(extraBody);for(long n:IntervalPlan.parse(Store.prefs(this).getString("extraIntervals","")))addExtra(extraBody,extras,n);extraBody.addView(button("添加复习间隔",1,v->addExtra(extraBody,extras,20160)));extraBody.setVisibility(View.GONE);extraToggle.setOnClickListener(v->{boolean open=extraBody.getVisibility()!=View.VISIBLE;extraBody.setVisibility(open?View.VISIBLE:View.GONE);extraToggle.setText("自定义间隔（"+extras.size()+" 轮）  "+(open?"▴":"▾"));});gap(content,10);
        content.addView(button("恢复默认间隔",2,v->{String[] reset=Store.DEFAULT_INTERVALS.split(",");for(int i=0;i<fields.length;i++)fields[i].setText(reset[i]);}));TextView note=text("修改只影响新建任务；已有任务会保留创建时的计划。",12,MUTED);content.addView(note);
        saveSettings=()->{try{StringBuilder packed=new StringBuilder(),extra=new StringBuilder();for(int i=0;i<fields.length;i++){long n=Long.parseLong(fields[i].getText().toString().trim());if(n<1||n>5256000)throw new IllegalArgumentException("每个间隔须在 1 到 5256000 分钟之间");if(i>0)packed.append(',');packed.append(n);}for(EditText field:extras){if(extra.length()>0)extra.append(',');extra.append(extraMinutes(field));}IntervalPlan.parse(extra.toString());boolean ok=Store.prefs(this).edit().putString("intervals",packed.toString()).putString("extraIntervals",extra.toString()).putBoolean("optional",useLast.isChecked()).commit();if(!ok)throw new IllegalStateException("设备存储写入失败");return true;}catch(Exception e){toast("未保存："+e.getMessage());return false;}};
    }
    private void reviewSettings(){
        sectionTitle("卡片复习","答案和拼写");Spinner mode=new Spinner(this);mode.setMinimumHeight(dp(52));mode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"先显示英文","先显示中文","随机显示中文或英文"}));mode.setSelection(Math.max(0,Math.min(2,Store.prefs(this).getInt("reviewMode",0))));content.addView(mode);Switch shuffle=new Switch(this);shuffle.setText("打乱单词顺序");shuffle.setMinHeight(dp(52));shuffle.setChecked(Store.prefs(this).getBoolean("shuffleWords",false));content.addView(shuffle);Switch motion=new Switch(this);motion.setText("界面动态效果");motion.setMinHeight(dp(52));motion.setChecked(Store.prefs(this).getBoolean("motion",true));content.addView(motion);gap(content,8);
        content.addView(text("词性/义项练习可在任务详情里单独开始：正面英文，从多个词性释义中选择。",12,MUTED));gap(content,12);
        android.content.SharedPreferences prefs=Store.prefs(this);int initialSpelling=Math.max(0,Math.min(2,prefs.getInt("spellingMode",SpellingPlan.NONE)));boolean wasConfigured=prefs.getBoolean("spellingConfigured",false);
        sectionTitle("复习后拼写","默认方式");Spinner spelling=new Spinner(this);spelling.setMinimumHeight(dp(52));spelling.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"默认：全部单词","默认：错题本中的错词","默认：不拼写"}));spelling.setSelection(initialSpelling);content.addView(spelling);
        Switch askAgain=new Switch(this);askAgain.setText("每轮复习后重新询问拼写方式");askAgain.setMinHeight(dp(52));askAgain.setChecked(prefs.getBoolean("askSpellingMode",false));content.addView(askAgain);
        Switch forgottenSpelling=new Switch(this);forgottenSpelling.setText("额外拼写本轮忘记的单词");forgottenSpelling.setMinHeight(dp(52));forgottenSpelling.setChecked(prefs.getBoolean("spellForgottenWords",false));content.addView(forgottenSpelling);
        Switch forgottenExamples=new Switch(this);forgottenExamples.setText("复习后为忘记的词补充例句");forgottenExamples.setMinHeight(dp(52));forgottenExamples.setChecked(prefs.getBoolean("addExamplesForForgotten",false));content.addView(forgottenExamples);
        content.addView(text("额外拼写即使默认设为不拼写也会生效。补例句只处理本轮忘记且尚无例句的单词，可逐个跳过。离开本分类后自动保存。",12,MUTED));
        saveSettings=()->{boolean selectedExplicitly=spelling.getSelectedItemPosition()!=initialSpelling;boolean ok=prefs.edit().putInt("reviewMode",mode.getSelectedItemPosition()).putBoolean("shuffleWords",shuffle.isChecked()).putBoolean("motion",motion.isChecked()).putInt("spellingMode",spelling.getSelectedItemPosition()).putBoolean("spellingConfigured",wasConfigured||selectedExplicitly).putBoolean("askSpellingMode",askAgain.isChecked()).putBoolean("spellForgottenWords",forgottenSpelling.isChecked()).putBoolean("addExamplesForForgotten",forgottenExamples.isChecked()).commit();if(!ok)toast("设置未能保存");return ok;};
    }
    private void soundSettings(){
        sectionTitle("单词朗读","");Switch autoSpeak=settingSwitch("揭晓答案时自动朗读单词和例句","autoSpeak",true);content.addView(textAction("朗读声音与语速",v->SpeechSettings.show(this)));sectionTitle("交互反馈","");Switch feedbackSound=settingSwitch("复习确认音效","feedbackSound",true);LinearLayout previews=row();previews.addView(textAction("试听：记得",v->interaction.preview(this,Feedback.KNOWN)));previews.addView(textAction("试听：忘记了",v->interaction.preview(this,Feedback.RETRY)));content.addView(previews);Switch haptic=settingSwitch("卡片交互触感","haptic",true);Switch reminderSound=settingSwitch("到期通知使用声音","reminderSound",true);content.addView(text("到期通知使用系统通知声音，仍受系统静音与勿扰设置影响。",12,MUTED));
        saveSettings=()->{boolean ok=Store.prefs(this).edit().putBoolean("autoSpeak",autoSpeak.isChecked()).putBoolean("feedbackSound",feedbackSound.isChecked()).putBoolean("haptic",haptic.isChecked()).putBoolean("reminderSound",reminderSound.isChecked()).commit();if(!ok){toast("设置未能保存");return false;}Notifications.ensureChannel(this);return true;};
    }
    private void windowSettings(){
        sectionTitle("每日提醒时段","可添加多个时段");TimeWindowPicker windows=new TimeWindowPicker(this,Store.prefs(this).getString("windows",""),()->{});content.addView(windows.view());gap(content,8);Switch respect=new Switch(this);respect.setText("只在选择的时段内提醒");respect.setMinHeight(dp(54));respect.setChecked(ReminderWindows.restricted(this));content.addView(respect);content.addView(text("关闭后全天可提醒。开启后，不在时段内到期的任务会顺延到下一时段；支持跨午夜。",12,MUTED));
        saveSettings=()->{try{String range=windows.value();if(respect.isChecked()&&range.isEmpty())throw new IllegalArgumentException("请添加至少一个时段，或关闭时段限制");boolean ok=Store.prefs(this).edit().putString("windows",range).putBoolean("respectWindows",respect.isChecked()).commit();if(!ok)throw new IllegalStateException("设备存储写入失败");return true;}catch(Exception e){toast("未保存："+e.getMessage());return false;}};
    }
    private void permissionSettings(){
        settingsNotificationStatus=text(notificationStatus(),14,INK);content.addView(settingsNotificationStatus);content.addView(button("通知权限设置",2,v->Notifications.openNotifications(this)));settingsExactStatus=text(exactStatus(),14,INK);gap(content,8);content.addView(settingsExactStatus);if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this))content.addView(button("精确提醒权限设置",2,v->Notifications.openExactAlarmSettings(this)));saveSettings=()->true;
    }
    private Switch settingSwitch(String label,String key,boolean initial){Switch control=new Switch(this);control.setText(label);control.setMinHeight(dp(52));control.setTextSize(14);control.setTextColor(INK);control.setChecked(Store.prefs(this).getBoolean(key,initial));content.addView(control);return control;}
    private String intervalDraftValue(EditText[] base,List<EditText> extra,Switch optional){StringBuilder out=new StringBuilder();for(EditText field:base)out.append(field.getText()).append('|');out.append('/');for(EditText field:extra)out.append(field.getText()).append(':').append(((Spinner)field.getTag()).getSelectedItemPosition()).append('|');return out.append('/').append(optional.isChecked()).toString();}
    private long extraMinutes(EditText field){int unit=((Spinner)field.getTag()).getSelectedItemPosition();long factor=unit==2?1440:unit==1?60:1;long value=Long.parseLong(field.getText().toString().trim());if(value<1||value>5256000/factor)throw new IllegalArgumentException("自定义间隔必须在 1 分钟到 3650 天之间");return value*factor;}
    private void addExtra(LinearLayout container,List<EditText> fields,long minutes){
        if(fields.size()>=100){toast("最多添加 100 个自定义间隔");return;}LinearLayout line=row();line.setPadding(0,dp(5),0,dp(5));int selection=minutes%1440==0?2:minutes%60==0?1:0;EditText field=input("间隔",String.valueOf(minutes/(selection==2?1440:selection==1?60:1)),true);fields.add(field);line.addView(field,new LinearLayout.LayoutParams(0,dp(52),1));Spinner unit=new Spinner(this);unit.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"分钟","小时","天"}));unit.setSelection(selection);field.setTag(unit);line.addView(unit,new LinearLayout.LayoutParams(dp(92),dp(52)));Button remove=button("−",2,v->{Motion.layout(container);container.removeView(line);fields.remove(field);});remove.setText("");remove.setPadding(dp(17),0,dp(17),0);remove.setContentDescription("删除此间隔");line.addView(remove,new LinearLayout.LayoutParams(dp(54),dp(52)));int insert=container.getChildCount();if(insert>0&&container.getChildAt(insert-1) instanceof Button)insert--;container.addView(line,insert);
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void confirmExit(){
        if(Store.prefs(this).getBoolean("skipSettingsExitPrompt",false)){finish();return;}
        LinearLayout body=column();body.setPadding(dp(20),0,dp(20),0);body.addView(text("设置分类离开时已自动保存；退出前会再次写入设置。",14,INK));CheckBox dontRemind=new CheckBox(this);dontRemind.setText("不再提示");dontRemind.setMinHeight(dp(48));body.addView(dontRemind);
        new AlertDialog.Builder(this).setTitle("保存设置并退出？").setView(body).setNegativeButton("继续设置",null).setPositiveButton("保存并退出",(d,w)->{if(!Store.prefs(this).edit().putBoolean("skipSettingsExitPrompt",dontRemind.isChecked()).commit()){toast("保存失败，仍留在应用中");return;}finish();}).show();
    }
    @Override public void onBackPressed(){if(currentPage==2&&settingsCategory>=0){leaveSettingsCategory();return;}if(currentPage==2){confirmExit();return;}if(currentPage!=0){goPage(0);return;}super.onBackPressed();}
    @Override protected void onDestroy(){interaction.release();super.onDestroy();}
}
