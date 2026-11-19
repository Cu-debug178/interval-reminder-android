package com.example.reviewreminder;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Future;

/**
 * Vocabulary relations: meaning, word form, and named groups. Works from the cards already on
 * the device; AI suggestions are optional and never required.
 */
public final class RelationActivity extends ScreenActivity {
    private WordUi ui;
    private SpeechManager speech;
    private String wordId="",english="",chinese="",groupId="";
    /** Sibling cards from the unsaved plan that opened this screen. Not used for review or the global list. */
    private JSONArray draftWords=new JSONArray();
    private Future<?> pending;
    private boolean busy;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        speech=new SpeechManager(this);
        if(state!=null){
            wordId=state.getString("wordId","");
            english=state.getString("english","");
            chinese=state.getString("chinese","");
            groupId=state.getString("groupId","");
            draftWords=arrayExtra(state.getString("draftWords"));
        }else{
            wordId=textExtra("wordId");
            english=textExtra("english");
            chinese=textExtra("chinese");
            groupId=textExtra("groupId");
            draftWords=arrayExtra(getIntent().getStringExtra("draftWords"));
        }
        show();
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("wordId",wordId);
        out.putString("english",english);
        out.putString("chinese",chinese);
        out.putString("groupId",groupId);
        out.putString("draftWords",draftWords==null?"[]":draftWords.toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume(){super.onResume();if(ui!=null&&ui.body!=null&&!busy)show();}

    private String textExtra(String key){String value=getIntent().getStringExtra(key);return value==null?"":value;}

    private static JSONArray arrayExtra(String raw){
        if(raw==null||raw.trim().isEmpty())return new JSONArray();
        try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}
    }

    /** Saved vocabulary, plus the siblings of the unsaved plan that opened this screen. */
    private JSONArray catalog(){
        JSONArray saved=WordRelations.catalog(this);
        if(draftWords==null||draftWords.length()==0)return saved;
        try{return Words.mergeCatalog(saved,draftWords);}catch(Exception e){return saved;}
    }

    @Override public void onBackPressed(){
        if(groupId!=null&&!groupId.isEmpty()){groupId="";show();return;}
        if(wordId!=null&&!wordId.isEmpty()&&(getIntent().getStringExtra("wordId")==null||getIntent().getStringExtra("wordId").isEmpty())){wordId="";english="";chinese="";show();return;}
        super.onBackPressed();
    }

    private void show(){
        if(groupId!=null&&!groupId.isEmpty())showGroup();
        else if(wordId!=null&&!wordId.isEmpty())showWord();
        else showOverview();
    }

    private void showOverview(){
        ui.start("词汇关系");
        JSONObject graph=WordRelations.graph(this);
        JSONArray groups=graph.optJSONArray("groups");
        JSONArray links=graph.optJSONArray("links");
        int groupCount=groups==null?0:groups.length();
        int linkCount=links==null?0:links.length();
        if(groupCount==0&&linkCount==0){
            ui.body.addView(ui.empty(R.drawable.ic_layers,"还没有词汇关系","把语义相近或词形相近的词连起来，也可以归成一组。没有网络也能手动添加。",null,null));
            ui.note(ui.body,"从单词卡或复习页的「相关词」进入，就能对着当前这个词来连。");
            return;
        }
        ui.note(ui.body,"近义看意思，词形看拼写和派生。归纳是你自己起名的一组词。");
        if(groupCount>0){
            ui.section(ui.body,"归纳",groupCount+" 组");
            LinearLayout list=ui.group();
            JSONArray catalog=catalog();
            for(int i=0;i<groupCount;i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null)continue;
                final String id=group.optString("id");
                ui.item(list,R.drawable.ic_layers,group.optString("name"),WordGraph.kindLabel(group.optString("kind"))+" · "+memberNames(graph,catalog,id),null,true,v->openGroup(id));
            }
            ui.body.addView(list,WordUi.lp());
        }
        if(linkCount>0){
            ui.section(ui.body,"词与词",linkCount+" 条");
            LinearLayout list=ui.group();
            JSONArray catalog=catalog();
            int shown=0;
            for(int i=0;i<linkCount&&shown<40;i++){
                JSONObject link=links.optJSONObject(i);
                if(link==null)continue;
                shown++;
                String a=nameOf(catalog,link.optString("a")),b=nameOf(catalog,link.optString("b"));
                final String focus=link.optString("a");
                ui.item(list,0,a+"  ·  "+b,WordGraph.kindLabel(link.optString("kind"))+(link.optString("note").isEmpty()?"":" · "+link.optString("note")),null,true,v->openWord(focus));
            }
            ui.body.addView(list,WordUi.lp());
            if(linkCount>40)ui.note(ui.body,"还有 "+(linkCount-40)+" 条，打开具体单词可以看全。");
        }
    }

    private void showWord(){
        JSONArray catalog=catalog();
        JSONObject self=findCard(catalog,wordId);
        if(self!=null){english=self.optString("english",english);chinese=self.optString("chinese",chinese);}
        ui.start(english.isEmpty()?"相关词":english,R.drawable.ic_ellipsis,"更多",v->wordMenu());
        TextView head=ui.type(english.isEmpty()?"（未命名单词）":english,Theme.T_DISPLAY);
        head.setTypeface(Theme.SERIF);
        ui.body.addView(head,WordUi.lp());
        if(!chinese.isEmpty()){
            TextView gloss=ui.type(chinese,Theme.T_BODY);
            gloss.setPadding(0,ui.dp(4),0,0);
            ui.body.addView(gloss,WordUi.lp());
        }
        JSONObject graph=WordRelations.graph(this);
        JSONArray related;
        try{related=WordGraph.related(graph,catalog,wordId);}catch(Exception e){related=new JSONArray();}
        JSONArray groups;
        try{groups=WordGraph.groupsFor(graph,wordId);}catch(Exception e){groups=new JSONArray();}
        if(related.length()==0&&groups.length()==0){
            ui.gap(ui.body,Theme.SPACE_24);
            ui.body.addView(ui.empty(R.drawable.ic_layers,"还没有关联","可以连一个近义词或词形相近的词，也可以把它放进一组。离线就能做。",null,null));
        }else{
            addKind(catalog,related,WordGraph.MEANING,"近义");
            addKind(catalog,related,WordGraph.FORM,"词形");
        }
        if(groups.length()>0){
            ui.section(ui.body,"归纳",null);
            LinearLayout list=ui.group();
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null)continue;
                final String id=group.optString("id");
                ui.item(list,R.drawable.ic_layers,group.optString("name"),WordGraph.kindLabel(group.optString("kind"))+" · "+memberNames(graph,catalog,id),null,true,v->openGroup(id));
            }
            ui.body.addView(list,WordUi.lp());
        }
        Button speak=ui.secondaryButton("朗读",v->speech.speak(english,"relation"));
        Button add=ui.primaryButton("添加关联",v->addLink());
        ui.pinPair(speak,add);
    }

    private void addKind(JSONArray catalog,JSONArray related,String kind,String title){
        LinearLayout list=null;
        for(int i=0;i<related.length();i++){
            JSONObject row=related.optJSONObject(i);
            if(row==null||!"link".equals(row.optString("via"))||!kind.equals(row.optString("kind")))continue;
            if(list==null){ui.section(ui.body,title,null);list=ui.group();}
            final JSONObject item=row;
            String subtitle=row.optString("chinese");
            if(!row.optString("note").isEmpty())subtitle=(subtitle.isEmpty()?"":subtitle+" · ")+row.optString("note");
            ui.item(list,0,display(row),subtitle,null,true,v->linkActions(item));
        }
        if(list!=null)ui.body.addView(list,WordUi.lp());
    }

    private void showGroup(){
        JSONObject graph=WordRelations.graph(this);
        JSONObject group=null;
        JSONArray groups=graph.optJSONArray("groups");
        for(int i=0;groups!=null&&i<groups.length();i++){
            JSONObject item=groups.optJSONObject(i);
            if(item!=null&&groupId.equals(item.optString("id")))group=item;
        }
        if(group==null){groupId="";show();return;}
        final JSONObject current=group;
        ui.start(current.optString("name"),R.drawable.ic_ellipsis,"更多",v->groupMenu(current));
        ui.note(ui.body,WordGraph.kindLabel(current.optString("kind"))+"归纳。组里的词在复习时会互相出现。");
        JSONArray catalog=catalog();
        JSONArray members=WordGraph.membersOf(graph,groupId);
        if(members.length()==0){
            ui.body.addView(ui.empty(R.drawable.ic_layers,"这组还没有词","把已经学过的词加进来。",null,null));
        }else{
            LinearLayout list=ui.group();
            for(int i=0;i<members.length();i++){
                final String id=members.optString(i);
                JSONObject card=findCard(catalog,id);
                String title=card==null||card.optString("english").isEmpty()?nameOf(catalog,id):card.optString("english");
                String subtitle=card==null?"":card.optString("chinese");
                ui.item(list,0,title,subtitle,null,true,v->memberActions(id,title));
            }
            ui.body.addView(list,WordUi.lp());
        }
        ui.pin(ui.primaryButton("添加单词",v->pickMember()));
    }

    private void wordMenu(){
        new Sheet(this).title(english.isEmpty()?"相关词":english)
            .item(R.drawable.ic_layers,"归入一组","放进已有归纳，或新建一组",this::addToGroup)
            .item(R.drawable.ic_spell_check,"本机形近建议","按词形和拼写，不需要联网",this::suggestLocal)
            .item(R.drawable.ic_sparkles,"AI 建议关联","可选。未配置 AI 时不会影响手动添加",this::suggestAi)
            .item(R.drawable.ic_list,"全部词汇关系",() -> {groupId="";wordId="";show();})
            .secondary("关闭",null).show();
    }

    private void groupMenu(JSONObject group){
        new Sheet(this).title(group.optString("name"))
            .item(R.drawable.ic_pen_line,"重命名",() -> renameGroup(group))
            .dangerItem(R.drawable.ic_trash_2,"删除这组归纳",() -> Sheet.confirm(this,"删除这组归纳？","只解散这一组，单词和已经单独连上的关系都还在。","取消","删除",true,() -> {
                try{WordRelations.removeGroup(this,groupId);groupId="";ui.toast("已删除归纳");show();}catch(Exception e){ui.error(message(e));}
            }))
            .secondary("关闭",null).show();
    }

    private void linkActions(JSONObject row){
        final String other=row.optString("wordId");
        new Sheet(this).title(display(row)).message(row.optString("chinese"))
            .item(R.drawable.ic_volume_2,"朗读",() -> speech.speak(row.optString("english"),"relation"))
            .item(R.drawable.ic_layers,"查看这个词的关系",() -> openWord(other))
            .dangerItem(R.drawable.ic_x,"取消关联",() -> {
                try{WordRelations.unlink(this,row.optString("linkId"));ui.toast("已取消关联");show();}catch(Exception e){ui.error(message(e));}
            })
            .secondary("关闭",null).show();
    }

    private void memberActions(String id,String title){
        new Sheet(this).title(title)
            .item(R.drawable.ic_layers,"查看这个词的关系",() -> openWord(id))
            .item(R.drawable.ic_volume_2,"朗读",() -> speech.speak(title,"relation"))
            .dangerItem(R.drawable.ic_x,"移出这组",() -> {
                try{WordRelations.removeMember(this,groupId,id);ui.toast("已移出");show();}catch(Exception e){ui.error(message(e));}
            })
            .secondary("关闭",null).show();
    }

    private void addLink(){
        JSONArray others=others();
        if(others.length()==0){ui.error("词表里还没有其他已保存的单词");return;}
        final int[] kindIndex={0};
        LinearLayout form=ui.col();
        form.addView(ui.segmented(new String[]{"近义","词形"},0,index->kindIndex[0]=index),WordUi.lp());
        ui.gap(form,Theme.SPACE_12);
        EditText query=WordUi.input(this,"搜索英文或中文","");
        query.setSingleLine(true);
        form.addView(query,WordUi.lp());
        LinearLayout list=ui.group();
        LinearLayout.LayoutParams listLp=WordUi.lp();
        listLp.topMargin=ui.dp(Theme.SPACE_12);
        form.addView(list,listLp);
        TextView more=ui.type("",Theme.T_CAPTION);
        form.addView(more,ui.lpMargin(Theme.SPACE_8,0));
        final Sheet[] dialog=new Sheet[1];
        Runnable refill=() -> fillWordList(list,more,others,query.getText().toString(),card -> {
            try{
                String kind=kindIndex[0]==1?WordGraph.FORM:WordGraph.MEANING;
                WordRelations.link(this,wordId,card.optString("wordId"),kind,"");
                if(dialog[0]!=null)dialog[0].dismiss();
                ui.toast("已连上 "+card.optString("english"));
                show();
            }catch(Exception e){ui.error(message(e));}
        });
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){refill.run();}
            public void afterTextChanged(Editable s){}
        });
        refill.run();
        dialog[0]=new Sheet(this).title("关联一个词").message("近义看意思，词形看拼写、派生和词根。").content(form).secondary("取消",null).show();
    }

    private void addToGroup(){
        JSONObject graph=WordRelations.graph(this);
        JSONArray groups=graph.optJSONArray("groups");
        Sheet sheet=new Sheet(this).title("归入一组").message("一组里的词会在复习这个词时一起出现。");
        if(groups!=null){
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null)continue;
                final String id=group.optString("id");
                sheet.item(R.drawable.ic_layers,group.optString("name"),WordGraph.kindLabel(group.optString("kind")),() -> {
                    try{WordRelations.addMember(this,id,wordId);ui.toast("已加入「"+group.optString("name")+"」");show();}catch(Exception e){ui.error(message(e));}
                });
            }
        }
        sheet.item(R.drawable.ic_plus,"新建归纳",this::createGroup).secondary("取消",null).show();
    }

    private void createGroup(){
        LinearLayout form=ui.col();
        EditText name=WordUi.input(this,"例如：看起来像的词","");
        name.setSingleLine(true);
        form.addView(name,WordUi.lp());
        ui.gap(form,Theme.SPACE_12);
        final int[] kindIndex={0};
        form.addView(ui.segmented(new String[]{"语义","词形"},0,index->kindIndex[0]=index),WordUi.lp());
        new Sheet(this).title("新建归纳").content(form).secondary("取消",null).primaryIf("创建",() -> {
            String title=name.getText().toString().trim();
            if(title.isEmpty()){name.setError("请填写名称");name.requestFocus();return false;}
            try{
                String id=WordRelations.addGroup(this,title,kindIndex[0]==1?WordGraph.FORM:WordGraph.MEANING,wordId);
                ui.toast("已建立「"+title+"」");
                openGroup(id);
                return true;
            }catch(Exception e){ui.error(message(e));return false;}
        }).show();
    }

    private void renameGroup(JSONObject group){
        EditText name=WordUi.input(this,"归纳名称",group.optString("name"));
        name.setSingleLine(true);
        new Sheet(this).title("重命名").content(name).secondary("取消",null).primaryIf("保存",() -> {
            try{WordRelations.renameGroup(this,groupId,name.getText().toString());ui.toast("名称已更新");show();return true;}
            catch(IllegalArgumentException e){name.setError(e.getMessage());name.requestFocus();return false;}
            catch(Exception e){ui.error(message(e));return false;}
        }).show();
    }

    private void pickMember(){
        JSONArray others=others();
        if(others.length()==0){ui.error("词表里还没有其他已保存的单词");return;}
        LinearLayout form=ui.col();
        EditText query=WordUi.input(this,"搜索英文或中文","");
        query.setSingleLine(true);
        form.addView(query,WordUi.lp());
        LinearLayout list=ui.group();
        LinearLayout.LayoutParams listLp=WordUi.lp();
        listLp.topMargin=ui.dp(Theme.SPACE_12);
        form.addView(list,listLp);
        TextView more=ui.type("",Theme.T_CAPTION);
        form.addView(more,ui.lpMargin(Theme.SPACE_8,0));
        final Sheet[] dialog=new Sheet[1];
        Runnable refill=() -> fillWordList(list,more,others,query.getText().toString(),card -> {
            try{
                WordRelations.addMember(this,groupId,card.optString("wordId"));
                if(dialog[0]!=null)dialog[0].dismiss();
                ui.toast("已加入 "+card.optString("english"));
                show();
            }catch(Exception e){ui.error(message(e));}
        });
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){refill.run();}
            public void afterTextChanged(Editable s){}
        });
        refill.run();
        dialog[0]=new Sheet(this).title("添加到这一组").content(form).secondary("取消",null).show();
    }

    private interface Pick { void pick(JSONObject card); }

    private void fillWordList(LinearLayout list,TextView more,JSONArray cards,String query,Pick pick){
        list.removeAllViews();
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        int shown=0,matched=0;
        for(int i=0;i<cards.length();i++){
            JSONObject card=cards.optJSONObject(i);
            if(card==null)continue;
            String en=card.optString("english"),zh=card.optString("chinese");
            if(!needle.isEmpty()&&!en.toLowerCase(Locale.ROOT).contains(needle)&&!zh.toLowerCase(Locale.ROOT).contains(needle))continue;
            matched++;
            if(shown>=30)continue;
            shown++;
            final JSONObject chosen=card;
            ui.item(list,0,en.isEmpty()?"（无英文）":en,zh,null,false,v->pick.pick(chosen));
        }
        if(shown==0){
            TextView empty=ui.type(needle.isEmpty()?"没有可选的单词":"没有匹配的单词",Theme.T_CAPTION);
            empty.setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16));
            list.addView(empty);
        }
        more.setText(matched>shown?"还有 "+(matched-shown)+" 个，请继续输入以缩小范围":"");
    }

    private void suggestLocal(){
        JSONArray catalog=catalog();
        JSONArray found;
        try{found=WordGraph.suggestForm(catalog,wordId,english,8);}catch(Exception e){ui.error(message(e));return;}
        if(found.length()==0){ui.toast("没有发现明显的形近词");return;}
        confirmSuggestions("本机形近建议",found,true);
    }

    private void suggestAi(){
        if(busy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        JSONArray others=others();
        if(others.length()==0){ui.error("词表里还没有其他已保存的单词");return;}
        StringBuilder catalog=new StringBuilder();
        int limit=Math.min(others.length(),80);
        for(int i=0;i<limit;i++){
            JSONObject card=others.optJSONObject(i);
            if(card==null)continue;
            if(catalog.length()>0)catalog.append('\n');
            catalog.append(card.optString("english"));
            if(!card.optString("chinese").isEmpty())catalog.append(" / ").append(card.optString("chinese"));
        }
        busy=true;
        ui.toast("正在根据已有单词建议关联…");
        pending=AiClient.chatJson(this,AiPrompts.systemRelations(),AiPrompts.userRelations(english,chinese,catalog.toString()),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                busy=false;
                JSONArray links=json.optJSONArray("links");
                JSONArray matched=matchSuggestions(links);
                if(matched.length()==0){ui.toast("没有可加入现有词表的建议");return;}
                confirmSuggestions("AI 建议的关联",matched,false);
            }
            @Override public void onError(String message){busy=false;ui.error(message);}
        });
    }

    private JSONArray matchSuggestions(JSONArray links){
        JSONArray catalog=catalog();
        JSONArray out=new JSONArray();
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        if(links==null)return out;
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link==null)continue;
            String kind=link.optString("kind");
            if(!WordGraph.validKind(kind))continue;
            String wanted=WordGraph.letters(link.optString("english"));
            if(wanted.isEmpty())continue;
            for(int j=0;j<catalog.length();j++){
                JSONObject card=catalog.optJSONObject(j);
                if(card==null||wordId.equals(card.optString("wordId")))continue;
                if(!wanted.equals(WordGraph.letters(card.optString("english"))))continue;
                if(!seen.add(card.optString("wordId")+"|"+kind))continue;
                try{out.put(new JSONObject(card.toString()).put("kind",kind).put("reason",link.optString("note")));}catch(Exception ignored){}
                break;
            }
        }
        return out;
    }

    private void confirmSuggestions(String title,JSONArray rows,boolean formOnly){
        LinearLayout form=ui.col();
        ArrayList<CheckBox> boxes=new ArrayList<>();
        ArrayList<JSONObject> kept=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row==null)continue;
            String kind=formOnly?WordGraph.FORM:row.optString("kind");
            String label=row.optString("english")+"  "+WordGraph.kindLabel(kind);
            String reason=row.optString("reason");
            if(!reason.isEmpty())label=label+" · "+reason;
            CheckBox box=ui.check(label,true);
            form.addView(box,WordUi.lp());
            boxes.add(box);
            try{kept.add(new JSONObject(row.toString()).put("kind",kind));}catch(Exception ignored){kept.add(row);}
        }
        new Sheet(this).title(title).message("未勾选的不会写入。这些建议来自你已经保存的单词。").content(form)
            .secondary("取消",null)
            .primaryIf("添加",() -> {
                JSONArray specs=new JSONArray();
                for(int i=0;i<boxes.size();i++){
                    if(!boxes.get(i).isChecked())continue;
                    JSONObject row=kept.get(i);
                    try{specs.put(new JSONObject().put("left",wordId).put("right",row.optString("wordId")).put("kind",row.optString("kind")).put("note",row.optString("reason")));}
                    catch(Exception ignored){}
                }
                if(specs.length()==0){ui.toast("没有选择要添加的关系");return false;}
                try{WordRelations.linkAll(this,specs);ui.toast("已添加 "+specs.length()+" 条关联");show();return true;}
                catch(Exception e){ui.error(message(e));return false;}
            }).show();
    }

    private JSONArray others(){
        JSONArray catalog=catalog();
        JSONArray out=new JSONArray();
        for(int i=0;i<catalog.length();i++){
            JSONObject card=catalog.optJSONObject(i);
            if(card!=null&&!wordId.equals(card.optString("wordId"))&&!card.optString("english").trim().isEmpty())out.put(card);
        }
        return out;
    }

    private void openWord(String id){
        JSONObject card=findCard(catalog(),id);
        wordId=id;
        groupId="";
        english=card==null?"":card.optString("english");
        chinese=card==null?"":card.optString("chinese");
        show();
    }

    private void openGroup(String id){groupId=id;show();}

    private static JSONObject findCard(JSONArray catalog,String id){
        if(catalog==null||id==null)return null;
        for(int i=0;i<catalog.length();i++){
            JSONObject card=catalog.optJSONObject(i);
            if(card!=null&&id.equals(card.optString("wordId")))return card;
        }
        return null;
    }

    private static String nameOf(JSONArray catalog,String id){
        JSONObject card=findCard(catalog,id);
        if(card==null)return "已不在词表";
        String english=card.optString("english").trim();
        return english.isEmpty()?card.optString("chinese"):english;
    }

    private static String display(JSONObject row){
        String english=row.optString("english").trim();
        return english.isEmpty()?(row.optString("chinese").isEmpty()?"已不在词表":row.optString("chinese")):english;
    }

    private String memberNames(JSONObject graph,JSONArray catalog,String id){
        JSONArray members=WordGraph.membersOf(graph,id);
        if(members.length()==0)return "还没有词";
        StringBuilder text=new StringBuilder();
        int shown=0;
        for(int i=0;i<members.length()&&shown<3;i++){
            String name=nameOf(catalog,members.optString(i));
            if(name.isEmpty())continue;
            if(text.length()>0)text.append("、");
            text.append(name);
            shown++;
        }
        if(members.length()>shown)text.append("…");
        return text.toString();
    }

    private static String message(Exception e){String value=e.getMessage();return value==null||value.trim().isEmpty()?"没有完成":value;}

    @Override protected void onPause(){if(speech!=null)speech.stop();super.onPause();}
    @Override protected void onDestroy(){AiClient.cancel(pending);if(speech!=null)speech.shutdown();super.onDestroy();}
}
