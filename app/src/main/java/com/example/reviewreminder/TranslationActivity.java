package com.example.reviewreminder;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Future;

/**
 * AI 翻译训练 — Paper & Ink dual-mode translation practice (节省模式 / AI 批改模式) with English
 * click-to-speak audio, single or batch AI grading, and persistent history in SQLite.
 */
public final class TranslationActivity extends ScreenActivity {
    private static final int[] COUNTS={3,5,8,10};
    private static final String[] DIFFICULTIES={"简单","适中","挑战"};
    private WordUi ui;
    private String selectionId;
    private JSONArray words,current=new JSONArray();
    private int position,gradingMode,directionIndex,countIndex=1,difficultyIndex=1;
    private Future<?> pending;
    private boolean generating,grading,active=true;
    private EditText answer,note;
    private SpeechManager speech;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        speech=new SpeechManager(this);
        selectionId=getIntent().getStringExtra("selectionId");
        try{
            JSONObject selection=TrainingStore.selection(this,selectionId);
            if(selection==null)throw new IllegalStateException("训练选词已失效");
            words=selection.getJSONArray("words");
            if(state!=null){
                current=new JSONArray(state.getString("current","[]"));
                position=state.getInt("position",0);
            }
            if(current.length()>0)showExercise();
            else showSetup();
        }catch(Exception e){ui.error(e.getMessage());finish();}
    }

    @Override protected void onSaveInstanceState(Bundle out){
        saveCurrent();
        out.putString("current",current.toString());
        out.putInt("position",position);
        super.onSaveInstanceState(out);
    }

    private void showSetup(){
        saveCurrent();
        ui.start("AI 翻译训练");
        ui.body.addView(ui.type("已选 "+words.length()+" 个单词。题目生成后保存在本机 SQLite，节省模式可离线反复练习。",Theme.T_CALLOUT),WordUi.lp());

        ui.section(ui.body,"出题设置",null);
        LinearLayout box=ui.card();
        box.addView(ui.type("翻译方向",Theme.T_CAPTION),WordUi.lp());
        box.addView(ui.segmented(new String[]{"英译中（默认）","中译英"},directionIndex,i->directionIndex=i),ui.lpMargin(Theme.SPACE_6,Theme.SPACE_16));

        box.addView(ui.type("题目数量",Theme.T_CAPTION),WordUi.lp());
        box.addView(ui.segmented(new String[]{"3 题","5 题","8 题","10 题"},countIndex,i->countIndex=i),ui.lpMargin(Theme.SPACE_6,Theme.SPACE_16));

        box.addView(ui.type("难度",Theme.T_CAPTION),WordUi.lp());
        box.addView(ui.segmented(DIFFICULTIES,difficultyIndex,i->difficultyIndex=i),ui.lpMargin(Theme.SPACE_6,Theme.SPACE_16));

        box.addView(ui.type("批改模式",Theme.T_CAPTION),WordUi.lp());
        box.addView(ui.segmented(new String[]{"节省模式（自评）","AI 问答批改"},gradingMode,i->gradingMode=i),ui.lpMargin(Theme.SPACE_6,0));
        ui.body.addView(box,WordUi.lp());

        Button generate=ui.primaryButton("生成小批量题目",null);
        generate.setOnClickListener(v->{
            if(generating)return;
            if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
            generating=true;
            WordUi.enable(generate,false);
            generate.setText("正在生成…");
            generate(generate);
        });
        ui.body.addView(generate,ui.lpMargin(Theme.SPACE_16,0));

        ui.section(ui.body,"历史题目","点击查看或再练");
        JSONArray all=TranslationStore.all(this);
        if(all.length()==0){
            ui.body.addView(ui.type("暂无翻译练习记录",Theme.T_CALLOUT),WordUi.lp());
        }else{
            LinearLayout list=ui.group();
            for(int i=all.length()-1;i>=0&&i>=all.length()-15;i--){
                JSONObject exercise=all.optJSONObject(i);
                if(exercise==null)continue;
                String dir="en_zh".equals(exercise.optString("direction"))?"英译中":"中译英";
                String state=!exercise.optString("aiFeedback").isEmpty()?"AI 已批改":(exercise.optString("selfRating").isEmpty()?"未完成":exercise.optString("selfRating"));
                ui.item(list,0,exercise.optString("prompt"),dir+" · "+exercise.optString("difficulty","")+" · "+state,null,true,v->{
                    current=new JSONArray().put(exercise);position=0;showExercise();
                });
            }
            ui.body.addView(list,WordUi.lp());
        }
    }

    private void generate(Button button){
        try{
            JSONArray targets=new JSONArray();
            int limit=Math.min(words.length(),20);
            for(int i=0;i<limit;i++){
                JSONObject w=words.optJSONObject(i);
                if(w!=null)targets.put(new JSONObject().put("english",w.optString("english")).put("chinese",w.optString("chinese")));
            }
            String dir=directionIndex==0?"en_zh":"zh_en";
            String diff=DIFFICULTIES[difficultyIndex];
            int amount=COUNTS[countIndex];
            final int chosenMode=gradingMode;
            pending=AiClient.chatJson(this,AiPrompts.systemTranslation(),AiPrompts.userTranslation(targets.toString(),amount,dir,diff),new AiClient.Callback(){
                @Override public void onResult(JSONObject json){
                    generating=false;
                    if(isFinishing()||!active)return;
                    WordUi.enable(button,true);
                    button.setText("生成小批量题目");
                    try{
                        JSONArray items=json.optJSONArray("items");
                        if(items==null||items.length()==0)throw new IllegalStateException("模型没有返回有效题目");
                        current=new JSONArray();
                        for(int i=0;i<items.length();i++){
                            JSONObject item=items.optJSONObject(i);
                            if(item==null||item.optString("prompt").trim().isEmpty()||item.optString("reference").trim().isEmpty())continue;
                            JSONObject created=TranslationStore.create(item,selectionId,dir,diff,chosenMode);
                            TranslationStore.add(TranslationActivity.this,created);
                            current.put(created);
                        }
                        if(current.length()==0)throw new IllegalStateException("题目字段不完整，请重试");
                        position=0;
                        showExercise();
                    }catch(Exception e){ui.error(e.getMessage());}
                }
                @Override public void onError(String message){
                    generating=false;
                    if(isFinishing()||!active)return;
                    WordUi.enable(button,true);
                    button.setText("重试生成");
                    ui.error(message+"；已有题目和答案未改动");
                }
            });
        }catch(Exception e){generating=false;WordUi.enable(button,true);button.setText("生成小批量题目");ui.error(e.getMessage());}
    }

    private void showExercise(){
        if(current.length()==0){showSetup();return;}
        position=Math.max(0,Math.min(position,current.length()-1));
        note=null;
        JSONObject exercise=current.optJSONObject(position);
        gradingMode=exercise.optInt("gradingMode",0);
        boolean enToZh="en_zh".equals(exercise.optString("direction"));
        String diff=exercise.optString("difficulty","");
        ui.startModal((position+1)+" / "+current.length()+" · "+(enToZh?"英译中":"中译英")+(diff.isEmpty()?"":" · "+diff));
        ui.barTitle.setTextSize(Theme.CALLOUT_SIZE);
        ui.barTitle.setTypeface(Theme.MEDIUM);
        ui.barTitle.setTextColor(Theme.INK_2);
        ui.barTitle.setFontFeatureSettings("tnum");
        ui.body.setPadding(ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_2),ui.dp(Theme.PAGE_PAD),ui.dp(Theme.SPACE_24));

        ProgressLine bar=new ProgressLine(this);
        bar.set((position+1)/(float)Math.max(1,current.length()),false);
        ui.body.addView(bar,ui.lpMargin(0,Theme.SPACE_16));

        // Prompt card with visible speaker icon for English text (PRD VO-04).
        LinearLayout card=ui.card();
        LinearLayout promptRow=ui.row();
        promptRow.setGravity(Gravity.TOP);
        TextView prompt=ui.type(exercise.optString("prompt"),Theme.T_HEADLINE);
        prompt.setTextSize(20);
        if(enToZh)prompt.setTypeface(Theme.SERIF);
        promptRow.addView(prompt,WordUi.weight());
        final String englishText=enToZh?exercise.optString("prompt"):exercise.optString("reference");
        if(enToZh){
            ImageButton speakBtn=ui.iconButton(R.drawable.ic_volume_2,"朗读题目英文",v->speech.speak(englishText,"translation"));
            promptRow.addView(speakBtn,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
            prompt.setClickable(true);prompt.setFocusable(true);
            Theme.applyRipple(prompt,null,Theme.RIPPLE_LIGHT);
            prompt.setOnClickListener(v->speech.speak(englishText,"translation"));
        }
        card.addView(promptRow,WordUi.lp());
        ui.body.addView(card,ui.lpMargin(0,Theme.SPACE_16));

        answer=ui.field("你的翻译",exercise.optString("userAnswer"),4);
        ui.body.addView(ui.primaryButton(gradingMode==0?"保存并查看参考答案":"保存并 AI 批改本题",v->{
            saveCurrent();
            if(gradingMode==0)showReference(exercise);
            else gradeSingle(exercise);
        }),WordUi.lp());

        if(gradingMode==1&&current.length()>1){
            ui.body.addView(ui.tonalButton("一键 AI 批改全部已答题目",R.drawable.ic_sparkles,v->{
                saveCurrent();
                gradeAllAnswered();
            }),ui.lpMargin(Theme.SPACE_8,0));
        }

        // Reference / AI feedback card with speaker icon for English reference (PRD VO-04).
        boolean showRef=!exercise.optString("aiFeedback").isEmpty()||exercise.optBoolean("referenceViewed")||!exercise.optString("selfRating").isEmpty();
        if(showRef){
            LinearLayout refCard=ui.card();
            LinearLayout refHead=ui.row();
            refHead.addView(ui.type(!exercise.optString("aiFeedback").isEmpty()?"AI 批改反馈":"参考译文",Theme.T_OVERLINE),WordUi.weight());
            ImageButton refSpeak=ui.iconButton(R.drawable.ic_volume_2,"朗读参考英文",v->speech.speak(englishText,"translation_answer"));
            refHead.addView(refSpeak,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)));
            refCard.addView(refHead,WordUi.lp());
            String content=!exercise.optString("aiFeedback").isEmpty()
                ?exercise.optString("aiFeedback")+"\n\n"+referenceText(exercise)
                :referenceText(exercise);
            TextView refBody=ui.type(content,Theme.T_BODY);
            refBody.setTextIsSelectable(true);
            refCard.addView(refBody,ui.lpMargin(Theme.SPACE_6,0));
            ui.body.addView(refCard,ui.lpMargin(Theme.SPACE_12,0));
        }

        if(gradingMode==0){
            ui.gap(ui.body,Theme.SPACE_12);
            note=ui.field("备注（可选）",exercise.optString("note"),2);
            LinearLayout ratings=ui.row();
            String[] labels={"正确","部分正确","错误"};
            for(int i=0;i<labels.length;i++){
                final String label=labels[i];
                boolean chosen=label.equals(exercise.optString("selfRating"));
                Button b=chosen?ui.primaryButton(label,v->rateSelf(exercise,label)):ui.secondaryButton(label,v->rateSelf(exercise,label));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(46),1);
                if(i>0)lp.leftMargin=ui.dp(6);
                ratings.addView(b,lp);
            }
            ui.body.addView(ratings,WordUi.lp());
        }

        LinearLayout nav=ui.row();
        nav.addView(ui.ghostButton("上一题",v->{saveCurrent();if(position>0){position--;showExercise();}else ui.toast("已经是第一题");}),new LinearLayout.LayoutParams(0,ui.dp(48),1));
        LinearLayout.LayoutParams nextLp=new LinearLayout.LayoutParams(0,ui.dp(48),1);
        nextLp.leftMargin=ui.dp(8);
        nav.addView(ui.ghostButton(position+1<current.length()?"下一题":"完成并返回",v->{saveCurrent();if(position+1<current.length()){position++;showExercise();}else showSetup();}),nextLp);
        ui.body.addView(nav,ui.lpMargin(Theme.SPACE_16,0));

        LinearLayout tools=ui.row();
        tools.setGravity(Gravity.CENTER);
        tools.addView(ui.textButton("加入目标词到错题本",v->addWrong(exercise)));
        tools.addView(ui.textButton("以此题再练一次",v->{
            try{
                saveCurrent();
                JSONObject copy=TranslationStore.copyForRetry(exercise);
                TranslationStore.add(this,copy);
                current=new JSONArray().put(copy);position=0;showExercise();
            }catch(Exception e){ui.error(e.getMessage());}
        }));
        ui.body.addView(tools,ui.lpMargin(Theme.SPACE_8,0));
    }

    private void rateSelf(JSONObject exercise,String label){
        saveCurrent();
        try{
            exercise.put("selfRating",label);
            TranslationStore.update(this,exercise);
            ui.toast("已记录："+label);
            showExercise();
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void showReference(JSONObject exercise){
        try{exercise.put("referenceViewed",true);TranslationStore.update(this,exercise);}catch(Exception e){ui.error(e.getMessage());}
        showExercise();
    }

    private String referenceText(JSONObject exercise){
        return "参考答案："+exercise.optString("reference")+(exercise.optString("explanation").isEmpty()?"":"\n"+exercise.optString("explanation"));
    }

    private void gradeSingle(JSONObject exercise){
        String value=answer.getText().toString().trim();
        if(value.isEmpty()){answer.setError("请先写下你的翻译");return;}
        if(grading){ui.toast("正在批改，请稍候");return;}
        grading=true;ui.toast("正在 AI 批改…");
        pending=AiClient.chatJson(this,AiPrompts.systemTranslationGrade(),AiPrompts.userTranslationGrade(exercise.optString("direction"),exercise.optString("prompt"),exercise.optString("reference"),value),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                if(!active||isFinishing())return;
                grading=false;
                try{
                    String text=formatGrade(json);
                    exercise.put("aiFeedback",text);
                    TranslationStore.update(TranslationActivity.this,exercise);
                    if(current.optJSONObject(position)==exercise)showExercise();
                }catch(Exception e){ui.error(e.getMessage());}
            }
            @Override public void onError(String message){
                if(!active||isFinishing())return;
                grading=false;
                ui.error(message+"；你的答案已保留，可重试");
            }
        });
    }

    /** Sequential on-demand batch grading of all answered but ungraded questions (PRD AI-02). */
    private void gradeAllAnswered(){
        if(grading){ui.toast("正在批改，请稍候");return;}
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        java.util.ArrayList<JSONObject> todo=new java.util.ArrayList<>();
        for(int i=0;i<current.length();i++){
            JSONObject ex=current.optJSONObject(i);
            if(ex!=null&&!ex.optString("userAnswer").trim().isEmpty()&&ex.optString("aiFeedback").trim().isEmpty())todo.add(ex);
        }
        if(todo.isEmpty()){ui.toast("没有待批改的已答题目");return;}
        grading=true;
        ui.toast("开始逐题 AI 批改（共 "+todo.size()+" 题）…");
        gradeNextInBatch(todo,0,0);
    }

    private void gradeNextInBatch(java.util.List<JSONObject> todo,int idx,int doneCount){
        if(!active||isFinishing()){grading=false;return;}
        if(idx>=todo.size()){
            grading=false;
            ui.toast("批量批改完成（"+doneCount+"/"+todo.size()+" 题）");
            showExercise();
            return;
        }
        JSONObject ex=todo.get(idx);
        pending=AiClient.chatJson(this,AiPrompts.systemTranslationGrade(),AiPrompts.userTranslationGrade(ex.optString("direction"),ex.optString("prompt"),ex.optString("reference"),ex.optString("userAnswer").trim()),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                if(!active||isFinishing()){grading=false;return;}
                try{
                    ex.put("aiFeedback",formatGrade(json));
                    TranslationStore.update(TranslationActivity.this,ex);
                    gradeNextInBatch(todo,idx+1,doneCount+1);
                }catch(Exception e){
                    grading=false;ui.error(e.getMessage());showExercise();
                }
            }
            @Override public void onError(String message){
                grading=false;
                if(!active||isFinishing())return;
                ui.error("第 "+(idx+1)+" 题批改失败："+message+"；已完成 "+doneCount+" 题，其余答案已保留");
                showExercise();
            }
        });
    }

    private static String formatGrade(JSONObject json){
        String verdict=json.optString("verdict");
        String verdictLabel="correct".equals(verdict)?"正确":"partial".equals(verdict)?"部分正确":"需改进";
        return "判定："+verdictLabel+"\n"+json.optString("feedback")+(json.optString("improved").isEmpty()?"":"\n改进译文："+json.optString("improved"));
    }

    private void addWrong(JSONObject exercise){
        try{
            JSONArray targets=exercise.optJSONArray("targets");int added=0;
            for(int i=0;i<words.length();i++){
                JSONObject word=words.optJSONObject(i);if(word==null)continue;
                boolean match=targets==null||targets.length()==0?words.length()==1:false;
                for(int j=0;targets!=null&&j<targets.length();j++)if(word.optString("english").equalsIgnoreCase(targets.optString(j)))match=true;
                if(match&&WrongBook.manualAdd(this,word.optLong("_taskId"),word.optInt("_wordIndex"),Words.stableId(word)))added++;
            }
            ui.toast(added==0?"未找到可加入的目标词":"已加入 "+added+" 个目标词；不影响拼写连续正确次数");
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void saveCurrent(){
        if(answer==null||current==null||current.length()==0||position>=current.length())return;
        JSONObject exercise=current.optJSONObject(position);
        try{
            exercise.put("userAnswer",answer.getText().toString());
            if(note!=null)exercise.put("note",note.getText().toString());
            TranslationStore.update(this,exercise);
        }catch(Exception e){ui.error("答案暂未保存："+e.getMessage());}
    }

    @Override public void onBackPressed(){
        saveCurrent();
        if(ui.body!=null&&current.length()>0){current=new JSONArray();showSetup();}
        else super.onBackPressed();
    }
    @Override protected void onPause(){saveCurrent();speech.stop();super.onPause();}
    @Override protected void onDestroy(){active=false;AiClient.cancel(pending);speech.shutdown();super.onDestroy();}
}
