package com.example.reviewreminder;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * 错题本 — every word the learner has missed, weakest first.
 *
 *   overview   N 个错词 · AI 情景短文
 *   需要巩固   grouped rows: serif headword, meaning, task · rounds   [错误 N 次] ●●○○○
 *   造句问题   recent sentence feedback (read-only)
 *
 * Tapping a word opens one sheet with every action for it (spell, sense quiz, AI analysis,
 * roots, clear). Store writes, intents and AI calls are unchanged from the previous screen.
 */
public final class WrongBookActivity extends ScreenActivity {
    private static final String[] STATUS_FILTERS={"全部","未掌握","学习中","已掌握"};
    private WordUi ui;
    private Future<?> pending;
    /** One AI request at a time; set when a result sheet opens, cleared by its callback or dismissal. */
    private boolean aiBusy;
    private boolean rendered;
    private int statusFilter;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        if(state!=null)statusFilter=state.getInt("statusFilter",0);
        ui.start("错题本");
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putInt("statusFilter",statusFilter);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume(){
        super.onResume();
        render();
    }

    @Override protected void onDestroy(){
        AiClient.cancel(pending);
        super.onDestroy();
    }

    private int dp(float n){return Theme.dp(this,n);}

    // ── Page ──────────────────────────────────────────────────────────────────

    private void render(){
        LinearLayout body=ui.body;
        body.removeAllViews();

        List<Object[]> rows=collectWrongWords();
        JSONArray mistakes=AiNotes.all(this);

        if(rows.isEmpty()){
            ui.gap(body,Theme.SPACE_16);
            body.addView(ui.empty(R.drawable.ic_book_x,"还没有错词","复习时下滑标记“忘记”，或拼写没有独立答对，都会收录到这里。连续两次独立拼对记为已掌握。",null,null),WordUi.lp());
        }else{
            int unmastered=0,learning=0,mastered=0;
            for(Object[] row:rows){
                int streak=((JSONObject)row[3]).optInt("streak",0);
                if(streak>=2)mastered++;else if(streak==1)learning++;else unmastered++;
            }
            body.addView(overview(rows.size(),unmastered+learning,rows),WordUi.lp());

            // Status filter chips (PRD UI-01: 未掌握 / 学习中 / 已掌握筛选).
            int[] counts={rows.size(),unmastered,learning,mastered};
            LinearLayout chipRow=ui.row();
            for(int i=0;i<STATUS_FILTERS.length;i++){
                final int idx=i;
                boolean on=statusFilter==idx;
                TextView chip=ui.chip(STATUS_FILTERS[i]+(counts[i]>0?" "+counts[i]:""),on?Theme.PRIMARY:Theme.SUNK,on?android.graphics.Color.WHITE:Theme.INK_2);
                chip.setPadding(dp(12),dp(6),dp(12),dp(6));
                chip.setClickable(true);chip.setFocusable(true);
                Theme.applyRipple(chip,chip.getBackground(),on?Theme.RIPPLE_DARK:Theme.RIPPLE_LIGHT);
                Motion.press(chip,.95f);
                chip.setOnClickListener(v->{statusFilter=idx;Feedback.tick(v);render();});
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
                if(i>0)cp.leftMargin=dp(Theme.SPACE_8);
                chipRow.addView(chip,cp);
            }
            android.widget.HorizontalScrollView hsv=new android.widget.HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(chipRow);
            body.addView(hsv,ui.lpMargin(Theme.SPACE_16,0));

            List<Object[]> filtered=new ArrayList<>();
            for(Object[] row:rows){
                int streak=((JSONObject)row[3]).optInt("streak",0);
                if(statusFilter==0||(statusFilter==1&&streak==0)||(statusFilter==2&&streak==1)||(statusFilter==3&&streak>=2))filtered.add(row);
            }
            ui.section(body,STATUS_FILTERS[statusFilter]+"错词","掌握度从低到高 · "+filtered.size()+" 词");
            if(filtered.isEmpty()){
                body.addView(ui.type("当前状态没有错词。",Theme.T_CALLOUT),WordUi.lp());
            }else{
                LinearLayout group=ui.group();
                for(Object[] row:filtered)addWord(group,(JSONObject)row[0],(JSONObject)row[1],(Integer)row[2],(JSONObject)row[3]);
                body.addView(group,WordUi.lp());
                ui.note(body,"点按单词可以拼写强化、查看拼写历史、打开词汇关系或查看 AI 分析。");
            }
        }

        if(mistakes.length()>0)addMistakes(body,mistakes);

        if(!rendered)Motion.stagger(body);
        rendered=true;
    }

    /** One row per stable word id. Global wrong-book state is the source of truth. */
    private List<Object[]> collectWrongWords(){
        LinkedHashMap<String,Object[]> unique=new LinkedHashMap<>();
        JSONArray tasks=Store.tasks(this);
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);
            if(task==null)continue;
            JSONArray words=task.optJSONArray("words");
            if(words==null)continue;
            for(int j=0;j<words.length();j++){
                JSONObject word=words.optJSONObject(j);
                if(word==null)continue;
                JSONObject state=WrongBook.stateForWord(this,word);
                if(!WrongBook.inBook(state))continue;
                String id=Words.stableId(word);
                if(!unique.containsKey(id))unique.put(id,new Object[]{task,word,j,state});
            }
        }
        List<Object[]> rows=new ArrayList<>(unique.values());
        Collections.sort(rows,(a,b)->{
            JSONObject sa=(JSONObject)a[3],sb=(JSONObject)b[3];
            int streak=Integer.compare(sa.optInt("streak",0),sb.optInt("streak",0));
            if(streak!=0)return streak;
            return Integer.compare(sb.optInt("wrongCount",0),sa.optInt("wrongCount",0));
        });
        return rows;
    }

    /** Count of words to work on, plus 1-tap wrong-word drill and AI scenario. */
    private View overview(int count,int pendingCount,List<Object[]> rows){
        LinearLayout wrap=ui.col();
        LinearLayout box=ui.row();
        box.setGravity(Gravity.BOTTOM);
        box.setPadding(dp(Theme.SPACE_4),dp(Theme.SPACE_8),0,0);

        LinearLayout copy=ui.col();
        LinearLayout figure=ui.row();
        figure.setGravity(Gravity.BOTTOM);
        TextView number=ui.type(String.valueOf(count),Theme.T_DISPLAY);
        number.setTextSize(34);
        number.setIncludeFontPadding(false);
        figure.addView(number);
        TextView unit=ui.type("个错词",Theme.T_CALLOUT);
        unit.setPadding(dp(Theme.SPACE_6),0,0,dp(3));
        figure.addView(unit);
        copy.addView(figure);
        TextView caption=ui.type(pendingCount>0?pendingCount+" 个待掌握 · 连续独立拼对两次记为已掌握":"全部错词均已掌握",Theme.T_CAPTION);
        caption.setPadding(0,dp(Theme.SPACE_6),0,0);
        copy.addView(caption);
        box.addView(copy,WordUi.weight());

        Button scenario=ui.quietPill("AI 情景短文",R.drawable.ic_sparkles,v->aiScenarioAll());
        scenario.setContentDescription("AI 情景短文（全部错词）");
        box.addView(scenario,new LinearLayout.LayoutParams(-2,-2));
        wrap.addView(box,WordUi.lp());

        if(pendingCount>0){
            Button drill=ui.primaryButton("错词专项强化（"+pendingCount+" 词）",v->startWrongDrill(rows));
            wrap.addView(drill,ui.lpMargin(Theme.SPACE_16,0));
        }
        return wrap;
    }

    private void startWrongDrill(List<Object[]> rows){
        try{
            JSONArray out=new JSONArray();
            for(Object[] row:rows){
                JSONObject st=(JSONObject)row[3];
                if(st.optInt("streak",0)>=2)continue;
                JSONObject task=(JSONObject)row[0],word=(JSONObject)row[1];
                int idx=(Integer)row[2];
                JSONObject copy=new JSONObject(word.toString());
                copy.put("_taskId",task.optLong("id")).put("_wordIndex",idx).put("wordId",Words.stableId(word));
                out.put(copy);
            }
            if(out.length()==0){ui.toast("没有未掌握的错词");return;}
            String selection=TrainingStore.createSelection(this,out);
            Motion.open(this,new Intent(this,SpellingActivity.class)
                .putExtra("selectionId",selection).putExtra("wrongMode",true)
                .putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString()));
        }catch(Exception e){ui.error(message(e));}
    }

    private void addWord(LinearLayout group,JSONObject task,JSONObject word,int index,JSONObject state){
        ui.divide(group,Theme.ROW_PAD);
        LinearLayout row=ui.row();
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(dp(Theme.ROW_HEIGHT));
        row.setPadding(dp(Theme.ROW_PAD),dp(14),dp(Theme.ROW_PAD),dp(14));

        // Left: headword, meaning, where and when it was missed.
        LinearLayout copy=ui.col();
        TextView english=ui.type(word.optString("english"),Theme.T_HEADLINE);
        english.setTypeface(Theme.SERIF);
        english.setTextSize(19);
        copy.addView(english);

        String meaning=word.optString("chinese");
        if(!meaning.isEmpty()){
            TextView chinese=ui.type(meaning,Theme.T_CALLOUT);
            chinese.setMaxLines(2);
            chinese.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams cp=WordUi.lp();
            cp.topMargin=dp(2);
            copy.addView(chinese,cp);
        }

        String rounds=roundsText(state);
        String status=WrongBook.status(state)+" · 连续独立正确 "+Math.min(2,state.optInt("streak",0))+"/2";
        TextView record=ui.type(rounds.isEmpty()?task.optString("title")+" · "+status:task.optString("title")+" · "+status+" · "+rounds,Theme.T_CAPTION);
        record.setMaxLines(2);
        record.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rp=WordUi.lp();
        rp.topMargin=dp(Theme.SPACE_6);
        copy.addView(record,rp);

        String note=word.optString("aiAnalysis","").trim();
        if(!note.isEmpty()){
            TextView analysis=ui.type(note,Theme.T_CAPTION);
            analysis.setTextColor(Theme.INK_2);
            analysis.setMaxLines(2);
            analysis.setEllipsize(TextUtils.TruncateAt.END);
            analysis.setPadding(dp(10),dp(Theme.SPACE_8),dp(10),dp(Theme.SPACE_8));
            analysis.setBackground(Theme.shape(this,Theme.BG,Theme.RADIUS_CHIP));
            LinearLayout.LayoutParams ap=WordUi.lp();
            ap.topMargin=dp(Theme.SPACE_8);
            copy.addView(analysis,ap);
        }
        row.addView(copy,WordUi.weight());

        // Right: how often, and how well it is known now.
        LinearLayout side=ui.col();
        side.setGravity(Gravity.END);
        int count=state.optInt("wrongCount",word.optInt("wrongCount"));
        boolean heavy=count>=3;
        TextView chip=ui.chip("错误 "+count+" 次",heavy?Theme.DANGER_SOFT:Theme.SUNK,heavy?Theme.DANGER:Theme.INK_2);
        side.addView(chip,new LinearLayout.LayoutParams(-2,-2));
        int level=Mastery.level(word);
        StageDots mastery=new StageDots(this,5,level,false).ringNext(false);
        mastery.setContentDescription(Mastery.label(level));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
        mp.topMargin=dp(10);
        mp.gravity=Gravity.END;
        side.addView(mastery,mp);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);
        sp.leftMargin=dp(Theme.SPACE_12);
        sp.topMargin=dp(3);
        row.addView(side,sp);

        long taskId=task.optLong("id");
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v->wordActions(taskId,index,word,state));
        Theme.applyRipple(row,null,Theme.RIPPLE_LIGHT);
        Motion.press(row,.985f);
        group.addView(row,WordUi.lp());
    }

    /** “出错于 第1轮、第3轮” — empty when no rounds were recorded. */
    private static String roundsText(JSONObject state){
        JSONArray rounds=state==null?null:state.optJSONArray("wrongRounds");
        StringBuilder labels=new StringBuilder();
        if(rounds!=null){
            for(int i=0;i<rounds.length();i++){
                if(i>0)labels.append("、");
                labels.append("第").append(rounds.optInt(i)).append("轮");
            }
        }
        return labels.length()==0?"":"出错于 "+labels;
    }

    /** Recent sentence-writing feedback, newest first (last 8). */
    private void addMistakes(LinearLayout body,JSONArray mistakes){
        ui.section(body,"造句问题","最近 "+Math.min(8,mistakes.length())+" 条");
        LinearLayout group=ui.group();
        for(int i=mistakes.length()-1;i>=0&&i>=mistakes.length()-8;i--){
            JSONObject m=mistakes.optJSONObject(i);
            if(m==null)continue;
            ui.divide(group,Theme.ROW_PAD);
            LinearLayout item=ui.col();
            item.setPadding(dp(Theme.ROW_PAD),dp(14),dp(Theme.ROW_PAD),dp(14));
            TextView word=ui.type(m.optString("english"),Theme.T_LABEL);
            word.setTypeface(Theme.SERIF);
            word.setTextSize(16);
            item.addView(word);
            String sentence=m.optString("sentence");
            if(!sentence.isEmpty()){
                TextView s=ui.type(sentence,Theme.T_BODY);
                s.setTypeface(Theme.SERIF_ITALIC);
                s.setTextColor(Theme.INK_2);
                LinearLayout.LayoutParams p=WordUi.lp();
                p.topMargin=dp(Theme.SPACE_4);
                item.addView(s,p);
            }
            String feedback=m.optString("feedback");
            if(!feedback.isEmpty()){
                TextView f=ui.type(feedback,Theme.T_CALLOUT);
                LinearLayout.LayoutParams p=WordUi.lp();
                p.topMargin=dp(Theme.SPACE_8);
                item.addView(f,p);
            }
            group.addView(item,WordUi.lp());
        }
        body.addView(group,WordUi.lp());
    }

    // ── Word actions ──────────────────────────────────────────────────────────

    private void wordActions(long taskId,int index,JSONObject word,JSONObject state){
        Sheet sheet=new Sheet(this).title(word.optString("english"));
        if(sheet.titleView()!=null){
            sheet.titleView().setTypeface(Theme.SERIF);
            sheet.titleView().setTextSize(22);
        }
        String meaning=word.optString("chinese");
        String detail=WrongBook.status(state)+" · 错误 "+state.optInt("wrongCount",0)+" 次 · "+Mastery.label(Mastery.level(word));
        sheet.message(meaning.isEmpty()?detail:meaning+"\n"+detail);

        String note=word.optString("aiAnalysis","").trim();
        if(!note.isEmpty())sheet.content(noteBlock("AI 分析",note));

        sheet.item(R.drawable.ic_spell_check,state.optInt("streak",0)>=2?"再次强化":"拼写此词",()->startSpelling(taskId,index));
        sheet.item(R.drawable.ic_undo_2,"重新标为未掌握","连续独立正确次数回到 0",()->{
            try{WrongBook.unmaster(this,taskId,index);render();ui.toast("已标为未掌握");}
            catch(Exception e){ui.error(message(e));}
        });
        sheet.item(R.drawable.ic_clock,"拼写与错误历史","查看该词历次独立/提示/错题记录",()->showAttemptHistory(word));
        sheet.item(R.drawable.ic_layers,"相关词","查看近义、扩、同根关系与思维导图",()->
                Motion.open(this,new Intent(this,RelationActivity.class).putExtra("wordId",Words.stableId(word)).putExtra("english",word.optString("english")).putExtra("chinese",word.optString("chinese"))));
        sheet.item(R.drawable.ic_list_checks,"义项练习","练习本组中的错词义项",()->
                Motion.open(this,new Intent(this,SenseQuizActivity.class).putExtra("taskId",taskId).putExtra("wrongOnly",true)));
        sheet.item(R.drawable.ic_sparkles,"AI 分析",note.isEmpty()?"记忆钩子、易混点与建议":"重新生成并替换已有分析",()->aiAnalyze(taskId,index,word));
        sheet.item(R.drawable.ic_book_open,"词根对比","词根、派生词与形近词",()->aiRoots(word));
        sheet.dangerItem(R.drawable.ic_trash_2,"清除记录",()->confirmClear(taskId,index,word));
        sheet.show();
    }

    private void showAttemptHistory(JSONObject word){
        JSONArray attempts=WrongBook.attempts(this,Words.stableId(word));
        LinearLayout box=ui.col();
        if(attempts.length()==0){
            box.addView(ui.type("暂无逐次作答记录",Theme.T_CALLOUT));
        }else{
            java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("M月d日 HH:mm",java.util.Locale.CHINA);
            int cap=Math.min(20,attempts.length());
            for(int i=0;i<cap;i++){
                JSONObject a=attempts.optJSONObject(i);
                if(a==null)continue;
                String type=a.optString("type");
                String label="independent".equals(type)?"独立拼写正确":"assisted".equals(type)?"提示/跳过（不计掌握）":"拼写错误/标记忘记";
                int color="independent".equals(type)?Theme.PRIMARY:"assisted".equals(type)?Theme.WARN:Theme.DANGER;
                LinearLayout row=ui.row();
                row.setPadding(0,dp(6),0,dp(6));
                TextView l=ui.type(label,Theme.T_CALLOUT);
                l.setTextColor(color);
                row.addView(l,WordUi.weight());
                row.addView(ui.type(fmt.format(new java.util.Date(a.optLong("at"))),Theme.T_CAPTION));
                box.addView(row,WordUi.lp());
            }
        }
        new Sheet(this).title(word.optString("english")+" · 练习历史").content(box).secondary("关闭",null).show();
    }

    private void startSpelling(long taskId,int index){
        try{
            String selection=TrainingStore.forTask(this,taskId,new int[]{index});
            Intent intent=new Intent(this,SpellingActivity.class)
                    .putExtra("taskId",taskId)
                    .putExtra("selectionId",selection)
                    .putExtra("indices",new int[]{index})
                    .putExtra("round",-1)
                    .putExtra("wrongMode",true)
                    .putExtra("attemptId",UUID.randomUUID().toString())
                    .putExtra("sessionId",UUID.randomUUID().toString());
            Motion.open(this,intent);
        }catch(Exception e){ui.error(message(e));}
    }

    private void confirmClear(long taskId,int index,JSONObject word){
        Sheet.confirm(this,"清除错词记录？","单词卡会保留，只清除错误次数和轮次记录。","取消","清除",true,()->{
            try{
                WrongBook.clear(this,taskId,index);
                render();
                ui.toast("已清除 "+word.optString("english")+" 的错词记录");
            }catch(Exception e){
                ui.error(message(e));
            }
        });
    }

    // ── AI ────────────────────────────────────────────────────────────────────
    //
    // Each action opens its result sheet at once with a quiet loading state, then fills it in
    // place. One request at a time: a second tap while busy does nothing, and closing the sheet
    // cancels the request. Callbacks from a superseded or cancelled request are ignored.

    private void aiAnalyze(long taskId,int index,JSONObject word){
        if(aiBusy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        Sheet sheet=loadingSheet("错题分析");
        final Future<?>[] req=new Future<?>[1];
        req[0]=AiClient.chatJson(this,AiPrompts.systemWrongAnalysis(),AiPrompts.userWrongAnalysis(word.optString("english"),word.optString("chinese"),word.optInt("wrongCount")),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                // The analysis is always saved to the word (as before the redesign), even if the
                // sheet was closed while it was loading; only the sheet update is skipped then.
                if(isFinishing()||isDestroyed())return;
                boolean current=pending==req[0];
                if(current)aiBusy=false;
                String text="记忆钩子："+json.optString("hooks")+"\n易混点："+json.optString("confusion")+"\n建议："+json.optString("tip");
                String saveError=null;
                try{
                    JSONObject task=Store.find(WrongBookActivity.this,taskId);
                    if(task!=null){
                        JSONArray words=task.getJSONArray("words");
                        JSONObject saved=words.getJSONObject(index);
                        AiNotes.putWordNote(saved,"aiAnalysis",text);
                        Store.updateCard(WrongBookActivity.this,taskId,index,saved);
                    }
                }catch(Exception e){
                    saveError=message(e);
                }
                if(!current){if(saveError!=null)ui.error("分析未能保存："+saveError);else ui.toast("AI 分析已保存");render();return;}
                LinearLayout content=ui.col();
                content.addView(block("记忆钩子",json.optString("hooks"),false),WordUi.lp());
                content.addView(block("易混点",json.optString("confusion"),false),blockLp());
                content.addView(block("建议",json.optString("tip"),false),blockLp());
                if(saveError!=null)content.addView(errorText("分析未能保存："+saveError),blockLp());
                if(!fill(sheet,content)&&saveError!=null)ui.error(saveError);
                render();
            }
            @Override public void onError(String message){
                if(pending!=req[0])return;
                aiBusy=false;
                fill(sheet,errorText(message));
            }
        });
        track(sheet,req[0],false);
    }

    private void aiRoots(JSONObject word){
        if(aiBusy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        Sheet sheet=loadingSheet("词根 / 形近对比");
        final Future<?>[] req=new Future<?>[1];
        req[0]=AiClient.chatJson(this,AiPrompts.systemRoots(),AiPrompts.userRoots(word.optString("english"),word.optString("chinese")),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                if(pending!=req[0])return;
                aiBusy=false;
                LinearLayout content=ui.col();
                content.addView(block("词根",json.optString("root"),true),WordUi.lp());

                JSONArray der=json.optJSONArray("derivatives");
                if(der!=null){
                    StringBuilder joined=new StringBuilder();
                    for(int i=0;i<der.length();i++){
                        if(i>0)joined.append("、");
                        joined.append(der.optString(i));
                    }
                    content.addView(block("派生",joined.toString(),true),blockLp());
                }

                JSONArray looks=json.optJSONArray("lookalikes");
                if(looks!=null&&looks.length()>0){
                    LinearLayout list=ui.col();
                    list.addView(overline("形近词"));
                    for(int i=0;i<looks.length();i++){
                        JSONObject item=looks.optJSONObject(i);
                        if(item==null)continue;
                        LinearLayout line=ui.col();
                        line.setPadding(0,dp(Theme.SPACE_6),0,0);
                        TextView w=ui.type(item.optString("word"),Theme.T_LABEL);
                        w.setTypeface(Theme.SERIF);
                        w.setTextSize(16);
                        line.addView(w);
                        TextView diff=ui.type(item.optString("diff"),Theme.T_CALLOUT);
                        line.addView(diff);
                        list.addView(line,WordUi.lp());
                    }
                    content.addView(list,blockLp());
                }

                content.addView(block("提示",json.optString("tip"),false),blockLp());
                fill(sheet,content);
            }
            @Override public void onError(String message){
                if(pending!=req[0])return;
                aiBusy=false;
                fill(sheet,errorText(message));
            }
        });
        track(sheet,req[0]);
    }

    private void aiScenarioAll(){
        if(aiBusy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        try{
            JSONArray list=new JSONArray();
            JSONArray tasks=Store.tasks(this);
            for(int i=0;i<tasks.length();i++){
                JSONObject task=tasks.optJSONObject(i);
                if(task==null)continue;
                JSONArray words=task.optJSONArray("words");
                if(words==null)continue;
                for(int j=0;j<words.length();j++){
                    JSONObject w=words.optJSONObject(j);
                    if(w!=null&&w.optInt("wrongCount")>0)list.put(new JSONObject().put("english",w.optString("english")).put("chinese",w.optString("chinese")));
                    if(list.length()>=12)break;
                }
                if(list.length()>=12)break;
            }
            if(list.length()==0){ui.toast("暂无错词可生成情景");return;}
            Sheet sheet=loadingSheet("情景练习");
            final Future<?>[] req=new Future<?>[1];
            req[0]=AiClient.chatJson(this,AiPrompts.systemScenario(),AiPrompts.userScenario(list.toString()),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){
                    if(pending!=req[0])return;
                    aiBusy=false;
                    LinearLayout content=ui.col();
                    String title=json.optString("title").trim();
                    if(!title.isEmpty()){
                        TextView t=ui.type(title,Theme.T_HEADLINE);
                        t.setTypeface(Theme.SERIF_ITALIC);
                        t.setTextSize(18);
                        content.addView(t,WordUi.lp());
                    }
                    TextView text=ui.type(json.optString("text").trim(),Theme.T_BODY);
                    text.setTypeface(Theme.SERIF);
                    text.setTextSize(16);
                    text.setLineSpacing(0,1.45f);
                    text.setTextIsSelectable(true);
                    LinearLayout.LayoutParams p=WordUi.lp();
                    if(!title.isEmpty())p.topMargin=dp(Theme.SPACE_12);
                    content.addView(text,p);
                    fill(sheet,content);
                }
                @Override public void onError(String message){
                    if(pending!=req[0])return;
                    aiBusy=false;
                    fill(sheet,errorText(message));
                }
            });
            track(sheet,req[0]);
        }catch(Exception e){
            aiBusy=false;
            ui.error(message(e));
        }
    }

    /** Result sheet shown immediately: final title, a quiet “正在生成…” line and a thin indeterminate bar. */
    private Sheet loadingSheet(String title){
        aiBusy=true;
        LinearLayout box=ui.col();
        box.setPadding(0,dp(Theme.SPACE_8),0,dp(Theme.SPACE_8));
        TextView label=ui.type("正在生成…",Theme.T_CALLOUT);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(label,WordUi.lp());
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Theme.PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.SUNK));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(140),dp(4));
        bp.topMargin=dp(Theme.SPACE_12);
        box.addView(bar,bp);
        return new Sheet(this).title(title).content(box).secondary("关闭",null).show();
    }

    /** Remembers the in-flight request; closing the sheet cancels it and frees the busy guard. */
    private void track(Sheet sheet,Future<?> req){track(sheet,req,true);}
    /** @param cancel false keeps the request running after the sheet closes (results that are saved). */
    private void track(Sheet sheet,Future<?> req,boolean cancel){
        pending=req;
        sheet.onDismiss(()->{
            if(pending!=req)return;
            if(cancel)AiClient.cancel(req);
            pending=null;
            aiBusy=false;
        });
    }

    /** Swaps the loading state for the result (or error) while the sheet is still open. */
    private boolean fill(Sheet sheet,View content){
        if(!sheet.isShowing())return false;
        Motion.layout(sheet.body);
        sheet.clearContent().content(content);
        return true;
    }

    private TextView errorText(String message){
        TextView t=ui.type(message==null||message.isEmpty()?"AI 请求失败":message,Theme.T_CALLOUT);
        t.setTextColor(Theme.DANGER);
        t.setTextIsSelectable(true);
        return t;
    }

    // ── Small parts ───────────────────────────────────────────────────────────

    private TextView overline(String label){
        TextView t=ui.type(label,Theme.T_OVERLINE);
        t.setPadding(0,0,0,dp(Theme.SPACE_4));
        return t;
    }

    /** Labelled paragraph used in AI result sheets; English content can be set in serif. */
    private LinearLayout block(String label,String value,boolean english){
        LinearLayout b=ui.col();
        b.addView(overline(label));
        TextView v=ui.type(value==null||value.isEmpty()?"—":value,Theme.T_BODY);
        if(english){
            v.setTypeface(Theme.SERIF);
            v.setTextSize(16);
        }
        v.setTextIsSelectable(true);
        b.addView(v);
        return b;
    }

    private LinearLayout.LayoutParams blockLp(){
        LinearLayout.LayoutParams p=WordUi.lp();
        p.topMargin=dp(Theme.SPACE_16);
        return p;
    }

    /** Saved analysis shown at the top of a word's action sheet. */
    private View noteBlock(String label,String value){
        LinearLayout b=ui.col();
        b.setPadding(dp(14),dp(12),dp(14),dp(12));
        b.setBackground(Theme.shape(this,Theme.BG,Theme.RADIUS_FIELD));
        b.addView(overline(label));
        TextView v=ui.type(value,Theme.T_CALLOUT);
        v.setTextColor(Theme.INK);
        v.setTextIsSelectable(true);
        b.addView(v);
        return b;
    }

    private static String message(Exception e){
        String m=e.getMessage();
        return m==null||m.isEmpty()?"操作未完成，请重试":m;
    }
}
