package com.example.reviewreminder;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TimeWindowPicker {
    private static final class Range { int start,end; Range(int start,int end){this.start=start;this.end=end;} }
    private final Activity activity;
    private final LinearLayout root,rows;
    private final List<Range> ranges=new ArrayList<>();
    private Runnable changed;

    TimeWindowPicker(Activity activity,String value,Runnable changed){
        this.activity=activity;this.changed=changed;
        for(int[] range:Store.windows(value))ranges.add(new Range(range[0],range[1]));
        root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);root.addView(rows);
        Button add=new Button(activity);add.setText("添加提醒时间段");add.setAllCaps(false);add.setTextSize(14);add.setMinHeight(dp(52));add.setMinimumHeight(dp(52));
        add.setTextColor(WordUi.GREEN);add.setBackground(background(0xFFDEF2EA));UiIcons.apply(add,WordUi.GREEN);add.setOnClickListener(v->{ranges.add(new Range(9*60,10*60));render();notifyChanged();});Motion.press(add);root.addView(add);
        render();
    }
    void setOnChanged(Runnable changed){this.changed=changed;}
    View view(){return root;}
    String value(){String value=draftValue();Store.windows(value);for(Range range:ranges)if(range.start==range.end)throw new IllegalArgumentException("开始时间和结束时间不能相同");return value;}
    String draftValue(){
        StringBuilder packed=new StringBuilder();
        for(Range range:ranges){if(packed.length()>0)packed.append(',');packed.append(format(range.start)).append('-').append(format(range.end));}
        return packed.toString();
    }
    boolean isEmpty(){return ranges.isEmpty();}
    private void render(){
        rows.removeAllViews();
        for(int i=0;i<ranges.size();i++){
            final Range range=ranges.get(i);LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(4),0,dp(4));
            TextView label=new TextView(activity);label.setText("时段 "+(i+1));label.setTextSize(14);label.setTextColor(WordUi.MUTED);row.addView(label,new LinearLayout.LayoutParams(0,dp(50),1));
            Button start=timeButton(range,range.start,true);Button end=timeButton(range,range.end,false);row.addView(start,new LinearLayout.LayoutParams(dp(92),dp(50)));
            TextView to=new TextView(activity);to.setText("  至  ");to.setTextSize(13);to.setTextColor(WordUi.MUTED);row.addView(to);
            row.addView(end,new LinearLayout.LayoutParams(dp(92),dp(50)));
            Button remove=new Button(activity);remove.setText("−");remove.setTextSize(22);remove.setTextColor(WordUi.GREEN);remove.setContentDescription("删除时段 "+(i+1));remove.setAllCaps(false);remove.setMinWidth(dp(48));remove.setMinimumWidth(dp(48));remove.setMinHeight(dp(50));remove.setBackground(background(Color.WHITE));
            UiIcons.apply(remove,WordUi.GREEN);remove.setText("");remove.setOnClickListener(v->{ranges.remove(range);render();notifyChanged();});Motion.press(remove);row.addView(remove,new LinearLayout.LayoutParams(dp(52),dp(50)));rows.addView(row);
        }
        if(ranges.isEmpty()){TextView hint=new TextView(activity);hint.setText("尚未添加时间段。全天提醒时无需添加。\n例如：早上 07:30 至 08:30");hint.setTextSize(13);hint.setTextColor(WordUi.MUTED);hint.setPadding(0,dp(8),0,dp(12));rows.addView(hint);}
    }
    private Button timeButton(Range range,int minutes,boolean start){Button b=new Button(activity);b.setText(format(minutes));b.setTextSize(15);b.setTextColor(WordUi.INK);b.setAllCaps(false);b.setMinHeight(dp(50));b.setBackground(background(Color.WHITE));b.setTag(range);b.setContentDescription((start?"开始时间 ":"结束时间 ")+format(minutes));b.setOnClickListener(v->new TimePickerDialog(activity,(picker,hour,minute)->{int chosen=hour*60+minute;if(start)range.start=chosen;else range.end=chosen;render();notifyChanged();},minutes/60,minutes%60,true).show());return b;}
    private void notifyChanged(){if(changed!=null)changed.run();}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));d.setStroke(dp(1),0xFFE2ECE7);return d;}
    private int dp(float n){return (int)(activity.getResources().getDisplayMetrics().density*n+.5f);}
    private static String format(int minutes){return String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);}
}
