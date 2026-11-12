package com.example.reviewreminder;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Proofreads the word cards of a draft (or of an existing task).
 *
 * Two presentations share one data model:
 *  - the editor: the group name on paper, a pager (counter · ‹ ›) with a progress line, and the
 *    current card as one white surface holding its fields; the primary action is pinned below.
 *  - quick proofreading ({@code quickReview}): a calm read-only card, confirmed one by one; a
 *    problem opens the same editor for just that card and returns to the confirmation.
 */
public final class WordEditorActivity extends ScreenActivity {
    private WordUi ui;
    private JSONObject document;
    private JSONArray words;
    private int index;
    private long taskId;
    private EditText english,chinese,example,title;
    private boolean committed,changing,quickReview,editingIssue;
    private int direction;
    private Future<?> pending;

    /** The part of the page that represents the current card; it slides when the card changes. */
    private View content;
    private Button aiButton;
    private boolean aiBusy,rendered;
    private float lastProgress=-1;
    private final Feedback interaction=new Feedback();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        taskId=getIntent().getLongExtra("taskId",0);
        quickReview=getIntent().getBooleanExtra("quickReview",false);
        index=state==null?0:state.getInt("index");
        editingIssue=state!=null&&state.getBoolean("editingIssue");
        try{
            document=taskId==0?Words.draft(this):Store.find(Store.tasks(this),taskId);
            if(document==null)throw new Exception("任务已不存在");
            words=document.optJSONArray("words");
            if(words==null)words=new JSONArray();
            if(words.length()==0)words.put(new JSONObject().put("english","").put("chinese","").put("example",""));
            document.put("words",words);
            // The completion chime is only ever played when a new plan is created from a draft.
            if(taskId==0&&Store.prefs(this).getBoolean("feedbackSound",true))interaction.prepare(this);
            // Keep result notices clear of the pinned action bar.
            Notice.setBottomOffset(this,76);
            show();
        }catch(Exception e){
            finish();
            ui.error(e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out){
        save(false);
        out.putInt("index",index);
        out.putBoolean("editingIssue",editingIssue);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy(){
        AiClient.cancel(pending);
        // Let a just-started completion sound finish before the pool goes away.
        final Feedback sounds=interaction;
        new Handler(Looper.getMainLooper()).postDelayed(sounds::release,1500);
        super.onDestroy();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void show(){
        index=Math.max(0,Math.min(index,words.length()-1));
        if(taskId==0&&quickReview&&!editingIssue){showQuickCard();return;}
        showEditor();
    }

    private void showEditor(){
        ui.start(taskId==0?(quickReview?"修改单词卡":"校对单词卡"):"编辑单词卡");
        LinearLayout body=ui.body;
        // Text actions align their label (not their ripple) with the content edge.
        body.setClipToPadding(false);

        // Group name belongs to the whole set, so it sits above the pager, directly on paper.
        if(taskId==0&&!quickReview){
            labelRow(body,"词组名称",null,null);
            title=WordUi.input(this,"例如：Lesson 1",document.optString("title"));
            title.setSingleLine(true);
            body.addView(title,WordUi.lp());
            String source=document.optString("source");
            TextView helper=ui.type(source.isEmpty()?"名称会显示在主页和复习提醒里":source,Theme.T_CAPTION);
            helper.setPadding(ui.dp(4),ui.dp(8),ui.dp(4),0);
            body.addView(helper,WordUi.lp());
            ui.gap(body,Theme.SPACE_24);
        }

        body.addView(pager(),WordUi.lp());
        ui.gap(body,Theme.SPACE_12);

        JSONObject word=words.optJSONObject(index);
        if(word==null)word=new JSONObject();
        LinearLayout card=ui.card();
        card.setClipToPadding(false);

        labelRow(card,"英文单词",null,null);
        english=cardInput(card,word.optString("english"),1,"例如：ambition");
        english.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        english.setTypeface(Theme.SERIF);
        english.setTextSize(22);
        ui.gap(card,Theme.SPACE_20);

        labelRow(card,"中文释义",null,ui.textButton("编辑词性义项",v->editSenses()));
        chinese=cardInput(card,word.optString("chinese"),2,"可含 n./v. 等词性，用分号分隔");
        try{Senses.ensure(word);}catch(Exception ignored){}
        String senseLabel=Senses.labelJoin(Senses.of(word));
        TextView sense=ui.type(senseLabel.isEmpty()?"义项将在保存后自动拆分":"义项："+senseLabel,Theme.T_CAPTION);
        sense.setPadding(ui.dp(4),ui.dp(8),ui.dp(4),0);
        card.addView(sense,WordUi.lp());
        ui.gap(card,Theme.SPACE_20);

        aiButton=ui.textButton(aiBusy?"正在生成…":"AI 生成例句",v->aiExample());
        ui.setIcon(aiButton,R.drawable.ic_sparkles,Theme.PRIMARY,16);
        aiButton.setCompoundDrawablePadding(ui.dp(6));
        WordUi.enable(aiButton,!aiBusy);
        labelRow(card,"例句","可留空",aiButton);
        example=cardInput(card,word.optString("example"),2,"英文例句");

        body.addView(card,WordUi.lp());
        content=card;

        String saveLabel=taskId==0?(quickReview?"保存并返回确认":"保存词组并开始计划"):"保存修改";
        Button primary=ui.primaryButton(saveLabel,v->saveAndContinue(v));
        Button more=ui.textButton("更多操作",v->moreActions());
        LinearLayout actions=ui.row();
        actions.addView(more,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-2,1);
        pp.leftMargin=ui.dp(Theme.SPACE_8);
        actions.addView(primary,pp);
        ui.pin(actions);

        reveal();
    }

    /** Counter (tap for the card list) on the left, ‹ › on the right, a progress line underneath. */
    private LinearLayout pager(){
        LinearLayout box=ui.col();
        box.setClipChildren(false);
        LinearLayout nav=ui.row();
        nav.setClipChildren(false);

        TextView counter=ui.type((index+1)+" / "+words.length()+" 张",Theme.T_CALLOUT);
        counter.setTypeface(Theme.MEDIUM);
        counter.setTextColor(Theme.INK);
        android.graphics.drawable.Drawable down=getDrawable(R.drawable.ic_chevron_down).mutate();
        down.setTint(Theme.INK_3);
        int size=ui.dp(16);
        down.setBounds(0,0,size,size);
        counter.setCompoundDrawables(null,null,down,null);
        counter.setCompoundDrawablePadding(ui.dp(4));
        counter.setGravity(Gravity.CENTER_VERTICAL);
        counter.setMinHeight(ui.dp(Theme.TOUCH));
        counter.setPadding(ui.dp(4),0,ui.dp(8),0);
        counter.setClickable(true);
        counter.setFocusable(true);
        counter.setBackground(Theme.shape(this,android.graphics.Color.TRANSPARENT,Theme.RADIUS_CHIP));
        Theme.applyRipple(counter,counter.getBackground(),Theme.RIPPLE_LIGHT);
        Motion.press(counter,.97f);
        counter.setOnClickListener(v->{if(save(true))showCardList();});
        nav.addView(counter,new LinearLayout.LayoutParams(-2,-2));

        nav.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));

        ImageButton prev=ui.iconButton(R.drawable.ic_chevron_left,"上一张",v->move(-1));
        WordUi.enable(prev,index>0);
        nav.addView(prev,new LinearLayout.LayoutParams(ui.dp(Theme.TOUCH),ui.dp(Theme.TOUCH)));
        ImageButton next=ui.iconButton(R.drawable.ic_chevron_right,"下一张",v->move(1));
        WordUi.enable(next,index<words.length()-1);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(ui.dp(Theme.TOUCH),ui.dp(Theme.TOUCH));
        np.rightMargin=-ui.dp(12);
        nav.addView(next,np);
        box.addView(nav,WordUi.lp());

        ui.gap(box,Theme.SPACE_4);
        box.addView(progress(),WordUi.lp());
        return box;
    }

    private void showQuickCard(){
        clearFields();
        ui.start("快速确认");
        LinearLayout body=ui.body;

        String group=document.optString("title","").trim();
        if(!group.isEmpty()){
            TextView name=ui.type(group,Theme.T_OVERLINE);
            name.setPadding(ui.dp(2),0,0,ui.dp(6));
            body.addView(name,WordUi.lp());
        }
        LinearLayout counterRow=ui.row();
        TextView counter=ui.type("第 "+(index+1)+" 张，共 "+words.length()+" 张",Theme.T_CALLOUT);
        counterRow.addView(counter,WordUi.weight());
        TextView hint=ui.type("看一眼，没问题就继续",Theme.T_CAPTION);
        counterRow.addView(hint);
        body.addView(counterRow,WordUi.lp());
        ui.gap(body,Theme.SPACE_8);
        body.addView(progress(),WordUi.lp());
        ui.gap(body,Theme.SPACE_20);

        JSONObject word=words.optJSONObject(index);
        if(word==null)word=new JSONObject();
        LinearLayout card=ui.card();
        card.setGravity(Gravity.CENTER);
        card.setMinimumHeight(ui.dp(280));
        card.setPadding(ui.dp(24),ui.dp(40),ui.dp(24),ui.dp(40));

        String en=word.optString("english","");
        TextView englishCard=ui.type(en.trim().isEmpty()?"（未填写英文）":en,Theme.T_DISPLAY);
        englishCard.setTypeface(Theme.SERIF);
        englishCard.setTextSize(34);
        englishCard.setLetterSpacing(0);
        if(en.trim().isEmpty()){englishCard.setTextSize(Theme.HEADLINE_SIZE);englishCard.setTextColor(Theme.INK_3);}
        englishCard.setGravity(Gravity.CENTER);
        card.addView(englishCard,WordUi.lp());

        View divider=new View(this);
        divider.setBackgroundColor(Theme.HAIRLINE);
        LinearLayout.LayoutParams dividerLp=new LinearLayout.LayoutParams(ui.dp(32),Math.max(1,ui.dp(1)));
        dividerLp.topMargin=ui.dp(20);
        dividerLp.bottomMargin=ui.dp(18);
        card.addView(divider,dividerLp);

        TextView chineseCard=ui.type(word.optString("chinese",""),Theme.T_BODY);
        chineseCard.setTextSize(18);
        chineseCard.setLineSpacing(0,1.35f);
        chineseCard.setGravity(Gravity.CENTER);
        card.addView(chineseCard,WordUi.lp());

        String exampleText=word.optString("example","").trim();
        if(!exampleText.isEmpty()){
            TextView exampleCard=ui.type(exampleText,Theme.T_CALLOUT);
            exampleCard.setTypeface(Theme.SERIF_ITALIC);
            exampleCard.setTextSize(Theme.BODY_SIZE);
            exampleCard.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams exampleLp=WordUi.lp();
            exampleLp.topMargin=ui.dp(16);
            card.addView(exampleCard,exampleLp);
        }
        body.addView(card,WordUi.lp());
        content=card;

        boolean last=index==words.length()-1;
        Button issue=ui.secondaryButton("有问题，修改",v->{
            if(changing)return;
            editingIssue=true;
            direction=0;
            show();
        });
        issue.setPadding(ui.dp(16),0,ui.dp(16),0);
        Button ok=ui.primaryButton(last?"没问题，完成校对":"没问题，下一张",v->{if(!last)Feedback.tick(v);confirmCurrent();});
        LinearLayout actions=ui.row();
        actions.addView(issue,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(0,-2,1);
        op.leftMargin=ui.dp(Theme.SPACE_8);
        actions.addView(ok,op);
        ui.pin(actions);

        reveal();
    }

    /** First render rises in gently; later card changes slide in the direction of travel. */
    private void reveal(){
        if(!rendered){rendered=true;Motion.stagger(ui.body);}
        else if(content!=null)Motion.enter(content,direction);
        direction=0;
    }

    private ProgressLine progress(){
        ProgressLine line=new ProgressLine(this);
        float target=(index+1)/(float)Math.max(1,words.length());
        if(lastProgress<0||lastProgress==target)line.set(target,false);
        else{line.set(lastProgress,false);line.set(target,true);}
        lastProgress=target;
        return line;
    }

    // ── Small private parts ───────────────────────────────────────────────────

    /** Field label (Callout · medium) with an optional quiet hint and a trailing text action. */
    private void labelRow(LinearLayout parent,String label,String hint,View trailing){
        LinearLayout r=ui.row();
        r.setClipChildren(false);
        r.setMinimumHeight(ui.dp(40));
        TextView l=ui.type(label,Theme.T_CALLOUT);
        l.setTypeface(Theme.MEDIUM);
        l.setTextColor(Theme.INK_2);
        l.setPadding(ui.dp(4),0,0,0);
        r.addView(l);
        if(hint!=null){
            TextView h=ui.type(hint,Theme.T_CAPTION);
            LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-2,-2);
            hp.leftMargin=ui.dp(8);
            r.addView(h,hp);
        }
        r.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        if(trailing!=null){
            // Align the action's text with the field edge; its ripple may extend into the padding.
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);
            tp.rightMargin=-ui.dp(10);
            r.addView(trailing,tp);
        }
        LinearLayout.LayoutParams rp=WordUi.lp();
        rp.bottomMargin=ui.dp(4);
        parent.addView(r,rp);
    }

    /** Input on the white card: paper fill, hairline at rest, pine outline when focused. */
    private EditText cardInput(LinearLayout parent,String value,int lines,String hint){
        EditText e=WordUi.input(this,hint,value);
        e.setBackground(Theme.fieldBackground(this,Theme.BG));
        if(lines>1){
            e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(lines);
            e.setMaxLines(Math.max(6,lines));
            e.setGravity(Gravity.TOP|Gravity.START);
        }else{
            e.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        }
        parent.addView(e,WordUi.lp());
        return e;
    }

    private void focusEnglish(){
        if(english==null)return;
        EditText target=english;
        target.requestFocus();
        target.post(()->{
            InputMethodManager im=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(im!=null)im.showSoftInput(target,InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void saveAndContinue(View v){
        if(!save(true))return;
        if(taskId==0&&quickReview){
            Feedback.tick(v);
            editingIssue=false;
            clearFields();
            direction=0;
            show();
            ui.toast("已保存修改");
        }else if(taskId==0){
            finishProof();
        }else{
            Feedback.tick(v);
            finish();
            ui.toast("单词卡已保存");
        }
    }

    private void moreActions(){
        JSONObject word=words.optJSONObject(index);
        String en=english!=null?english.getText().toString().trim():word==null?"":word.optString("english").trim();
        new Sheet(this)
                .title(en.isEmpty()?"第 "+(index+1)+" 张单词卡":en)
                .item(R.drawable.ic_list,"卡片列表","共 "+words.length()+" 张，跳到任意一张",()->{
                    if(!save(true))return;
                    showCardList();
                })
                .item(R.drawable.ic_plus,"添加卡片","在末尾新建一张空白卡",()->{
                    if(!save(true))return;
                    try{
                        words.put(new JSONObject().put("english","").put("chinese","").put("example",""));
                        int target=words.length()-1;
                        direction=target>index?1:0;
                        index=target;
                        show();
                        focusEnglish();
                    }catch(Exception e){ui.error(e.getMessage());}
                })
                .dangerItem(R.drawable.ic_trash_2,"删除此卡",this::remove)
                .show();
    }

    private void showCardList(){
        String[] labels=new String[words.length()];
        for(int i=0;i<words.length();i++){
            JSONObject w=words.optJSONObject(i);
            String en=w==null?"":w.optString("english","").trim();
            labels[i]=(i+1)+". "+(en.isEmpty()?"未填写":en);
        }
        Sheet.pick(this,"选择单词卡",labels,index,this::jump);
    }

    /** Jumps to any card (already saved by the caller), sliding toward it. */
    private void jump(int target){
        if(changing||target==index||target<0||target>=words.length())return;
        int step=target>index?1:-1;
        changing=true;
        direction=step;
        Motion.leave(content!=null?content:ui.body,step,()->{
            if(isFinishing()||isDestroyed())return;
            index=target;
            changing=false;
            show();
        });
    }

    private void editSenses(){
        if(!save(true))return;
        try{
            JSONObject word=words.getJSONObject(index);
            Senses.ensure(word);
            JSONArray senses=word.getJSONArray("senses");
            StringBuilder draft=new StringBuilder();
            for(int i=0;i<senses.length();i++){
                JSONObject s=senses.optJSONObject(i);
                if(s==null)continue;
                if(draft.length()>0)draft.append('\n');
                String pos=s.optString("pos","").trim();
                String meaning=s.optString("meaning","").trim();
                draft.append(pos.isEmpty()?meaning:(pos+" "+meaning));
                String ex=s.optString("example","").trim();
                if(!ex.isEmpty())draft.append(" | ").append(ex);
            }

            LinearLayout form=ui.col();
            TextView sample=ui.type("n. 书\nv. 预订 | I booked a room.",Theme.T_CALLOUT);
            sample.setTypeface(Theme.SERIF);
            sample.setPadding(ui.dp(14),ui.dp(10),ui.dp(14),ui.dp(10));
            sample.setBackground(Theme.shape(this,Theme.SUNK,Theme.RADIUS_FIELD));
            form.addView(sample,WordUi.lp());
            ui.gap(form,Theme.SPACE_12);
            EditText field=WordUi.input(this,"每行一个义项",draft.toString());
            field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            field.setMinLines(5);
            field.setMaxLines(10);
            field.setGravity(Gravity.TOP|Gravity.START);
            form.addView(field,WordUi.lp());

            new Sheet(this)
                    .title("编辑词性义项")
                    .message("每行一个义项，格式：词性 释义，可在“|”后接例句。例如：")
                    .content(form)
                    .secondary("取消",null)
                    .primary("保存",()->{
                        try{
                            String[] lines=field.getText().toString().split("\\n");
                            JSONArray next=new JSONArray();
                            for(String line:lines){
                                String raw=line.trim();
                                if(raw.isEmpty())continue;
                                String ex="",left=raw;
                                int bar=raw.indexOf('|');
                                if(bar>=0){ex=raw.substring(bar+1).trim();left=raw.substring(0,bar).trim();}
                                String pos="",meaning=left;
                                java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(n\\.|v\\.|adj\\.|adv\\.|prep\\.|conj\\.|pron\\.|vt\\.|vi\\.)\\s*(.+)$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(left);
                                if(m.matches()){pos=Senses.normalizePos(m.group(1));meaning=m.group(2).trim();}
                                next.put(Senses.sense(pos,meaning,ex,0));
                            }
                            if(next.length()==0)next.put(Senses.sense("","","",0));
                            word.put("senses",next).put("chinese",Senses.labelJoin(next));
                            if(chinese!=null)chinese.setText(word.optString("chinese"));
                            if(taskId==0)Words.writeDraft(this,document);
                            else{
                                JSONArray all=Store.tasks(this);
                                JSONObject current=Store.find(all,taskId);
                                if(current!=null){current.put("words",words);Store.save(this,all);}
                            }
                            show();
                            ui.toast("义项已更新");
                        }catch(Exception e){ui.error(e.getMessage());}
                    })
                    .show();
        }catch(Exception e){ui.error(e.getMessage());}
    }

    private void aiExample(){
        if(aiBusy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        if(!save(true))return;
        JSONObject word=words.optJSONObject(index);
        if(word==null)return;
        String en=word.optString("english").trim();
        if(en.isEmpty()){ui.error("请先填写英文单词");return;}
        setAiBusy(true);
        pending=AiClient.chatJson(this,AiPrompts.systemExample(),AiPrompts.userExample(en,word.optString("chinese")),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                setAiBusy(false);
                String value=json.optString("example","").trim();
                if(value.isEmpty()){ui.error("模型未返回例句");return;}
                // Only fill the visible field if the user is still on the card the sentence is for.
                if(example!=null&&words.optJSONObject(index)==word)example.setText(value);
                try{
                    word.put("example",value);
                    if(taskId==0)Words.writeDraft(WordEditorActivity.this,document);
                    else{
                        JSONArray all=Store.tasks(WordEditorActivity.this);
                        JSONObject current=Store.find(all,taskId);
                        if(current!=null){current.put("words",words);Store.save(WordEditorActivity.this,all);}
                    }
                    ui.toast("例句已填入，可继续修改");
                }catch(Exception e){ui.error(e.getMessage());}
            }
            @Override public void onError(String message){
                setAiBusy(false);
                ui.error(message);
            }
        });
    }

    private void setAiBusy(boolean busy){
        aiBusy=busy;
        if(aiButton==null)return;
        aiButton.setText(busy?"正在生成…":"AI 生成例句");
        WordUi.enable(aiButton,!busy);
    }

    private void confirmCurrent(){
        if(changing)return;
        if(index<words.length()-1){
            changing=true;
            direction=1;
            Motion.leave(content!=null?content:ui.body,1,()->{
                if(isFinishing()||isDestroyed())return;
                index++;
                changing=false;
                show();
            });
            return;
        }
        finishProof();
    }

    private void finishProof(){
        if(taskId!=0){
            if(save(true)){finish();ui.toast("单词卡已保存");}
            return;
        }
        for(int i=0;i<words.length();i++){
            if(words.optJSONObject(i)==null||words.optJSONObject(i).optString("english").trim().isEmpty()){
                direction=i>index?1:i<index?-1:0;
                index=i;
                if(quickReview)editingIssue=true;
                show();
                if(english!=null)english.setError("请填写英文单词或删除此卡");
                return;
            }
        }
        if(!document.optString("title","").trim().isEmpty()){commit();return;}

        EditText name=WordUi.input(this,"例如：Lesson 1","");
        name.setSingleLine(true);
        Sheet sheet=new Sheet(this)
                .title("给这组单词命名")
                .message("名称会显示在主页和复习提醒里。")
                .content(name)
                .secondary("取消",null)
                .primaryIf("创建复习计划",()->{
                    String value=name.getText().toString().trim();
                    if(value.isEmpty()){name.setError("请填写词组名称");name.requestFocus();return false;}
                    try{
                        document.put("title",value);
                        Words.writeDraft(this,document);
                    }catch(Exception e){
                        ui.error("保存失败："+e.getMessage());
                        return true;
                    }
                    commit();
                    return true;
                });
        if(sheet.dialog.getWindow()!=null)sheet.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        name.requestFocus();
        sheet.show();
    }

    private void clearFields(){english=null;chinese=null;example=null;title=null;}

    private boolean save(boolean report){
        if(document==null||english==null||committed)return true;
        if(taskId!=0&&english.getText().toString().trim().isEmpty()){
            if(report)english.setError("英文不能为空");
            return false;
        }
        try{
            JSONObject word=words.getJSONObject(index);
            String nextChinese=chinese.getText().toString().trim();
            String prevChinese=word.optString("chinese","");
            word.put("english",english.getText().toString().trim()).put("chinese",nextChinese).put("example",example.getText().toString().trim());
            if(!nextChinese.equals(prevChinese)||word.optJSONArray("senses")==null||word.optJSONArray("senses").length()==0)word.put("senses",Senses.migrate(word));
            else Senses.ensure(word);
            if(title!=null)document.put("title",title.getText().toString().trim());
            document.put("words",words);
            if(taskId==0)Words.writeDraft(this,document);
            else{
                JSONArray all=Store.tasks(this);
                JSONObject current=Store.find(all,taskId);
                if(current==null)throw new Exception("任务已删除");
                current.put("words",words);
                Store.save(this,all);
            }
            return true;
        }catch(Exception e){
            if(report)ui.error("保存失败："+e.getMessage());
            return false;
        }
    }

    private void move(int step){
        if(changing||index+step<0||index+step>=words.length()||!save(true))return;
        changing=true;
        direction=step;
        Motion.leave(content!=null?content:ui.body,step,()->{
            if(isFinishing()||isDestroyed())return;
            index+=step;
            changing=false;
            show();
        });
    }

    private void remove(){
        if(changing)return;
        if(taskId!=0&&words.length()==1){ui.error("词组至少需要一张卡片，可在主页删除整个任务");return;}
        JSONObject word=words.optJSONObject(index);
        String en=english!=null?english.getText().toString().trim():word==null?"":word.optString("english").trim();
        Sheet.confirm(this,"删除当前单词卡？",en.isEmpty()?"这张卡将从这组单词中移除。":"“"+en+"”将从这组单词中移除。","取消","删除",true,()->{
            if(title!=null)try{document.put("title",title.getText().toString().trim());}catch(Exception ignored){}
            words.remove(index);
            if(words.length()==0)words.put(new JSONObject());
            index=Math.min(index,words.length()-1);
            direction=0;
            show();
            if(save(true))ui.toast("已删除此卡");
        });
    }

    private void commit(){
        if(committed)return;
        try{
            if(document.optString("title").isEmpty()){ui.error("请填写词组名称");return;}
            for(int i=0;i<words.length();i++){
                if(words.getJSONObject(i).optString("english").trim().isEmpty()){
                    index=i;
                    if(quickReview)editingIssue=true;
                    show();
                    if(english!=null)english.setError("请填写英文单词或删除此卡");
                    else ui.error("请填写英文单词或删除此卡");
                    return;
                }
            }
            List<Long> plan=IntervalPlan.active(Store.prefs(this).getString("intervals",Store.DEFAULT_INTERVALS),Store.prefs(this).getBoolean("optional",false),Store.prefs(this).getString("extraIntervals",""));
            JSONArray intervals=new JSONArray();
            for(long n:plan)intervals.put(n);
            long now=System.currentTimeMillis(),id=Store.prefs(this).getLong("nextId",0)+1;
            String windows=ReminderWindows.active(this);
            JSONObject t=new JSONObject(document.toString()).put("id",id).put("created",now).put("stage",0).put("intervals",intervals).put("windows",windows).put("history",new JSONArray()).put("snoozes",0).put("done",false).put("due",Schedule.due(now,plan.get(0),Store.windows(windows)));
            JSONArray all=Store.tasks(this);
            all.put(t);
            Store.save(this,all);
            Store.prefs(this).edit().putLong("nextId",id).apply();
            boolean exact=Alarms.schedule(this,t);
            committed=true;
            Words.clearDraft(this);
            interaction.confirm(getWindow().getDecorView());
            String message="已保存 "+words.length()+" 张单词卡"+(exact?"":"；系统可能延迟提醒");
            Intent home=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
            finish();
            // Finishing first lets the notice fall back to a system toast that survives the transition.
            ui.toast(message);
        }catch(Exception e){ui.error("无法建立计划："+e.getMessage());}
    }

    @Override protected void onPause(){save(false);super.onPause();}

    @Override public void onBackPressed(){
        if(quickReview&&editingIssue){
            if(save(true)){editingIssue=false;clearFields();direction=0;show();}
            return;
        }
        if(save(true))super.onBackPressed();
    }
}
