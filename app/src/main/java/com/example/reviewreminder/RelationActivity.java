package com.example.reviewreminder;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Interactive Vocabulary Mind-Map & Relations Studio.
 *
 * Modes:
 *   - Overview: mind-map theme cards + filterable word pairs + create / AI-organise mind maps.
 *   - Group Mind-Map: full-canvas interactive {@link WordMapView} with left/right curved branches,
 *     labeled edges (扩 / 近义 / 同根 / 反义 / 词形), zoom/pan, node tap actions, and list toggle.
 *   - Word Focus: centered interactive mind-map around one word (up to 2 hops) + list toggle.
 *   - Task Mind-Map / Draft Preview: visual preview of a plan's or imported draft's mind-map.
 */
public final class RelationActivity extends ScreenActivity {
    private WordUi ui;
    private SpeechManager speech;
    private String wordId="",english="",chinese="",groupId="",taskCategory="";
    private long taskId;
    private boolean listMode,showChinese=true,draftPreview,landscape;
    private int overviewKindFilter,previewGroupIndex; // overviewKindFilter: 0 = all, 1..5 = KINDS[i-1]
    /** Sibling cards from the unsaved plan that opened this screen. */
    private JSONArray draftWords=new JSONArray();
    private JSONObject previewGraph;
    private String previewTitle="";
    private Future<?> pending;
    private boolean busy;
    private WordMapView activeMap;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ui=new WordUi(this);
        speech=new SpeechManager(this);
        if(state!=null){
            wordId=state.getString("wordId","");
            english=state.getString("english","");
            chinese=state.getString("chinese","");
            groupId=state.getString("groupId","");
            taskCategory=state.getString("taskCategory","");
            taskId=state.getLong("taskId",0);
            listMode=state.getBoolean("listMode",false);
            showChinese=state.getBoolean("showChinese",true);
            draftPreview=state.getBoolean("draftPreview",false);
            landscape=state.getBoolean("landscape",false);
            previewGroupIndex=state.getInt("previewGroupIndex",0);
            previewTitle=state.getString("previewTitle","");
            draftWords=arrayExtra(state.getString("draftWords"));
            previewGraph=objectExtra(state.getString("previewGraph"));
        }else{
            wordId=textExtra("wordId");
            english=textExtra("english");
            chinese=textExtra("chinese");
            groupId=textExtra("groupId");
            taskCategory=textExtra("category");
            taskId=getIntent().getLongExtra("taskId",0);
            draftPreview=getIntent().getBooleanExtra("draftPreview",false);
            previewTitle=textExtra("previewTitle");
            draftWords=arrayExtra(getIntent().getStringExtra("draftWords"));
            previewGraph=objectExtra(getIntent().getStringExtra("previewGraph"));
        }
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        show();
    }

    @Override public void onConfigurationChanged(Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        landscape=newConfig.orientation==Configuration.ORIENTATION_LANDSCAPE;
        if(ui!=null&&!busy)show();
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("wordId",wordId);
        out.putString("english",english);
        out.putString("chinese",chinese);
        out.putString("groupId",groupId);
        out.putString("taskCategory",taskCategory);
        out.putLong("taskId",taskId);
        out.putBoolean("listMode",listMode);
        out.putBoolean("showChinese",showChinese);
        out.putBoolean("draftPreview",draftPreview);
        out.putBoolean("landscape",landscape);
        out.putInt("previewGroupIndex",previewGroupIndex);
        out.putString("previewTitle",previewTitle);
        out.putString("draftWords",draftWords==null?"[]":draftWords.toString());
        if(previewGraph!=null)out.putString("previewGraph",previewGraph.toString());
        super.onSaveInstanceState(out);
    }

    private void setCanvasLandscape(boolean on){
        landscape=on;
        setRequestedOrientation(on?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void exitLandscapeIfNeeded(){
        if(getRequestedOrientation()==ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE){
            landscape=false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @Override protected void onResume(){super.onResume();if(ui!=null&&!busy&&!draftPreview)show();}

    private String textExtra(String key){String value=getIntent().getStringExtra(key);return value==null?"":value;}
    private static JSONArray arrayExtra(String raw){if(raw==null||raw.trim().isEmpty())return new JSONArray();try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}}
    private static JSONObject objectExtra(String raw){if(raw==null||raw.trim().isEmpty())return null;try{return new JSONObject(raw);}catch(Exception e){return null;}}

    private JSONArray catalog(){
        JSONArray saved=WordRelations.catalog(this);
        if(draftWords==null||draftWords.length()==0)return saved;
        try{return Words.mergeCatalog(saved,draftWords);}catch(Exception e){return saved;}
    }

    private Set<String> unmasteredWrongIds(){
        Set<String> out=new HashSet<>();
        try{
            JSONObject all=WrongBook.allStates(this);
            Iterator<String> keys=all.keys();
            while(keys.hasNext()){
                String id=keys.next();
                JSONObject st=all.optJSONObject(id);
                if(WrongBook.inBook(st)&&st.optInt("streak",0)<2)out.add(id);
            }
        }catch(Exception ignored){}
        return out;
    }

    @Override public void onBackPressed(){
        exitLandscapeIfNeeded();
        if(draftPreview){super.onBackPressed();return;}
        if(groupId!=null&&!groupId.isEmpty()&&textExtra("groupId").isEmpty()){groupId="";listMode=false;show();return;}
        if(wordId!=null&&!wordId.isEmpty()&&textExtra("wordId").isEmpty()){wordId="";english="";chinese="";listMode=false;show();return;}
        if(taskId>0&&getIntent().getLongExtra("taskId",0)==0){taskId=0;taskCategory="";show();return;}
        super.onBackPressed();
    }

    private void show(){
        activeMap=null;
        if(draftPreview&&previewGraph!=null)showDraftPreview();
        else if(groupId!=null&&!groupId.isEmpty())showGroup();
        else if(wordId!=null&&!wordId.isEmpty())showWord();
        else if(taskId>0)showTaskMap();
        else showOverview();
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    private void showOverview(){
        exitLandscapeIfNeeded();
        ui.start("词汇关系",R.drawable.ic_plus,"新建主题图",v->overviewCreateMenu());
        JSONObject graph=WordRelations.graph(this);
        JSONArray groups=graph.optJSONArray("groups");
        JSONArray links=graph.optJSONArray("links");
        int groupCount=groups==null?0:groups.length();
        int linkCount=links==null?0:links.length();

        // Top actions strip: create blank mind map or let AI organise a plan into a mind map.
        LinearLayout actions=ui.row();
        Button newMap=ui.tonalButton("新建主题图",R.drawable.ic_plus,v->createGroup(false));
        LinearLayout.LayoutParams lp1=new LinearLayout.LayoutParams(0,ui.dp(46),1);
        lp1.rightMargin=ui.dp(Theme.SPACE_8);
        actions.addView(newMap,lp1);
        Button aiMap=ui.secondaryButton("AI 整理词组",R.drawable.ic_sparkles,v->pickTaskForAiOrganize());
        actions.addView(aiMap,new LinearLayout.LayoutParams(0,ui.dp(46),1));
        ui.body.addView(actions,ui.lpMargin(Theme.SPACE_4,Theme.SPACE_8));

        if(groupCount==0&&linkCount==0){
            ui.gap(ui.body,Theme.SPACE_8);
            ui.body.addView(ui.empty(R.drawable.ic_layers,"还没有词汇关系","用中心主题把近义、派生(扩)、同根、反义或形近词串成思维导图。拍照录词时也会自动识别教材导图。",null,null),WordUi.lp());
            return;
        }

        JSONArray catalog=catalog();
        if(groupCount>0){
            ui.section(ui.body,"归纳导图",groupCount+" 组");
            for(int i=0;i<groupCount;i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null)continue;
                addGroupPreviewCard(graph,catalog,group);
            }
        }

        if(linkCount>0){
            ui.section(ui.body,"词与词连线",linkCount+" 条");
            String[] labels={"全部","近义","扩","同根","反义","词形"};
            LinearLayout chipRow=ui.row();
            android.widget.HorizontalScrollView hsv=new android.widget.HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            for(int i=0;i<labels.length;i++){
                final int idx=i;
                boolean on=overviewKindFilter==idx;
                TextView chip=ui.chip(labels[i],on?Theme.PRIMARY:Theme.SUNK,on?Color.WHITE:Theme.INK_2);
                chip.setPadding(ui.dp(12),ui.dp(6),ui.dp(12),ui.dp(6));
                chip.setClickable(true);chip.setFocusable(true);
                Theme.applyRipple(chip,chip.getBackground(),on?Theme.RIPPLE_DARK:Theme.RIPPLE_LIGHT);
                Motion.press(chip,.95f);
                chip.setOnClickListener(v->{overviewKindFilter=idx;showOverview();});
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
                if(i>0)cp.leftMargin=ui.dp(Theme.SPACE_6);
                chipRow.addView(chip,cp);
            }
            hsv.addView(chipRow);
            ui.body.addView(hsv,ui.lpMargin(0,Theme.SPACE_12));

            LinearLayout list=ui.group();
            int shown=0;
            String filterKind=overviewKindFilter==0?null:WordGraph.KINDS[overviewKindFilter-1];
            for(int i=0;i<linkCount&&shown<50;i++){
                JSONObject link=links.optJSONObject(i);
                if(link==null)continue;
                if(filterKind!=null&&!filterKind.equals(link.optString("kind")))continue;
                shown++;
                String a=nameOf(catalog,link.optString("a")),b=nameOf(catalog,link.optString("b"));
                String badge=WordGraph.edgeLabel(link);
                final String focus=link.optString("a");
                ui.item(list,0,a+"  —"+badge+"—  "+b,WordGraph.kindLabel(link.optString("kind"))+(link.optString("note").isEmpty()||link.optString("note").equals(badge)?"":" · "+link.optString("note")),null,true,v->openWord(focus));
            }
            if(shown==0){
                TextView empty=ui.type("这个分类下还没有连线",Theme.T_CALLOUT);
                empty.setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16));
                list.addView(empty);
            }
            ui.body.addView(list,WordUi.lp());
            if(linkCount>50)ui.note(ui.body,"还有更多连线，点击任意单词或关联图即可查看完整图谱。");
        }
    }

    private void addGroupPreviewCard(JSONObject graph,JSONArray catalog,JSONObject group){
        final String id=group.optString("id");
        JSONArray members=WordGraph.membersOf(graph,id);
        int edgeCount=countGroupLinks(graph,members);

        LinearLayout card=ui.card();
        card.setPadding(ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16),ui.dp(Theme.SPACE_16));

        LinearLayout top=ui.row();
        FrameLayout badge=new FrameLayout(this);
        badge.setBackground(Theme.oval(Theme.PRIMARY_SOFT));
        badge.addView(ui.icon(R.drawable.ic_layers,Theme.PRIMARY),new FrameLayout.LayoutParams(ui.dp(18),ui.dp(18),Gravity.CENTER));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ui.dp(36),ui.dp(36));
        bp.rightMargin=ui.dp(Theme.SPACE_12);
        top.addView(badge,bp);

        LinearLayout copy=ui.col();
        TextView name=ui.type(group.optString("name"),Theme.T_HEADLINE);
        copy.addView(name);
        TextView sub=ui.type(members.length()+" 个词 · "+edgeCount+" 条连线",Theme.T_CAPTION);
        copy.addView(sub,ui.lpMargin(2,0));
        top.addView(copy,WordUi.weight());
        top.addView(ui.icon(R.drawable.ic_chevron_right,Theme.INK_3),new LinearLayout.LayoutParams(ui.dp(Theme.ICON_SMALL),ui.dp(Theme.ICON_SMALL)));
        card.addView(top,WordUi.lp());

        // Preview chips of core words inside this mind map.
        if(members.length()>0){
            LinearLayout preview=ui.row();
            int cap=Math.min(5,members.length());
            for(int i=0;i<cap;i++){
                String wordName=nameOf(catalog,members.optString(i));
                int colorIdx=i%WordMapView.BRANCH_INK.length;
                TextView c=ui.chip(wordName,WordMapView.BRANCH_SOFT[colorIdx],WordMapView.BRANCH_INK[colorIdx]);
                c.setTypeface(Theme.SERIF);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
                if(i>0)cp.leftMargin=ui.dp(6);
                preview.addView(c,cp);
            }
            if(members.length()>cap){
                TextView more=ui.type("+"+(members.length()-cap),Theme.T_CAPTION);
                LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
                mp.leftMargin=ui.dp(6);
                preview.addView(more,mp);
            }
            android.widget.HorizontalScrollView hsv=new android.widget.HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(preview);
            card.addView(hsv,ui.lpMargin(Theme.SPACE_12,0));
        }

        card.setClickable(true);card.setFocusable(true);
        Theme.applyRipple(card,card.getBackground(),Theme.RIPPLE_LIGHT);
        Motion.press(card,.985f);
        card.setOnClickListener(v->openGroup(id));
        ui.body.addView(card,ui.lpMargin(0,Theme.SPACE_12));
    }

    private int countGroupLinks(JSONObject graph,JSONArray members){
        Set<String> set=new HashSet<>();
        for(int i=0;i<members.length();i++)set.add(members.optString(i));
        int count=0;
        JSONArray links=graph.optJSONArray("links");
        for(int i=0;links!=null&&i<links.length();i++){
            JSONObject l=links.optJSONObject(i);
            if(l!=null&&set.contains(l.optString("a"))&&set.contains(l.optString("b")))count++;
        }
        return count;
    }

    private void overviewCreateMenu(){
        new Sheet(this).title("新建词汇关系")
            .item(R.drawable.ic_plus,"手动创建主题归纳","起一个中心主题名（如“优秀的”相关），再添加分支词",()->createGroup(false))
            .item(R.drawable.ic_sparkles,"AI 自动整理已有词组","挑一个单词计划，自动聚类成思维导图",this::pickTaskForAiOrganize)
            .secondary("取消",null).show();
    }

    // ── Group Mind-Map Canvas & List Mode ─────────────────────────────────────

    private void showGroup(){
        JSONObject graph=WordRelations.graph(this);
        JSONObject group=findGroup(graph,groupId);
        if(group==null){groupId="";show();return;}
        final JSONObject current=group;
        JSONArray catalog=catalog();

        if(!listMode){
            WordMapView.Node tree=WordMapView.buildGroupTree(graph,catalog,groupId,unmasteredWrongIds());
            showCanvasScreen(current.optString("name"),tree,v->groupMenu(current),true,false,null);
            return;
        }

        exitLandscapeIfNeeded();
        ui.start(current.optString("name")+" · 列表",R.drawable.ic_ellipsis,"更多",v->groupMenu(current));
        LinearLayout topBar=ui.row();
        topBar.addView(ui.pill("切换关系图视图",R.drawable.ic_layers,v->{listMode=false;show();}));
        ui.body.addView(topBar,ui.lpMargin(0,Theme.SPACE_12));

        JSONArray meta=WordGraph.memberMetaOf(graph,groupId);
        if(meta.length()==0){
            ui.body.addView(ui.empty(R.drawable.ic_layers,"这组词汇关系还没有词","把已经学过的词加进来，或直接创建核心分支。",null,null));
        }else{
            LinearLayout list=ui.group();
            for(int i=0;i<meta.length();i++){
                JSONObject m=meta.optJSONObject(i);
                if(m==null)continue;
                final String id=m.optString("wordId");
                final boolean isRoot=m.optBoolean("isRoot",false);
                JSONObject card=findCard(catalog,id);
                String title=card==null||card.optString("english").isEmpty()?nameOf(catalog,id):card.optString("english");
                String subtitle=(isRoot?"[核心分支] ":"")+(card==null?"":card.optString("chinese"));
                ui.item(list,0,title,subtitle,null,true,v->nodeActions(id,title,card==null?"":card.optString("chinese"),""));
            }
            ui.body.addView(list,WordUi.lp());
        }
        ui.pin(ui.primaryButton("添加单词到此组",v->pickMember(true)));
    }

    // ── Word Focus Mind-Map & List Mode ───────────────────────────────────────

    private void showWord(){
        JSONArray catalog=catalog();
        JSONObject self=findCard(catalog,wordId);
        if(self!=null){english=self.optString("english",english);chinese=self.optString("chinese",chinese);}
        JSONObject graph=WordRelations.graph(this);
        JSONArray related;
        try{related=WordGraph.related(graph,catalog,wordId);}catch(Exception e){related=new JSONArray();}
        JSONArray groups;
        try{groups=WordGraph.groupsFor(graph,wordId);}catch(Exception e){groups=new JSONArray();}

        if(!listMode&&(related.length()>0||groups.length()>0)){
            WordMapView.Node tree=WordMapView.buildWordTree(graph,catalog,wordId,english,chinese,unmasteredWrongIds());
            showCanvasScreen(english.isEmpty()?"相关词":english,tree,v->wordMenu(),false,false,null);
            return;
        }

        exitLandscapeIfNeeded();
        ui.start(english.isEmpty()?"相关词":english,R.drawable.ic_ellipsis,"更多",v->wordMenu());
        TextView head=ui.type(english.isEmpty()?"（未命名单词）":english,Theme.T_DISPLAY);
        head.setTypeface(Theme.SERIF);
        ui.body.addView(head,WordUi.lp());
        if(!chinese.isEmpty()){
            TextView gloss=ui.type(chinese,Theme.T_BODY);
            gloss.setPadding(0,ui.dp(4),0,0);
            ui.body.addView(gloss,WordUi.lp());
        }
        if(related.length()>0||groups.length()>0){
            LinearLayout sw=ui.row();
            sw.addView(ui.pill("切换关系图视图",R.drawable.ic_layers,v->{listMode=false;show();}));
            ui.body.addView(sw,ui.lpMargin(Theme.SPACE_12,0));
        }

        if(related.length()==0&&groups.length()==0){
            ui.gap(ui.body,Theme.SPACE_20);
            ui.body.addView(ui.empty(R.drawable.ic_layers,"还没有相关词","可以连上近义词、扩展派生词(扩)、同根词、反义词或形近词，也可以把它放进主题归纳。",null,null));
        }else{
            for(int k=0;k<WordGraph.KINDS.length;k++){
                addKind(related,WordGraph.KINDS[k],WordGraph.KIND_LABELS[k]);
            }
        }
        if(groups.length()>0){
            ui.section(ui.body,"所属归纳",null);
            LinearLayout list=ui.group();
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null)continue;
                final String id=group.optString("id");
                ui.item(list,R.drawable.ic_layers,group.optString("name"),memberNames(graph,catalog,id),null,true,v->openGroup(id));
            }
            ui.body.addView(list,WordUi.lp());
        }
        Button speak=ui.secondaryButton("朗读",v->speech.speak(english,"relation"));
        Button add=ui.primaryButton("添加相关词",v->addLinkFrom(wordId,english));
        ui.pinPair(speak,add);
    }

    // ── Task Mind-Map & Draft Preview ─────────────────────────────────────────

    private void showTaskMap(){
        JSONObject task=Store.find(this,taskId);
        if(task==null){taskId=0;show();return;}
        JSONObject graph=WordRelations.graph(this);
        JSONArray catalog=catalog();
        JSONArray taskWords=task.optJSONArray("words");

        // Discover distinct word categories inside this plan so multi-cluster plans stay super clear.
        LinkedHashSet<String> catSet=new LinkedHashSet<>();
        for(int i=0;taskWords!=null&&i<taskWords.length();i++){
            JSONObject w=taskWords.optJSONObject(i);
            String c=w==null?"":w.optString("category","").trim();
            if(!c.isEmpty())catSet.add(c);
        }
        List<String> cats=new ArrayList<>(catSet);
        if(taskCategory.isEmpty()&&!cats.isEmpty())taskCategory=cats.get(0);
        if(!taskCategory.isEmpty()&&!"__all__".equals(taskCategory)&&!catSet.contains(taskCategory)){
            taskCategory=cats.isEmpty()?"":cats.get(0);
        }
        String activeFilter="__all__".equals(taskCategory)?"":taskCategory;
        WordMapView.Node tree=WordMapView.buildTaskTree(graph,catalog,task.optString("title"),taskWords,unmasteredWrongIds(),activeFilter);

        View switcher=null;
        if(cats.size()>=2){
            LinearLayout row=ui.row();
            for(String cat:cats){
                addCanvasSwitcherChip(row,cat,cat.equals(taskCategory),()->{taskCategory=cat;show();});
            }
            addCanvasSwitcherChip(row,"全部","__all__".equals(taskCategory),()->{taskCategory="__all__";show();});
            HorizontalScrollView hsv=new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(row);
            switcher=hsv;
        }
        String heading=activeFilter.isEmpty()?task.optString("title"):(task.optString("title")+" · "+activeFilter);
        showCanvasScreen(heading,tree,v->taskMapMenu(task),false,false,switcher);
    }

    private void showDraftPreview(){
        JSONArray catalog=catalog();
        JSONArray groups=previewGraph.optJSONArray("groups");
        WordMapView.Node tree;
        View switcher=null;
        if(groups!=null&&groups.length()>0){
            previewGroupIndex=Math.max(0,Math.min(previewGroupIndex,groups.length()-1));
            tree=WordMapView.buildGroupTree(previewGraph,catalog,groups.optJSONObject(previewGroupIndex).optString("id"),null);
            if(groups.length()>=2){
                LinearLayout row=ui.row();
                for(int i=0;i<groups.length();i++){
                    final int idx=i;
                    String gname=groups.optJSONObject(i).optString("name","分组 "+(i+1));
                    addCanvasSwitcherChip(row,gname,idx==previewGroupIndex,()->{previewGroupIndex=idx;show();});
                }
                HorizontalScrollView hsv=new HorizontalScrollView(this);
                hsv.setHorizontalScrollBarEnabled(false);
                hsv.addView(row);
                switcher=hsv;
            }
        }else{
            tree=WordMapView.buildTaskTree(previewGraph,catalog,previewTitle.isEmpty()?"词汇关系预览":previewTitle,draftWords,null);
        }
        showCanvasScreen(previewTitle.isEmpty()?"词汇关系预览":previewTitle,tree,null,false,true,switcher);
    }

    private void addCanvasSwitcherChip(LinearLayout row,String label,boolean active,Runnable action){
        TextView chip=ui.chip(label,active?Theme.PRIMARY:Theme.SURFACE,active?Color.WHITE:Theme.INK_2);
        chip.setPadding(ui.dp(10),ui.dp(5),ui.dp(10),ui.dp(5));
        if(!active)chip.setBackground(Theme.shape(this,Theme.SURFACE,10,Theme.HAIRLINE,1));
        chip.setClickable(true);chip.setFocusable(true);
        Theme.applyRipple(chip,chip.getBackground(),active?Theme.RIPPLE_DARK:Theme.RIPPLE_LIGHT);
        Motion.press(chip,.95f);
        chip.setOnClickListener(v->{Feedback.tick(v);action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        if(row.getChildCount()>0)lp.leftMargin=ui.dp(6);
        row.addView(chip,lp);
    }

    // ── Shared Full-Canvas Mind-Map Screen (Portrait & Landscape) ─────────────

    private void showCanvasScreen(String title,WordMapView.Node tree,View.OnClickListener menuAction,boolean isGroupCanvas,boolean readOnly,View switcherBar){
        boolean isWide=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        landscape=isWide;

        LinearLayout screen=ui.col();
        screen.setBackgroundColor(Theme.BG);
        LinearLayout topBar=ui.pageChrome(title,R.drawable.ic_arrow_left,"返回",menuAction==null?0:R.drawable.ic_ellipsis,menuAction==null?null:"更多",menuAction);
        if(isWide){
            topBar.setMinimumHeight(ui.dp(44));
            topBar.setPadding(ui.dp(Theme.SPACE_4),ui.dp(2),ui.dp(Theme.SPACE_4),ui.dp(2));
        }
        screen.addView(topBar);

        View topHairline=new View(this);
        topHairline.setBackgroundColor(Theme.HAIRLINE);
        screen.addView(topHairline,new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(.75f))));

        if(switcherBar!=null){
            LinearLayout barWrap=ui.row();
            barWrap.setBackgroundColor(Theme.BG);
            barWrap.setPadding(ui.dp(Theme.SPACE_12),ui.dp(isWide?4:6),ui.dp(Theme.SPACE_12),ui.dp(isWide?4:6));
            barWrap.addView(switcherBar,WordUi.lp());
            screen.addView(barWrap,WordUi.lp());
            View subHairline=new View(this);
            subHairline.setBackgroundColor(Theme.HAIRLINE);
            screen.addView(subHairline,new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(.75f))));
        }

        FrameLayout canvasWrap=new FrameLayout(this);
        WordMapView map=new WordMapView(this);
        map.setShowChinese(showChinese);
        map.setTree(tree);
        map.setContentDescription("词汇关系思维导图，可双指缩放、拖动查看、横屏显示或点按单词操作");
        map.setListener(new WordMapView.Listener(){
            @Override public void onNodeTap(WordMapView.Node node){
                if(readOnly){
                    if(!node.isCenter&&!node.id.startsWith("__")){
                        speech.speak(node.label,"relation");
                        ui.toast(node.label+(node.sublabel.isEmpty()?"":" · "+node.sublabel));
                    }
                    return;
                }
                if(node.isCenter){
                    if(isGroupCanvas)centerGroupActions();
                    else if(!wordId.isEmpty())wordMenu();
                    else ui.toast("点击任意单词节点可朗读或延伸关联");
                    return;
                }
                if(node.id.startsWith("__group__")){
                    openGroup(node.id.substring("__group__".length()));
                    return;
                }
                nodeActions(node.id,node.label,node.sublabel,node.linkId);
            }
            @Override public void onNodeLongPress(WordMapView.Node node){
                if(readOnly||node.isCenter||node.id.startsWith("__"))return;
                addLinkFrom(node.id,node.label);
            }
        });
        activeMap=map;
        canvasWrap.addView(map,new FrameLayout.LayoutParams(-1,-1));

        // Floating top-right badges: Landscape/Portrait toggle, Chinese gloss toggle, optional List toggle.
        LinearLayout floating=ui.row();
        Button orientBtn=ui.quietPill(isWide?"竖屏":"横屏",0,v->{
            Feedback.tick(v);
            setCanvasLandscape(!isWide);
        });
        floating.addView(orientBtn,new LinearLayout.LayoutParams(-2,ui.dp(32)));

        Button zhBtn=ui.quietPill(showChinese?"隐藏释义":"显示释义",0,v->{
            showChinese=!showChinese;
            map.setShowChinese(showChinese);
            ((Button)v).setText(showChinese?"隐藏释义":"显示释义");
        });
        LinearLayout.LayoutParams zhLp=new LinearLayout.LayoutParams(-2,ui.dp(32));
        zhLp.leftMargin=ui.dp(6);
        floating.addView(zhBtn,zhLp);

        if(!readOnly&&(isGroupCanvas||!wordId.isEmpty())){
            Button listBtn=ui.quietPill("列表",R.drawable.ic_list,v->{listMode=true;show();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,ui.dp(32));
            lp.leftMargin=ui.dp(6);
            floating.addView(listBtn,lp);
        }
        if(isWide){
            LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-2,-2);
            tlp.rightMargin=ui.dp(4);
            topBar.addView(floating,Math.max(0,topBar.getChildCount()-1),tlp);
        }else{
            FrameLayout.LayoutParams flp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
            flp.topMargin=ui.dp(10);flp.rightMargin=ui.dp(12);
            canvasWrap.addView(floating,flp);
        }

        // Legend hint only in portrait so landscape gives 100% canvas height to the flowchart.
        if(!isWide){
            TextView hint=ui.type(readOnly?"双指缩放 · 横屏可全览 · 轻点单词朗读":"双指缩放 · 右上可切横屏 · 轻点节点编辑/延伸",Theme.T_CAPTION);
            hint.setBackground(Theme.shape(this,Theme.alpha(Theme.BG,.88f),10));
            hint.setPadding(ui.dp(10),ui.dp(4),ui.dp(10),ui.dp(4));
            FrameLayout.LayoutParams hlp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            hlp.bottomMargin=ui.dp(10);
            canvasWrap.addView(hint,hlp);
        }

        screen.addView(canvasWrap,new LinearLayout.LayoutParams(-1,0,1));

        // Bottom control dock (compact in landscape).
        View botHairline=new View(this);
        botHairline.setBackgroundColor(Theme.HAIRLINE);
        screen.addView(botHairline,new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(.75f))));

        int btnH=ui.dp(isWide?36:44);
        LinearLayout dock=ui.row();
        dock.setBackgroundColor(Theme.SURFACE);
        dock.setPadding(ui.dp(Theme.SPACE_12),ui.dp(isWide?4:Theme.SPACE_8),ui.dp(Theme.SPACE_12),ui.dp(isWide?6:Theme.SPACE_12));
        Button zoomOut=ui.ghostButton("−",v->map.zoomBy(.78f));
        zoomOut.setPadding(0,0,0,0);zoomOut.setMinHeight(btnH);zoomOut.setMinimumHeight(btnH);
        dock.addView(zoomOut,new LinearLayout.LayoutParams(btnH,btnH));
        Button fit=ui.ghostButton("适应",v->map.fitToView(true));
        fit.setTextSize(13);fit.setMinHeight(btnH);fit.setMinimumHeight(btnH);
        fit.setPadding(ui.dp(10),0,ui.dp(10),0);
        LinearLayout.LayoutParams fitLp=new LinearLayout.LayoutParams(-2,btnH);
        fitLp.leftMargin=ui.dp(6);
        dock.addView(fit,fitLp);
        Button zoomIn=ui.ghostButton("+",v->map.zoomBy(1.28f));
        zoomIn.setPadding(0,0,0,0);zoomIn.setMinHeight(btnH);zoomIn.setMinimumHeight(btnH);
        LinearLayout.LayoutParams ziLp=new LinearLayout.LayoutParams(btnH,btnH);
        ziLp.leftMargin=ui.dp(6);
        dock.addView(zoomIn,ziLp);

        dock.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));

        if(!readOnly){
            if(isGroupCanvas){
                Button addBranch=ui.primaryButton("添加分支词",v->pickMember(true));
                addBranch.setMinHeight(btnH);addBranch.setMinimumHeight(btnH);
                addBranch.setPadding(ui.dp(16),0,ui.dp(16),0);
                dock.addView(addBranch,new LinearLayout.LayoutParams(-2,btnH));
            }else if(!wordId.isEmpty()){
                Button addRel=ui.primaryButton("添加相关词",v->addLinkFrom(wordId,english));
                addRel.setMinHeight(btnH);addRel.setMinimumHeight(btnH);
                addRel.setPadding(ui.dp(16),0,ui.dp(16),0);
                dock.addView(addRel,new LinearLayout.LayoutParams(-2,btnH));
            }else if(taskId>0){
                JSONObject task=Store.find(this,taskId);
                Button aiOrg=ui.primaryButton("AI 整理导图",v->{if(task!=null)aiOrganizeTask(task);});
                aiOrg.setMinHeight(btnH);aiOrg.setMinimumHeight(btnH);
                aiOrg.setPadding(ui.dp(16),0,ui.dp(16),0);
                dock.addView(aiOrg,new LinearLayout.LayoutParams(-2,btnH));
            }
        }
        screen.addView(dock,WordUi.lp());
        setContentView(screen);
        ui.systemBars();
        Notice.setBottomOffset(this,isWide?52:72);
    }

    // ── Node & Group Actions ──────────────────────────────────────────────────

    private void centerGroupActions(){
        JSONObject graph=WordRelations.graph(this);
        JSONObject group=findGroup(graph,groupId);
        if(group==null)return;
        new Sheet(this).title(group.optString("name")).message("中心主题节点")
            .item(R.drawable.ic_plus,"添加核心分支词","直接连在中心主题上的词",()->pickMember(true))
            .item(R.drawable.ic_pen_line,"重命名主题",()->renameGroup(group))
            .item(R.drawable.ic_list,"切换为列表管理",()->{listMode=true;show();})
            .secondary("关闭",null).show();
    }

    private void nodeActions(String targetId,String targetEnglish,String targetChinese,String linkId){
        Sheet sheet=new Sheet(this).title(targetEnglish).message(targetChinese);
        if(sheet.titleView()!=null)sheet.titleView().setTypeface(Theme.SERIF);
        sheet.item(R.drawable.ic_volume_2,"朗读单词",()->speech.speak(targetEnglish,"relation"));
        sheet.item(R.drawable.ic_plus,"从此词延伸关联","连接 扩 / 近义 / 同根 / 反义 / 词形",()->addLinkFrom(targetId,targetEnglish));
        if(!targetId.equals(wordId)){
            sheet.item(R.drawable.ic_layers,"以此词为中心查看",()->openWord(targetId));
        }
        if(!groupId.isEmpty()){
            JSONObject graph=WordRelations.graph(this);
            boolean rootNow=isGroupRoot(graph,groupId,targetId);
            sheet.item(R.drawable.ic_target,rootNow?"取消核心分支地位":"设为直接连中心的分支词",()->{
                try{WordRelations.setMemberRoot(this,groupId,targetId,!rootNow);show();}
                catch(Exception e){ui.error(message(e));}
            });
            sheet.dangerItem(R.drawable.ic_x,"移出此关联图",()->{
                try{WordRelations.removeMember(this,groupId,targetId);ui.toast("已移出");show();}
                catch(Exception e){ui.error(message(e));}
            });
        }
        if(linkId!=null&&!linkId.isEmpty()){
            sheet.dangerItem(R.drawable.ic_trash_2,"断开这条连线",()->{
                try{WordRelations.unlink(this,linkId);ui.toast("已断开连线");show();}
                catch(Exception e){ui.error(message(e));}
            });
        }
        sheet.secondary("关闭",null).show();
    }

    private boolean isGroupRoot(JSONObject graph,String gid,String wid){
        JSONArray meta=WordGraph.memberMetaOf(graph,gid);
        for(int i=0;i<meta.length();i++){
            JSONObject m=meta.optJSONObject(i);
            if(m!=null&&wid.equals(m.optString("wordId")))return m.optBoolean("isRoot",false);
        }
        return false;
    }

    private void addKind(JSONArray related,String kind,String title){
        LinearLayout list=null;
        for(int i=0;i<related.length();i++){
            JSONObject row=related.optJSONObject(i);
            if(row==null||!"link".equals(row.optString("via"))||!kind.equals(row.optString("kind")))continue;
            if(list==null){ui.section(ui.body,title,null);list=ui.group();}
            final JSONObject item=row;
            String subtitle=row.optString("chinese");
            if(!row.optString("note").isEmpty())subtitle=(subtitle.isEmpty()?"":subtitle+" · ")+row.optString("note");
            ui.item(list,0,display(row),subtitle,null,true,v->nodeActions(item.optString("wordId"),display(item),item.optString("chinese"),item.optString("linkId")));
        }
        if(list!=null)ui.body.addView(list,WordUi.lp());
    }

    private void wordMenu(){
        new Sheet(this).title(english.isEmpty()?"相关词":english)
            .item(R.drawable.ic_layers,"归入主题归纳","放进已有归纳，或新建一组",this::addToGroup)
            .item(R.drawable.ic_spell_check,"本机形近/派生建议","离线按词形与拼写查找",this::suggestLocal)
            .item(R.drawable.ic_sparkles,"AI 建议关联","从已有词库挑选近义/扩/同根/反义词",this::suggestAi)
            .item(R.drawable.ic_list,listMode?"切换关系图视图":"切换列表视图",()->{listMode=!listMode;show();})
            .item(R.drawable.ic_book_open,"全部词汇关系",()->{groupId="";wordId="";taskId=0;listMode=false;show();})
            .secondary("关闭",null).show();
    }

    private void groupMenu(JSONObject group){
        new Sheet(this).title(group.optString("name"))
            .item(R.drawable.ic_plus,"添加分支词",()->pickMember(true))
            .item(R.drawable.ic_list,listMode?"切换关系图视图":"切换列表视图",()->{listMode=!listMode;show();})
            .item(R.drawable.ic_pen_line,"重命名主题",()->renameGroup(group))
            .dangerItem(R.drawable.ic_trash_2,"删除这组归纳",()->Sheet.confirm(this,"删除这组归纳？","只解散主题分组，单词卡和单独的词间连线都保留。","取消","删除",true,()->{
                try{WordRelations.removeGroup(this,groupId);groupId="";ui.toast("已删除归纳");show();}catch(Exception e){ui.error(message(e));}
            }))
            .secondary("关闭",null).show();
    }

    private void taskMapMenu(JSONObject task){
        new Sheet(this).title(task.optString("title"))
            .item(R.drawable.ic_sparkles,"AI 自动整理词汇关系","把本组单词聚成中心主题与层级连线",()->aiOrganizeTask(task))
            .item(R.drawable.ic_book_open,"全部词汇关系",()->{taskId=0;taskCategory="";groupId="";wordId="";show();})
            .secondary("关闭",null).show();
    }

    // ── Adding Links (Supports All 5 Kinds: 近义 / 扩 / 同根 / 反义 / 词形) ────

    private void addLinkFrom(String sourceId,String sourceEnglish){
        JSONArray others=othersExcept(sourceId);
        if(others.length()==0){ui.error("词表里还没有其他已保存的单词");return;}
        final int[] kindIndex={1}; // Default to 扩 or 近义; let user pick with one tap!
        LinearLayout form=ui.col();

        // Kind selector chips: 近义 / 扩 / 同根 / 反义 / 词形
        LinearLayout kindRow=ui.row();
        final TextView[] chips=new TextView[WordGraph.KINDS.length];
        Runnable paintKinds=()->{
            for(int i=0;i<chips.length;i++){
                boolean on=kindIndex[0]==i;
                chips[i].setBackground(Theme.shape(this,on?Theme.PRIMARY:Theme.SUNK,10));
                chips[i].setTextColor(on?Color.WHITE:Theme.INK_2);
            }
        };
        for(int i=0;i<WordGraph.KINDS.length;i++){
            final int idx=i;
            TextView c=ui.chip(WordGraph.KIND_LABELS[i],Theme.SUNK,Theme.INK_2);
            c.setPadding(ui.dp(12),ui.dp(7),ui.dp(12),ui.dp(7));
            c.setClickable(true);c.setFocusable(true);
            Motion.press(c,.95f);
            c.setOnClickListener(v->{kindIndex[0]=idx;paintKinds.run();Feedback.tick(v);});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
            if(i>0)cp.leftMargin=ui.dp(6);
            kindRow.addView(c,cp);
            chips[i]=c;
        }
        kindIndex[0]=0;
        paintKinds.run();
        android.widget.HorizontalScrollView khsv=new android.widget.HorizontalScrollView(this);
        khsv.setHorizontalScrollBarEnabled(false);
        khsv.addView(kindRow);
        form.addView(khsv,WordUi.lp());
        ui.gap(form,Theme.SPACE_12);

        EditText noteField=WordUi.input(this,"自定义连线标注（可留空，默认显示类型名）","");
        noteField.setSingleLine(true);
        form.addView(noteField,WordUi.lp());
        ui.gap(form,Theme.SPACE_12);

        EditText query=WordUi.input(this,"搜索要关联的英文或中文","");
        query.setSingleLine(true);
        form.addView(query,WordUi.lp());
        LinearLayout list=ui.group();
        LinearLayout.LayoutParams listLp=WordUi.lp();
        listLp.topMargin=ui.dp(Theme.SPACE_12);
        form.addView(list,listLp);
        TextView more=ui.type("",Theme.T_CAPTION);
        form.addView(more,ui.lpMargin(Theme.SPACE_8,0));
        final Sheet[] dialog=new Sheet[1];
        Runnable refill=()->fillWordList(list,more,others,query.getText().toString(),card->{
            try{
                String kind=WordGraph.KINDS[kindIndex[0]];
                String customNote=noteField.getText().toString().trim();
                String targetId=card.optString("wordId");
                WordRelations.link(this,sourceId,targetId,kind,customNote);
                if(!groupId.isEmpty()){
                    WordRelations.addMember(this,groupId,targetId,false,-1);
                }
                if(dialog[0]!=null)dialog[0].dismiss();
                ui.toast("已连上 "+sourceEnglish+" —"+(customNote.isEmpty()?WordGraph.kindLabel(kind):customNote)+"— "+card.optString("english"));
                show();
            }catch(Exception e){ui.error(message(e));}
        });
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){refill.run();}
            public void afterTextChanged(Editable s){}
        });
        refill.run();
        dialog[0]=new Sheet(this).title("从 "+sourceEnglish+" 延伸关联").message("选择关系类型后，点选目标单词即可连线。").content(form).secondary("取消",null).show();
    }

    private void addToGroup(){
        JSONObject graph=WordRelations.graph(this);
        JSONArray groups=graph.optJSONArray("groups");
        Sheet sheet=new Sheet(this).title("归入关联图").message("归入后会出现在这张主题关联图中。");
        if(groups!=null){
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null)continue;
                final String id=group.optString("id");
                sheet.item(R.drawable.ic_layers,group.optString("name"),WordGraph.kindLabel(group.optString("kind")),()->{
                    try{WordRelations.addMember(this,id,wordId,true,-1);ui.toast("已加入「"+group.optString("name")+"」");show();}catch(Exception e){ui.error(message(e));}
                });
            }
        }
        sheet.item(R.drawable.ic_plus,"新建主题关联图",()->createGroup(true)).secondary("取消",null).show();
    }

    private void createGroup(boolean includeCurrentWord){
        LinearLayout form=ui.col();
        EditText name=WordUi.input(this,"例如：“优秀的”相关","");
        name.setSingleLine(true);
        form.addView(name,WordUi.lp());
        ui.gap(form,Theme.SPACE_12);
        final int[] kindIndex={0};
        form.addView(ui.segmented(new String[]{"主题/近义","派生/词根","词形"},0,index->kindIndex[0]=index),WordUi.lp());
        new Sheet(this).title("新建关联图").message("给中心主题起个名字，建好后可在画布上添加分支词与连线。").content(form).secondary("取消",null).primaryIf("创建",()->{
            String title=name.getText().toString().trim();
            if(title.isEmpty()){name.setError("请填写主题名称");name.requestFocus();return false;}
            try{
                String kind=kindIndex[0]==2?WordGraph.FORM:(kindIndex[0]==1?WordGraph.DERIVE:WordGraph.MEANING);
                String id=WordRelations.addGroup(this,title,kind,includeCurrentWord?wordId:"",true);
                ui.toast("已建立「"+title+"」");
                listMode=false;
                openGroup(id);
                return true;
            }catch(Exception e){ui.error(message(e));return false;}
        }).show();
    }

    private void renameGroup(JSONObject group){
        EditText name=WordUi.input(this,"主题名称",group.optString("name"));
        name.setSingleLine(true);
        new Sheet(this).title("重命名主题").content(name).secondary("取消",null).primaryIf("保存",()->{
            try{WordRelations.renameGroup(this,groupId,name.getText().toString());ui.toast("名称已更新");show();return true;}
            catch(IllegalArgumentException e){name.setError(e.getMessage());name.requestFocus();return false;}
            catch(Exception e){ui.error(message(e));return false;}
        }).show();
    }

    private void pickMember(boolean asRoot){
        JSONArray others=catalog();
        if(others.length()==0){ui.error("词表里还没有已保存的单词");return;}
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
        Runnable refill=()->fillWordList(list,more,others,query.getText().toString(),card->{
            try{
                WordRelations.addMember(this,groupId,card.optString("wordId"),asRoot,-1);
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
        dialog[0]=new Sheet(this).title("添加分支词到关联图").content(form).secondary("取消",null).show();
    }

    // ── AI Auto-Organize Plan into Mind-Map ───────────────────────────────────

    private void pickTaskForAiOrganize(){
        if(busy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        JSONArray tasks=Store.tasks(this);
        ArrayList<JSONObject> valid=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<tasks.length();i++){
            JSONObject t=tasks.optJSONObject(i);
            if(t==null)continue;
            JSONArray w=t.optJSONArray("words");
            if(w==null||w.length()<2)continue;
            valid.add(t);
            labels.add(t.optString("title")+" · "+w.length()+" 词");
        }
        if(valid.isEmpty()){ui.toast("至少需要一个包含 2 个以上单词的词组");return;}
        Sheet.pick(this,"选择要整理的词组",labels.toArray(new String[0]),0,i->aiOrganizeTask(valid.get(i)));
    }

    private void aiOrganizeTask(JSONObject task){
        if(busy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        JSONArray words=task.optJSONArray("words");
        if(words==null||words.length()<2){ui.toast("词组单词太少，无法整理");return;}
        StringBuilder sb=new StringBuilder();
        int cap=Math.min(words.length(),60);
        for(int i=0;i<cap;i++){
            JSONObject w=words.optJSONObject(i);
            if(w==null)continue;
            if(sb.length()>0)sb.append('\n');
            sb.append(w.optString("english"));
            if(!w.optString("chinese").isEmpty())sb.append(" — ").append(w.optString("chinese"));
        }
        busy=true;
        ui.toast("AI 正在把「"+task.optString("title")+"」整理成关联图…");
        pending=AiClient.chatJson(this,AiPrompts.systemOrganizeMindMap(),AiPrompts.userOrganizeMindMap(task.optString("title"),sb.toString()),new AiClient.Callback(){
            @Override public void onResult(JSONObject json){
                busy=false;
                if(isFinishing()||isDestroyed())return;
                try{
                    JSONArray g=json.optJSONArray("groups"),l=json.optJSONArray("links");
                    int[] counts=WordRelations.applyImported(RelationActivity.this,words,g,l);
                    if(counts[0]==0&&counts[1]==0){ui.toast("未生成新的关联");return;}
                    ui.toast("已生成 "+counts[0]+" 张关联图 · "+counts[1]+" 条连线");
                    JSONObject saved=WordRelations.graph(RelationActivity.this);
                    JSONArray savedGroups=saved.optJSONArray("groups");
                    if(savedGroups!=null&&savedGroups.length()>0){
                        groupId=savedGroups.getJSONObject(savedGroups.length()-1).optString("id");
                        wordId="";taskId=0;listMode=false;
                    }
                    show();
                }catch(Exception e){ui.error(message(e));}
            }
            @Override public void onError(String message){busy=false;if(!isFinishing())ui.error(message);}
        });
    }

    // ── Local & AI Suggestions for Single Word ────────────────────────────────

    private void suggestLocal(){
        JSONArray catalog=catalog();
        JSONArray found;
        try{found=WordGraph.suggestForm(catalog,wordId,english,8);}catch(Exception e){ui.error(message(e));return;}
        if(found.length()==0){ui.toast("没有发现明显的派生或形近词");return;}
        confirmSuggestions("本机派生 / 形近建议",found);
    }

    private void suggestAi(){
        if(busy)return;
        if(!AiSettings.ready(this)){ui.error(AiSettings.notReadyMessage(this));return;}
        JSONArray others=othersExcept(wordId);
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
                confirmSuggestions("AI 建议的关联",matched);
            }
            @Override public void onError(String message){busy=false;ui.error(message);}
        });
    }

    private JSONArray matchSuggestions(JSONArray links){
        JSONArray catalog=catalog();
        JSONArray out=new JSONArray();
        HashSet<String> seen=new HashSet<>();
        if(links==null)return out;
        for(int i=0;i<links.length();i++){
            JSONObject link=links.optJSONObject(i);
            if(link==null)continue;
            String kind=WordGraph.normalizeKind(link.optString("kind"));
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

    private void confirmSuggestions(String title,JSONArray rows){
        LinearLayout form=ui.col();
        ArrayList<CheckBox> boxes=new ArrayList<>();
        ArrayList<JSONObject> kept=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row==null)continue;
            String kind=WordGraph.normalizeKind(row.optString("kind",WordGraph.FORM));
            String label=row.optString("english")+"  ["+WordGraph.kindLabel(kind)+"]";
            String reason=row.optString("reason");
            if(!reason.isEmpty())label=label+" · "+reason;
            CheckBox box=ui.check(label,true);
            form.addView(box,WordUi.lp());
            boxes.add(box);
            try{kept.add(new JSONObject(row.toString()).put("kind",kind));}catch(Exception ignored){kept.add(row);}
        }
        new Sheet(this).title(title).message("未勾选的不会写入。").content(form)
            .secondary("取消",null)
            .primaryIf("添加",()->{
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private JSONArray othersExcept(String excludeId){
        JSONArray catalog=catalog();
        JSONArray out=new JSONArray();
        for(int i=0;i<catalog.length();i++){
            JSONObject card=catalog.optJSONObject(i);
            if(card!=null&&!excludeId.equals(card.optString("wordId"))&&!card.optString("english").trim().isEmpty())out.put(card);
        }
        return out;
    }

    private void openWord(String id){
        JSONObject card=findCard(catalog(),id);
        wordId=id;groupId="";taskId=0;listMode=false;
        english=card==null?"":card.optString("english");
        chinese=card==null?"":card.optString("chinese");
        show();
    }

    private void openGroup(String id){groupId=id;taskId=0;listMode=false;show();}

    private static JSONObject findGroup(JSONObject graph,String id){
        JSONArray groups=graph.optJSONArray("groups");
        for(int i=0;groups!=null&&i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);
            if(g!=null&&id.equals(g.optString("id")))return g;
        }
        return null;
    }

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
        for(int i=0;i<members.length()&&shown<4;i++){
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
