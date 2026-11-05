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
    private static final int BG=Color.rgb(244,248,246), SURFACE=Color.WHITE;
    private static final int INK=Color.rgb(29,53,49), FOREST=Color.rgb(24,78,69);
    private static final int MUTED=Color.rgb(103,123,118), MINT=Color.rgb(222,242,234);
    private static final int CORAL=Color.rgb(191,82,60), CORAL_PALE=Color.rgb(255,237,230);
    private static final int LINE=Color.rgb(229,237,233), GOLD=Color.rgb(235,177,83);
    private LinearLayout root, content;
    private int currentPage=0;
    private int completedFilter=0;
    private boolean completedExpanded=true;
    private long calendarDate=System.currentTimeMillis();
    private final Feedback interaction=new Feedback();
    private boolean changingPage;
    private interface Check { boolean get(); }
    private Check settingsChanged,saveSettings;
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
        if(isFinishing()||setupDialog!=null&&setupDialog.isShowing())return;
        setupStep=0;setupRuleChosen=false;setupWindows=Store.prefs(this).getString("windows","");setupRespectWindows=ReminderWindows.restricted(this);
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
    private void saveSetup(boolean complete){if(!Store.prefs(this).edit().putString("windows",setupWindows).putBoolean("respectWindows",setupRespectWindows).putBoolean("setupComplete",complete).commit()){toast("设置未能保存，请重试");return;}if(setupDialog!=null)setupDialog.dismiss();setupNotificationStatus=null;setupExactStatus=null;render();}
    private void skipSetup(){setupWindows=Store.prefs(this).getString("windows","");setupRespectWindows=ReminderWindows.restricted(this);saveSetup(true);}
    private void showPermissionGuide(){
        LinearLayout body=column();body.setPadding(dp(20),dp(2),dp(20),dp(4));permissionStatus=text(notificationStatus(),15,INK);body.addView(permissionStatus);body.addView(button("打开通知权限设置",1,v->Notifications.openNotifications(this)));gap(body,10);
        permissionExactStatus=text(exactStatus(),15,INK);body.addView(permissionExactStatus);if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this))body.addView(button("打开精确提醒设置",1,v->Notifications.openExactAlarmSettings(this)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("提醒权限").setView(body).setPositiveButton("完成",null).show();permissionDialog=dialog;dialog.setOnDismissListener(d->{permissionStatus=null;permissionExactStatus=null;permissionDialog=null;});
    }
    private GradientDrawable shape(int color,float radius,int strokeColor,float stroke) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if(stroke>0)d.setStroke(dp(stroke),strokeColor); return d;
    }
    private TextView text(String value,int size,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(true); return v;
    }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setOrientation(0); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(1); return v; }
    private void gap(LinearLayout p,int h) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private View divider() { View v=new View(this); v.setBackgroundColor(LINE); return v; }
    private Button button(String label,int style,View.OnClickListener click) {
        Button b=new Button(this); b.setText(label); b.setTextSize(15); b.setTypeface(null,1); b.setAllCaps(false);
        b.setMinHeight(dp(52)); b.setMinimumHeight(dp(52)); b.setPadding(dp(18),0,dp(18),0); b.setStateListAnimator(null);
        if(style==0) { b.setTextColor(Color.WHITE); b.setBackground(shape(FOREST,16,FOREST,0)); b.setElevation(dp(3)); }
        else if(style==1) { b.setTextColor(FOREST); b.setBackground(shape(MINT,16,MINT,0)); }
        else { b.setTextColor(INK); b.setBackground(shape(SURFACE,16,LINE,1)); }
        b.setOnClickListener(click); UiIcons.apply(b,style==0?Color.WHITE:FOREST);Motion.press(b); return b;
    }
    private void render() {
        settingsChanged=null;saveSettings=null;
        root=column(); root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp=new LinearLayout.LayoutParams(-1,0,1); root.addView(scroll,scrollLp);
        content=column(); content.setPadding(dp(22),dp(22),dp(22),dp(26)); scroll.addView(content);
        if(currentPage==0) homePage(); else if(currentPage==1) calendarPage(); else settingsPage();
        root.addView(bottomBar(),new LinearLayout.LayoutParams(-1,dp(90)));
        setContentView(root);
        Motion.enter(content);
    }
    private void renderAtScroll(){int y=root!=null&&root.getChildAt(0) instanceof ScrollView?((ScrollView)root.getChildAt(0)).getScrollY():0;render();ScrollView scroll=(ScrollView)root.getChildAt(0);scroll.post(()->scroll.scrollTo(0,y));}
    private void pageHeading(String title,String subtitle) {
        LinearLayout brand=row();
        ImageView icon=new ImageView(this); icon.setImageResource(com.example.reviewreminder.R.drawable.ic_app);
        LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(42),dp(42)); iconLp.rightMargin=dp(12); brand.addView(icon,iconLp);
        LinearLayout copy=column(); TextView name=text(title,25,INK); name.setTypeface(null,1); copy.addView(name);
        if(!subtitle.isEmpty()) { TextView sub=text(subtitle,13,MUTED); copy.addView(sub); }
        brand.addView(copy); content.addView(brand); gap(content,20);
    }
    private LinearLayout bottomBar() {
        LinearLayout host=new LinearLayout(this); host.setGravity(Gravity.CENTER); host.setPadding(dp(22),dp(4),dp(22),dp(12));
        LinearLayout glass=row(); glass.setPadding(dp(7),dp(6),dp(7),dp(6)); glass.setBackground(shape(0xF5FFFFFF,25,0xD9FFFFFF,1));
        if(Build.VERSION.SDK_INT>=21)glass.setElevation(dp(12));
        String[] names={"主页","日历","我的"};
        for(int i=0;i<3;i++) {
            final int page=i; boolean selected=i==currentPage;
            LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setFocusable(true); item.setClickable(true);
            item.setMinimumWidth(dp(80)); item.setMinimumHeight(dp(60)); item.setPadding(dp(10),dp(4),dp(10),dp(4));
            item.setBackground(shape(selected?MINT:Color.TRANSPARENT,19,Color.TRANSPARENT,0));
            ImageView icon=new ImageView(this);icon.setImageResource(new int[]{R.drawable.ic_house,R.drawable.ic_calendar_days,R.drawable.ic_user_round}[i]);icon.setImageTintList(ColorStateList.valueOf(selected?FOREST:MUTED));item.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));
            TextView label=text(names[i],12,selected?FOREST:MUTED); if(selected)label.setTypeface(null,1);
            LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(-2,-2); labelLp.topMargin=dp(2); item.addView(label,labelLp);
            item.setContentDescription(names[i]); item.setOnClickListener(v->goPage(page));
            Motion.press(item);
            glass.addView(item,new LinearLayout.LayoutParams(0,dp(60),1));
        }
        host.addView(glass,new LinearLayout.LayoutParams(-1,dp(72))); return host;
    }
    private void goPage(int page){
        if(page==currentPage||changingPage)return;
        if(currentPage==2&&settingsChanged!=null&&settingsChanged.get()){
            new AlertDialog.Builder(this).setTitle("保存设置修改？").setNegativeButton("放弃修改",(d,w)->navigate(page)).setNeutralButton("继续编辑",null).setPositiveButton("保存并离开",(d,w)->{if(saveSettings.get())navigate(page);}).show();
        }else navigate(page);
    }
    private void navigate(int page){int direction=page>currentPage?1:-1;changingPage=true;Motion.leave(content,direction,()->{if(isFinishing()||isDestroyed())return;currentPage=page;render();Motion.enter(content,direction);changingPage=false;});}
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
        pageHeading("间隔提醒",todayLabel());
        LinearLayout summary=column(); summary.setPadding(dp(20),dp(17),dp(20),dp(18)); summary.setBackground(shape(FOREST,22,FOREST,0));
        TextView section=text("今天要复习",15,Color.rgb(207,236,225)); summary.addView(section);
        gap(summary,3); LinearLayout stat=row(); TextView count=text(String.valueOf(today),34,Color.WHITE);count.setTypeface(null,1);stat.addView(count);
        TextView suffix=text(" 项到时间",16,Color.WHITE);LinearLayout.LayoutParams sx=new LinearLayout.LayoutParams(-2,-2);sx.leftMargin=dp(7);sx.topMargin=dp(10);stat.addView(suffix,sx);summary.addView(stat);
        TextView note=text(today==0?"今天没有到期任务":"按计划完成，下一次从完成时间重新计算",12,Color.rgb(209,229,222));summary.addView(note);
        content.addView(summary); gap(content,16);
        LinearLayout actions=row();
        actions.addView(button("新建任务",0,v->create()),new LinearLayout.LayoutParams(0,dp(54),1));
        LinearLayout.LayoutParams settingLp=new LinearLayout.LayoutParams(0,dp(54),1);settingLp.leftMargin=dp(10);
        actions.addView(button("提醒设置",2,v->goPage(2)),settingLp);content.addView(actions);gap(content,20);
        content.addView(button("照片录词 / 校对草稿",1,v->Motion.open(this,new Intent(this,ImportActivity.class))));gap(content,16);
        if(!Notifications.enabled(this)){
            LinearLayout warning=column();warning.setPadding(dp(15),dp(13),dp(15),dp(13));warning.setBackground(shape(0xFFFFF2D8,16,0,0));
            TextView w=text("通知尚未开启",14,Color.rgb(133,81,17));w.setTypeface(null,1);warning.addView(w);warning.addView(text("开启后，复习到期时才能显示系统提醒。",13,Color.rgb(133,81,17)));
            warning.setOnClickListener(v->showPermissionGuide());content.addView(warning);gap(content,16);
        }
        if(active.isEmpty()) emptyState("暂时没有复习任务","新建一项任务，先从第一轮开始。", "创建任务",()->create());
        else {sectionTitle("待复习",today+" 项今天");
            boolean hasFuture=false;
            for(JSONObject t:active){if(t.optLong("due")<end)addTask(t,false);else hasFuture=true;}
            if(hasFuture){gap(content,10);sectionTitle("之后安排",(active.size()-today)+" 项计划中");for(JSONObject t:active)if(t.optLong("due")>=end)addTask(t,false);}}
        gap(content,14);completedSection();
    }
    private void completedSection(){
        List<JSONObject> done=taskList(true);Button toggle=button("已完成的任务（"+done.size()+"）  "+(completedExpanded?"▴":"▾"),2,v->{completedExpanded=!completedExpanded;renderAtScroll();});content.addView(toggle);if(!completedExpanded)return;
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
        LinearLayout weekdays=row();for(String label:new String[]{"一","二","三","四","五","六","日"}){TextView t=text(label,12,MUTED);t.setGravity(Gravity.CENTER);weekdays.addView(t,new LinearLayout.LayoutParams(0,dp(30),1));}grid.addView(weekdays);
        for(int week=0;week<6;week++){LinearLayout line=row();for(int day=0;day<7;day++){int number=week*7+day-offset+1;if(number<1||number>days){line.addView(new View(this),new LinearLayout.LayoutParams(0,dp(48),1));continue;}Calendar selected=(Calendar)month.clone();selected.set(Calendar.DAY_OF_MONTH,number);long value=selected.getTimeInMillis();boolean chosen=dayStart(value)==dayStart(calendarDate),today=dayStart(value)==dayStart(System.currentTimeMillis());TextView cell=text(String.valueOf(number),15,chosen?Color.WHITE:today?FOREST:INK);cell.setGravity(Gravity.CENTER);if(chosen||today)cell.setBackground(shape(chosen?FOREST:MINT,14,0,0));cell.setContentDescription(new SimpleDateFormat("yyyy年M月d日",Locale.CHINA).format(selected.getTime()));cell.setOnClickListener(v->{calendarDate=value;showMonth(grid,events);showCalendarEvents(events);});line.addView(cell,new LinearLayout.LayoutParams(0,dp(48),1));}grid.addView(line);}
    }
    private ImageButton calendarArrow(int icon,String label){ImageButton b=new ImageButton(this);b.setImageResource(icon);b.setImageTintList(ColorStateList.valueOf(FOREST));b.setContentDescription(label);b.setBackground(shape(SURFACE,14,LINE,1));b.setPadding(dp(14),dp(14),dp(14),dp(14));Motion.press(b);return b;
    }
    private void showCalendarEvents(LinearLayout events){
        events.removeAllViews();long start=dayStart(calendarDate),end=shiftDay(start,1);TextView heading=text(new SimpleDateFormat("yyyy年M月d日 EEEE",Locale.CHINA).format(new Date(start)),18,INK);heading.setTypeface(null,1);events.addView(heading);gap(events,10);int count=0;
        JSONArray all=Store.tasks(this);for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task==null)continue;long id=task.optLong("id"),due=task.optLong("due");if(!task.optBoolean("done")&&due>=start&&due<end){calendarEvent(events,"计划  "+date(due),task.optString("title"),()->details(id));count++;}JSONArray history=task.optJSONArray("history");if(history!=null)for(int j=0;j<history.length();j++){JSONObject h=history.optJSONObject(j);if(h==null)continue;long at=h.optLong("at");if(at>=start&&at<end){calendarEvent(events,"完成第 "+h.optInt("stage")+" 轮  "+date(at),task.optString("title"),()->details(id));count++;}}}
        if(count==0)events.addView(text("当天没有计划或完成记录",14,MUTED));
    }
    private void calendarEvent(LinearLayout parent,String subtitle,String title,Runnable action){LinearLayout line=column();line.setPadding(dp(15),dp(12),dp(15),dp(12));line.setBackground(shape(SURFACE,16,LINE,1));TextView name=text(title,16,INK);name.setTypeface(null,1);line.addView(name);line.addView(text(subtitle,13,MUTED));line.setMinimumHeight(dp(64));line.setOnClickListener(v->action.run());Motion.press(line);parent.addView(line);gap(parent,8);}
    private void statistics(){
        sectionTitle("复习统计","最近 12 周");int total=0,days=0;long today=dayStart(System.currentTimeMillis());java.util.Map<Long,Integer> counts=new java.util.HashMap<>();JSONArray all=Store.tasks(this);for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task==null)continue;JSONArray history=task.optJSONArray("history");if(history==null)continue;for(int j=0;j<history.length();j++){JSONObject entry=history.optJSONObject(j);if(entry==null||entry.optLong("at")<=0)continue;long day=dayStart(entry.optLong("at"));Integer old=counts.get(day);counts.put(day,old==null?1:old+1);}}for(int i=0;i<84;i++){Integer n=counts.get(shiftDay(today,-i));if(n!=null){total+=n;days++;}}
        TextView stat=text("完成 "+total+" 轮     活跃 "+days+" 天",16,INK);stat.setTypeface(null,1);content.addView(stat);gap(content,12);
        LinearLayout grid=column();for(int row=0;row<7;row++){LinearLayout cells=row();for(int col=0;col<12;col++){final long date=shiftDay(today,-(83-(col*7+row)));Integer value=counts.get(date);int n=value==null?0:value;TextView cell=text("",11,INK);cell.setGravity(Gravity.CENTER);cell.setBackground(shape(n==0?0xFFE8EFEB:n==1?0xFFBFE3D2:n<4?0xFF78BE9B:FOREST,4,0,0));cell.setContentDescription(new SimpleDateFormat("M月d日",Locale.CHINA).format(new Date(date))+" 完成 "+n+" 轮");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(24),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));cells.addView(cell,p);}grid.addView(cells);}content.addView(grid);gap(content,8);content.addView(text("浅到深表示当天完成轮次由少到多；删除任务也会删除其记录。",12,MUTED));gap(content,22);
    }
    private void sectionTitle(String label,String trailing) {
        LinearLayout r=row();TextView title=text(label,18,INK);title.setTypeface(null,1);r.addView(title,new LinearLayout.LayoutParams(0,-2,1));r.addView(text(trailing,12,MUTED));content.addView(r);gap(content,10);
    }
    private void emptyState(String title,String subtitle,String action,Runnable run) {
        LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(22),dp(35),dp(22),dp(35));box.setBackground(shape(SURFACE,20,LINE,1));
        TextView heading=text(title,18,INK);heading.setTypeface(null,1);heading.setGravity(Gravity.CENTER);box.addView(heading);gap(box,7);
        TextView detail=text(subtitle,14,MUTED);detail.setGravity(Gravity.CENTER);box.addView(detail);
        if(action!=null){gap(box,14);box.addView(button(action,1,v->run.run()));}content.addView(box);
    }
    private void addTask(JSONObject task,boolean completed) {
        long id=task.optLong("id"); int stage=task.optInt("stage"); JSONArray intervals=task.optJSONArray("intervals");
        long due=task.optLong("due"); boolean overdue=!completed&&due<=System.currentTimeMillis();
        LinearLayout card=column();card.setPadding(dp(16),dp(15),dp(16),dp(14));card.setBackground(shape(SURFACE,19,LINE,1));
        LinearLayout top=row();TextView title=text(task.optString("title"),17,INK);title.setTypeface(null,1);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text(completed?"完成":"第 "+(stage+1)+" 次",12,completed?FOREST:overdue?CORAL:FOREST);badge.setPadding(dp(10),dp(6),dp(10),dp(6));badge.setBackground(shape(completed?MINT:overdue?CORAL_PALE:MINT,14,0,0));top.addView(badge);card.addView(top);gap(card,9);
        String status=completed?"最后完成  "+date(lastCompleted(task)):overdue?"现在可以开始复习":"计划时间  "+date(due);
        card.addView(text(status,14,completed?MUTED:overdue?CORAL:FOREST));gap(card,5);
        int total=intervals==null?7:intervals.length();int shown=completed?total:Math.min(stage,total);
        card.addView(text("复习进度  "+shown+" / "+total+" 次",12,MUTED));gap(card,12);
        LinearLayout actions=row();
        if(!completed){
            boolean hasWords=task.optJSONArray("words")!=null&&task.optJSONArray("words").length()>0;
            actions.addView(button(hasWords?"开始复习":"完成复习",0,v->{if(hasWords)Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id));else confirmComplete(id);}),new LinearLayout.LayoutParams(0,dp(52),1));
            LinearLayout.LayoutParams snoozeLp=new LinearLayout.LayoutParams(0,dp(52),1);snoozeLp.leftMargin=dp(9);
            actions.addView(button("稍后提醒",2,v->snooze(id)),snoozeLp);
        } else {
            actions.addView(button("查看记录",2,v->details(id)),new LinearLayout.LayoutParams(-1,dp(50)));
        }
        card.addView(actions); if(!completed){card.setOnClickListener(v->details(id));card.setClickable(true);}
        content.addView(card);gap(content,10);
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
        String message="下一次复习会从现在重新计算。"+(task.optLong("due")>System.currentTimeMillis()?"\n这次还没到计划时间，仍要标记完成吗？":"");
        new AlertDialog.Builder(this).setTitle("完成本次复习？").setMessage(message).setNegativeButton("继续复习",null).setPositiveButton("完成",(d,w)->complete(id,task.optInt("stage"))).show();
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
        if(task.optJSONArray("words")!=null&&task.optJSONArray("words").length()>0){new AlertDialog.Builder(this).setTitle(task.optString("title")+" · "+task.optJSONArray("words").length()+" 个单词").setItems(new String[]{"快速复习","校对单词卡","查看复习记录","删除任务"},(d,which)->{if(which==0)Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id));else if(which==1)Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("taskId",id));else if(which==2)history(task);else confirmDelete(id);}).setNegativeButton("关闭",null).show();return;}
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
    private void settingsPage() {
        pageHeading("我的","统计与设置");statistics();
        sectionTitle("复习计划","默认 7 轮 + 可选轮次");
        Button baseToggle=button("默认间隔   ▾",2,null);content.addView(baseToggle);
        String[] defaults=Store.DEFAULT_INTERVALS.split(","),values=readIntervals();EditText[] fields=new EditText[8];
        LinearLayout group=column();group.setPadding(dp(15),dp(3),dp(15),dp(3));group.setBackground(shape(SURFACE,19,LINE,1));
        String[] stageNames={"第一次复习","第二次复习","第三次复习","第四次复习","第五次复习","第六次复习","第七次复习","最后一轮（可选）"};
        for(int i=0;i<8;i++) {
            final int index=i;LinearLayout line=row();line.setMinimumHeight(dp(72));
            LinearLayout label=column();TextView stage=text(stageNames[i],14,INK);stage.setTypeface(null,1);label.addView(stage);
            long original=Long.parseLong(defaults[i]);TextView helper=text("默认 "+prettyMinutes(original),11,MUTED);LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(-2,-2);hl.topMargin=dp(2);label.addView(helper,hl);
            line.addView(label,new LinearLayout.LayoutParams(0,-2,1));fields[i]=input("分钟",values[i],true);fields[i].setGravity(Gravity.CENTER);line.addView(fields[i],new LinearLayout.LayoutParams(dp(92),dp(48)));
            TextView unit=text("分钟",12,MUTED);LinearLayout.LayoutParams unitLp=new LinearLayout.LayoutParams(-2,-2);unitLp.leftMargin=dp(7);line.addView(unit,unitLp);
            group.addView(line);if(i<7)group.addView(divider(),new LinearLayout.LayoutParams(-1,dp(1)));
        }
        content.addView(group);group.setVisibility(View.GONE);baseToggle.setOnClickListener(v->{Motion.layout(content);boolean expand=group.getVisibility()==View.GONE;group.setVisibility(expand?View.VISIBLE:View.GONE);baseToggle.setText(expand?"默认间隔   ▴":"默认间隔   ▾");});gap(content,10);
        Switch useLast=new Switch(this);useLast.setText("启用第 8 轮（默认 14 天）");useLast.setMinHeight(dp(52));useLast.setTextSize(14);useLast.setTextColor(INK);useLast.setChecked(Store.prefs(this).getBoolean("optional",false));
        if(Build.VERSION.SDK_INT>=21)useLast.setButtonTintList(ColorStateList.valueOf(FOREST));content.addView(useLast);gap(content,20);
        List<EditText> extras=new ArrayList<>();LinearLayout extraBody=column();Button extraToggle=button("自定义间隔   ▾",2,null);content.addView(extraToggle);content.addView(extraBody);
        for(long n:IntervalPlan.parse(Store.prefs(this).getString("extraIntervals","")))addExtra(extraBody,extras,n);
        extraBody.addView(button("添加复习间隔",1,v->{Motion.layout(extraBody);addExtra(extraBody,extras,20160);extraToggle.setText("自定义间隔（"+extras.size()+" 轮）  ▴");}));extraBody.setVisibility(View.GONE);
        extraToggle.setText("自定义间隔（"+extras.size()+" 轮）  ▾");extraToggle.setOnClickListener(v->{Motion.layout(content);boolean expand=extraBody.getVisibility()==View.GONE;extraBody.setVisibility(expand?View.VISIBLE:View.GONE);extraToggle.setText("自定义间隔（"+extras.size()+" 轮）  "+(expand?"▴":"▾"));});gap(content,20);
        sectionTitle("快速复习","答案显示方式");
        Spinner mode=new Spinner(this);mode.setMinimumHeight(dp(52));mode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"先显示英文","先显示中文","随机显示中文或英文"}));mode.setSelection(Math.max(0,Math.min(2,Store.prefs(this).getInt("reviewMode",0))));content.addView(mode);
        Switch shuffle=new Switch(this);shuffle.setText("打乱单词顺序");shuffle.setMinHeight(dp(52));shuffle.setChecked(Store.prefs(this).getBoolean("shuffleWords",false));content.addView(shuffle);
        Switch motion=new Switch(this);motion.setText("界面动态效果");motion.setMinHeight(dp(52));motion.setChecked(Store.prefs(this).getBoolean("motion",true));content.addView(motion);gap(content,20);
        sectionTitle("声音与触感","");
        Switch autoSpeak=settingSwitch("显示英文时自动朗读","autoSpeak",true);
        Switch feedbackSound=settingSwitch("复习确认音效","feedbackSound",true);
        LinearLayout previews=row();LinearLayout.LayoutParams previewLeft=new LinearLayout.LayoutParams(0,dp(48),1);previewLeft.rightMargin=dp(8);previews.addView(button("试听：记得",1,v->interaction.preview(this,Feedback.KNOWN)),previewLeft);previews.addView(button("试听：再看一次",1,v->interaction.preview(this,Feedback.RETRY)),new LinearLayout.LayoutParams(0,dp(48),1));content.addView(previews);
        Switch haptic=settingSwitch("复习确认触感","haptic",true);
        Switch reminderSound=settingSwitch("到期通知使用声音","reminderSound",true);
        content.addView(text("这是系统通知声音，不是持续响铃的闹钟；声音与震动也受系统设置控制。",12,MUTED));gap(content,20);
        sectionTitle("每日提醒时间","可分别选择开始和结束");
        TimeWindowPicker windows=new TimeWindowPicker(this,Store.prefs(this).getString("windows",""),()->{});content.addView(windows.view());gap(content,8);
        Switch respectWindows=new Switch(this);respectWindows.setText("只在选择的时段内提醒");respectWindows.setMinHeight(dp(54));respectWindows.setTextSize(14);respectWindows.setTextColor(INK);respectWindows.setChecked(ReminderWindows.restricted(this));content.addView(respectWindows);
        TextView windowHelp=text("关闭后全天可提醒。开启后，如果间隔到期时不在可用时段内，会顺延至下一时段。跨午夜也可设置。",12,MUTED);content.addView(windowHelp);gap(content,16);
        sectionTitle("提醒权限","");
        settingsNotificationStatus=text(notificationStatus(),14,INK);content.addView(settingsNotificationStatus);content.addView(button("通知权限设置",2,v->Notifications.openNotifications(this)));
        settingsExactStatus=text(exactStatus(),14,INK);gap(content,6);content.addView(settingsExactStatus);if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this))content.addView(button("精确提醒权限设置",2,v->Notifications.openExactAlarmSettings(this)));gap(content,16);
        final String initial=settingsValue(fields,extras,windows,respectWindows,useLast,mode,shuffle,motion,autoSpeak,feedbackSound,haptic,reminderSound);
        settingsChanged=()->!initial.equals(settingsValue(fields,extras,windows,respectWindows,useLast,mode,shuffle,motion,autoSpeak,feedbackSound,haptic,reminderSound));
        saveSettings=()->{
            try {
                StringBuilder packed=new StringBuilder();for(int i=0;i<fields.length;i++){long n=Long.parseLong(fields[i].getText().toString());if(n<1||n>5256000)throw new IllegalArgumentException("每个间隔必须在 1 到 5256000 分钟之间");if(i>0)packed.append(',');packed.append(n);}
                StringBuilder extra=new StringBuilder();for(EditText field:extras){if(extra.length()>0)extra.append(',');extra.append(extraMinutes(field));}IntervalPlan.parse(extra.toString());
                String range=windows.value();boolean restricted=respectWindows.isChecked();if(restricted&&range.isEmpty())throw new IllegalArgumentException("请添加至少一个提醒时段，或关闭“只在选择的时段内提醒”");
                if(!Store.prefs(this).edit().putString("intervals",packed.toString()).putString("extraIntervals",extra.toString()).putString("windows",range).putBoolean("respectWindows",restricted).putBoolean("optional",useLast.isChecked()).putInt("reviewMode",mode.getSelectedItemPosition()).putBoolean("shuffleWords",shuffle.isChecked()).putBoolean("motion",motion.isChecked()).putBoolean("autoSpeak",autoSpeak.isChecked()).putBoolean("feedbackSound",feedbackSound.isChecked()).putBoolean("haptic",haptic.isChecked()).putBoolean("reminderSound",reminderSound.isChecked()).commit())throw new IllegalStateException("设备存储写入失败");toast("设置已保存");return true;
            }catch(Exception e){toast("未保存："+e.getMessage());return false;}
        };
        content.addView(button("保存设置",0,v->{if(saveSettings.get())navigate(0);}));gap(content,9);
        content.addView(button("恢复默认间隔",2,v->{String[] reset=Store.DEFAULT_INTERVALS.split(",");for(int i=0;i<fields.length;i++)fields[i].setText(reset[i]);toast("已填入默认间隔，点击保存后生效");}));
        gap(content,12);TextView note=text("已创建的任务会继续使用创建时的间隔与提醒时段。",12,MUTED);note.setGravity(Gravity.CENTER);content.addView(note);
    }
    private Switch settingSwitch(String label,String key,boolean initial){Switch control=new Switch(this);control.setText(label);control.setMinHeight(dp(52));control.setTextSize(14);control.setTextColor(INK);control.setChecked(Store.prefs(this).getBoolean(key,initial));content.addView(control);return control;}
    private String settingsValue(EditText[] base,List<EditText> extra,TimeWindowPicker windows,Switch respectWindows,Switch optional,Spinner mode,Switch shuffle,Switch motion,Switch... feedback){StringBuilder out=new StringBuilder();for(EditText field:base)out.append(field.getText()).append('|');out.append('/');for(EditText field:extra)out.append(field.getText()).append(':').append(((Spinner)field.getTag()).getSelectedItemPosition()).append('|');out.append('/').append(windows.draftValue()).append('/').append(respectWindows.isChecked()).append('/').append(optional.isChecked()).append('/').append(mode.getSelectedItemPosition()).append('/').append(shuffle.isChecked()).append('/').append(motion.isChecked());for(Switch control:feedback)out.append('/').append(control.isChecked());return out.toString();}
    private long extraMinutes(EditText field){int unit=((Spinner)field.getTag()).getSelectedItemPosition();long factor=unit==2?1440:unit==1?60:1;long value=Long.parseLong(field.getText().toString().trim());if(value<1||value>5256000/factor)throw new IllegalArgumentException("自定义间隔必须在 1 分钟到 3650 天之间");return value*factor;}
    private void addExtra(LinearLayout container,List<EditText> fields,long minutes){
        if(fields.size()>=100){toast("最多添加 100 个自定义间隔");return;}LinearLayout line=row();line.setPadding(0,dp(5),0,dp(5));int selection=minutes%1440==0?2:minutes%60==0?1:0;EditText field=input("间隔",String.valueOf(minutes/(selection==2?1440:selection==1?60:1)),true);fields.add(field);line.addView(field,new LinearLayout.LayoutParams(0,dp(52),1));Spinner unit=new Spinner(this);unit.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"分钟","小时","天"}));unit.setSelection(selection);field.setTag(unit);line.addView(unit,new LinearLayout.LayoutParams(dp(92),dp(52)));Button remove=button("−",2,v->{Motion.layout(container);container.removeView(line);fields.remove(field);});remove.setText("");remove.setPadding(dp(17),0,dp(17),0);remove.setContentDescription("删除此间隔");line.addView(remove,new LinearLayout.LayoutParams(dp(54),dp(52)));int insert=container.getChildCount();if(insert>0&&container.getChildAt(insert-1) instanceof Button)insert--;container.addView(line,insert);
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(currentPage!=0)goPage(0);else super.onBackPressed();}
    @Override protected void onDestroy(){interaction.release();super.onDestroy();}
}
