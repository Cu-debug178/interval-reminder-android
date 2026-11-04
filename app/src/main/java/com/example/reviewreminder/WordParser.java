package com.example.reviewreminder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WordParser {
    public static final class Entry {
        public String english,chinese,example;
        public Entry(String en,String zh,String ex){english=en;chinese=zh;example=ex;}
    }
    private static final Pattern HEAD=Pattern.compile("^\\s*(?:[0-9*★]+[ .、:：-]*)?([A-Za-z][A-Za-z'’-]{1,48})(?:\\b|$)");
    private static final Pattern CHINESE=Pattern.compile("[\\u3400-\\u9fff]");
    private WordParser() {}
    public static List<Entry> parse(String text) {
        List<Entry> out=new ArrayList<>();
        for(String line:text.replace('\r','\n').split("\n")) {
            String clean=line.trim();if(clean.isEmpty())continue;
            String[] cells=clean.split("\\t",-1);
            if(cells.length>=2&&cells[0].trim().matches("[A-Za-z][A-Za-z '’-]*")) {
                out.add(new Entry(cells[0].trim(),cells[1].trim(),cells.length>2?cells[2].trim():""));continue;
            }
            Matcher head=HEAD.matcher(clean),zh=CHINESE.matcher(clean);
            if(head.find()) {
                String en=head.group(1),lower=en.toLowerCase(java.util.Locale.ROOT);
                if(lower.matches("unit|lesson|page|english|noun|adj|adv|n|v|vt|vi"))continue;
                String meaning=zh.find()?clean.substring(zh.start()).trim():"";
                out.add(new Entry(en,meaning,""));
            } else if(zh.find()&&!out.isEmpty()) {
                Entry previous=out.get(out.size()-1);
                previous.chinese+=(previous.chinese.isEmpty()?"":"；")+clean.substring(zh.start());
            }
        }
        return out;
    }
}
