package com.example.reviewreminder;

import java.util.ArrayList;
import java.util.List;

public final class IntervalPlan {
    private IntervalPlan() {}
    public static List<Long> parse(String csv) {
        List<Long> result=new ArrayList<>();
        if(csv.trim().isEmpty())return result;
        for(String value:csv.split(",",-1)) {
            long n;
            try { n=Long.parseLong(value.trim()); } catch(NumberFormatException e) { throw new IllegalArgumentException("请输入有效的间隔数字"); }
            if(n<1||n>5256000)throw new IllegalArgumentException("间隔须在 1 分钟到 3650 天之间");
            result.add(n);
        }
        if(result.size()>100)throw new IllegalArgumentException("最多添加 100 个自定义间隔");
        return result;
    }
    public static List<Long> active(String base,boolean optional,String extra) {
        List<Long> defaults=parse(base);
        if(defaults.size()!=8)throw new IllegalArgumentException("默认阶段数据不完整");
        List<Long> out=new ArrayList<>(defaults.subList(0,optional?8:7));out.addAll(parse(extra));return out;
    }
    public static String pack(List<Long> values) {
        StringBuilder out=new StringBuilder();for(long n:values){if(out.length()>0)out.append(',');out.append(n);}return out.toString();
    }
    public static String label(long minutes) {
        if(minutes%1440==0)return (minutes/1440)+" 天";
        if(minutes%60==0)return (minutes/60)+" 小时";
        return minutes+" 分钟";
    }
}
