package com.example.reviewreminder;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Daily reminder windows, edited in place.
 *
 * One white group holds one row per window:  [09:00] – [10:00]  ·  次日        ✕
 * Each time is a quiet sunk pill that opens the system time picker. Rows are inserted and
 * removed individually so only the affected row animates; a tonal "添加提醒时间段" sits below.
 *
 * Public API (constructor, view(), value(), draftValue(), isEmpty(), setOnChanged()) and the
 * validation in value() are unchanged. onSurface() adapts the colours for a white sheet.
 */
final class TimeWindowPicker {
    private static final class Range {
        int start,end;
        Range(int start,int end){this.start=start;this.end=end;}
    }

    /** Views belonging to one window row, kept so a time change updates text in place. */
    private static final class RowViews {
        Range range;
        LinearLayout root;
        View divider;
        Button start,end;
        TextView note;
    }

    private final Activity activity;
    private final WordUi ui;
    private final LinearLayout root,rows;
    private final TextView emptyHint;
    private final List<Range> ranges=new ArrayList<>();
    private final List<RowViews> rowViews=new ArrayList<>();
    private Runnable changed;
    /** True when hosted on a white surface (a sheet): the group turns paper and the pills turn white. */
    private boolean onSurface;

    TimeWindowPicker(Activity activity,String value,Runnable changed){
        this.activity=activity;
        this.changed=changed;
        this.ui=new WordUi(activity);
        for(int[] range:Store.windows(value))ranges.add(new Range(range[0],range[1]));

        root=ui.col();

        rows=ui.group();
        emptyHint=ui.type("尚未添加时间段，全天都可以提醒。\n例如：早上 07:30 – 08:30",Theme.T_CALLOUT);
        emptyHint.setTextColor(Theme.INK_3);
        emptyHint.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_16),dp(Theme.ROW_PAD),dp(Theme.SPACE_16));
        rows.addView(emptyHint,WordUi.lp());
        root.addView(rows,WordUi.lp());

        Button add=ui.tonalButton("添加提醒时间段",R.drawable.ic_plus,v->{
            Motion.layout(root);
            Range range=new Range(9*60,10*60);
            ranges.add(range);
            addRow(range);
            syncChrome();
            Feedback.tick(v);
            notifyChanged();
        });
        LinearLayout.LayoutParams addLp=WordUi.lp();
        addLp.topMargin=dp(Theme.SPACE_12);
        root.addView(add,addLp);

        for(Range range:ranges)addRow(range);
        syncChrome();
    }

    void setOnChanged(Runnable changed){this.changed=changed;}

    /**
     * For use on a white surface such as a sheet: the white group would vanish into it, so the
     * group takes the paper tone and the time pills become white. The settings page keeps the
     * default (white group, sunk pills).
     */
    TimeWindowPicker onSurface(){
        onSurface=true;
        rows.setBackground(Theme.shape(activity,Theme.BG,Theme.RADIUS_GROUP));
        for(RowViews holder:rowViews){
            paintPill(holder.start);
            paintPill(holder.end);
        }
        return this;
    }

    View view(){return root;}

    String value(){
        String value=draftValue();
        Store.windows(value);
        for(Range range:ranges)if(range.start==range.end)throw new IllegalArgumentException("开始时间和结束时间不能相同");
        return value;
    }

    String draftValue(){
        StringBuilder packed=new StringBuilder();
        for(Range range:ranges){
            if(packed.length()>0)packed.append(',');
            packed.append(format(range.start)).append('-').append(format(range.end));
        }
        return packed.toString();
    }

    boolean isEmpty(){return ranges.isEmpty();}

    // ── Rows ──────────────────────────────────────────────────────────────────

    private void addRow(Range range){
        RowViews holder=new RowViews();
        holder.range=range;

        LinearLayout container=ui.col();
        holder.root=container;

        View divider=new View(activity);
        divider.setBackgroundColor(Theme.HAIRLINE);
        LinearLayout.LayoutParams dividerLp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.75f)));
        dividerLp.leftMargin=dp(Theme.ROW_PAD);
        container.addView(divider,dividerLp);
        holder.divider=divider;

        LinearLayout line=ui.row();
        line.setMinimumHeight(dp(Theme.ROW_HEIGHT+8));
        line.setPadding(dp(Theme.SPACE_12),dp(Theme.SPACE_8),dp(Theme.SPACE_4),dp(Theme.SPACE_8));

        holder.start=timePill(holder,true);
        line.addView(holder.start,new LinearLayout.LayoutParams(-2,dp(40)));

        TextView dash=ui.type("–",Theme.T_BODY);
        dash.setTextColor(Theme.INK_3);
        dash.setGravity(Gravity.CENTER);
        dash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(dash,new LinearLayout.LayoutParams(dp(28),-2));

        holder.end=timePill(holder,false);
        line.addView(holder.end,new LinearLayout.LayoutParams(-2,dp(40)));

        holder.note=ui.type("",Theme.T_CAPTION);
        LinearLayout.LayoutParams noteLp=new LinearLayout.LayoutParams(0,-2,1);
        noteLp.leftMargin=dp(Theme.SPACE_12);
        line.addView(holder.note,noteLp);

        ImageButton remove=ui.iconButton(R.drawable.ic_x,"删除时段",v->{
            int index=rowViews.indexOf(holder);
            if(index<0)return;
            Motion.layout(root);
            ranges.remove(holder.range);
            rowViews.remove(index);
            rows.removeView(holder.root);
            syncChrome();
            Feedback.tick(v);
            notifyChanged();
        });
        remove.setImageTintList(android.content.res.ColorStateList.valueOf(Theme.INK_3));
        line.addView(remove,new LinearLayout.LayoutParams(dp(Theme.TOUCH),dp(Theme.TOUCH)));

        container.addView(line,WordUi.lp());
        rows.addView(container,WordUi.lp());
        rowViews.add(holder);
        bind(holder);
    }

    /** A sunk, rounded time chip; Medium 16sp with tabular figures so times align row to row. */
    private Button timePill(RowViews holder,boolean start){
        Button b=new Button(activity);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Theme.MEDIUM);
        b.setTextColor(Theme.INK);
        b.setFontFeatureSettings("tnum");
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(14),0,dp(14),0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setTag(holder.range);
        paintPill(b);
        b.setOnClickListener(v->{
            Range range=holder.range;
            int minutes=start?range.start:range.end;
            new TimePickerDialog(activity,(picker,hour,minute)->{
                int chosen=hour*60+minute;
                if(start)range.start=chosen;
                else range.end=chosen;
                bind(holder);
                notifyChanged();
            },minutes/60,minutes%60,true).show();
        });
        Motion.press(b,.96f);
        return b;
    }

    /** Sunk pill on the white group; white pill on the paper group used inside sheets. */
    private void paintPill(Button b){
        Theme.applyRipple(b,Theme.shape(activity,onSurface?Theme.SURFACE:Theme.SUNK,Theme.RADIUS_FIELD),Theme.RIPPLE_LIGHT);
    }

    /** Writes the range into its row: both times, accessible labels and the quiet status note. */
    private void bind(RowViews holder){
        Range range=holder.range;
        holder.start.setText(format(range.start));
        holder.end.setText(format(range.end));
        holder.start.setContentDescription("开始时间 "+format(range.start));
        holder.end.setContentDescription("结束时间 "+format(range.end));
        if(range.start==range.end){
            holder.note.setText("首尾相同");
            holder.note.setTextColor(Theme.DANGER);
        }else if(range.end<range.start){
            holder.note.setText("至次日");
            holder.note.setTextColor(Theme.INK_3);
        }else{
            holder.note.setText(duration(range.end-range.start));
            holder.note.setTextColor(Theme.INK_3);
        }
    }

    /** First row has no divider; the empty hint shows only while there are no windows. */
    private void syncChrome(){
        for(int i=0;i<rowViews.size();i++)rowViews.get(i).divider.setVisibility(i==0?View.GONE:View.VISIBLE);
        emptyHint.setVisibility(ranges.isEmpty()?View.VISIBLE:View.GONE);
    }

    private void notifyChanged(){if(changed!=null)changed.run();}

    private int dp(float n){return Theme.dp(activity,n);}

    private static String duration(int minutes){
        int h=minutes/60,m=minutes%60;
        if(h==0)return m+" 分钟";
        if(m==0)return h+" 小时";
        return h+" 小时 "+m+" 分";
    }

    private static String format(int minutes){return String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);}
}
