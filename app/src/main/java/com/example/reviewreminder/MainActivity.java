package com.example.reviewreminder;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The daily home of the app: Home (today's reviews), Calendar, and 我的 (statistics, practice
 * entries and settings categories), plus the first-run wizard and every task sheet.
 *
 * Layout: a persistent shell — scrolling {@link #content} above a paper bottom bar. Pages are
 * rebuilt into {@link #content}; tab switches slide in the direction of travel, in-place
 * refreshes keep the scroll position and never re-animate.
 */
public class MainActivity extends ScreenActivity {
    private static final long DAY=86400000L;
    private static final String[] NAV_NAMES={"主页","日历","我的"};
    private static final int[] NAV_ICONS={R.drawable.ic_house,R.drawable.ic_calendar_days,R.drawable.ic_user_round};
    private static final String[] WEEKDAYS={"周日","周一","周二","周三","周四","周五","周六"};

    private WordUi ui;
    private LinearLayout root, content;
    private ScrollView scroll;
    private final LinearLayout[] navItems=new LinearLayout[3];
    private final View[] navPills=new View[3];
    private final ImageView[] navIcons=new ImageView[3];
    private final TextView[] navLabels=new TextView[3];

    private int currentPage=0;
    private int settingsCategory=-1;
    private int completedFilter=0;
    private boolean completedExpanded=true;
    private long calendarDate=System.currentTimeMillis();
    private final Feedback interaction=new Feedback();
    private boolean changingPage, resumedOnce, animateNumbers;
    private int profileScrollY;

    private interface Check { boolean get(); }
    private interface Draft { String value(); }
    private Check saveSettings;
    private Draft settingsDraft;
    private String settingsDraftStart;

    private boolean exactStateKnown,lastExactAllowed,setupRuleChosen;
    private Dialog setupDialog,permissionDialog;
    private Sheet setupSheet;
    private LinearLayout setupContainer;
    private ProgressLine setupProgress;
    private TextView setupStepLabel,setupError;
    private int setupStep;
    private String setupWindows="";
    private boolean setupRespectWindows;
    private TimeWindowPicker setupPicker;
    private RadioGroup setupRule;
    private TextView setupNotificationStatus,setupExactStatus,permissionStatus;
    private TextView permissionExactStatus,settingsNotificationStatus,settingsExactStatus;
    private CheckBox setupDontRemind;
    private boolean setupDontShow;

    // Home: completed section body (rebuilt in place on toggle / filter).
    private LinearLayout completedBody;
    // Calendar parts (rebuilt in place on month/day change).
    private LinearLayout calendarGrid, calendarAgenda;
    private TextView monthTitle;
    private Button calendarToday;
    /** In-month day numbers of the visible grid, keyed by day start; lets a tap repaint two cells instead of 42. */
    private final Map<Long,TextView> dayNums=new HashMap<>();

    private int dp(float n) { return Theme.dp(this,n); }

    // ══ Lifecycle ═════════════════════════════════════════════════════════════
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        interaction.prepare(this);
        ui=new WordUi(this);
        buildShell();
        WordUi.systemBars(this);
        Notice.setBottomOffset(this,64);
        renderPage(true);
        Motion.stagger(content);
        lastExactAllowed=Notifications.exactAllowed(this);
        exactStateKnown=true;
        if(!Store.prefs(this).getBoolean("setupComplete",false))getWindow().getDecorView().post(this::showSetupWizard);
    }

    @Override protected void onResume(){
        super.onResume();
        boolean exact=Notifications.exactAllowed(this);
        if(exactStateKnown&&!lastExactAllowed&&exact)RestoreReceiver.restore(this);
        lastExactAllowed=exact;
        exactStateKnown=true;
        if(setupNotificationStatus!=null)setupNotificationStatus.setText(notificationStatus());
        if(setupExactStatus!=null)setupExactStatus.setText(exactStatus());
        if(permissionStatus!=null)permissionStatus.setText(notificationStatus());
        if(permissionExactStatus!=null)permissionExactStatus.setText(exactStatus());
        if(settingsNotificationStatus!=null)settingsNotificationStatus.setText(notificationShort());
        if(settingsExactStatus!=null)settingsExactStatus.setText(exactShort());
        // The first resume follows onCreate's render; re-rendering would only cancel the entrance.
        if(!resumedOnce){resumedOnce=true;return;}
        // Never rebuild a settings category with edits in progress; the permission page has none.
        if(root!=null&&(currentPage!=2||settingsCategory<0||settingsCategory==5))renderAtScroll();
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        currentPage=0;
        settingsCategory=-1;
        paintNav(0,false);
        render();
    }

    @Override public void onBackPressed(){
        if(currentPage==2&&settingsCategory>=0){leaveSettingsCategory();return;}
        if(currentPage==2){confirmExit();return;}
        if(currentPage!=0){goPage(0);return;}
        super.onBackPressed();
    }

    @Override protected void onDestroy(){interaction.release();super.onDestroy();}

    // ══ Shell & navigation ════════════════════════════════════════════════════
    private void buildShell(){
        root=ui.col();
        root.setBackgroundColor(Theme.BG);
        scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        content=new WordUi.Column(this);
        content.setPadding(dp(Theme.PAGE_PAD),dp(Theme.SPACE_12),dp(Theme.PAGE_PAD),dp(Theme.SPACE_40));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(buildNav(),new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        paintNav(currentPage,false);
    }

    private LinearLayout buildNav(){
        LinearLayout host=ui.col();
        host.setBackgroundColor(Theme.BG);
        View hairline=new View(this);
        hairline.setBackgroundColor(Theme.HAIRLINE);
        host.addView(hairline,new LinearLayout.LayoutParams(-1,Math.max(1,dp(.75f))));
        LinearLayout bar=ui.row();
        bar.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_6),dp(Theme.SPACE_8),dp(Theme.SPACE_6));
        for(int i=0;i<3;i++){
            final int page=i;
            LinearLayout item=ui.col();
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription(NAV_NAMES[i]);
            FrameLayout iconHost=new FrameLayout(this);
            View pill=new View(this);
            pill.setBackground(Theme.shape(this,Theme.PRIMARY_SOFT,15));
            pill.setAlpha(0);
            iconHost.addView(pill,new FrameLayout.LayoutParams(dp(56),dp(30),Gravity.CENTER));
            ImageView icon=ui.icon(NAV_ICONS[i],Theme.INK_3);
            iconHost.addView(icon,new FrameLayout.LayoutParams(dp(Theme.ICON),dp(Theme.ICON),Gravity.CENTER));
            item.addView(iconHost,new LinearLayout.LayoutParams(dp(56),dp(30)));
            TextView label=ui.type(NAV_NAMES[i],Theme.T_CAPTION);
            LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(-2,-2);
            labelLp.topMargin=dp(3);
            item.addView(label,labelLp);
            item.setOnClickListener(v->goPage(page));
            Theme.applyRipple(item,null,Theme.RIPPLE_LIGHT);
            Motion.press(item,.94f);
            navItems[i]=item;navPills[i]=pill;navIcons[i]=icon;navLabels[i]=label;
            bar.addView(item,new LinearLayout.LayoutParams(0,dp(56),1));
        }
        // Tabs share the content's 600dp reading width on tablets; the hairline stays full width.
        WordUi.Column col=new WordUi.Column(this);
        col.setPadding(0,0,0,0);
        col.addView(bar,WordUi.lp());
        host.addView(col,WordUi.lp());
        return host;
    }

    /** Selected tab: pine icon on a soft pill + medium ink label. The pill grows in when animated. */
    private void paintNav(int page,boolean animate){
        boolean motion=animate&&Motion.enabled(this);
        for(int i=0;i<3;i++){
            boolean on=i==page;
            navItems[i].setSelected(on);
            navIcons[i].setImageTintList(ColorStateList.valueOf(on?Theme.PRIMARY:Theme.INK_3));
            navLabels[i].setTextColor(on?Theme.INK:Theme.INK_3);
            navLabels[i].setTypeface(on?Theme.MEDIUM:Theme.REGULAR);
            View pill=navPills[i];
            pill.animate().cancel();
            if(on){
                if(motion&&pill.getAlpha()<1){
                    pill.setScaleX(.55f);pill.setAlpha(0);
                    pill.animate().alpha(1).scaleX(1).setDuration(Theme.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
                }else{pill.setAlpha(1);pill.setScaleX(1);}
            }else if(motion&&pill.getAlpha()>0){
                pill.animate().alpha(0).setDuration(Theme.LEAVE_MS+40).setInterpolator(Motion.EASE).start();
            }else pill.setAlpha(0);
        }
    }

    private void goPage(int page){
        if(changingPage)return;
        if(page==currentPage){
            if(page==2&&settingsCategory>=0)leaveSettingsCategory();
            else scroll.smoothScrollTo(0,0);
            return;
        }
        if(currentPage==2&&settingsCategory>=0){
            if(!commitSettings())return;
            settingsCategory=-1;
        }
        navigate(page);
    }

    private void navigate(int page){
        int direction=page>currentPage?1:-1;
        changingPage=true;
        paintNav(page,true);
        Motion.leave(content,direction,()->{
            if(isFinishing()||isDestroyed())return;
            currentPage=page;
            if(page!=2)settingsCategory=-1;
            render();
            Motion.enter(content,direction);
            changingPage=false;
        });
    }

    /** Fresh render (new page): scroll to top, count-ups may play. */
    private void render(){renderPage(true);scroll.scrollTo(0,0);}

    /** In-place refresh after data changes: keeps scroll, no entrance motion. */
    private void renderAtScroll(){
        int y=scroll.getScrollY();
        renderPage(false);
        scroll.post(()->scroll.scrollTo(0,y));
    }

    private void renderPage(boolean fresh){
        animateNumbers=fresh;
        saveSettings=null;
        settingsDraft=null;
        settingsDraftStart=null;
        settingsNotificationStatus=null;
        settingsExactStatus=null;
        completedBody=null;
        calendarGrid=null;
        calendarAgenda=null;
        content.removeAllViews();
        if(currentPage==0)homePage();
        else if(currentPage==1)calendarPage();
        else profilePage();
    }

    // ══ Shared helpers ════════════════════════════════════════════════════════
    private void toast(String message){ui.toast(message);}
    private void error(String message){ui.error(message);}

    private long dayStart(long time){
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(time);
        c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        return c.getTimeInMillis();
    }
    private long shiftDay(long time,int days){
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(dayStart(time));
        c.add(Calendar.DAY_OF_YEAR,days);
        return c.getTimeInMillis();
    }
    private static String format(String pattern,long time){return new SimpleDateFormat(pattern,Locale.CHINA).format(new Date(time));}
    private String date(long time){return format("M月d日 HH:mm",time);}
    private String clock(long time){return format("HH:mm",time);}
    private String todayLabel(){return format("M月d日 EEEE",System.currentTimeMillis());}
    private String weekday(long time){Calendar c=Calendar.getInstance();c.setTimeInMillis(time);return WEEKDAYS[c.get(Calendar.DAY_OF_WEEK)-1];}

    /** Human relative time: 今天 22:00 · 明天 08:00 · 周五 09:00 · 11月20日. */
    private String when(long time){
        long today=dayStart(System.currentTimeMillis());
        int diff=Math.round((dayStart(time)-today)/(float)DAY);
        String hm=clock(time);
        if(diff==0)return "今天 "+hm;
        if(diff==1)return "明天 "+hm;
        if(diff==-1)return "昨天 "+hm;
        if(diff>1&&diff<7)return weekday(time)+" "+hm;
        Calendar a=Calendar.getInstance(),b=Calendar.getInstance();
        b.setTimeInMillis(time);
        return format(a.get(Calendar.YEAR)==b.get(Calendar.YEAR)?"M月d日":"yyyy年M月d日",time);
    }

    private String greeting(){
        int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if(hour>=5&&hour<12)return "早上好";
        if(hour>=12&&hour<18)return "下午好";
        return "晚上好";
    }

    private static int wordCount(JSONObject task){JSONArray w=task.optJSONArray("words");return w==null?0:w.length();}
    private static int totalRounds(JSONObject task){JSONArray i=task.optJSONArray("intervals");return i==null?7:i.length();}

    private void gap(LinearLayout parent,int h){ui.gap(parent,h);}

    private LinearLayout.LayoutParams margins(int w,int h,int left,int top,int right,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);
        p.leftMargin=dp(left);p.topMargin=dp(top);p.rightMargin=dp(right);p.bottomMargin=dp(bottom);
        return p;
    }

    /** Makes a hand-built surface clickable with ripple + press (AGENTS.md). */
    private void clickable(View v,View.OnClickListener click,float scale){
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(click);
        Theme.applyRipple(v,v.getBackground(),Theme.RIPPLE_LIGHT);
        Motion.press(v,scale);
    }

    private TextView fieldLabel(LinearLayout parent,String label,int top){
        TextView t=ui.type(label,Theme.T_CALLOUT);
        t.setTypeface(Theme.MEDIUM);
        t.setTextColor(Theme.INK_2);
        t.setPadding(dp(Theme.SPACE_4),0,0,0);
        parent.addView(t,margins(-1,-2,0,top,0,Theme.SPACE_6));
        return t;
    }

    private CheckBox checkBox(String label){return ui.check(label,false);}

    private TextView inlineError(){
        TextView t=ui.type("",Theme.T_CALLOUT);
        t.setTextColor(Theme.DANGER);
        t.setVisibility(View.GONE);
        return t;
    }
    private void showInline(TextView t,String message){
        t.setText(message);
        if(t.getVisibility()!=View.VISIBLE){t.setVisibility(View.VISIBLE);Motion.enter(t);}
        Feedback.tick(t);
    }

    private EditText numberInput(String value){
        EditText e=ui.input("",value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(6),0,dp(6),0);
        e.setMinHeight(dp(42));
        e.setMinimumHeight(dp(42));
        e.setSelectAllOnFocus(true);
        e.setFontFeatureSettings("tnum");
        return e;
    }

    private void onText(EditText e,Runnable changed){
        e.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(Editable s){changed.run();}
        });
    }

    private void openReview(long id,boolean practice){
        Intent intent=new Intent(this,ReviewActivity.class).putExtra("taskId",id);
        if(practice)intent.putExtra("practice",true);
        Motion.open(this,intent);
    }

    // ══ First-run setup (Sheet) ═══════════════════════════════════════════════
    private String notificationStatus(){return Notifications.enabled(this)?"通知权限：已开启":"通知权限：未开启，到期时不会显示系统通知";}
    private String exactStatus(){
        if(!Notifications.exactPermissionNeeded(this))return "精确提醒：当前应用版本无需额外授权";
        return Notifications.exactAllowed(this)?"精确提醒权限：已开启":"精确提醒权限：未开启，系统可能延迟提醒";
    }
    private String notificationShort(){return Notifications.enabled(this)?"已开启":"未开启，到期时不会显示通知";}
    private String exactShort(){
        if(!Notifications.exactPermissionNeeded(this))return "当前系统无需额外授权";
        return Notifications.exactAllowed(this)?"已开启":"未开启，系统可能延迟提醒";
    }

    private void showSetupWizard(){
        if(isFinishing()||setupDialog!=null&&setupDialog.isShowing()||Store.prefs(this).getBoolean("dontShowSetup",false))return;
        setupStep=0;
        setupRuleChosen=false;
        setupDontShow=false;
        setupWindows=Store.prefs(this).getString("windows","");
        setupRespectWindows=ReminderWindows.restricted(this);

        Sheet sheet=new Sheet(this);
        sheet.dialog.setCanceledOnTouchOutside(false);
        LinearLayout wrap=ui.col();
        setupProgress=new ProgressLine(this);
        wrap.addView(setupProgress,new LinearLayout.LayoutParams(-1,dp(3)));
        setupStepLabel=ui.type("",Theme.T_CAPTION);
        wrap.addView(setupStepLabel,margins(-1,-2,0,Theme.SPACE_8,0,0));
        setupError=inlineError();
        wrap.addView(setupError,margins(-1,-2,0,Theme.SPACE_8,0,0));
        setupContainer=ui.col();
        wrap.addView(setupContainer,margins(-1,-2,0,Theme.SPACE_16,0,0));
        sheet.content(wrap);
        sheet.secondaryIf("稍后设置",()->{setupBack();return false;});
        sheet.primaryIf("下一步",()->{advanceSetup();return false;});
        setupSheet=sheet;
        setupDialog=sheet.dialog;
        replaceSetupPage(0);
        sheet.show();
    }

    private void setupBack(){
        if(setupStep==0){skipSetup();return;}
        setupStep--;
        replaceSetupPage(-1);
    }

    private void replaceSetupPage(){replaceSetupPage(0);}
    private void replaceSetupPage(int direction){
        setupContainer.removeAllViews();
        setupContainer.addView(setupPage(),WordUi.lp());
        setupStepLabel.setText("设置提醒时间 · 第 "+(setupStep+1)+" 步，共 3 步");
        setupProgress.set((setupStep+1)/3f,direction!=0);
        setupError.setVisibility(View.GONE);
        if(setupSheet!=null){
            if(setupSheet.primaryButton()!=null)setupSheet.primaryButton().setText(setupStep==2?"完成设置":"下一步");
            if(setupSheet.secondaryButton()!=null)setupSheet.secondaryButton().setText(setupStep==0?"稍后设置":"上一步");
        }
        if(direction!=0)Motion.enter(setupContainer,direction);
    }

    private TextView setupQuestion(LinearLayout page,String question,String detail){
        TextView q=ui.type(question,Theme.T_HEADLINE);
        q.setTextSize(19);
        page.addView(q);
        if(detail!=null){
            TextView d=ui.type(detail,Theme.T_CALLOUT);
            page.addView(d,margins(-1,-2,0,Theme.SPACE_6,0,0));
        }
        return q;
    }

    private View setupPage(){
        LinearLayout page=ui.col();
        if(setupStep==0){
            setupQuestion(page,"你每天什么时候方便复习？","可以添加多个时段。点击开始和结束时间，用系统时间选择器设置。");
            setupPicker=new TimeWindowPicker(this,setupWindows,()->{}).onSurface();
            page.addView(setupPicker.view(),margins(-1,-2,0,Theme.SPACE_16,0,0));
            ui.note(page,"不添加时段表示全天都可以提醒。跨午夜时段也可以设置。");
            setupDontRemind=checkBox("不再显示首次设置引导");
            setupDontRemind.setChecked(setupDontShow);
            setupDontRemind.setOnCheckedChangeListener((button,checked)->{setupDontShow=checked;Feedback.tick(button);});
            page.addView(setupDontRemind,margins(-1,-2,0,Theme.SPACE_8,0,0));
        }else if(setupStep==1){
            setupQuestion(page,"如果复习到期时不在你选择的时段内，怎么办？",null);
            setupRule=new RadioGroup(this);
            setupRule.setOrientation(RadioGroup.VERTICAL);
            RadioButton wait=choiceRow(1,"等到下一个可用时段再提醒","到期任务顺延到你选择的时段");
            RadioButton anytime=choiceRow(2,"全天都可提醒，不等待时段","到期即提醒，时段仅作参考");
            setupRule.addView(wait,margins(-1,-2,0,0,0,Theme.SPACE_8));
            setupRule.addView(anytime,margins(-1,-2,0,0,0,0));
            setupRule.check(setupRespectWindows?1:2);
            setupRule.setOnCheckedChangeListener((group,id)->{setupRuleChosen=true;Feedback.tick(group);});
            page.addView(setupRule,margins(-1,-2,0,Theme.SPACE_16,0,0));
            ui.note(page,"这项选择只影响之后新建的任务，不会改动已有任务。");
        }else{
            setupQuestion(page,"检查提醒权限","到期后需要通知权限才能显示提醒。精确提醒权限是否需要，由 Android 版本和应用版本决定。");
            setupNotificationStatus=ui.type(notificationStatus(),Theme.T_BODY);
            page.addView(setupNotificationStatus,margins(-1,-2,0,Theme.SPACE_20,0,0));
            if(!Notifications.enabled(this))page.addView(ui.tonalButton("打开通知权限设置",R.drawable.ic_bell,v->Notifications.openNotifications(this)),margins(-1,-2,0,Theme.SPACE_8,0,0));
            setupExactStatus=ui.type(exactStatus(),Theme.T_BODY);
            page.addView(setupExactStatus,margins(-1,-2,0,Theme.SPACE_16,0,0));
            if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this))page.addView(ui.tonalButton("开启精确提醒权限",R.drawable.ic_clock,v->Notifications.openExactAlarmSettings(this)),margins(-1,-2,0,Theme.SPACE_8,0,0));
            ui.note(page,"拍照和选图使用系统相机、图片选择器及临时文件授权，不要求开放整部相册权限。");
        }
        return page;
    }

    /** Roomy radio option: outlined card that turns pine-tinted when checked. */
    private RadioButton choiceRow(int id,String label,String detail){return ui.choice(label,detail,id);}

    private void showSetupError(String message){if(setupError!=null)showInline(setupError,message);else error(message);}

    private void advanceSetup(){
        try{
            if(setupStep==0){
                setupWindows=setupPicker.value();
                if(!setupRuleChosen)setupRespectWindows=!setupWindows.isEmpty();
                setupStep=1;
                replaceSetupPage(1);
            }else if(setupStep==1){
                setupRespectWindows=setupRule.getCheckedRadioButtonId()==1;
                setupRuleChosen=true;
                if(setupRespectWindows&&setupWindows.isEmpty()){
                    setupStep=0;
                    replaceSetupPage(-1);
                    showSetupError("请先添加至少一个提醒时段，或选择全天都可提醒");
                    return;
                }
                setupStep=2;
                replaceSetupPage(1);
            }else{
                saveSetup(true);
            }
        }catch(Exception e){showSetupError("时间设置无效："+e.getMessage());}
    }

    private void saveSetup(boolean complete){
        if(!Store.prefs(this).edit().putString("windows",setupWindows).putBoolean("respectWindows",setupRespectWindows).putBoolean("setupComplete",complete).putBoolean("dontShowSetup",setupDontShow).commit()){
            showSetupError("设置未能保存，请重试");
            return;
        }
        if(setupDialog!=null)setupDialog.dismiss();
        setupNotificationStatus=null;
        setupExactStatus=null;
        renderAtScroll();
        toast(complete?"设置完成，提醒会按你的时段送达":"可以随时在「我的」中修改提醒时段");
    }

    private void skipSetup(){
        setupWindows=Store.prefs(this).getString("windows","");
        setupRespectWindows=ReminderWindows.restricted(this);
        setupDontShow=setupDontRemind!=null&&setupDontRemind.isChecked();
        saveSetup(setupDontShow);
    }

    // ══ Permission guide (Sheet) ══════════════════════════════════════════════
    private void showPermissionGuide(){showPermissionGuide(false);}

    /**
     * @param force true when the user explicitly asked (home banner tap). Intentional change from the
     *              old design: the "不再显示此权限说明" preference only suppresses the automatic guide,
     *              so a deliberate tap on the banner is never a dead no-op.
     */
    private void showPermissionGuide(boolean force){
        if(!force&&Store.prefs(this).getBoolean("dontShowPermissionGuide",false))return;
        if(permissionDialog!=null&&permissionDialog.isShowing())return;
        LinearLayout body=ui.col();
        permissionStatus=ui.type(notificationStatus(),Theme.T_BODY);
        body.addView(permissionStatus);
        body.addView(ui.tonalButton("打开通知权限设置",R.drawable.ic_bell,v->Notifications.openNotifications(this)),margins(-1,-2,0,Theme.SPACE_8,0,0));
        permissionExactStatus=ui.type(exactStatus(),Theme.T_BODY);
        body.addView(permissionExactStatus,margins(-1,-2,0,Theme.SPACE_20,0,0));
        if(Notifications.exactPermissionNeeded(this)&&!Notifications.exactAllowed(this))
            body.addView(ui.tonalButton("打开精确提醒设置",R.drawable.ic_clock,v->Notifications.openExactAlarmSettings(this)),margins(-1,-2,0,Theme.SPACE_8,0,0));
        CheckBox dontRemind=checkBox("不再显示此权限说明");
        body.addView(dontRemind,margins(-1,-2,0,Theme.SPACE_12,0,0));
        Sheet sheet=new Sheet(this).title("提醒权限").message("开启后，复习到期时会准时出现在通知栏。").content(body);
        sheet.primary("完成",()->{if(dontRemind.isChecked())Store.prefs(this).edit().putBoolean("dontShowPermissionGuide",true).apply();});
        sheet.onDismiss(()->{permissionStatus=null;permissionExactStatus=null;permissionDialog=null;});
        sheet.show();
        permissionDialog=sheet.dialog;
    }

    // ══ Home ══════════════════════════════════════════════════════════════════
    private List<JSONObject> taskList(boolean completed){
        JSONArray all=Store.tasks(this);
        List<JSONObject> list=new ArrayList<>();
        for(int i=0;i<all.length();i++){
            JSONObject t=all.optJSONObject(i);
            if(t!=null&&t.optBoolean("done")==completed)list.add(t);
        }
        Collections.sort(list,new Comparator<JSONObject>(){
            @Override public int compare(JSONObject a,JSONObject b){
                long x=completed?lastCompleted(a):a.optLong("due"),y=completed?lastCompleted(b):b.optLong("due");
                return Long.compare(completed?-x:x,completed?-y:y);
            }
        });
        return list;
    }

    private long lastCompleted(JSONObject t){
        JSONArray h=t.optJSONArray("history");
        return h==null||h.length()==0?t.optLong("created"):h.optJSONObject(h.length()-1).optLong("at");
    }

    private JSONObject findTaskByTitle(String title){
        JSONArray all=Store.tasks(this);
        for(int i=0;i<all.length();i++){
            JSONObject t=all.optJSONObject(i);
            if(t!=null&&title.equals(t.optString("title")))return t;
        }
        return null;
    }

    private void homePage(){
        List<JSONObject> active=taskList(false);
        long end=shiftDay(dayStart(System.currentTimeMillis()),1);
        List<JSONObject> due=new ArrayList<>(),later=new ArrayList<>();
        int words=0;
        for(JSONObject t:active){
            if(t.optLong("due")<end){due.add(t);words+=wordCount(t);}
            else later.add(t);
        }

        homeHeader(due.size(),words);

        if(!Notifications.enabled(this)){
            gap(content,Theme.SPACE_20);
            content.addView(ui.banner(R.drawable.ic_bell,"通知未开启，到期时不会提醒你",true,v->showPermissionGuide(true)),WordUi.lp());
        }

        if(active.isEmpty()){
            gap(content,Theme.SPACE_24);
            View.OnClickListener capture=v->Motion.open(this,new Intent(this,ImportActivity.class));
            if(!taskList(true).isEmpty())content.addView(ui.empty(R.drawable.ic_circle_check,"全部复习完成","可在下方回顾已完成的词组，或录入新词。","去录词",capture),WordUi.lp());
            else content.addView(ui.empty(R.drawable.ic_book_open,"暂时没有复习任务","录词或打开课程后创建计划，也可随时练习已有词卡。","去录词",capture),WordUi.lp());
        }else{
            ui.section(content,"待复习",due.isEmpty()?null:due.size()+" 组");
            if(due.isEmpty())restCard(later.get(0));
            else{
                focusCard(due.get(0));
                if(due.size()>1){
                    LinearLayout group=ui.group();
                    for(int i=1;i<due.size();i++)dueRow(group,due.get(i));
                    content.addView(group,margins(-1,-2,0,Theme.SPACE_8,0,0));
                }
            }
            if(!later.isEmpty()){
                ui.section(content,"之后安排",later.size()+" 组");
                LinearLayout group=ui.group();
                // Keep home short: the next few plans, then a link to the full calendar.
                for(int i=0;i<Math.min(5,later.size());i++){
                    JSONObject t=later.get(i);
                    long id=t.optLong("id");
                    ui.item(group,0,t.optString("title"),"第 "+(t.optInt("stage")+1)+" 轮",when(t.optLong("due")),false,v->details(id));
                }
                if(later.size()>5)ui.item(group,0,"查看全部 "+later.size()+" 组",null,null,true,v->goPage(1));
                content.addView(group,WordUi.lp());
            }
        }

        ui.section(content,"课程","内置词表");
        lessonCard();
        completedSection();
    }

    private void homeHeader(int dueCount,int words){
        LinearLayout top=ui.row();
        TextView date=ui.type(todayLabel(),Theme.T_CAPTION);
        date.setTextColor(Theme.INK_2);
        top.addView(date,WordUi.weight());
        Button capture=ui.quietPill("录词",R.drawable.ic_camera,v->Motion.open(this,new Intent(this,ImportActivity.class)));
        top.addView(capture,new LinearLayout.LayoutParams(-2,dp(Theme.BUTTON_SMALL)));
        ImageButton add=ui.iconButton(R.drawable.ic_plus,"新建",v->create());
        add.setPadding(dp(8),dp(8),dp(8),dp(8));
        Theme.applyRipple(add,Theme.oval(Theme.SUNK),Theme.RIPPLE_LIGHT);
        top.addView(add,margins(dp2(Theme.BUTTON_SMALL),dp2(Theme.BUTTON_SMALL),Theme.SPACE_8,0,0,0));
        content.addView(top,WordUi.lp());

        TextView title=ui.type(greeting(),Theme.T_DISPLAY);
        content.addView(title,margins(-1,-2,0,Theme.SPACE_12,0,0));

        CharSequence status;
        if(dueCount==0)status="今天没有待复习";
        else{
            String head="今天 "+dueCount+" 组";
            SpannableString s=new SpannableString(head+(words>0?" · "+words+" 个单词":""));
            s.setSpan(new ForegroundColorSpan(Theme.PRIMARY),0,head.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new TypefaceSpan("sans-serif-medium"),0,head.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            status=s;
        }
        TextView line=ui.type("",Theme.T_CALLOUT);
        line.setTextSize(Theme.BODY_SIZE);
        line.setText(status);
        content.addView(line,margins(-1,-2,0,Theme.SPACE_4,0,0));
    }

    /** Due wording that matches the header: “3 天前到期” for earlier days, “已到期” for earlier today, “22:00 到期” later today. */
    private String dueLabel(long due){
        long now=System.currentTimeMillis();
        if(due>now)return clock(due)+" 到期";
        long today=dayStart(now),day=dayStart(due);
        if(day<today)return Math.round((today-day)/(double)DAY)+" 天前到期";
        return "已到期";
    }

    /** dp() for LayoutParams helpers that take raw sizes. */
    private int dp2(int n){return dp(n);}

    /** The one task to do now: generous card with the page's single filled action. */
    private void focusCard(JSONObject task){
        long id=task.optLong("id");
        int stage=task.optInt("stage"),total=totalRounds(task);
        long due=task.optLong("due");
        boolean overdue=due<=System.currentTimeMillis();
        boolean hasWords=wordCount(task)>0;

        LinearLayout card=ui.card();
        card.setPadding(dp(Theme.SPACE_20),dp(Theme.SPACE_20),dp(Theme.SPACE_20),dp(Theme.SPACE_12));

        LinearLayout top=ui.row();
        TextView round=ui.type("第 "+(stage+1)+" 轮",Theme.T_OVERLINE);
        top.addView(round,WordUi.weight());
        top.addView(ui.chip(dueLabel(due),overdue?Theme.DANGER_SOFT:Theme.PRIMARY_SOFT,overdue?Theme.DANGER:Theme.PRIMARY));
        card.addView(top,WordUi.lp());

        TextView title=ui.type(task.optString("title"),Theme.T_HEADLINE);
        title.setTextSize(20);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title,margins(-1,-2,0,Theme.SPACE_8,0,0));

        String meta;
        if(hasWords)meta=wordCount(task)+" 个单词 · 掌握 "+String.format(Locale.CHINA,"%.1f",Mastery.average(task.optJSONArray("words")))+"/5";
        else meta=task.optString("description").isEmpty()?"无单词任务 · 完成后进入下一轮":task.optString("description");
        TextView metaLine=ui.type(meta,Theme.T_CALLOUT);
        metaLine.setMaxLines(2);
        metaLine.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(metaLine,margins(-1,-2,0,Theme.SPACE_4,0,0));

        StageDots dots=new StageDots(this,total,Math.min(stage,total),overdue);
        card.addView(dots,margins(-2,-2,0,Theme.SPACE_16,0,0));

        Button primary=ui.primaryButton(hasWords?"开始复习":"完成复习",v->{if(hasWords)openReview(id,false);else confirmComplete(id);});
        card.addView(primary,margins(-1,-2,0,Theme.SPACE_20,0,0));
        LinearLayout secondary=ui.row();
        secondary.setGravity(Gravity.CENTER);
        secondary.addView(hasWords?ui.textButton("任意复习",v->openReview(id,true)):ui.textButton("稍后",v->snooze(id)));
        card.addView(secondary,margins(-1,-2,0,Theme.SPACE_4,0,0));

        clickable(card,v->details(id),.985f);
        content.addView(card,WordUi.lp());
    }

    private void dueRow(LinearLayout group,JSONObject task){
        long id=task.optLong("id");
        boolean hasWords=wordCount(task)>0;
        String subtitle="第 "+(task.optInt("stage")+1)+" 轮 · "+dueLabel(task.optLong("due"));
        LinearLayout row=ui.item(group,0,task.optString("title"),subtitle,null,false,v->details(id));
        Button pill=hasWords?ui.pill("开始",0,v->openReview(id,false)):ui.pill("完成",0,v->confirmComplete(id));
        row.addView(pill,margins(-2,dp2(Theme.BUTTON_SMALL),Theme.SPACE_12,0,0,0));
    }

    /** Nothing due today: say so calmly and point at what comes next. */
    private void restCard(JSONObject next){
        long id=next.optLong("id");
        LinearLayout card=ui.card();
        card.setPadding(dp(Theme.SPACE_20),dp(Theme.SPACE_20),dp(Theme.SPACE_20),dp(wordCount(next)>0?Theme.SPACE_8:Theme.SPACE_20));
        card.addView(ui.type("下一组",Theme.T_HEADLINE));
        TextView detail=ui.type("「"+next.optString("title")+"」"+when(next.optLong("due"))+" 开始",Theme.T_CALLOUT);
        card.addView(detail,margins(-1,-2,0,Theme.SPACE_4,0,0));
        if(wordCount(next)>0){
            Button practice=ui.textButton("任意复习",v->openReview(id,true));
            card.addView(practice,margins(-2,-2,-Theme.SPACE_12,Theme.SPACE_8,0,0));
        }
        clickable(card,v->details(id),.985f);
        content.addView(card,WordUi.lp());
    }

    private void lessonCard(){
        LinearLayout card=ui.card();
        card.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_16),dp(Theme.SPACE_12),dp(Theme.SPACE_12));
        LinearLayout top=ui.row();
        FrameLayout halo=new FrameLayout(this);
        halo.setBackground(Theme.oval(Theme.PRIMARY_SOFT));
        halo.addView(ui.icon(R.drawable.ic_book_open,Theme.PRIMARY),new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
        top.addView(halo,margins(dp2(40),dp2(40),0,0,Theme.SPACE_12,0));
        LinearLayout copy=ui.col();
        TextView title=ui.type("Lesson 1",Theme.T_HEADLINE);
        copy.addView(title);
        copy.addView(ui.type("42 张内置单词卡",Theme.T_CAPTION));
        top.addView(copy,WordUi.weight());
        card.addView(top,WordUi.lp());

        LinearLayout actions=ui.row();
        actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        actions.addView(ui.textButton("打开校对",v->Sheet.confirm(this,"打开 Lesson 1？","将用内置 42 张卡替换当前草稿，不影响已创建的任务。","取消","打开",false,()->{
            try{
                Words.lesson(this);
                Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("quickReview",true));
            }catch(Exception e){error(e.getMessage()==null?"打开失败":e.getMessage());}
        })));
        JSONObject lessonTask=findTaskByTitle("Lesson 1");
        // Only offer free practice here when the lesson is not already on today's list above.
        boolean lessonListed=lessonTask!=null&&!lessonTask.optBoolean("done")&&lessonTask.optLong("due")<shiftDay(dayStart(System.currentTimeMillis()),1);
        if(lessonTask!=null&&wordCount(lessonTask)>0&&!lessonListed){
            long lessonId=lessonTask.optLong("id");
            actions.addView(ui.pill("任意复习",0,v->openReview(lessonId,true)),margins(-2,dp2(Theme.BUTTON_SMALL),Theme.SPACE_4,0,0,0));
        }
        card.addView(actions,margins(-1,-2,0,Theme.SPACE_8,0,0));
        content.addView(card,WordUi.lp());
    }

    private void completedSection(){
        List<JSONObject> done=taskList(true);
        LinearLayout header=ui.row();
        header.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_12),dp(Theme.SPACE_4),dp(Theme.SPACE_12));
        header.setMinimumHeight(dp(Theme.TOUCH));
        header.setBackground(Theme.shape(this,Color.TRANSPARENT,Theme.RADIUS_CHIP));
        TextView label=ui.sectionLabel("已完成");
        header.addView(label);
        TextView count=ui.type(String.valueOf(done.size()),Theme.T_CAPTION);
        header.addView(count,margins(-2,-2,Theme.SPACE_8,0,0,0));
        header.addView(new View(this),WordUi.weight());
        ImageView chevron=ui.icon(R.drawable.ic_chevron_down,Theme.INK_3);
        chevron.setRotation(completedExpanded?180:0);
        header.addView(chevron,new LinearLayout.LayoutParams(dp(Theme.ICON_SMALL),dp(Theme.ICON_SMALL)));
        clickable(header,v->{
            completedExpanded=!completedExpanded;
            Motion.rotate(chevron,completedExpanded?180:0);
            Motion.layout(content);
            fillCompleted(done);
        },.985f);
        header.setContentDescription(completedExpanded?"收起已完成":"展开已完成");
        content.addView(header,margins(-1,-2,0,Theme.SPACE_12,0,0));
        completedBody=ui.col();
        content.addView(completedBody,WordUi.lp());
        fillCompleted(done);
    }

    private void fillCompleted(List<JSONObject> done){
        if(completedBody==null)return;
        completedBody.removeAllViews();
        if(!completedExpanded)return;
        LinearLayout rows=ui.col();
        Segmented filter=ui.segmented(new String[]{"今天完成","近 7 天","近 14 天","更早"},completedFilter,index->{
            completedFilter=index;
            Motion.layout(content);
            fillCompletedRows(rows,done);
        });
        completedBody.addView(filter,WordUi.lp());
        completedBody.addView(rows,margins(-1,-2,0,Theme.SPACE_12,0,0));
        fillCompletedRows(rows,done);
    }

    private void fillCompletedRows(LinearLayout rows,List<JSONObject> done){
        rows.removeAllViews();
        long today=dayStart(System.currentTimeMillis());
        long start=completedFilter==0?today:shiftDay(today,completedFilter==1?-6:-13),end=shiftDay(today,1);
        LinearLayout group=ui.group();
        for(JSONObject t:done){
            JSONArray h=t.optJSONArray("history");
            if(h==null||h.length()==0)continue;
            long at=lastCompleted(t);
            boolean match=completedFilter==3?at<start:at>=start&&at<end;
            if(!match)continue;
            long id=t.optLong("id");
            ui.item(group,0,t.optString("title"),"完成于 "+when(at)+" · 共 "+totalRounds(t)+" 轮",null,true,v->details(id));
        }
        if(group.getChildCount()==0){
            TextView empty=ui.type("此时间范围没有已完成的任务",Theme.T_CALLOUT);
            empty.setTextColor(Theme.INK_3);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0,dp(Theme.SPACE_20),0,dp(Theme.SPACE_20));
            rows.addView(empty,WordUi.lp());
        }else rows.addView(group,WordUi.lp());
    }

    // ══ Calendar ══════════════════════════════════════════════════════════════
    private void calendarPage(){
        ui.largeTitle(content,"日历",null);
        LinearLayout card=ui.card();
        card.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_8),dp(Theme.SPACE_8),dp(Theme.SPACE_12));

        LinearLayout head=ui.row();
        head.setPadding(dp(Theme.SPACE_12),0,0,0);
        monthTitle=ui.type("",Theme.T_HEADLINE);
        monthTitle.setFontFeatureSettings("tnum");
        head.addView(monthTitle,WordUi.weight());
        calendarToday=ui.quietPill("今天",0,v->{
            long now=System.currentTimeMillis();
            int direction=Long.compare(dayStart(now),dayStart(calendarDate));
            calendarDate=now;
            fillMonth(direction);
            fillAgenda(true);
        });
        head.addView(calendarToday,margins(-2,dp2(Theme.BUTTON_SMALL),0,0,Theme.SPACE_4,0));
        head.addView(ui.iconButton(R.drawable.ic_chevron_left,"上个月",v->shiftMonth(-1)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        head.addView(ui.iconButton(R.drawable.ic_chevron_right,"下个月",v->shiftMonth(1)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        card.addView(head,WordUi.lp());

        LinearLayout weekdays=ui.row();
        for(String label:new String[]{"一","二","三","四","五","六","日"}){
            TextView t=ui.type(label,Theme.T_CAPTION);
            t.setGravity(Gravity.CENTER);
            weekdays.addView(t,new LinearLayout.LayoutParams(0,dp(28),1));
        }
        card.addView(weekdays,margins(-1,-2,0,Theme.SPACE_8,0,0));

        calendarGrid=new SwipeGrid(this);
        calendarGrid.setOrientation(LinearLayout.VERTICAL);
        card.addView(calendarGrid,WordUi.lp());
        content.addView(card,margins(-1,-2,0,Theme.SPACE_20,0,0));

        calendarAgenda=ui.col();
        content.addView(calendarAgenda,WordUi.lp());
        fillMonth(0);
        fillAgenda(false);
    }

    private void shiftMonth(int delta){
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(calendarDate);
        c.set(Calendar.DAY_OF_MONTH,1);
        c.add(Calendar.MONTH,delta);
        calendarDate=c.getTimeInMillis();
        fillMonth(delta);
        fillAgenda(true);
    }

    /** Day → 2 when a pending plan falls on it, 1 when it only has completed history. */
    private Map<Long,Integer> monthMarks(long from,long to){
        Map<Long,Integer> marks=new HashMap<>();
        JSONArray all=Store.tasks(this);
        for(int i=0;i<all.length();i++){
            JSONObject task=all.optJSONObject(i);
            if(task==null)continue;
            long due=task.optLong("due");
            if(!task.optBoolean("done")&&due>=from&&due<to)marks.put(dayStart(due),2);
            JSONArray history=task.optJSONArray("history");
            if(history==null)continue;
            for(int j=0;j<history.length();j++){
                JSONObject h=history.optJSONObject(j);
                if(h==null)continue;
                long at=h.optLong("at");
                if(at>=from&&at<to&&!marks.containsKey(dayStart(at)))marks.put(dayStart(at),1);
            }
        }
        return marks;
    }

    private void fillMonth(int direction){
        if(calendarGrid==null)return;
        calendarGrid.removeAllViews();
        dayNums.clear();
        Calendar month=Calendar.getInstance();
        month.setTimeInMillis(calendarDate);
        month.set(Calendar.DAY_OF_MONTH,1);
        month.set(Calendar.HOUR_OF_DAY,0);month.set(Calendar.MINUTE,0);month.set(Calendar.SECOND,0);month.set(Calendar.MILLISECOND,0);
        int offset=(month.get(Calendar.DAY_OF_WEEK)+5)%7;
        int days=month.getActualMaximum(Calendar.DAY_OF_MONTH);
        monthTitle.setText(format("yyyy年M月",month.getTimeInMillis()));
        Calendar now=Calendar.getInstance();
        boolean currentMonth=now.get(Calendar.YEAR)==month.get(Calendar.YEAR)&&now.get(Calendar.MONTH)==month.get(Calendar.MONTH);
        calendarToday.setVisibility(currentMonth&&dayStart(calendarDate)==dayStart(now.getTimeInMillis())?View.GONE:View.VISIBLE);

        long first=month.getTimeInMillis();
        long last=shiftDay(first,days);
        Map<Long,Integer> marks=monthMarks(first,last);
        for(int week=0;week<6;week++){
            LinearLayout line=ui.row();
            for(int day=0;day<7;day++){
                int index=week*7+day-offset;
                long value=shiftDay(first,index);
                Calendar c=Calendar.getInstance();
                c.setTimeInMillis(value);
                boolean inMonth=index>=0&&index<days;
                Integer mark=marks.get(value);
                line.addView(dayCell(value,c.get(Calendar.DAY_OF_MONTH),inMonth,mark==null?0:mark),new LinearLayout.LayoutParams(0,dp(46),1));
            }
            calendarGrid.addView(line,WordUi.lp());
        }
        if(direction!=0)Motion.enter(calendarGrid,direction>0?1:-1);
    }

    private View dayCell(long value,int number,boolean inMonth,int mark){
        LinearLayout cell=ui.col();
        cell.setGravity(Gravity.CENTER);
        TextView num=new TextView(this);
        num.setText(String.valueOf(number));
        num.setGravity(Gravity.CENTER);
        num.setTextSize(15);
        num.setTypeface(Theme.REGULAR);
        num.setFontFeatureSettings("tnum");
        cell.addView(num,new LinearLayout.LayoutParams(dp(36),dp(36)));
        View dot=new View(this);
        cell.addView(dot,margins(dp2(4),dp2(4),0,2,0,0));
        if(!inMonth){
            num.setTextColor(Theme.alpha(Theme.INK_3,.45f));
            return cell;
        }
        dayNums.put(value,num);
        paintDay(num,value);
        if(mark>0)dot.setBackground(Theme.oval(mark==2?Theme.PRIMARY:Theme.INK_3));
        cell.setContentDescription(format("yyyy年M月d日",value));
        cell.setClickable(true);
        cell.setFocusable(true);
        Theme.applyBorderlessRipple(cell,Theme.RIPPLE_LIGHT);
        cell.setOnClickListener(v->{
            long previous=dayStart(calendarDate);
            if(previous==value)return;
            calendarDate=value;
            Feedback.tick(v);
            // Repaint only the two affected numbers so the ripple and press spring finish undisturbed.
            TextView old=dayNums.get(previous);
            if(old!=null)paintDay(old,previous);
            paintDay(num,value);
            Motion.enter(num);
            calendarToday.setVisibility(value==dayStart(System.currentTimeMillis())?View.GONE:View.VISIBLE);
            fillAgenda(true);
        });
        Motion.press(cell,.92f);
        return cell;
    }

    /** Selected = filled pine disc, today = pine ring, otherwise plain ink. */
    private void paintDay(TextView num,long value){
        if(value==dayStart(calendarDate)){
            num.setBackground(Theme.oval(Theme.PRIMARY));
            num.setTextColor(Color.WHITE);
            num.setTypeface(Theme.MEDIUM);
        }else if(value==dayStart(System.currentTimeMillis())){
            GradientDrawable ring=Theme.oval(Color.TRANSPARENT);
            ring.setStroke(Math.max(1,dp(1)),Theme.PRIMARY);
            num.setBackground(ring);
            num.setTextColor(Theme.PRIMARY);
            num.setTypeface(Theme.MEDIUM);
        }else{
            num.setBackground(null);
            num.setTextColor(Theme.INK);
            num.setTypeface(Theme.REGULAR);
        }
    }

    private void fillAgenda(boolean animate){
        if(calendarAgenda==null)return;
        calendarAgenda.removeAllViews();
        long start=dayStart(calendarDate),end=shiftDay(start,1);
        final List<Object[]> events=new ArrayList<>();
        JSONArray all=Store.tasks(this);
        for(int i=0;i<all.length();i++){
            JSONObject task=all.optJSONObject(i);
            if(task==null)continue;
            long id=task.optLong("id"),due=task.optLong("due");
            if(!task.optBoolean("done")&&due>=start&&due<end)
                events.add(new Object[]{due,task.optString("title"),"计划复习 · 第 "+(task.optInt("stage")+1)+" 轮",id,true});
            JSONArray history=task.optJSONArray("history");
            if(history==null)continue;
            for(int j=0;j<history.length();j++){
                JSONObject h=history.optJSONObject(j);
                if(h==null)continue;
                long at=h.optLong("at");
                if(at>=start&&at<end)events.add(new Object[]{at,task.optString("title"),"完成第 "+h.optInt("stage")+" 轮",id,false});
            }
        }
        Collections.sort(events,(a,b)->Long.compare((Long)a[0],(Long)b[0]));
        boolean isToday=start==dayStart(System.currentTimeMillis());
        ui.section(calendarAgenda,(isToday?"今天 · ":"")+format("M月d日",start)+" "+format("EEEE",start),events.isEmpty()?null:events.size()+" 项");
        if(events.isEmpty()){
            TextView empty=ui.type("当天没有计划或完成记录",Theme.T_CALLOUT);
            empty.setTextColor(Theme.INK_3);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(Theme.SPACE_16),dp(Theme.SPACE_24),dp(Theme.SPACE_16),dp(Theme.SPACE_24));
            empty.setBackground(Theme.shape(this,Theme.SURFACE,Theme.RADIUS_GROUP));
            calendarAgenda.addView(empty,WordUi.lp());
        }else{
            LinearLayout group=ui.group();
            for(Object[] e:events)agendaRow(group,(Long)e[0],(String)e[1],(String)e[2],(Long)e[3],(Boolean)e[4]);
            calendarAgenda.addView(group,WordUi.lp());
        }
        if(animate)Motion.enter(calendarAgenda);
    }

    private void agendaRow(LinearLayout group,long time,String title,String subtitle,long id,boolean plan){
        ui.divide(group,Theme.ROW_PAD+56);
        LinearLayout row=ui.row();
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_12),dp(Theme.ROW_PAD),dp(Theme.SPACE_12));
        TextView t=ui.type(clock(time),Theme.T_CALLOUT);
        t.setTypeface(Theme.MEDIUM);
        t.setFontFeatureSettings("tnum");
        t.setTextColor(plan?Theme.PRIMARY:Theme.INK_3);
        row.addView(t,new LinearLayout.LayoutParams(dp(56),-2));
        LinearLayout copy=ui.col();
        TextView name=ui.type(title,Theme.T_BODY);
        name.setTypeface(Theme.MEDIUM);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(name);
        copy.addView(ui.type(subtitle,Theme.T_CAPTION),margins(-1,-2,0,2,0,0));
        row.addView(copy,WordUi.weight());
        Theme.applyRipple(row,null,Theme.RIPPLE_LIGHT);
        clickable(row,v->details(id),.985f);
        group.addView(row,WordUi.lp());
    }

    /** Month grid that changes month on a horizontal swipe while leaving taps to the day cells. */
    private final class SwipeGrid extends LinearLayout {
        private final int slop;
        private float startX,startY;
        private boolean swiping;
        SwipeGrid(Context c){super(c);slop=ViewConfiguration.get(c).getScaledTouchSlop();}
        private boolean horizontal(MotionEvent e){
            float dx=e.getX()-startX,dy=e.getY()-startY;
            return Math.abs(dx)>slop*2&&Math.abs(dx)>Math.abs(dy)*1.5f;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:startX=e.getX();startY=e.getY();swiping=false;break;
                case MotionEvent.ACTION_MOVE:
                    if(!swiping&&horizontal(e)){swiping=true;getParent().requestDisallowInterceptTouchEvent(true);return true;}
                    break;
                default:break;
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:startX=e.getX();startY=e.getY();swiping=false;return true;
                case MotionEvent.ACTION_MOVE:
                    if(!swiping&&horizontal(e)){swiping=true;getParent().requestDisallowInterceptTouchEvent(true);}
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx=e.getX()-startX;
                    if(swiping&&Math.abs(dx)>dp(48))shiftMonth(dx<0?1:-1);
                    swiping=false;
                    return true;
                case MotionEvent.ACTION_CANCEL:swiping=false;return true;
                default:return true;
            }
        }
    }

    // ══ 我的: statistics, practice, settings ══════════════════════════════════
    private String[] settingNames(){return new String[]{"复习计划","复习与拼写","声音与触感","提醒时段","AI","权限管理"};}

    private void profilePage(){
        if(settingsCategory>=0){categoryPage();return;}
        ui.largeTitle(content,"我的",null);
        statistics();

        ui.section(content,"练习",null);
        LinearLayout practice=ui.group();
        ui.item(practice,R.drawable.ic_book_x,"错题本","集中复习反复出错的单词",null,true,v->Motion.open(this,new Intent(this,WrongBookActivity.class)));
        ui.item(practice,R.drawable.ic_pen_line,"创造力造句","用学过的词写句子，AI 帮你批改",null,true,v->Motion.open(this,new Intent(this,CreateActivity.class)));
        content.addView(practice,WordUi.lp());

        ui.section(content,"设置",null);
        LinearLayout settings=ui.group();
        String[] names=settingNames();
        int[] icons={R.drawable.ic_repeat,R.drawable.ic_spell_check,R.drawable.ic_volume_2,R.drawable.ic_clock,R.drawable.ic_sparkles,R.drawable.ic_shield_check};
        String[] notes={"默认间隔与自定义轮次","卡片正面、拼写方式","朗读、音效与触感",windowSummary(),aiSummary(),permissionSummary()};
        for(int i=0;i<names.length;i++){
            final int category=i;
            ui.item(settings,icons[i],names[i],notes[i],null,true,v->openSettingsCategory(category));
        }
        content.addView(settings,WordUi.lp());
    }

    private String windowSummary(){
        String windows=Store.prefs(this).getString("windows","");
        if(!ReminderWindows.restricted(this)||windows.isEmpty())return "全天可提醒";
        return windows.replace(",","、");
    }
    private String aiSummary(){
        if(!AiSettings.enabled(this))return "未开启 · DeepSeek / 小米 MiMo / 自定义";
        String[] providers={"DeepSeek","小米 MiMo","自定义端点"};
        return "已开启 · "+providers[AiSettings.provider(this)];
    }
    private String permissionSummary(){
        boolean exactOk=!Notifications.exactPermissionNeeded(this)||Notifications.exactAllowed(this);
        if(Notifications.enabled(this)&&exactOk)return "通知与精确提醒均已开启";
        return "有权限未开启，提醒可能不准时";
    }

    private void statistics(){
        Map<Long,Integer> counts=new HashMap<>();
        int total=0;
        JSONArray all=Store.tasks(this);
        for(int i=0;i<all.length();i++){
            JSONObject task=all.optJSONObject(i);
            if(task==null)continue;
            JSONArray history=task.optJSONArray("history");
            if(history==null)continue;
            for(int j=0;j<history.length();j++){
                JSONObject entry=history.optJSONObject(j);
                if(entry==null||entry.optLong("at")<=0)continue;
                long day=dayStart(entry.optLong("at"));
                Integer old=counts.get(day);
                counts.put(day,old==null?1:old+1);
                total++;
            }
        }
        long today=dayStart(System.currentTimeMillis());
        long cursor=counts.containsKey(today)?today:counts.containsKey(shiftDay(today,-1))?shiftDay(today,-1):-1;
        int streak=0;
        while(cursor>0&&counts.containsKey(cursor)){streak++;cursor=shiftDay(cursor,-1);}

        ui.section(content,"复习统计",null);
        LinearLayout card=ui.card();

        LinearLayout stats=ui.row();
        stats.setGravity(Gravity.TOP);
        stat(stats,total,"完成轮次");
        stat(stats,counts.size(),"活跃天数");
        stat(stats,streak,"连续天数");
        card.addView(stats,WordUi.lp());

        // 12 weeks, Monday-first rows, current week last.
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(today);
        int weekdayIndex=(c.get(Calendar.DAY_OF_WEEK)+5)%7;
        long start=shiftDay(shiftDay(today,-weekdayIndex),-77);
        int recentRounds=0,recentDays=0;
        LinearLayout grid=ui.col();
        for(int row=0;row<7;row++){
            LinearLayout cells=ui.row();
            for(int col=0;col<12;col++){
                long date=shiftDay(start,col*7+row);
                Square cell=new Square(this);
                if(date<=today){
                    Integer value=counts.get(date);
                    int n=value==null?0:value;
                    if(n>0){recentRounds+=n;recentDays++;}
                    cell.setBackground(Theme.shape(this,heat(n),3));
                    cell.setContentDescription(format("M月d日",date)+" 完成 "+n+" 轮");
                }
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
                p.setMargins(dp(1.5f),dp(1.5f),dp(1.5f),dp(1.5f));
                cells.addView(cell,p);
            }
            grid.addView(cells,WordUi.lp());
        }
        card.addView(grid,margins(-1,-2,0,Theme.SPACE_20,0,0));

        LinearLayout foot=ui.row();
        TextView caption=ui.type("完成 "+recentRounds+" 轮 · 活跃 "+recentDays+" 天 · 最近 12 周",Theme.T_CAPTION);
        foot.addView(caption,WordUi.weight());
        foot.addView(ui.type("少",Theme.T_CAPTION),margins(-2,-2,Theme.SPACE_8,0,Theme.SPACE_4,0));
        for(int n:new int[]{0,1,2,4}){
            View swatch=new View(this);
            swatch.setBackground(Theme.shape(this,heat(n),2));
            foot.addView(swatch,margins(dp2(10),dp2(10),1,0,1,0));
        }
        foot.addView(ui.type("多",Theme.T_CAPTION),margins(-2,-2,Theme.SPACE_4,0,0,0));
        card.addView(foot,margins(-1,-2,0,Theme.SPACE_12,0,0));
        content.addView(card,WordUi.lp());
    }

    private static int heat(int n){return n==0?Theme.SUNK:n==1?Theme.PRIMARY_SOFT:n<4?Theme.PRIMARY_TINT:Theme.PRIMARY;}

    private void stat(LinearLayout parent,int value,String label){
        LinearLayout col=ui.col();
        TextView number=ui.type("0",Theme.T_DISPLAY);
        number.setTextSize(28);
        number.setFontFeatureSettings("tnum");
        if(animateNumbers)Motion.count(number,value,"");else number.setText(String.valueOf(value));
        col.addView(number);
        col.addView(ui.type(label,Theme.T_CAPTION),margins(-2,-2,0,2,0,0));
        parent.addView(col,WordUi.weight());
    }

    /** Square heat-map cell: height follows the weighted width. */
    private static final class Square extends View {
        Square(Context c){super(c);}
        @Override protected void onMeasure(int w,int h){int size=MeasureSpec.getSize(w);setMeasuredDimension(size,size);}
    }

    // ── Settings categories ──────────────────────────────────────────────────
    private void openSettingsCategory(int category){
        if(changingPage)return;
        profileScrollY=scroll.getScrollY();
        settingsCategory=category;
        render();
        Motion.enter(content,1);
    }

    private void leaveSettingsCategory(){
        if(changingPage)return;
        if(!commitSettings())return;
        settingsCategory=-1;
        renderPage(false);
        int y=profileScrollY;
        scroll.post(()->scroll.scrollTo(0,y));
        Motion.enter(content,-1);
    }

    /** Auto-save contract: a category saves when left; only real changes announce themselves. */
    private boolean commitSettings(){
        if(saveSettings==null)return true;
        boolean changed=settingsDraft!=null&&settingsDraftStart!=null&&!settingsDraftStart.equals(settingsDraft.value());
        if(!saveSettings.get())return false;
        if(changed&&settingsCategory>=0)toast("已保存「"+settingNames()[settingsCategory]+"」");
        return true;
    }

    private void categoryPage(){
        LinearLayout head=ui.row();
        head.setMinimumHeight(dp(Theme.TOP_BAR));
        ImageButton back=ui.iconButton(R.drawable.ic_arrow_left,"返回设置目录",v->leaveSettingsCategory());
        head.addView(back,margins(dp2(Theme.TOUCH),dp2(Theme.TOUCH),-Theme.SPACE_16,0,0,0));
        TextView title=ui.type(settingNames()[settingsCategory],Theme.T_HEADLINE);
        head.addView(title,margins(0,-2,Theme.SPACE_4,0,0,0));
        ((LinearLayout.LayoutParams)title.getLayoutParams()).weight=1;
        content.addView(head,WordUi.lp());
        if(settingsCategory==0)intervalSettings();
        else if(settingsCategory==1)reviewSettings();
        else if(settingsCategory==2)soundSettings();
        else if(settingsCategory==3)windowSettings();
        else if(settingsCategory==4)aiSettings();
        else permissionSettings();
        settingsDraftStart=settingsDraft==null?null:settingsDraft.value();
    }

    private String[] readIntervals(){
        String stored=Store.prefs(this).getString("intervals",Store.DEFAULT_INTERVALS).trim();
        String[] parts=stored.split(",");
        if(parts.length!=8)return Store.DEFAULT_INTERVALS.split(",");
        for(String part:parts){
            try{if(Long.parseLong(part)<1)return Store.DEFAULT_INTERVALS.split(",");}
            catch(Exception e){return Store.DEFAULT_INTERVALS.split(",");}
        }
        return parts;
    }

    private String prettyMinutes(long minutes){
        if(minutes<60)return minutes+" 分钟";
        if(minutes%1440==0)return (minutes/1440)+" 天";
        if(minutes%60==0)return (minutes/60)+" 小时";
        return (minutes/60)+" 小时 "+(minutes%60)+" 分钟";
    }

    /** Live caption under a round: “= 12 小时 · 默认 5 分钟”, or a gentle range hint while invalid. */
    private void describeInterval(TextView hint,EditText field,long fallback){
        long n;
        try{n=Long.parseLong(field.getText().toString().trim());}catch(Exception e){n=-1;}
        if(n<1||n>5256000){hint.setText("请输入 1–5256000 分钟");hint.setTextColor(Theme.DANGER);return;}
        if(n==fallback){hint.setText("默认 "+prettyMinutes(fallback));hint.setTextColor(Theme.INK_3);}
        else{hint.setText("= "+prettyMinutes(n)+" · 默认 "+prettyMinutes(fallback));hint.setTextColor(Theme.INK_2);}
    }

    private void intervalSettings(){
        ui.section(content,"默认复习间隔","分钟");
        String[] defaults=Store.DEFAULT_INTERVALS.split(","),values=readIntervals();
        String[] names={"第 1 轮","第 2 轮","第 3 轮","第 4 轮","第 5 轮","第 6 轮","第 7 轮","第 8 轮（可选）"};
        EditText[] fields=new EditText[8];
        LinearLayout group=ui.group();
        for(int i=0;i<8;i++){
            ui.divide(group,Theme.ROW_PAD);
            LinearLayout line=ui.row();
            line.setMinimumHeight(dp(64));
            line.setPadding(dp(Theme.ROW_PAD),dp(10),dp(Theme.SPACE_12),dp(10));
            LinearLayout label=ui.col();
            TextView stage=ui.type(names[i],Theme.T_BODY);
            stage.setTypeface(Theme.MEDIUM);
            label.addView(stage);
            TextView hint=ui.type("",Theme.T_CAPTION);
            label.addView(hint,margins(-1,-2,0,2,0,0));
            line.addView(label,WordUi.weight());
            EditText field=numberInput(values[i]);
            fields[i]=field;
            long fallback=Long.parseLong(defaults[i]);
            describeInterval(hint,field,fallback);
            onText(field,()->describeInterval(hint,field,fallback));
            line.addView(field,margins(dp2(88),dp2(42),Theme.SPACE_12,0,0,0));
            group.addView(line,WordUi.lp());
        }
        content.addView(group,WordUi.lp());

        LinearLayout optionalGroup=ui.group();
        Switch useLast=ui.switchRow(optionalGroup,"启用第 8 轮","新建任务在第 7 轮之后再复习一次",Store.prefs(this).getBoolean("optional",false));
        content.addView(optionalGroup,margins(-1,-2,0,Theme.SPACE_12,0,0));

        // Custom intervals, appended after the base plan.
        List<EditText> extras=new ArrayList<>();
        List<Long> stored=IntervalPlan.parse(Store.prefs(this).getString("extraIntervals",""));
        LinearLayout extraGroup=ui.group();
        LinearLayout toggle=ui.row();
        toggle.setMinimumHeight(dp(Theme.ROW_HEIGHT));
        toggle.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12));
        TextView toggleTitle=ui.type("自定义间隔",Theme.T_BODY);
        toggleTitle.setTypeface(Theme.MEDIUM);
        toggle.addView(toggleTitle,WordUi.weight());
        TextView count=ui.type(stored.size()+" 轮",Theme.T_CALLOUT);
        count.setTextColor(Theme.INK_3);
        toggle.addView(count);
        ImageView chevron=ui.icon(R.drawable.ic_chevron_down,Theme.INK_3);
        toggle.addView(chevron,margins(dp2(Theme.ICON_SMALL),dp2(Theme.ICON_SMALL),Theme.SPACE_8,0,0,0));
        extraGroup.addView(toggle,WordUi.lp());

        LinearLayout extraBody=ui.col();
        extraBody.setPadding(dp(Theme.ROW_PAD),0,dp(Theme.SPACE_8),dp(Theme.ROW_PAD));
        extraBody.setVisibility(View.GONE);
        LinearLayout extraList=ui.col();
        extraBody.addView(extraList,WordUi.lp());
        Runnable recount=()->count.setText(extras.size()+" 轮");
        for(long n:stored)addExtra(extraList,extras,n,false,recount);
        extraBody.addView(ui.tonalButton("添加复习间隔",R.drawable.ic_plus,v->addExtra(extraList,extras,20160,true,recount)),margins(-1,-2,0,Theme.SPACE_8,Theme.SPACE_8,0));
        TextView extraNote=ui.type("依次排在第 7（或第 8）轮之后，每行一个间隔。",Theme.T_CAPTION);
        extraBody.addView(extraNote,margins(-1,-2,Theme.SPACE_4,Theme.SPACE_8,0,0));
        extraGroup.addView(extraBody,WordUi.lp());
        clickable(toggle,v->{
            boolean open=extraBody.getVisibility()!=View.VISIBLE;
            Motion.layout(content);
            extraBody.setVisibility(open?View.VISIBLE:View.GONE);
            Motion.rotate(chevron,open?180:0);
            recount.run();
        },.985f);
        content.addView(extraGroup,margins(-1,-2,0,Theme.SPACE_12,0,0));

        Button reset=ui.textButton("恢复默认间隔",v->{
            String[] defaultsAgain=Store.DEFAULT_INTERVALS.split(",");
            for(int i=0;i<fields.length;i++)fields[i].setText(defaultsAgain[i]);
            Feedback.tick(v);
            toast("已恢复默认间隔，离开本页时保存");
        });
        content.addView(reset,margins(-2,-2,-Theme.SPACE_8,Theme.SPACE_8,0,0));
        ui.note(content,"修改只影响新建任务；已有任务会保留创建时的计划。离开本页时自动保存。");

        settingsDraft=()->intervalDraftValue(fields,extras,useLast);
        saveSettings=()->{
            try{
                StringBuilder packed=new StringBuilder(),extra=new StringBuilder();
                for(int i=0;i<fields.length;i++){
                    long n=Long.parseLong(fields[i].getText().toString().trim());
                    if(n<1||n>5256000)throw new IllegalArgumentException("每个间隔须在 1 到 5256000 分钟之间");
                    if(i>0)packed.append(',');
                    packed.append(n);
                }
                for(EditText field:extras){
                    if(extra.length()>0)extra.append(',');
                    extra.append(extraMinutes(field));
                }
                IntervalPlan.parse(extra.toString());
                boolean ok=Store.prefs(this).edit().putString("intervals",packed.toString()).putString("extraIntervals",extra.toString()).putBoolean("optional",useLast.isChecked()).commit();
                if(!ok)throw new IllegalStateException("设备存储写入失败");
                return true;
            }catch(Exception e){error("未保存："+e.getMessage());return false;}
        };
    }

    private String intervalDraftValue(EditText[] base,List<EditText> extra,Switch optional){
        StringBuilder out=new StringBuilder();
        for(EditText field:base)out.append(field.getText()).append('|');
        out.append('/');
        for(EditText field:extra)out.append(field.getText()).append(':').append(((Segmented)field.getTag()).getSelectedItemPosition()).append('|');
        return out.append('/').append(optional.isChecked()).toString();
    }

    private long extraMinutes(EditText field){
        int unit=((Segmented)field.getTag()).getSelectedItemPosition();
        long factor=unit==2?1440:unit==1?60:1;
        long value=Long.parseLong(field.getText().toString().trim());
        if(value<1||value>5256000/factor)throw new IllegalArgumentException("自定义间隔必须在 1 分钟到 3650 天之间");
        return value*factor;
    }

    private void addExtra(LinearLayout container,List<EditText> fields,long minutes,boolean animate,Runnable changed){
        if(fields.size()>=100){error("最多添加 100 个自定义间隔");return;}
        LinearLayout line=ui.row();
        line.setPadding(0,dp(Theme.SPACE_6),0,dp(Theme.SPACE_6));
        int selection=minutes%1440==0?2:minutes%60==0?1:0;
        EditText field=numberInput(String.valueOf(minutes/(selection==2?1440:selection==1?60:1)));
        field.setHint("间隔");
        line.addView(field,new LinearLayout.LayoutParams(dp(72),dp(42)));
        Segmented unit=ui.segmented(new String[]{"分钟","小时","天"},selection,null);
        field.setTag(unit);
        line.addView(unit,margins(0,-2,Theme.SPACE_8,0,0,0));
        ((LinearLayout.LayoutParams)unit.getLayoutParams()).weight=1;
        ImageButton remove=ui.iconButton(R.drawable.ic_x,"删除此间隔",null);
        remove.setImageTintList(ColorStateList.valueOf(Theme.INK_3));
        remove.setOnClickListener(v->{
            Motion.layout(content);
            container.removeView(line);
            fields.remove(field);
            changed.run();
        });
        line.addView(remove,margins(dp2(44),dp2(44),Theme.SPACE_4,0,0,0));
        fields.add(field);
        if(animate)Motion.layout(content);
        container.addView(line,WordUi.lp());
        changed.run();
    }

    /** Group block: label above a segmented control. */
    private Segmented segmentBlock(LinearLayout group,String label,String[] options,int selected){
        ui.divide(group,Theme.ROW_PAD);
        LinearLayout block=ui.col();
        block.setPadding(dp(Theme.ROW_PAD),dp(14),dp(Theme.ROW_PAD),dp(Theme.ROW_PAD));
        TextView l=ui.type(label,Theme.T_BODY);
        l.setTypeface(Theme.MEDIUM);
        block.addView(l);
        Segmented segmented=ui.segmented(options,selected,null);
        block.addView(segmented,margins(-1,-2,0,Theme.SPACE_12,0,0));
        group.addView(block,WordUi.lp());
        return segmented;
    }

    private void reviewSettings(){
        SharedPreferences prefs=Store.prefs(this);
        ui.section(content,"卡片复习",null);
        LinearLayout cards=ui.group();
        Segmented mode=segmentBlock(cards,"卡片正面",new String[]{"先英文","先中文","随机"},Math.max(0,Math.min(2,prefs.getInt("reviewMode",0))));
        Switch shuffle=ui.switchRow(cards,"打乱单词顺序",null,prefs.getBoolean("shuffleWords",false));
        Switch motion=ui.switchRow(cards,"界面动态效果","关闭后页面与列表的过渡立即呈现",prefs.getBoolean("motion",true));
        content.addView(cards,WordUi.lp());
        ui.note(content,"词性/义项练习可在任务详情里单独开始：正面英文，从多个词性释义中选择。");

        int initialSpelling=Math.max(0,Math.min(2,prefs.getInt("spellingMode",SpellingPlan.NONE)));
        boolean wasConfigured=prefs.getBoolean("spellingConfigured",false);
        ui.section(content,"复习后拼写","默认方式");
        LinearLayout spellingGroup=ui.group();
        Segmented spelling=segmentBlock(spellingGroup,"默认拼写",new String[]{"全部单词","错词","不拼写"},initialSpelling);
        Switch askAgain=ui.switchRow(spellingGroup,"每轮复习后重新询问拼写方式",null,prefs.getBoolean("askSpellingMode",false));
        Switch forgottenSpelling=ui.switchRow(spellingGroup,"额外拼写本轮忘记的单词",null,prefs.getBoolean("spellForgottenWords",false));
        Switch forgottenExamples=ui.switchRow(spellingGroup,"复习后为忘记的词补充例句",null,prefs.getBoolean("addExamplesForForgotten",false));
        content.addView(spellingGroup,WordUi.lp());
        ui.note(content,"“错词”指本词组错题本中的单词。额外拼写即使默认设为不拼写也会生效。补例句只处理本轮忘记且尚无例句的单词，可逐个跳过。离开本页后自动保存。");

        settingsDraft=()->mode.getSelected()+"|"+shuffle.isChecked()+"|"+motion.isChecked()+"|"+spelling.getSelected()+"|"+askAgain.isChecked()+"|"+forgottenSpelling.isChecked()+"|"+forgottenExamples.isChecked();
        saveSettings=()->{
            boolean selectedExplicitly=spelling.getSelectedItemPosition()!=initialSpelling;
            boolean ok=prefs.edit()
                    .putInt("reviewMode",mode.getSelectedItemPosition())
                    .putBoolean("shuffleWords",shuffle.isChecked())
                    .putBoolean("motion",motion.isChecked())
                    .putInt("spellingMode",spelling.getSelectedItemPosition())
                    .putBoolean("spellingConfigured",wasConfigured||selectedExplicitly)
                    .putBoolean("askSpellingMode",askAgain.isChecked())
                    .putBoolean("spellForgottenWords",forgottenSpelling.isChecked())
                    .putBoolean("addExamplesForForgotten",forgottenExamples.isChecked())
                    .commit();
            if(!ok)error("设置未能保存");
            return ok;
        };
    }

    private void soundSettings(){
        SharedPreferences prefs=Store.prefs(this);
        ui.section(content,"朗读",null);
        LinearLayout speech=ui.group();
        Switch autoSpeak=ui.switchRow(speech,"揭晓答案时自动朗读单词和例句",null,prefs.getBoolean("autoSpeak",true));
        ui.item(speech,0,"朗读声音与语速","选择声音、调整语速",null,true,v->SpeechSettings.show(this));
        content.addView(speech,WordUi.lp());

        ui.section(content,"交互反馈",null);
        LinearLayout feedback=ui.group();
        Switch feedbackSound=ui.switchRow(feedback,"复习确认音效","在“记得”“忘记了”和完成本轮时播放",prefs.getBoolean("feedbackSound",true));
        LinearLayout previews=ui.row();
        previews.setPadding(dp(Theme.ROW_PAD),0,dp(Theme.ROW_PAD),dp(Theme.SPACE_16));
        previews.addView(ui.quietPill("试听：记得",R.drawable.ic_volume_2,v->interaction.preview(this,Feedback.KNOWN)),new LinearLayout.LayoutParams(-2,dp(Theme.BUTTON_SMALL)));
        previews.addView(ui.quietPill("试听：忘记了",R.drawable.ic_volume_2,v->interaction.preview(this,Feedback.RETRY)),margins(-2,dp2(Theme.BUTTON_SMALL),Theme.SPACE_8,0,0,0));
        feedback.addView(previews,WordUi.lp());
        Switch haptic=ui.switchRow(feedback,"卡片交互触感",null,prefs.getBoolean("haptic",true));
        // The pref is only saved on leaving the category, so tick from the switch's own new state.
        haptic.setOnCheckedChangeListener((b,on)->{if(on)b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);});
        Switch reminderSound=ui.switchRow(feedback,"到期通知使用声音",null,prefs.getBoolean("reminderSound",true));
        content.addView(feedback,WordUi.lp());
        ui.note(content,"到期通知使用系统通知声音，仍受系统静音与勿扰设置影响。");

        settingsDraft=()->autoSpeak.isChecked()+"|"+feedbackSound.isChecked()+"|"+haptic.isChecked()+"|"+reminderSound.isChecked();
        saveSettings=()->{
            boolean ok=prefs.edit()
                    .putBoolean("autoSpeak",autoSpeak.isChecked())
                    .putBoolean("feedbackSound",feedbackSound.isChecked())
                    .putBoolean("haptic",haptic.isChecked())
                    .putBoolean("reminderSound",reminderSound.isChecked())
                    .commit();
            if(!ok){error("设置未能保存");return false;}
            Notifications.ensureChannel(this);
            return true;
        };
    }

    private void windowSettings(){
        ui.section(content,"每日提醒时段","可添加多个时段");
        TimeWindowPicker windows=new TimeWindowPicker(this,Store.prefs(this).getString("windows",""),()->{});
        content.addView(windows.view(),WordUi.lp());
        LinearLayout group=ui.group();
        Switch respect=ui.switchRow(group,"只在选择的时段内提醒",null,ReminderWindows.restricted(this));
        content.addView(group,margins(-1,-2,0,Theme.SPACE_16,0,0));
        ui.note(content,"关闭后全天可提醒。开启后，不在时段内到期的任务会顺延到下一时段；支持跨午夜。");

        settingsDraft=()->windows.draftValue()+"|"+respect.isChecked();
        saveSettings=()->{
            try{
                String range=windows.value();
                if(respect.isChecked()&&range.isEmpty())throw new IllegalArgumentException("请添加至少一个时段，或关闭时段限制");
                boolean ok=Store.prefs(this).edit().putString("windows",range).putBoolean("respectWindows",respect.isChecked()).commit();
                if(!ok)throw new IllegalStateException("设备存储写入失败");
                return true;
            }catch(Exception e){error("未保存："+e.getMessage());return false;}
        };
    }

    private void aiSettings(){
        LinearLayout switchGroup=ui.group();
        Switch enabled=ui.switchRow(switchGroup,"启用 AI 功能","可选，默认关闭；未开启时仍可离线复习",AiSettings.enabled(this));
        content.addView(switchGroup,margins(-1,-2,0,Theme.SPACE_16,0,0));

        ui.section(content,"服务商",null);
        Segmented provider=ui.segmented(new String[]{"DeepSeek","小米 MiMo","自定义"},AiSettings.provider(this),null);
        content.addView(provider,WordUi.lp());

        ui.section(content,"连接",null);
        LinearLayout form=ui.col();
        fieldLabel(form,"Base URL",0);
        EditText base=ui.input("https://",AiSettings.storedBaseUrl(this).isEmpty()?AiSettings.baseUrl(this):AiSettings.storedBaseUrl(this));
        base.setSingleLine(true);
        base.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(base,WordUi.lp());
        String existingKey=AiSettings.apiKey(this);
        fieldLabel(form,"API Key",Theme.SPACE_16);
        EditText key=ui.input(existingKey.isEmpty()?"粘贴 API Key":"留空则保留已保存："+AiSecrets.mask(existingKey),"");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(key,WordUi.lp());
        fieldLabel(form,"模型名",Theme.SPACE_16);
        EditText model=ui.input("模型名",AiSettings.storedModel(this).isEmpty()?AiSettings.model(this):AiSettings.storedModel(this));
        model.setSingleLine(true);
        form.addView(model,WordUi.lp());
        content.addView(form,WordUi.lp());

        provider.setOnPick(position->{
            if(position==AiSettings.PROVIDER_DEEPSEEK){
                base.setText(AiSettings.DEEPSEEK_URL);
                if(model.getText().toString().trim().isEmpty()||model.getText().toString().contains("mimo")||model.getText().toString().equals(AiSettings.MIMO_MODEL))model.setText(AiSettings.DEEPSEEK_MODEL);
            }else if(position==AiSettings.PROVIDER_MIMO){
                base.setText(AiSettings.MIMO_URL);
                if(model.getText().toString().trim().isEmpty()||model.getText().toString().contains("deepseek")||model.getText().toString().equals(AiSettings.DEEPSEEK_MODEL))model.setText(AiSettings.MIMO_MODEL);
            }
        });

        Button test=ui.tonalButton("测试连接",0,null);
        test.setOnClickListener(v->{
            if(!AiSettings.save(this,true,provider.getSelectedItemPosition(),base.getText().toString(),model.getText().toString(),key.getText().toString())){error("设置未能保存");return;}
            if(AiSettings.apiKey(this).isEmpty()){error("请填写 API Key");return;}
            test.setText("正在测试…");
            WordUi.enable(test,false);
            AiClient.chatJson(this,AiPrompts.systemPing(),AiPrompts.userPing(),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){
                    test.setText("测试连接");
                    WordUi.enable(test,true);
                    toast(json.optBoolean("ok",true)?"连接成功："+json.optString("message","ok"):"连接成功");
                }
                @Override public void onError(String message){
                    test.setText("测试连接");
                    WordUi.enable(test,true);
                    error(message);
                }
            });
        });
        content.addView(test,margins(-1,-2,0,Theme.SPACE_24,0,0));
        Button clear=ui.dangerTextButton("清除本机 API Key",v->{
            AiSecrets.clear(this);
            key.setText("");
            key.setHint("粘贴 API Key");
            toast("已清除 Key");
        });
        content.addView(clear,margins(-2,-2,-Theme.SPACE_8,Theme.SPACE_8,0,0));
        ui.note(content,"Key 经本机密钥库加密保存，不会上传或写入工程文件。文本整理/复盘可用 DeepSeek；照片识词请用小米 MiMo，MiMo 请用按量 sk- Key。");

        settingsDraft=()->enabled.isChecked()+"|"+provider.getSelected()+"|"+base.getText()+"|"+model.getText()+"|"+key.getText();
        saveSettings=()->{
            boolean ok=AiSettings.save(this,enabled.isChecked(),provider.getSelectedItemPosition(),base.getText().toString(),model.getText().toString(),key.getText().toString());
            if(!ok)error("设置未能保存");
            return ok;
        };
    }

    private void permissionSettings(){
        ui.section(content,"提醒权限",null);
        LinearLayout group=ui.group();
        boolean notifications=Notifications.enabled(this);
        settingsNotificationStatus=permissionRow(group,notifications,"通知",notificationShort(),notifications?"设置":"去开启",v->Notifications.openNotifications(this));
        boolean needed=Notifications.exactPermissionNeeded(this),allowed=Notifications.exactAllowed(this);
        settingsExactStatus=permissionRow(group,!needed||allowed,"精确提醒",exactShort(),needed&&!allowed?"去开启":null,v->Notifications.openExactAlarmSettings(this));
        content.addView(group,WordUi.lp());
        ui.note(content,"通知让到期复习出现在通知栏；精确提醒让它准时送达。拍照和选图使用系统相机与图片选择器，不要求开放整部相册权限。");
        saveSettings=()->true;
    }

    private TextView permissionRow(LinearLayout group,boolean ok,String title,String status,String action,View.OnClickListener click){
        ui.divide(group,Theme.ROW_PAD+Theme.ICON+Theme.SPACE_16);
        LinearLayout row=ui.row();
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_12),dp(Theme.SPACE_12),dp(Theme.SPACE_12));
        row.addView(ui.icon(ok?R.drawable.ic_circle_check:R.drawable.ic_circle_alert,ok?Theme.PRIMARY:Theme.WARN),margins(dp2(Theme.ICON),dp2(Theme.ICON),0,0,Theme.SPACE_16,0));
        LinearLayout copy=ui.col();
        TextView name=ui.type(title,Theme.T_BODY);
        name.setTypeface(Theme.MEDIUM);
        copy.addView(name);
        TextView state=ui.type(status,Theme.T_CAPTION);
        state.setTextColor(ok?Theme.INK_3:Theme.WARN);
        copy.addView(state,margins(-1,-2,0,2,0,0));
        row.addView(copy,WordUi.weight());
        if(action!=null){
            Button button=ok?ui.quietPill(action,0,click):ui.pill(action,0,click);
            row.addView(button,margins(-2,dp2(Theme.BUTTON_SMALL),Theme.SPACE_12,0,0,0));
        }
        group.addView(row,WordUi.lp());
        return state;
    }

    // ══ Task sheets ═══════════════════════════════════════════════════════════
    private void create(){
        LinearLayout form=ui.col();
        fieldLabel(form,"名称",0);
        EditText title=ui.input("例如：英语第十二课","");
        title.setSingleLine(true);
        form.addView(title,WordUi.lp());
        fieldLabel(form,"备注",Theme.SPACE_16);
        EditText description=ui.input("可选","");
        description.setSingleLine(true);
        form.addView(description,WordUi.lp());
        LinearLayout switchHost=ui.col();
        String[] base=readIntervals();
        Switch optional=ui.switchRow(switchHost,"启用第 8 轮","再隔 "+prettyMinutes(Long.parseLong(base[7]))+" 复习一次，可在设置中修改",Store.prefs(this).getBoolean("optional",false));
        optional.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_12),0,dp(Theme.SPACE_12));
        form.addView(switchHost,margins(-1,-2,0,Theme.SPACE_8,0,0));
        TextView hint=ui.type("提醒时段和间隔使用当前默认设置。",Theme.T_CAPTION);
        form.addView(hint,margins(-1,-2,Theme.SPACE_4,0,0,0));
        TextView failure=inlineError();
        form.addView(failure,margins(-1,-2,Theme.SPACE_4,Theme.SPACE_8,0,0));

        Sheet sheet=new Sheet(this).title("新建任务").content(form);
        sheet.secondary("取消",null);
        sheet.primaryIf("创建任务",()->{
            String name=title.getText().toString().trim();
            if(name.isEmpty()){title.setError("请输入任务名称");title.requestFocus();Feedback.tick(title);return false;}
            try{
                List<Long> plan=IntervalPlan.active(Store.prefs(this).getString("intervals",Store.DEFAULT_INTERVALS),optional.isChecked(),Store.prefs(this).getString("extraIntervals",""));
                JSONArray schedule=new JSONArray();
                for(long n:plan)schedule.put(n);
                String windows=ReminderWindows.active(this);
                long now=System.currentTimeMillis();
                long id=Store.prefs(this).getLong("nextId",0)+1;
                JSONObject task=new JSONObject().put("id",id).put("title",name).put("description",description.getText().toString().trim()).put("stage",0).put("intervals",schedule).put("windows",windows).put("created",now).put("due",Schedule.due(now,schedule.getLong(0),Store.windows(windows))).put("history",new JSONArray()).put("snoozes",0).put("done",false);
                JSONArray all=Store.tasks(this);
                all.put(task);
                Store.save(this,all);
                Store.prefs(this).edit().putLong("nextId",id).apply();
                boolean exact=Alarms.schedule(this,task);
                currentPage=0;
                settingsCategory=-1;
                paintNav(0,false);
                render();
                if(!exact)error("精确提醒未获系统授权，系统可能延迟提醒");
                else toast("已创建「"+name+"」· 首次复习 "+when(task.optLong("due")));
                if(!Notifications.enabled(this))showPermissionGuide();
                return true;
            }catch(Exception e){showInline(failure,"创建失败："+e.getMessage());return false;}
        });
        sheet.show();
        title.requestFocus();
        if(sheet.dialog.getWindow()!=null)sheet.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void confirmComplete(long id){
        JSONObject task=Store.find(Store.tasks(this),id);
        if(task==null)return;
        if(Store.prefs(this).getBoolean("skipEmptyTaskConfirm",false)){complete(id,task.optInt("stage"));return;}
        String message="下一次复习会从现在重新计算。"+(task.optLong("due")>System.currentTimeMillis()?"\n这次还没到计划时间，仍要标记完成吗？":"");
        CheckBox dontRemind=checkBox("以后直接完成空单词任务，不再询问");
        new Sheet(this).title("完成本次复习？").message(message).content(dontRemind)
                .secondary("继续复习",null)
                .primary("完成",()->{
                    if(dontRemind.isChecked())Store.prefs(this).edit().putBoolean("skipEmptyTaskConfirm",true).apply();
                    complete(id,task.optInt("stage"));
                })
                .show();
    }

    private void complete(long id,int expectedStage){
        try{
            JSONArray all=Store.tasks(this);
            JSONObject task=Store.find(all,id);
            if(task==null||task.optBoolean("done")||task.optInt("stage")!=expectedStage)return;
            long now=System.currentTimeMillis();
            int nextStage=expectedStage+1;
            task.getJSONArray("history").put(new JSONObject().put("stage",nextStage).put("at",now));
            task.put("stage",nextStage).put("snoozes",0);
            Alarms.cancel(this,id);
            if(nextStage>=task.getJSONArray("intervals").length())task.put("done",true);
            else task.put("due",Schedule.due(now,task.getJSONArray("intervals").getLong(nextStage),Store.windows(task.optString("windows"))));
            Store.save(this,all);
            interaction.confirm(root);
            if(!task.optBoolean("done"))Alarms.schedule(this,task);
            renderAtScroll();
            toast(task.optBoolean("done")?"已完成全部 "+nextStage+" 轮":"已完成第 "+nextStage+" 轮 · 下次 "+when(task.optLong("due")));
        }catch(Exception e){error("保存失败："+e.getMessage());}
    }

    /** Same target computation as before; shared by the option preview and the actual snooze. */
    private long snoozeTarget(int which,List<int[]> windows,long now){
        long requested=now+(which==0?15:which==1?30:which==2?60:1)*60000L;
        if(which==3&&!windows.isEmpty()){
            requested=now+60000L;
            while(requested<now+2*86400000L&&Schedule.nextWindow(requested,windows)==requested)requested+=60000L;
        }
        return Schedule.nextWindow(requested,windows);
    }

    private void snooze(long id){
        JSONObject task=Store.find(Store.tasks(this),id);
        if(task==null)return;
        if(task.optInt("snoozes")>=2){toast("本次已延期两次，请完成复习");return;}
        String[] options={"15 分钟后","30 分钟后","1 小时后","下一个允许时间段"};
        List<int[]> windows=Store.windows(task.optString("windows"));
        long now=System.currentTimeMillis();
        Sheet sheet=new Sheet(this).title("稍后提醒").message("本次还可以延期 "+(2-task.optInt("snoozes"))+" 次");
        for(int i=0;i<options.length;i++){
            final int which=i;
            sheet.item(0,options[i],when(snoozeTarget(which,windows,now)),()->applySnooze(id,which));
        }
        sheet.secondary("取消",null).show();
    }

    private void applySnooze(long id,int which){
        try{
            JSONArray all=Store.tasks(this);
            JSONObject t=Store.find(all,id);
            if(t==null||t.optBoolean("done")||t.optInt("snoozes")>=2)return;
            long now=System.currentTimeMillis();
            List<int[]> windows=Store.windows(t.optString("windows"));
            long next=snoozeTarget(which,windows,now);
            t.put("due",next).put("snoozes",t.optInt("snoozes")+1);
            Store.save(this,all);
            Alarms.cancel(this,id);
            Alarms.schedule(this,t);
            renderAtScroll();
            toast("已安排到 "+date(next));
        }catch(Exception e){error("延期失败："+e.getMessage());}
    }

    private void details(long id){
        JSONObject task=Store.find(Store.tasks(this),id);
        if(task==null)return;
        int stage=task.optInt("stage"),total=totalRounds(task);
        boolean done=task.optBoolean("done");
        boolean overdue=!done&&task.optLong("due")<=System.currentTimeMillis();
        String progress=done?"已完成全部 "+total+" 轮":"第 "+(stage+1)+" 轮 · 下次 "+when(task.optLong("due"));

        if(wordCount(task)>0){
            Sheet sheet=new Sheet(this).title(task.optString("title")).message(wordCount(task)+" 个单词 · "+progress);
            LinearLayout dots=ui.col();
            dots.setPadding(0,dp(Theme.SPACE_4),0,dp(Theme.SPACE_8));
            dots.addView(new StageDots(this,total,Math.min(stage,total),overdue),new LinearLayout.LayoutParams(-2,-2));
            sheet.content(dots);
            sheet.item(R.drawable.ic_book_open,"计划复习",()->openReview(id,false));
            sheet.item(R.drawable.ic_repeat,"任意复习（不计轮次）",()->openReview(id,true));
            sheet.item(R.drawable.ic_list_checks,"词性义项练习",()->Motion.open(this,new Intent(this,SenseQuizActivity.class).putExtra("taskId",id)));
            sheet.item(R.drawable.ic_pencil,"校对单词卡",()->Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("taskId",id)));
            sheet.item(R.drawable.ic_target,"错词义项练习",()->Motion.open(this,new Intent(this,SenseQuizActivity.class).putExtra("taskId",id).putExtra("wrongOnly",true)));
            if(!done)sheet.item(R.drawable.ic_clock,"稍后提醒",()->snooze(id));
            sheet.item(R.drawable.ic_calendar_days,"查看复习记录",()->history(task));
            sheet.dangerItem(R.drawable.ic_trash_2,"删除任务",()->confirmDelete(id));
            sheet.show();
            return;
        }

        Sheet sheet=new Sheet(this).title(task.optString("title"));
        if(!task.optString("description").isEmpty())sheet.message(task.optString("description"));
        LinearLayout body=ui.col();
        body.addView(new StageDots(this,total,Math.min(stage,total),overdue),new LinearLayout.LayoutParams(-2,-2));
        infoRow(body,"进度",progress);
        infoRow(body,"创建时间",date(task.optLong("created")));
        infoRow(body,"提醒时段",task.optString("windows").isEmpty()?"全天":task.optString("windows"));
        TextView label=ui.sectionLabel("复习记录");
        body.addView(label,margins(-1,-2,0,Theme.SPACE_20,0,Theme.SPACE_4));
        body.addView(historyList(task.optJSONArray("history"),"暂无记录"),WordUi.lp());
        sheet.content(body);
        if(!done){
            sheet.item(R.drawable.ic_circle_check,"完成本次复习",()->confirmComplete(id));
            sheet.item(R.drawable.ic_clock,"稍后提醒",()->snooze(id));
        }
        sheet.secondary("关闭",null);
        sheet.danger("删除任务",()->Sheet.confirm(this,"删除任务？","删除后无法恢复。到期提醒也会一并取消。","保留","删除",true,()->delete(id)));
        sheet.show();
    }

    private void infoRow(LinearLayout parent,String label,String value){
        LinearLayout row=ui.row();
        row.setMinimumHeight(dp(36));
        TextView l=ui.type(label,Theme.T_CALLOUT);
        l.setTextColor(Theme.INK_3);
        row.addView(l,new LinearLayout.LayoutParams(dp(76),-2));
        TextView v=ui.type(value,Theme.T_CALLOUT);
        v.setTextColor(Theme.INK);
        row.addView(v,WordUi.weight());
        parent.addView(row,margins(-1,-2,0,Theme.SPACE_8,0,0));
    }

    private View historyList(JSONArray history,String empty){
        LinearLayout list=ui.col();
        if(history==null||history.length()==0){
            TextView none=ui.type(empty,Theme.T_CALLOUT);
            none.setTextColor(Theme.INK_3);
            none.setPadding(0,dp(Theme.SPACE_8),0,dp(Theme.SPACE_8));
            list.addView(none);
            return list;
        }
        for(int i=0;i<history.length();i++){
            JSONObject item=history.optJSONObject(i);
            if(item==null)continue;
            if(list.getChildCount()>0){
                View line=new View(this);
                line.setBackgroundColor(Theme.HAIRLINE);
                list.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,dp(.75f))));
            }
            LinearLayout row=ui.row();
            row.setMinimumHeight(dp(44));
            TextView round=ui.type("第 "+item.optInt("stage")+" 轮",Theme.T_BODY);
            row.addView(round,WordUi.weight());
            TextView at=ui.type(date(item.optLong("at")),Theme.T_CALLOUT);
            at.setFontFeatureSettings("tnum");
            row.addView(at);
            list.addView(row,WordUi.lp());
        }
        return list;
    }

    private void confirmDelete(long id){
        Sheet.confirm(this,"删除任务？","单词和复习记录将被删除，不能恢复。","保留","删除",true,()->delete(id));
    }

    private void history(JSONObject task){
        new Sheet(this).title("复习记录").message(task.optString("title"))
                .content(historyList(task.optJSONArray("history"),"暂无复习记录"))
                .secondary("关闭",null)
                .show();
    }

    private void delete(long id){
        JSONArray old=Store.tasks(this),out=new JSONArray();
        String title="";
        for(int i=0;i<old.length();i++){
            JSONObject t=old.optJSONObject(i);
            if(t==null)continue;
            if(t.optLong("id")!=id)out.put(t);
            else title=t.optString("title");
        }
        try{
            Store.save(this,out);
        }catch(Exception e){
            error("删除失败："+e.getMessage());
            return;
        }
        Alarms.cancel(this,id);
        renderAtScroll();
        toast(title.isEmpty()?"任务已删除":"已删除「"+title+"」");
    }

    private void confirmExit(){
        if(Store.prefs(this).getBoolean("skipSettingsExitPrompt",false)){finish();return;}
        CheckBox dontRemind=checkBox("不再提示");
        new Sheet(this).title("保存设置并退出？").message("设置分类离开时已自动保存；退出前会再次写入设置。").content(dontRemind)
                .secondary("继续设置",null)
                .primary("保存并退出",()->{
                    if(!Store.prefs(this).edit().putBoolean("skipSettingsExitPrompt",dontRemind.isChecked()).commit()){error("保存失败，仍留在应用中");return;}
                    finish();
                })
                .show();
    }
}
