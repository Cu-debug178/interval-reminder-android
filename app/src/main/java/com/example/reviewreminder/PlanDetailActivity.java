package com.example.reviewreminder;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 计划详情 — Complete Paper & Ink book view of a learning plan: progress hero, full practice
 * actions, interactive mind-map link, multi-category filterable word directory with 1-tap audio,
 * and review log.
 */
public final class PlanDetailActivity extends ScreenActivity {
    private static final String CAT_UNCATEGORIZED="__uncategorized__";
    private WordUi ui;
    private long id;
    private SpeechManager speech;
    private String wordFilter="",selectedCategory="";
    private LinearLayout directoryHost,categoryBarHost;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        speech=new SpeechManager(this);
        id=getIntent().getLongExtra("taskId",0);
        if(state!=null){
            wordFilter=state.getString("wordFilter","");
            selectedCategory=state.getString("selectedCategory","");
        }
        show();
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("wordFilter",wordFilter);
        out.putString("selectedCategory",selectedCategory);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume(){super.onResume();if(ui!=null&&ui.body!=null)show();}

    private int dp(float n){return Theme.dp(this,n);}

    private void show(){
        JSONObject task=Store.find(this,id);
        if(task==null){
            ui.start("计划详情");
            ui.body.addView(ui.empty(R.drawable.ic_book_open,"计划已不存在","可能已被删除。",null,null),WordUi.lp());
            return;
        }
        String titleText=task.optString("title","未命名计划");
        ui.start(titleText,R.drawable.ic_ellipsis,"更多操作",v->planMenu(task));
        LinearLayout body=ui.body;

        JSONArray words=task.optJSONArray("words"),history=task.optJSONArray("history");
        int wordCount=words==null?0:words.length();
        int total=task.optJSONArray("intervals")==null?0:task.optJSONArray("intervals").length();
        int stage=Math.min(task.optInt("stage"),total);
        float ratio=total==0?0f:Math.min(1f,stage/(float)total);
        String st=PlansActivity.status(task);
        boolean overdue=!task.optBoolean("done")&&!task.optBoolean("archived")&&task.optLong("due")>0&&task.optLong("due")<=System.currentTimeMillis();

        // Book Overview Hero Card.
        LinearLayout hero=ui.card();
        LinearLayout topRow=ui.row();
        topRow.addView(ui.chip(st,"进行中".equals(st)?Theme.PRIMARY_SOFT:"未开始".equals(st)?Theme.WARN_SOFT:Theme.SUNK,
            "进行中".equals(st)?Theme.PRIMARY:"未开始".equals(st)?Theme.WARN:Theme.INK_2));
        TextView created=ui.type("创建于 "+date(task.optLong("created")),Theme.T_CAPTION);
        LinearLayout.LayoutParams crLp=new LinearLayout.LayoutParams(-2,-2);
        crLp.leftMargin=dp(Theme.SPACE_8);
        topRow.addView(created,crLp);
        hero.addView(topRow,WordUi.lp());

        TextView title=ui.type(titleText,Theme.T_TITLE);
        hero.addView(title,ui.lpMargin(Theme.SPACE_12,0));
        if(!task.optString("description").trim().isEmpty()){
            hero.addView(ui.type(task.optString("description"),Theme.T_CALLOUT),ui.lpMargin(Theme.SPACE_4,0));
        }

        hero.addView(new StageDots(this,Math.max(1,total),stage,overdue),ui.lpMargin(Theme.SPACE_16,0));

        TextView stats=ui.type("第 "+stage+" / "+total+" 轮 · "+wordCount+" 个单词 · 平均掌握 "+String.format(Locale.CHINA,"%.1f",Mastery.average(words))+"/5",Theme.T_CALLOUT);
        stats.setFontFeatureSettings("tnum");
        hero.addView(stats,ui.lpMargin(Theme.SPACE_12,Theme.SPACE_8));

        ProgressLine line=new ProgressLine(this);
        line.set(ratio,false);
        hero.addView(line,WordUi.lp());

        String metaLine="提醒时段："+(task.optString("windows").isEmpty()?"全天":task.optString("windows"));
        if(!task.optBoolean("done")&&task.optLong("due")>0)metaLine+=" · 下次复习 "+date(task.optLong("due"));
        hero.addView(ui.type(metaLine,Theme.T_CAPTION),ui.lpMargin(Theme.SPACE_12,0));
        body.addView(hero,WordUi.lp());

        // Primary study actions.
        if(wordCount>0){
            ui.section(body,"自由学习",null);
            body.addView(ui.primaryButton("按原顺序浏览卡片",v->review(false)),WordUi.lp());
            LinearLayout pair=ui.row();
            Button rand=ui.secondaryButton("随机复习",v->review(true));
            LinearLayout.LayoutParams l1=new LinearLayout.LayoutParams(0,-2,1);
            l1.rightMargin=dp(Theme.SPACE_8);
            pair.addView(rand,l1);
            pair.addView(ui.secondaryButton("全量拼写",v->spelling(false)),new LinearLayout.LayoutParams(0,-2,1));
            body.addView(pair,ui.lpMargin(Theme.SPACE_8,Theme.SPACE_12));

            LinearLayout extras=ui.group();
            ui.item(extras,R.drawable.ic_layers,"本组词汇关系","以思维导图浏览或 AI 整理本组词汇关系",null,true,
                v->Motion.open(this,new Intent(this,RelationActivity.class).putExtra("taskId",id)));
            ui.item(extras,R.drawable.ic_target,"错词专项强化","仅针对本组未掌握错词，连续独立拼对两次",null,true,v->spelling(true));
            ui.item(extras,R.drawable.ic_file_text,"AI 翻译训练","基于本组单词生成中英翻译练习",null,true,v->{
                try{String selection=TrainingStore.forTask(this,id,null);Motion.open(this,new Intent(this,TranslationActivity.class).putExtra("selectionId",selection));}
                catch(Exception e){ui.error(e.getMessage());}
            });
            ui.item(extras,R.drawable.ic_list_checks,"词性义项练习","针对多词性多义项进行辨析",null,true,
                v->Motion.open(this,new Intent(this,SenseQuizActivity.class).putExtra("taskId",id)));
            ui.item(extras,R.drawable.ic_pencil,"校对与编辑单词卡","修改释义、类别、例句、相关词或记忆图",null,true,
                v->Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("taskId",id)));
            body.addView(extras,WordUi.lp());
        }

        // Searchable & Category-Filterable Word Directory.
        ui.section(body,"单词目录",wordCount+" 词");
        if(wordCount==0){
            body.addView(ui.type("这个计划还没有单词卡。",Theme.T_CALLOUT),WordUi.lp());
        }else{
            categoryBarHost=ui.col();
            body.addView(categoryBarHost,WordUi.lp());
            renderCategoryBar(words);
            if(wordCount>8){
                EditText filterField=WordUi.input(this,"筛选本组单词或类别…",wordFilter);
                filterField.setSingleLine(true);
                filterField.setMinHeight(dp(44));
                filterField.setMinimumHeight(dp(44));
                filterField.setPadding(dp(14),dp(8),dp(14),dp(8));
                filterField.addTextChangedListener(new TextWatcher(){
                    public void beforeTextChanged(CharSequence s,int st,int c,int a){}
                    public void onTextChanged(CharSequence s,int st,int b,int c){wordFilter=s.toString();fillDirectory(words);}
                    public void afterTextChanged(Editable s){}
                });
                body.addView(filterField,ui.lpMargin(0,Theme.SPACE_12));
            }
            directoryHost=ui.col();
            body.addView(directoryHost,WordUi.lp());
            fillDirectory(words);
        }

        // Review History Timeline.
        ui.section(body,"复习记录",history==null||history.length()==0?null:history.length()+" 条");
        if(history==null||history.length()==0){
            body.addView(ui.type("暂无复习记录",Theme.T_CALLOUT),WordUi.lp());
        }else{
            LinearLayout hGroup=ui.group();
            for(int i=history.length()-1;i>=0;i--){
                JSONObject h=history.optJSONObject(i);
                if(h==null)continue;
                String sub=date(h.optLong("at"))+(h.has("spellingTotal")?" · 拼写 "+h.optInt("spellingCorrect")+"/"+h.optInt("spellingTotal"):"");
                ui.item(hGroup,0,"第 "+h.optInt("stage")+" 轮",sub,null,false,null);
            }
            body.addView(hGroup,WordUi.lp());
        }
    }

    private Map<String,Integer> categoryCounts(JSONArray words){
        Map<String,Integer> map=new LinkedHashMap<>();
        int uncategorized=0;
        for(int i=0;words!=null&&i<words.length();i++){
            JSONObject w=words.optJSONObject(i);
            if(w==null)continue;
            String cat=w.optString("category","").trim();
            if(cat.isEmpty())uncategorized++;
            else map.put(cat,map.containsKey(cat)?map.get(cat)+1:1);
        }
        if(!map.isEmpty()&&uncategorized>0)map.put(CAT_UNCATEGORIZED,uncategorized);
        return map;
    }

    private void renderCategoryBar(JSONArray words){
        if(categoryBarHost==null)return;
        categoryBarHost.removeAllViews();
        Map<String,Integer> cats=categoryCounts(words);
        if(cats.isEmpty()){selectedCategory="";return;}
        if(!selectedCategory.isEmpty()&&!cats.containsKey(selectedCategory))selectedCategory="";

        HorizontalScrollView hsv=new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row=ui.row();
        addCategoryChip(row,"","全部 ("+words.length()+")",words);
        for(Map.Entry<String,Integer> e:cats.entrySet()){
            String key=e.getKey();
            String label=(CAT_UNCATEGORIZED.equals(key)?"未分类":key)+" ("+e.getValue()+")";
            addCategoryChip(row,key,label,words);
        }
        hsv.addView(row);
        categoryBarHost.addView(hsv,ui.lpMargin(0,Theme.SPACE_12));

        if(!selectedCategory.isEmpty()&&!CAT_UNCATEGORIZED.equals(selectedCategory)){
            LinearLayout scoped=ui.row();
            Button rev=ui.quietPill("复习此类",R.drawable.ic_book_open,v->reviewCategory(words,selectedCategory));
            scoped.addView(rev,new LinearLayout.LayoutParams(-2,dp(34)));
            Button sp=ui.quietPill("拼写此类",R.drawable.ic_spell_check,v->spellingCategory(words,selectedCategory));
            LinearLayout.LayoutParams spLp=new LinearLayout.LayoutParams(-2,dp(34));
            spLp.leftMargin=dp(Theme.SPACE_8);
            scoped.addView(sp,spLp);
            Button mp=ui.quietPill("此类关系图",R.drawable.ic_layers,v->Motion.open(this,new Intent(this,RelationActivity.class).putExtra("taskId",id).putExtra("category",selectedCategory)));
            LinearLayout.LayoutParams mpLp=new LinearLayout.LayoutParams(-2,dp(34));
            mpLp.leftMargin=dp(Theme.SPACE_8);
            scoped.addView(mp,mpLp);
            HorizontalScrollView shsv=new HorizontalScrollView(this);
            shsv.setHorizontalScrollBarEnabled(false);
            shsv.addView(scoped);
            categoryBarHost.addView(shsv,ui.lpMargin(0,Theme.SPACE_12));
        }
    }

    private void addCategoryChip(LinearLayout row,String key,String label,JSONArray words){
        boolean on=selectedCategory.equals(key);
        TextView chip=ui.chip(label,on?Theme.PRIMARY:Theme.SUNK,on?Color.WHITE:Theme.INK_2);
        chip.setPadding(dp(12),dp(6),dp(12),dp(6));
        chip.setClickable(true);chip.setFocusable(true);
        Theme.applyRipple(chip,chip.getBackground(),on?Theme.RIPPLE_DARK:Theme.RIPPLE_LIGHT);
        Motion.press(chip,.95f);
        chip.setOnClickListener(v->{
            selectedCategory=key;
            Feedback.tick(v);
            renderCategoryBar(words);
            fillDirectory(words);
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        if(row.getChildCount()>0)lp.leftMargin=dp(Theme.SPACE_6);
        row.addView(chip,lp);
    }

    private int[] indicesForCategory(JSONArray words,String category){
        List<Integer> list=new ArrayList<>();
        for(int i=0;words!=null&&i<words.length();i++){
            JSONObject w=words.optJSONObject(i);
            if(w==null)continue;
            String cat=w.optString("category","").trim();
            if(CAT_UNCATEGORIZED.equals(category)?cat.isEmpty():category.equals(cat))list.add(i);
        }
        int[] out=new int[list.size()];
        for(int i=0;i<list.size();i++)out[i]=list.get(i);
        return out;
    }

    private void reviewCategory(JSONArray words,String category){
        try{
            int[] idx=indicesForCategory(words,category);
            if(idx.length==0){ui.toast("该类别下暂无单词");return;}
            String selection=TrainingStore.forTask(this,id,idx);
            Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("selectionId",selection).putExtra("practice",true));
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void spellingCategory(JSONArray words,String category){
        try{
            int[] idx=indicesForCategory(words,category);
            if(idx.length==0){ui.toast("该类别下暂无单词");return;}
            String selection=TrainingStore.forTask(this,id,idx);
            Motion.open(this,new Intent(this,SpellingActivity.class)
                .putExtra("taskId",id).putExtra("selectionId",selection).putExtra("wrongMode",false)
                .putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString()));
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void fillDirectory(JSONArray words){
        if(directoryHost==null)return;
        directoryHost.removeAllViews();
        String q=wordFilter==null?"":wordFilter.trim().toLowerCase(Locale.ROOT);
        JSONObject allWrong=WrongBook.allStates(this);
        LinearLayout group=ui.group();
        int shown=0;
        for(int i=0;i<words.length();i++){
            JSONObject word=words.optJSONObject(i);
            if(word==null)continue;
            String en=word.optString("english",""),zh=word.optString("chinese",""),cat=word.optString("category","").trim();
            if(!selectedCategory.isEmpty()){
                if(CAT_UNCATEGORIZED.equals(selectedCategory)){if(!cat.isEmpty())continue;}
                else if(!selectedCategory.equals(cat))continue;
            }
            if(!q.isEmpty()&&!en.toLowerCase(Locale.ROOT).contains(q)&&!zh.toLowerCase(Locale.ROOT).contains(q)&&!cat.toLowerCase(Locale.ROOT).contains(q))continue;
            shown++;
            ui.divide(group,Theme.ROW_PAD);
            LinearLayout row=ui.row();
            row.setMinimumHeight(dp(62));
            row.setPadding(dp(Theme.ROW_PAD),dp(Theme.SPACE_12),dp(Theme.SPACE_8),dp(Theme.SPACE_12));

            LinearLayout copy=ui.col();
            LinearLayout headLine=ui.row();
            TextView enView=ui.type((i+1)+". "+en,Theme.T_HEADLINE);
            enView.setTypeface(Theme.SERIF);
            enView.setTextSize(17);
            headLine.addView(enView);
            JSONObject state=allWrong.optJSONObject(Words.stableId(word));
            if(WrongBook.inBook(state)){
                String stLabel=WrongBook.status(state);
                boolean mastered="已掌握".equals(stLabel);
                TextView badge=ui.chip(stLabel,mastered?Theme.PRIMARY_SOFT:Theme.DANGER_SOFT,mastered?Theme.PRIMARY:Theme.DANGER);
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);
                bp.leftMargin=dp(8);
                headLine.addView(badge,bp);
            }
            copy.addView(headLine,WordUi.lp());

            if(!zh.isEmpty()){
                TextView zhView=ui.type(zh,Theme.T_CALLOUT);
                zhView.setMaxLines(2);
                zhView.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(zhView,ui.lpMargin(2,0));
            }

            LinearLayout metaRow=ui.row();
            metaRow.addView(new StageDots(this,5,Mastery.level(word),false).ringNext(false));
            if(!cat.isEmpty()&&selectedCategory.isEmpty()){
                TextView catTag=ui.chip(cat,Theme.PRIMARY_SOFT,Theme.PRIMARY);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
                cp.leftMargin=dp(8);
                metaRow.addView(catTag,cp);
            }
            if(ConnectionImages.exists(this,word.optString("connectionImage"))){
                TextView imgTag=ui.type(" · 含记忆图",Theme.T_CAPTION);
                imgTag.setTextColor(Theme.PRIMARY);
                metaRow.addView(imgTag);
            }
            copy.addView(metaRow,ui.lpMargin(6,0));
            row.addView(copy,WordUi.weight());

            ImageButton speakBtn=ui.iconButton(R.drawable.ic_volume_2,"朗读 "+en,v->speech.speak(en,"word"));
            speakBtn.setImageTintList(android.content.res.ColorStateList.valueOf(Theme.INK_2));
            row.addView(speakBtn,new LinearLayout.LayoutParams(dp(44),dp(44)));

            final int index=i;
            row.setClickable(true);row.setFocusable(true);
            Theme.applyRipple(row,null,Theme.RIPPLE_LIGHT);
            Motion.press(row,.985f);
            row.setOnClickListener(v->wordActions(words,word,index));
            group.addView(row,WordUi.lp());
        }
        if(shown==0){
            directoryHost.addView(ui.type("没有匹配的单词",Theme.T_CALLOUT),WordUi.lp());
        }else{
            directoryHost.addView(group,WordUi.lp());
        }
    }

    private void planMenu(JSONObject task){
        boolean archived=task.optBoolean("archived");
        new Sheet(this).title(task.optString("title"))
            .item(R.drawable.ic_pen_line,"重命名计划",()->rename(task))
            .item(R.drawable.ic_pencil,"校对单词卡",()->Motion.open(this,new Intent(this,WordEditorActivity.class).putExtra("taskId",id)))
            .item(R.drawable.ic_layers,"本组词汇关系",()->Motion.open(this,new Intent(this,RelationActivity.class).putExtra("taskId",id)))
            .item(R.drawable.ic_book_open,archived?"取消归档":"归档计划（保留全部资料）",()->toggleArchive())
            .dangerItem(R.drawable.ic_trash_2,"删除计划",this::confirmDelete)
            .secondary("关闭",null).show();
    }

    private void toggleArchive(){
        try{
            JSONObject current=Store.find(this,id);
            if(current==null)return;
            boolean next=!current.optBoolean("archived");
            current.put("archived",next);
            Store.saveTask(this,current);
            if(next)Alarms.cancel(this,id);
            else if(!current.optBoolean("done"))Alarms.schedule(this,current);
            ui.toast(next?"已归档，资料仍可随时查看":"已取消归档");
            show();
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void review(boolean random){
        try{
            String selection=TrainingStore.forTask(this,id,null);
            if(random){
                JSONObject old=TrainingStore.selection(this,selection);
                JSONArray words=old.getJSONArray("words");
                List<JSONObject> list=new ArrayList<>();
                for(int i=0;i<words.length();i++)list.add(words.optJSONObject(i));
                Collections.shuffle(list);
                JSONArray shuffled=new JSONArray();
                for(JSONObject word:list)shuffled.put(word);
                selection=TrainingStore.createSelection(this,shuffled);
            }
            Motion.open(this,new Intent(this,ReviewActivity.class).putExtra("taskId",id).putExtra("selectionId",selection).putExtra("practice",true));
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void spelling(boolean wrongOnly){
        try{
            JSONObject task=Store.find(this,id);
            if(task==null)return;
            JSONArray words=task.optJSONArray("words");
            int[] indices=null;
            if(wrongOnly){
                List<Integer> list=new ArrayList<>();
                for(int i=0;words!=null&&i<words.length();i++){
                    JSONObject w=words.optJSONObject(i);
                    if(w==null)continue;
                    JSONObject st=WrongBook.stateForWord(this,w);
                    if(WrongBook.inBook(st)&&st.optInt("streak",0)<2)list.add(i);
                }
                if(list.isEmpty()){ui.toast("本组暂无未掌握的错词");return;}
                indices=new int[list.size()];
                for(int i=0;i<list.size();i++)indices[i]=list.get(i);
            }
            String selection=TrainingStore.forTask(this,id,indices);
            Motion.open(this,new Intent(this,SpellingActivity.class)
                .putExtra("taskId",id).putExtra("selectionId",selection).putExtra("wrongMode",wrongOnly)
                .putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString()));
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void rename(JSONObject task){
        EditText field=ui.input("任务名称",task.optString("title"));
        field.setSingleLine(true);
        new Sheet(this).title("重命名").message("只改名称。单词、计划和复习记录都保留。").content(field)
            .secondary("取消",null)
            .primaryIf("保存",()->{
                try{
                    JSONObject current=Store.find(this,id);
                    if(current==null){ui.toast("计划已不存在");return true;}
                    TaskTitle.rename(current,field.getText().toString());
                    Store.saveTask(this,current);show();ui.toast("已改名为「"+current.optString("title")+"」");return true;
                }catch(IllegalArgumentException e){field.setError(e.getMessage());field.requestFocus();Feedback.tick(field);return false;}
                catch(Exception e){ui.error(e.getMessage());return false;}
            }).show();
        field.requestFocus();
    }

    private void wordActions(JSONArray allWords,JSONObject word,int index){
        String example=word.optString("example").trim();
        String cat=word.optString("category","").trim();
        String msg=word.optString("chinese");
        if(!cat.isEmpty())msg=(msg.isEmpty()?"":msg+"\n")+"所属类别："+cat;
        Sheet sheet=new Sheet(this).title(word.optString("english")).message(msg);
        if(sheet.titleView()!=null)sheet.titleView().setTypeface(Theme.SERIF);
        if(!example.isEmpty()){
            LinearLayout exBox=ui.col();
            exBox.setPadding(dp(12),dp(10),dp(12),dp(10));
            exBox.setBackground(Theme.shape(this,Theme.BG,Theme.RADIUS_FIELD));
            TextView exText=ui.type(example,Theme.T_CALLOUT);
            exText.setTypeface(Theme.SERIF_ITALIC);
            exText.setTextColor(Theme.INK);
            exBox.addView(exText);
            exBox.addView(ui.type("轻点朗读例句",Theme.T_CAPTION),ui.lpMargin(4,0));
            exBox.setClickable(true);exBox.setFocusable(true);
            Theme.applyRipple(exBox,exBox.getBackground(),Theme.RIPPLE_LIGHT);
            Motion.press(exBox,.98f);
            exBox.setOnClickListener(v->speech.speak(example,"word_example"));
            sheet.content(exBox);
        }
        sheet.item(R.drawable.ic_volume_2,"朗读单词",()->speech.speak(word.optString("english"),"word"));
        sheet.item(R.drawable.ic_layers,"相关词","查看或编辑该词的近义、扩、同根关系",()->Motion.open(this,new Intent(this,RelationActivity.class).putExtra("wordId",Words.stableId(word)).putExtra("english",word.optString("english")).putExtra("chinese",word.optString("chinese"))));
        sheet.item(R.drawable.ic_pen_line,"设置所属类别",cat.isEmpty()?"为这个单词标注所属主题类别":"当前："+cat,()->editWordCategory(allWords,word,index));
        if(ConnectionImages.exists(this,word.optString("connectionImage")))sheet.item(R.drawable.ic_images,"查看附带记忆图",()->openConnection(word));
        sheet.item(R.drawable.ic_spell_check,"拼写此词",()->startSpelling(index));
        sheet.item(R.drawable.ic_book_x,"加入错题本",()->{
            try{WrongBook.manualAdd(this,id,index,Words.stableId(word));ui.toast("已加入错题本");show();}
            catch(Exception e){ui.error(e.getMessage());}
        });
        sheet.secondary("关闭",null).show();
    }

    private void editWordCategory(JSONArray allWords,JSONObject word,int index){
        LinearLayout form=ui.col();
        EditText field=ui.input("例如：“优秀的”相关（留空可清除）",word.optString("category"));
        field.setSingleLine(true);
        form.addView(field,WordUi.lp());
        Map<String,Integer> cats=categoryCounts(allWords);
        LinearLayout chipRow=ui.row();
        for(String key:cats.keySet()){
            if(CAT_UNCATEGORIZED.equals(key))continue;
            TextView chip=ui.chip(key,Theme.PRIMARY_SOFT,Theme.PRIMARY);
            chip.setPadding(dp(10),dp(5),dp(10),dp(5));
            chip.setClickable(true);chip.setFocusable(true);
            Theme.applyRipple(chip,chip.getBackground(),Theme.RIPPLE_ACCENT);
            Motion.press(chip,.95f);
            chip.setOnClickListener(v->{field.setText(key);field.setSelection(key.length());Feedback.tick(v);});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
            if(chipRow.getChildCount()>0)cp.leftMargin=dp(6);
            chipRow.addView(chip,cp);
        }
        if(chipRow.getChildCount()>0){
            HorizontalScrollView hsv=new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(chipRow);
            form.addView(hsv,ui.lpMargin(Theme.SPACE_8,0));
        }
        new Sheet(this).title("设置所属类别").message(word.optString("english"))
            .content(form).secondary("取消",null)
            .primaryIf("保存",()->{
                try{
                    String next=field.getText().toString().trim();
                    word.put("category",next);
                    Store.updateCard(this,id,index,word);
                    ui.toast(next.isEmpty()?"已清除类别":"已归入「"+next+"」");
                    show();
                    return true;
                }catch(Exception e){ui.error(e.getMessage());return false;}
            }).show();
    }

    private void openConnection(JSONObject word){Motion.open(this,new Intent(this,ConnectionImageActivity.class).putExtra("path",word.optString("connectionImage")).putExtra("english",word.optString("english")));}
    private void startSpelling(int index){try{String selection=TrainingStore.forTask(this,id,new int[]{index});Motion.open(this,new Intent(this,SpellingActivity.class).putExtra("taskId",id).putExtra("selectionId",selection).putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString()));}catch(Exception e){ui.error(e.getMessage());}}
    private void confirmDelete(){Sheet.confirm(this,"删除计划？","单词、计划和复习记录将被删除，不能恢复。","保留","删除",true,()->{Store.deleteTask(this,id);Alarms.cancel(this,id);finish();});}
    private String date(long time){return new SimpleDateFormat("yyyy年M月d日 HH:mm",Locale.CHINA).format(new Date(time));}
    @Override protected void onPause(){speech.stop();super.onPause();}
    @Override protected void onDestroy(){speech.shutdown();super.onDestroy();}
}
