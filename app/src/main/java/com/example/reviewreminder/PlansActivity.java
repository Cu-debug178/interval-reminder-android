package com.example.reviewreminder;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全部计划 — Bookshelf & list view of every learning plan (进行中 / 未开始 / 已完成 / 已归档).
 * Plans can be browsed, searched, filtered, and opened freely without scheduling today's review.
 */
public final class PlansActivity extends ScreenActivity {
    private static final String[] FILTER_LABELS={"全部","进行中","未开始","已完成","已归档"};
    private WordUi ui;
    private int filter;
    private boolean listView;
    private String searchQuery="";
    private LinearLayout listHost;
    private HorizontalScrollView chipScroll;
    private TextView[] filterChips=new TextView[FILTER_LABELS.length];

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        if(state!=null){
            filter=state.getInt("filter",0);
            listView=state.getBoolean("listView",false);
            searchQuery=state.getString("searchQuery","");
        }
        build();
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putInt("filter",filter);
        out.putBoolean("listView",listView);
        out.putString("searchQuery",searchQuery);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume(){
        super.onResume();
        if(ui!=null&&listHost!=null)refresh();
    }

    private int dp(float n){return Theme.dp(this,n);}

    private void build(){
        ui.start("全部计划",R.drawable.ic_plus,"录词",v->Motion.open(this,new Intent(this,ImportActivity.class)));
        // Add view-mode toggle button (书架 / 列表) right beside the trailing "+" button.
        ImageButton modeBtn=ui.iconButton(listView?R.drawable.ic_layers:R.drawable.ic_list,listView?"切换书架视图":"切换列表视图",v->{
            listView=!listView;
            build();
        });
        if(ui.bar!=null&&ui.bar.getChildCount()>=2){
            ui.bar.addView(modeBtn,ui.bar.getChildCount()-1,new LinearLayout.LayoutParams(dp(Theme.TOUCH),dp(Theme.TOUCH)));
        }

        LinearLayout body=ui.body;

        // Overview stats strip.
        JSONArray allTasks=Store.tasks(this);
        int totalPlans=allTasks.length(),totalWords=0,activeCount=0,doneCount=0;
        for(int i=0;i<allTasks.length();i++){
            JSONObject t=allTasks.optJSONObject(i);
            if(t==null)continue;
            JSONArray w=t.optJSONArray("words");
            if(w!=null)totalWords+=w.length();
            String st=status(t);
            if("进行中".equals(st))activeCount++;
            else if("已完成".equals(st))doneCount++;
        }

        LinearLayout summaryCard=ui.card();
        summaryCard.setOrientation(LinearLayout.HORIZONTAL);
        summaryCard.setPadding(dp(Theme.SPACE_8),dp(Theme.SPACE_16),dp(Theme.SPACE_8),dp(Theme.SPACE_16));
        statCol(summaryCard,String.valueOf(totalPlans),"全部计划",Theme.INK);
        statDiv(summaryCard);
        statCol(summaryCard,String.valueOf(totalWords),"累计词卡",Theme.PRIMARY);
        statDiv(summaryCard);
        statCol(summaryCard,String.valueOf(activeCount),"进行中",Theme.INK);
        statDiv(summaryCard);
        statCol(summaryCard,String.valueOf(doneCount),"已完成",Theme.INK_2);
        body.addView(summaryCard,ui.lpMargin(Theme.SPACE_4,Theme.SPACE_16));

        // Search input.
        EditText search=WordUi.input(this,"搜索计划名称或单词…",searchQuery);
        search.setSingleLine(true);
        search.setMinHeight(dp(44));
        search.setMinimumHeight(dp(44));
        search.setPadding(dp(14),dp(8),dp(14),dp(8));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){searchQuery=s.toString();refresh();}
            public void afterTextChanged(Editable s){}
        });
        body.addView(search,ui.lpMargin(0,Theme.SPACE_12));

        // Filter chips strip (Paper & Ink style — no raw Android Spinner).
        chipScroll=new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow=ui.row();
        for(int i=0;i<FILTER_LABELS.length;i++){
            final int idx=i;
            TextView chip=ui.chip(FILTER_LABELS[i],Theme.SUNK,Theme.INK_2);
            chip.setPadding(dp(14),dp(7),dp(14),dp(7));
            chip.setClickable(true);
            chip.setFocusable(true);
            Motion.press(chip,.95f);
            chip.setOnClickListener(v->{
                if(filter==idx)return;
                filter=idx;
                Feedback.tick(v);
                refresh();
            });
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
            if(i>0)cp.leftMargin=dp(Theme.SPACE_8);
            chipRow.addView(chip,cp);
            filterChips[i]=chip;
        }
        chipScroll.addView(chipRow);
        body.addView(chipScroll,ui.lpMargin(0,Theme.SPACE_16));

        listHost=ui.col();
        body.addView(listHost,WordUi.lp());
        refresh();
    }

    private void refresh(){
        if(listHost==null)return;
        listHost.removeAllViews();
        JSONArray tasks=Store.tasks(this);

        // Update filter chip counts & styles.
        int[] counts=new int[FILTER_LABELS.length];
        for(int i=0;i<tasks.length();i++){
            JSONObject t=tasks.optJSONObject(i);
            if(t==null)continue;
            counts[0]++;
            String st=status(t);
            if("进行中".equals(st))counts[1]++;
            else if("未开始".equals(st))counts[2]++;
            else if("已完成".equals(st))counts[3]++;
            else if("已归档".equals(st))counts[4]++;
        }
        for(int i=0;i<FILTER_LABELS.length;i++){
            TextView chip=filterChips[i];
            if(chip==null)continue;
            boolean on=filter==i;
            chip.setText(FILTER_LABELS[i]+(counts[i]>0?" "+counts[i]:""));
            chip.setBackground(Theme.shape(this,on?Theme.PRIMARY:Theme.SUNK,12));
            chip.setTextColor(on?Color.WHITE:Theme.INK_2);
            Theme.applyRipple(chip,chip.getBackground(),on?Theme.RIPPLE_DARK:Theme.RIPPLE_LIGHT);
        }

        // Filter tasks by category & search query.
        String q=searchQuery==null?"":searchQuery.trim().toLowerCase(Locale.ROOT);
        List<JSONObject> matched=new ArrayList<>();
        for(int i=0;i<tasks.length();i++){
            JSONObject t=tasks.optJSONObject(i);
            if(t!=null&&matchesFilter(t)&&matchesQuery(t,q))matched.add(t);
        }

        if(matched.isEmpty()){
            String title=tasks.length()==0?"还没有学习计划":(q.isEmpty()?"这个分类下还没有计划":"没有找到匹配的计划");
            String detail=tasks.length()==0?"通过拍照识词、粘贴词表或新建任务来建立你的第一本计划书。":"换一个分类或搜索关键词看看。";
            listHost.addView(ui.empty(R.drawable.ic_book_open,title,detail,tasks.length()==0?"去录词":null,
                tasks.length()==0?v->Motion.open(this,new Intent(this,ImportActivity.class)):null),WordUi.lp());
            return;
        }

        java.util.Set<Long> mappedTasks=WordRelations.tasksWithRelations(this);
        if(listView){
            LinearLayout group=ui.group();
            for(JSONObject task:matched)addListRow(group,task);
            listHost.addView(group,WordUi.lp());
        }else{
            // 2-column bookshelf grid with crisp uniform book-cover proportions.
            int bookHeight=dp(184);
            for(int i=0;i<matched.size();i+=2){
                LinearLayout row=ui.row();
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams leftLp=new LinearLayout.LayoutParams(0,bookHeight,1);
                leftLp.rightMargin=dp(Theme.SPACE_6);
                row.addView(bookCard(matched.get(i),mappedTasks.contains(matched.get(i).optLong("id"))),leftLp);
                if(i+1<matched.size()){
                    LinearLayout.LayoutParams rightLp=new LinearLayout.LayoutParams(0,bookHeight,1);
                    rightLp.leftMargin=dp(Theme.SPACE_6);
                    row.addView(bookCard(matched.get(i+1),mappedTasks.contains(matched.get(i+1).optLong("id"))),rightLp);
                }else{
                    LinearLayout.LayoutParams spacerLp=new LinearLayout.LayoutParams(0,bookHeight,1);
                    spacerLp.leftMargin=dp(Theme.SPACE_6);
                    row.addView(new View(this),spacerLp);
                }
                listHost.addView(row,ui.lpMargin(0,Theme.SPACE_12));
            }
        }
        ui.note(listHost,"轻点任意计划书可查看完整词表、学习进度、词汇关系与历史记录。");
    }

    /** Tactile bookshelf book cover card with colored left spine bar. */
    private View bookCard(JSONObject task,boolean hasMap){
        long id=task.optLong("id");
        String st=status(task);
        int spineColor=spineColor(st);
        int chipFill=chipFill(st);
        int chipInk=chipInk(st);
        int total=task.optJSONArray("intervals")==null?0:task.optJSONArray("intervals").length();
        int stage=Math.min(task.optInt("stage"),total);
        int wordCount=task.optJSONArray("words")==null?0:task.optJSONArray("words").length();
        float ratio=total==0?0f:Math.min(1f,stage/(float)total);
        int percent=Math.round(ratio*100f);

        LinearLayout shell=ui.row();
        shell.setGravity(Gravity.FILL_VERTICAL);
        shell.setBackground(Theme.shape(this,Theme.SURFACE,Theme.RADIUS_CARD,Theme.HAIRLINE,1));
        shell.setClipToOutline(true);
        shell.setMinimumHeight(dp(168));

        // Book spine stripe on the left edge.
        View spine=new View(this);
        spine.setBackgroundColor(spineColor);
        shell.addView(spine,new LinearLayout.LayoutParams(dp(5),-1));

        LinearLayout inner=ui.col();
        inner.setPadding(dp(14),dp(14),dp(14),dp(14));

        // Top row: status chip + optional relation-map badge.
        LinearLayout top=ui.row();
        top.addView(ui.chip(st,chipFill,chipInk));
        if(hasMap&&wordCount>0){
            TextView mapTag=ui.chip("关系图",Theme.PRIMARY_SOFT,Theme.PRIMARY);
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
            mp.leftMargin=dp(4);
            top.addView(mapTag,mp);
        }
        inner.addView(top,WordUi.lp());

        // Book title.
        TextView title=ui.type(task.optString("title","未命名计划"),Theme.T_HEADLINE);
        title.setTextSize(16);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        inner.addView(title,ui.lpMargin(Theme.SPACE_12,0));

        // Subtitle: words + mastery.
        String meta=wordCount>0
            ?(wordCount+" 词 · 掌握 "+String.format(Locale.CHINA,"%.1f",Mastery.average(task.optJSONArray("words")))+"/5")
            :"无单词提醒任务";
        TextView metaView=ui.type(meta,Theme.T_CAPTION);
        metaView.setTextColor(Theme.INK_2);
        metaView.setMaxLines(1);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        inner.addView(metaView,ui.lpMargin(Theme.SPACE_6,0));

        // Spacer so progress bar aligns cleanly along the bottom of every book cover.
        inner.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));

        // Round + due info.
        LinearLayout foot=ui.row();
        TextView round=ui.type("第 "+stage+"/"+total+" 轮",Theme.T_CAPTION);
        round.setFontFeatureSettings("tnum");
        foot.addView(round,WordUi.weight());
        TextView dueText=ui.type(dueCaption(task,st),Theme.T_CAPTION);
        dueText.setTextColor("进行中".equals(st)&&task.optLong("due")<=System.currentTimeMillis()?Theme.DANGER:Theme.INK_3);
        foot.addView(dueText);
        inner.addView(foot,ui.lpMargin(Theme.SPACE_12,Theme.SPACE_6));

        ProgressLine line=new ProgressLine(this);
        line.set(ratio,false);
        inner.addView(line,WordUi.lp());

        shell.addView(inner,new LinearLayout.LayoutParams(0,-1,1));
        shell.setContentDescription(task.optString("title")+"，"+st+"，"+wordCount+"个单词，进度"+percent+"%");
        shell.setClickable(true);
        shell.setFocusable(true);
        Theme.applyRipple(shell,shell.getBackground(),Theme.RIPPLE_LIGHT);
        Motion.press(shell,.975f);
        shell.setOnClickListener(v->Motion.open(this,new Intent(this,PlanDetailActivity.class).putExtra("taskId",id)));
        return shell;
    }

    private void addListRow(LinearLayout group,JSONObject task){
        long id=task.optLong("id");
        String st=status(task);
        int total=task.optJSONArray("intervals")==null?0:task.optJSONArray("intervals").length();
        int stage=Math.min(task.optInt("stage"),total);
        int words=task.optJSONArray("words")==null?0:task.optJSONArray("words").length();
        String sub=words+" 词 · 第 "+stage+"/"+total+" 轮 · 掌握 "+String.format(Locale.CHINA,"%.1f",Mastery.average(task.optJSONArray("words")))+"/5";
        ui.item(group,R.drawable.ic_book_open,task.optString("title"),sub,st,true,
            v->Motion.open(this,new Intent(this,PlanDetailActivity.class).putExtra("taskId",id)));
    }

    private void statCol(LinearLayout parent,String value,String label,int color){
        LinearLayout col=ui.col();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView n=ui.type(value,Theme.T_HEADLINE);
        n.setTextSize(20);
        n.setTextColor(color);
        n.setFontFeatureSettings("tnum");
        n.setGravity(Gravity.CENTER);
        col.addView(n);
        TextView l=ui.type(label,Theme.T_CAPTION);
        l.setGravity(Gravity.CENTER);
        col.addView(l,ui.lpMargin(2,0));
        parent.addView(col,WordUi.weight());
    }

    private void statDiv(LinearLayout parent){
        View v=new View(this);
        v.setBackgroundColor(Theme.HAIRLINE);
        parent.addView(v,new LinearLayout.LayoutParams(Math.max(1,dp(.75f)),dp(26)));
    }

    private boolean matchesFilter(JSONObject task){
        String st=status(task);
        if(filter==0)return true;
        if(filter==1)return "进行中".equals(st);
        if(filter==2)return "未开始".equals(st);
        if(filter==3)return "已完成".equals(st);
        return "已归档".equals(st);
    }

    private static boolean matchesQuery(JSONObject task,String q){
        if(q.isEmpty())return true;
        if(task.optString("title").toLowerCase(Locale.ROOT).contains(q))return true;
        if(task.optString("description").toLowerCase(Locale.ROOT).contains(q))return true;
        JSONArray words=task.optJSONArray("words");
        for(int i=0;words!=null&&i<words.length();i++){
            JSONObject w=words.optJSONObject(i);
            if(w==null)continue;
            if(w.optString("english").toLowerCase(Locale.ROOT).contains(q)||w.optString("chinese").toLowerCase(Locale.ROOT).contains(q))return true;
        }
        return false;
    }

    private static String dueCaption(JSONObject task,String status){
        if("已完成".equals(status))return "已完结";
        if("已归档".equals(status))return "已归档";
        long due=task.optLong("due",0);
        if(due<=0)return "";
        long now=System.currentTimeMillis();
        if(due<=now)return "已到期";
        Calendar today=Calendar.getInstance(),d=Calendar.getInstance();
        d.setTimeInMillis(due);
        if(today.get(Calendar.YEAR)==d.get(Calendar.YEAR)&&today.get(Calendar.DAY_OF_YEAR)==d.get(Calendar.DAY_OF_YEAR)){
            return new SimpleDateFormat("今天 HH:mm",Locale.CHINA).format(new Date(due));
        }
        return new SimpleDateFormat("M/d HH:mm",Locale.CHINA).format(new Date(due));
    }

    private static int spineColor(String st){
        if("进行中".equals(st))return Theme.PRIMARY;
        if("未开始".equals(st))return Theme.WARN;
        if("已完成".equals(st))return Theme.PRIMARY_TINT;
        return Theme.INK_3;
    }

    private static int chipFill(String st){
        if("进行中".equals(st))return Theme.PRIMARY_SOFT;
        if("未开始".equals(st))return Theme.WARN_SOFT;
        return Theme.SUNK;
    }

    private static int chipInk(String st){
        if("进行中".equals(st))return Theme.PRIMARY;
        if("未开始".equals(st))return Theme.WARN;
        return Theme.INK_2;
    }

    static String status(JSONObject task){
        if(task.optBoolean("archived"))return "已归档";
        if(task.optBoolean("done"))return "已完成";
        return task.optInt("stage")==0&&(task.optJSONArray("history")==null||task.optJSONArray("history").length()==0)?"未开始":"进行中";
    }
}
