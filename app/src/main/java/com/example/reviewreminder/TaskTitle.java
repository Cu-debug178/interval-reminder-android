package com.example.reviewreminder;

import org.json.JSONObject;

/** Renames an existing task without touching its id, words, schedule, or history. */
public final class TaskTitle {
    private TaskTitle(){}

    public static String rename(JSONObject task,String name)throws Exception{
        if(task==null)throw new IllegalArgumentException("任务不存在");
        String trimmed=name==null?"":name.trim();
        if(trimmed.isEmpty())throw new IllegalArgumentException("任务名称不能为空");
        long id=task.optLong("id");
        Object words=task.opt("words");
        Object intervals=task.opt("intervals");
        Object history=task.opt("history");
        Object windows=task.opt("windows");
        task.put("title",trimmed);
        if(task.optLong("id")!=id)throw new IllegalStateException("任务编号被改变");
        if(words!=null&&task.opt("words")!=words)throw new IllegalStateException("单词被改变");
        if(intervals!=null&&task.opt("intervals")!=intervals)throw new IllegalStateException("计划被改变");
        if(history!=null&&task.opt("history")!=history)throw new IllegalStateException("复习记录被改变");
        if(windows!=null&&task.opt("windows")!=windows)throw new IllegalStateException("提醒时段被改变");
        return trimmed;
    }
}
