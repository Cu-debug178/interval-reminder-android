package com.example.reviewreminder;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 混合训练 — Multi-plan word selection with scope filtering, shuffle option, and Paper & Ink UI.
 */
public final class PracticeSetupActivity extends ScreenActivity {
    private WordUi ui;
    private int scopeIndex;
    private Switch random;
    private TextView count;
    private final LinkedHashMap<Long,CheckBox> checks=new LinkedHashMap<>();
    private boolean translationOnly;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        translationOnly=getIntent().getBooleanExtra("translationOnly",false);
        show();
    }

    private void show(){
        ui.start(translationOnly?"AI 翻译训练":"混合训练");
        ui.body.addView(ui.type(translationOnly
            ?"选择一个或多个词组，再按难度生成翻译练习。合并后按稳定 ID 去重，不修改原计划。"
            :"多选两组及以上即可混合训练。按稳定 ID 去重，不修改原计划与提醒节奏。",Theme.T_CALLOUT),WordUi.lp());

        JSONArray tasks=Store.tasks(this);
        checks.clear();
        List<JSONObject> valid=new ArrayList<>();
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);
            if(task!=null&&task.optJSONArray("words")!=null&&task.optJSONArray("words").length()>0)valid.add(task);
        }
        if(valid.isEmpty()){
            ui.gap(ui.body,Theme.SPACE_20);
            ui.body.addView(ui.empty(R.drawable.ic_book_open,"还没有词组","请先录词并创建学习计划，再开启混合训练。","去录词",
                v->Motion.open(this,new Intent(this,ImportActivity.class))),WordUi.lp());
            return;
        }

        LinearLayout head=ui.section(ui.body,"选择词组",valid.size()+" 组");
        Button allBtn=ui.textButton("全选",v->{for(CheckBox c:checks.values())c.setChecked(true);updateCount();});
        Button noneBtn=ui.textButton("清空",v->{for(CheckBox c:checks.values())c.setChecked(false);updateCount();});
        noneBtn.setTextColor(Theme.INK_2);
        head.addView(allBtn);
        head.addView(noneBtn);

        LinearLayout group=ui.group();
        for(JSONObject task:valid){
            ui.divide(group,Theme.ROW_PAD);
            String label=String.format(Locale.CHINA,"%s · %d 词",task.optString("title"),task.optJSONArray("words").length());
            CheckBox check=ui.check(label,false);
            check.setPadding(ui.dp(Theme.ROW_PAD),ui.dp(Theme.SPACE_8),ui.dp(Theme.ROW_PAD),ui.dp(Theme.SPACE_8));
            check.setOnCheckedChangeListener((b,checked)->{Feedback.tick(b);updateCount();});
            checks.put(task.optLong("id"),check);
            group.addView(check,WordUi.lp());
        }
        ui.body.addView(group,WordUi.lp());

        ui.section(ui.body,"筛选范围",null);
        Segmented scope=ui.segmented(new String[]{"全部单词","仅错词","仅未掌握"},scopeIndex,idx->{
            scopeIndex=idx;
            updateCount();
        });
        ui.body.addView(scope,WordUi.lp());

        ui.gap(ui.body,Theme.SPACE_12);
        LinearLayout opts=ui.group();
        random=ui.switchRow(opts,"随机打乱顺序","只改变出题顺序，不影响必练范围与完成条件",false);
        ui.body.addView(opts,WordUi.lp());

        count=ui.type("已合并 0 个单词",Theme.T_LABEL);
        count.setTextColor(Theme.PRIMARY);
        count.setPadding(ui.dp(Theme.SPACE_4),ui.dp(Theme.SPACE_16),0,ui.dp(Theme.SPACE_8));
        ui.body.addView(count,WordUi.lp());

        if(translationOnly){
            ui.pin(ui.primaryButton("开始 AI 翻译训练",v->launch("translation")));
        }else{
            ui.body.addView(ui.primaryButton("卡片复习",v->launch("review")),ui.lpMargin(Theme.SPACE_8,0));
            LinearLayout pair=ui.row();
            Button spell=ui.secondaryButton("全量拼写",v->launch("spelling"));
            LinearLayout.LayoutParams lp1=new LinearLayout.LayoutParams(0,-2,1);
            lp1.rightMargin=ui.dp(Theme.SPACE_8);
            pair.addView(spell,lp1);
            pair.addView(ui.secondaryButton("错词强化",v->launch("wrong")),new LinearLayout.LayoutParams(0,-2,1));
            ui.body.addView(pair,ui.lpMargin(Theme.SPACE_8,0));
            ui.body.addView(ui.tonalButton("AI 翻译训练",R.drawable.ic_sparkles,v->launch("translation")),ui.lpMargin(Theme.SPACE_8,0));
        }
        updateCount();
    }

    private JSONArray build(String mode)throws Exception{
        List<JSONObject> list=new ArrayList<>();
        LinkedHashMap<String,Boolean> seen=new LinkedHashMap<>();
        JSONArray tasks=Store.tasks(this);
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;
            CheckBox check=checks.get(task.optLong("id"));if(check==null||!check.isChecked())continue;
            JSONArray words=task.optJSONArray("words");if(words==null)continue;
            for(int j=0;j<words.length();j++){
                JSONObject word=words.optJSONObject(j);if(word==null)continue;
                JSONObject state=WrongBook.stateForWord(this,word);
                boolean inBook=WrongBook.inBook(state);
                if("wrong".equals(mode)&&(!inBook||state.optInt("streak",0)>=2))continue;
                else if(!"wrong".equals(mode)&&scopeIndex==1&&!inBook)continue;
                else if(!"wrong".equals(mode)&&scopeIndex==2&&(!inBook||state.optInt("streak",0)>=2))continue;
                String id=Words.stableId(word);
                if(seen.containsKey(id))continue;
                seen.put(id,true);
                JSONObject copy=new JSONObject(word.toString());
                copy.put("_taskId",task.optLong("id")).put("_wordIndex",j).put("wordId",id);
                list.add(copy);
            }
        }
        if(random!=null&&random.isChecked())Collections.shuffle(list);
        JSONArray out=new JSONArray();
        for(JSONObject word:list)out.put(word);
        return out;
    }

    private void launch(String mode){
        try{
            JSONArray words=build(mode);
            if(words.length()==0){ui.toast("当前选择与筛选没有可练单词");return;}
            String selection=TrainingStore.createSelection(this,words);
            Intent intent;
            if("review".equals(mode))intent=new Intent(this,ReviewActivity.class);
            else if("translation".equals(mode))intent=new Intent(this,TranslationActivity.class);
            else intent=new Intent(this,SpellingActivity.class).putExtra("wrongMode","wrong".equals(mode)).putExtra("sessionId",UUID.randomUUID().toString()).putExtra("attemptId",UUID.randomUUID().toString());
            intent.putExtra("selectionId",selection).putExtra("practice",true);
            Motion.open(this,intent);
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void updateCount(){
        if(count==null)return;
        try{count.setText(String.format(Locale.CHINA,"已合并 %d 个去重单词",build("review").length()));}
        catch(Exception e){count.setText("无法计算选词数量");}
    }
}
