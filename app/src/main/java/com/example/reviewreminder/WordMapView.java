package com.example.reviewreminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Interactive Paper & Ink mind-map canvas for vocabulary relations.
 *
 * Renders a central theme or word hub with color-coded branches expanding smoothly to the left
 * and right, labeled edges (扩 / 近义 / 同根 / 反义 / 词形), optional Chinese glosses, pinch-zoom,
 * pan, double-tap fit, node tap, and long-press. Uses pure 2D Canvas primitives so it works
 * identically with hardware acceleration disabled.
 */
final class WordMapView extends View {
    /** Harmonious Paper & Ink branch palette: Pine, Slate Blue, Terracotta, Ochre, Plum. */
    static final int[] BRANCH_INK ={0xFF1F5A4C,0xFF33648B,0xFFB0503A,0xFF8A5F17,0xFF6B4E71};
    static final int[] BRANCH_SOFT={0xFFE4EEE9,0xFFE5EEF5,0xFFF6E8E3,0xFFF7EEDB,0xFFF0EBF2};

    static final class Node {
        final String id;
        final String label;
        final String sublabel;
        final boolean isCenter;
        final String edgeLabel;
        final String edgeKind;
        final String linkId;
        int branch;
        int depth;
        int side; // -1 left, 0 center, +1 right
        boolean isWrong;
        final List<Node> children=new ArrayList<>();
        float w,h,subtreeH,cx,cy;
        final RectF rect=new RectF();

        Node(String id,String label,String sublabel,boolean isCenter,String edgeLabel,String edgeKind,String linkId,int branch,int depth){
            this.id=id;
            this.label=label==null?"":label;
            this.sublabel=sublabel==null?"":sublabel;
            this.isCenter=isCenter;
            this.edgeLabel=edgeLabel==null?"":edgeLabel;
            this.edgeKind=edgeKind==null?"":edgeKind;
            this.linkId=linkId==null?"":linkId;
            this.branch=Math.abs(branch)%BRANCH_INK.length;
            this.depth=depth;
        }
    }

    interface Listener {
        void onNodeTap(Node node);
        void onNodeLongPress(Node node);
    }

    private final float density;
    private final Paint fillPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path edgePath=new Path();

    private Node root;
    private final List<Node> flat=new ArrayList<>();
    private final RectF contentBounds=new RectF();
    private String selectedId="";
    private boolean showChinese=true;
    private Listener listener;

    private final Matrix matrix=new Matrix();
    private final Matrix inverse=new Matrix();
    private float currentScale=1f;
    private float minScale=0.35f,maxScale=2.6f;
    private boolean fittedOnce;
    private ValueAnimator animator;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    WordMapView(Context c){
        super(c);
        density=c.getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setTypeface(Theme.REGULAR);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Theme.MEDIUM);
        gridPaint.setColor(Theme.alpha(Theme.INK_3,.18f));
        gridPaint.setStyle(Paint.Style.FILL);
        setBackgroundColor(Theme.BG);

        scaleDetector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                cancelAnim();
                float target=Math.max(minScale,Math.min(maxScale,currentScale*d.getScaleFactor()));
                float factor=target/currentScale;
                matrix.postScale(factor,factor,d.getFocusX(),d.getFocusY());
                updateScale();
                invalidate();
                return true;
            }
        });
        gestureDetector=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){cancelAnim();return true;}
            @Override public boolean onScroll(MotionEvent e1,MotionEvent e2,float dx,float dy){
                if(!scaleDetector.isInProgress()){
                    matrix.postTranslate(-dx,-dy);
                    invalidate();
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e){
                if(Math.abs(currentScale-fitScale())>0.25f)fitToView(true);
                else zoomBy(1.6f,e.getX(),e.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e){
                Node hit=hitTest(e.getX(),e.getY());
                if(hit!=null){
                    selectedId=hit.id;
                    Feedback.tick(WordMapView.this);
                    invalidate();
                    if(listener!=null)listener.onNodeTap(hit);
                    return true;
                }
                if(!selectedId.isEmpty()){selectedId="";invalidate();}
                return false;
            }
            @Override public void onLongPress(MotionEvent e){
                Node hit=hitTest(e.getX(),e.getY());
                if(hit!=null){
                    selectedId=hit.id;
                    if(Store.prefs(getContext()).getBoolean("haptic",true))
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    invalidate();
                    if(listener!=null)listener.onNodeLongPress(hit);
                }
            }
        });
    }

    private float dp(float v){return v*density;}
    private float sp(float v){return v*getResources().getDisplayMetrics().scaledDensity;}

    void setListener(Listener l){listener=l;}
    void setSelectedId(String id){selectedId=id==null?"":id;invalidate();}
    boolean isShowingChinese(){return showChinese;}
    void setShowChinese(boolean show){
        if(showChinese==show)return;
        showChinese=show;
        if(root!=null){layoutTree();fitToView(true);}
    }

    void setTree(Node rootNode){
        root=rootNode;
        fittedOnce=false;
        layoutTree();
        if(getWidth()>0&&getHeight()>0){fitToView(false);fittedOnce=true;}
        invalidate();
    }

    void zoomBy(float factor){zoomBy(factor,getWidth()/2f,getHeight()/2f);}
    private void zoomBy(float factor,float fx,float fy){
        float target=Math.max(minScale,Math.min(maxScale,currentScale*factor));
        Matrix next=new Matrix(matrix);
        float ratio=target/currentScale;
        next.postScale(ratio,ratio,fx,fy);
        animateMatrix(next);
    }

    void fitToView(boolean animate){
        if(root==null||getWidth()<=0||getHeight()<=0)return;
        float s=fitScale();
        Matrix target=new Matrix();
        target.postTranslate(-contentBounds.centerX(),-contentBounds.centerY());
        target.postScale(s,s);
        target.postTranslate(getWidth()/2f,getHeight()/2f);
        if(animate)animateMatrix(target);
        else{matrix.set(target);updateScale();invalidate();}
    }

    private float fitScale(){
        if(contentBounds.width()<=0||contentBounds.height()<=0||getWidth()<=0||getHeight()<=0)return 1f;
        boolean wide=getWidth()>getHeight();
        float pad=dp(wide?16:24);
        float availW=Math.max(dp(120),getWidth()-pad*2);
        float availH=Math.max(dp(100),getHeight()-pad*2);
        float s=Math.min(availW/contentBounds.width(),availH/contentBounds.height());
        minScale=Math.min(0.35f,s*0.6f);
        return Math.max(minScale,Math.min(wide?1.35f:1.22f,s));
    }

    private void animateMatrix(Matrix target){
        cancelAnim();
        if(!Motion.enabled(getContext())){matrix.set(target);updateScale();invalidate();return;}
        float[] from=new float[9],to=new float[9],cur=new float[9];
        matrix.getValues(from);target.getValues(to);
        animator=ValueAnimator.ofFloat(0f,1f);
        animator.setDuration(Theme.ENTER_MS);
        animator.setInterpolator(Motion.EASE_OUT);
        animator.addUpdateListener(a->{
            float f=(Float)a.getAnimatedValue();
            for(int i=0;i<9;i++)cur[i]=from[i]+(to[i]-from[i])*f;
            matrix.setValues(cur);
            updateScale();
            invalidate();
        });
        animator.start();
    }

    private void cancelAnim(){if(animator!=null){animator.cancel();animator=null;}}
    private void updateScale(){float[] v=new float[9];matrix.getValues(v);currentScale=Math.max(0.01f,v[Matrix.MSCALE_X]);}

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(root!=null&&w>0&&h>0&&(!fittedOnce||oldw<=0||oldh<=0||(w>h)!=(oldw>oldh))){
            fitToView(false);
            fittedOnce=true;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(getParent()!=null&&event.getActionMasked()==MotionEvent.ACTION_DOWN){
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        boolean s=scaleDetector.onTouchEvent(event);
        boolean g=gestureDetector.onTouchEvent(event);
        return s||g||super.onTouchEvent(event);
    }

    private Node hitTest(float viewX,float viewY){
        if(!matrix.invert(inverse))return null;
        float[] pt={viewX,viewY};
        inverse.mapPoints(pt);
        float pad=dp(6)/Math.max(0.5f,currentScale);
        for(int i=flat.size()-1;i>=0;i--){
            Node n=flat.get(i);
            if(pt[0]>=n.rect.left-pad&&pt[0]<=n.rect.right+pad&&pt[1]>=n.rect.top-pad&&pt[1]<=n.rect.bottom+pad)return n;
        }
        return null;
    }

    // ── Layout Engine ─────────────────────────────────────────────────────────

    private void layoutTree(){
        flat.clear();
        contentBounds.setEmpty();
        if(root==null)return;
        measureSubtree(root);

        // Balance depth-1 branches cleanly between left and right sides by total subtree height,
        // preserving reading order (first half of core roots on left, rest on right, like a book mind-map).
        List<Node> left=new ArrayList<>(),right=new ArrayList<>();
        if(root.children.size()<=2){
            for(int i=0;i<root.children.size();i++){
                Node c=root.children.get(i);
                if(i==0&&root.children.size()==2)left.add(c);
                else right.add(c);
            }
        }else{
            float totalH=0;
            for(Node c:root.children)totalH+=c.subtreeH+dp(16);
            float acc=0;
            for(int i=0;i<root.children.size();i++){
                Node c=root.children.get(i);
                float h=c.subtreeH+dp(16);
                if(left.isEmpty()||(acc+h*0.5f<=totalH*0.52f&&right.isEmpty()&&i<root.children.size()-1)){
                    left.add(c);acc+=h;
                }else{
                    right.add(c);
                }
            }
            if(right.isEmpty()&&left.size()>1)right.add(left.remove(left.size()-1));
        }

        root.side=0;
        root.cx=0;root.cy=0;
        root.rect.set(-root.w/2f,-root.h/2f,root.w/2f,root.h/2f);
        flat.add(root);

        placeSide(left,-1);
        placeSide(right,1);

        contentBounds.set(root.rect);
        for(Node n:flat)contentBounds.union(n.rect);
    }

    private void measureSubtree(Node node){
        boolean hasSub=showChinese&&!node.sublabel.isEmpty();
        if(node.isCenter){
            textPaint.setTypeface(node.id.startsWith("__")?Theme.MEDIUM:Theme.SERIF);
            textPaint.setTextSize(sp(17));
            float tw=textPaint.measureText(node.label);
            float sw=0;
            if(hasSub){subPaint.setTextSize(sp(11));sw=subPaint.measureText(clip(node.sublabel,14));}
            node.w=Math.max(dp(96),Math.max(tw,sw)+dp(32));
            node.h=hasSub?dp(54):dp(44);
        }else if(node.depth==1){
            textPaint.setTypeface(Theme.SERIF);
            textPaint.setTextSize(sp(15.5f));
            float tw=textPaint.measureText(node.label);
            float sw=0;
            if(hasSub){subPaint.setTextSize(sp(10.5f));sw=subPaint.measureText(clip(node.sublabel,12));}
            node.w=Math.max(dp(76),Math.max(tw,sw)+dp(26));
            node.h=hasSub?dp(48):dp(36);
        }else{
            textPaint.setTypeface(Theme.SERIF);
            textPaint.setTextSize(sp(14));
            float tw=textPaint.measureText(node.label);
            float sw=0;
            if(hasSub){subPaint.setTextSize(sp(10));sw=subPaint.measureText(clip(node.sublabel,12));}
            node.w=Math.max(dp(68),Math.max(tw,sw)+dp(22));
            node.h=hasSub?dp(44):dp(32);
        }

        if(node.children.isEmpty()){
            node.subtreeH=node.h;
            return;
        }
        float vGap=node.depth==0?dp(20):dp(12);
        float sum=0;
        for(int i=0;i<node.children.size();i++){
            Node child=node.children.get(i);
            measureSubtree(child);
            if(i>0)sum+=vGap;
            sum+=child.subtreeH;
        }
        node.subtreeH=Math.max(node.h,sum);
    }

    private void placeSide(List<Node> roots,int side){
        if(roots.isEmpty())return;
        float gap=dp(16);
        float totalH=0;
        for(int i=0;i<roots.size();i++){if(i>0)totalH+=gap;totalH+=roots.get(i).subtreeH;}
        float y=-totalH/2f;
        float rootEdgeGap=dp(40);
        for(Node branch:roots){
            float cy=y+branch.subtreeH/2f;
            float cx=side>0?(root.rect.right+rootEdgeGap+branch.w/2f):(root.rect.left-rootEdgeGap-branch.w/2f);
            placeNode(branch,side,cx,cy);
            y+=branch.subtreeH+gap;
        }
    }

    private void placeNode(Node node,int side,float cx,float cy){
        node.side=side;
        node.cx=cx;
        node.cy=cy;
        node.rect.set(cx-node.w/2f,cy-node.h/2f,cx+node.w/2f,cy+node.h/2f);
        flat.add(node);
        if(node.children.isEmpty())return;

        float vGap=dp(10);
        float totalH=0;
        for(int i=0;i<node.children.size();i++){if(i>0)totalH+=vGap;totalH+=node.children.get(i).subtreeH;}
        float y=cy-totalH/2f;
        for(Node child:node.children){
            float edgeGap=child.edgeLabel.isEmpty()?dp(30):dp(48);
            float childCy=y+child.subtreeH/2f;
            float childCx=side>0?(node.rect.right+edgeGap+child.w/2f):(node.rect.left-edgeGap-child.w/2f);
            placeNode(child,side,childCx,childCy);
            y+=child.subtreeH+vGap;
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        drawDotGrid(canvas);
        if(root==null)return;
        canvas.save();
        canvas.concat(matrix);
        drawEdges(canvas,root);
        for(Node n:flat)drawNode(canvas,n);
        canvas.restore();
    }

    private void drawDotGrid(Canvas canvas){
        float step=dp(24)*Math.max(0.55f,Math.min(1.6f,currentScale));
        if(step<dp(12))return;
        float[] v=new float[9];matrix.getValues(v);
        float ox=((v[Matrix.MTRANS_X]%step)+step)%step;
        float oy=((v[Matrix.MTRANS_Y]%step)+step)%step;
        float r=dp(1.1f);
        int w=getWidth(),h=getHeight();
        for(float x=ox;x<w;x+=step){
            for(float y=oy;y<h;y+=step){
                canvas.drawCircle(x,y,r,gridPaint);
            }
        }
    }

    private void drawEdges(Canvas canvas,Node parent){
        for(Node child:parent.children){
            int color=BRANCH_INK[child.branch];
            float startX,endX;
            if(child.side>0){
                startX=parent.rect.right;
                endX=child.rect.left;
            }else{
                startX=parent.rect.left;
                endX=child.rect.right;
            }
            float startY=parent.cy,endY=child.cy;
            float midX=(startX+endX)/2f;

            edgePath.reset();
            edgePath.moveTo(startX,startY);
            edgePath.cubicTo(midX,startY,midX,endY,endX,endY);

            edgePaint.setColor(parent.isCenter?color:Theme.alpha(color,.72f));
            edgePaint.setStrokeWidth(parent.isCenter?dp(2.6f):dp(1.6f));
            canvas.drawPath(edgePath,edgePaint);

            if(!child.edgeLabel.isEmpty()){
                // Cubic Bezier midpoint at t=0.5 is exact (start+end)/2 when control points share midX.
                float lx=midX;
                float ly=(startY+endY)/2f;
                labelPaint.setTextSize(sp(10f));
                float lw=labelPaint.measureText(child.edgeLabel)+dp(10);
                float lh=dp(17);
                RectF pill=new RectF(lx-lw/2f,ly-lh/2f,lx+lw/2f,ly+lh/2f);
                fillPaint.setColor(Theme.BG);
                canvas.drawRoundRect(pill,dp(8),dp(8),fillPaint);
                strokePaint.setColor(Theme.alpha(color,.45f));
                strokePaint.setStrokeWidth(dp(1f));
                canvas.drawRoundRect(pill,dp(8),dp(8),strokePaint);
                labelPaint.setColor(color);
                Paint.FontMetrics fm=labelPaint.getFontMetrics();
                canvas.drawText(child.edgeLabel,lx,ly-(fm.ascent+fm.descent)/2f,labelPaint);
            }
            drawEdges(canvas,child);
        }
    }

    private void drawNode(Canvas canvas,Node node){
        boolean selected=!selectedId.isEmpty()&&selectedId.equals(node.id);
        int ink=BRANCH_INK[node.branch];
        int soft=BRANCH_SOFT[node.branch];
        float radius=node.isCenter?dp(22):(node.depth==1?dp(12):dp(10));

        // Gentle warm paper shadow for depth 0 and 1.
        if(node.depth<=1||selected){
            fillPaint.setColor(selected?Theme.alpha(ink,.14f):0x121B2220);
            RectF shadow=new RectF(node.rect);
            shadow.offset(0,dp(selected?2.5f:1.8f));
            if(selected)shadow.inset(-dp(2.5f),-dp(2.5f));
            canvas.drawRoundRect(shadow,radius+dp(selected?2:0),radius+dp(selected?2:0),fillPaint);
        }

        if(node.isCenter){
            fillPaint.setColor(Theme.PRIMARY);
            canvas.drawRoundRect(node.rect,radius,radius,fillPaint);
            if(selected){
                strokePaint.setColor(Theme.PRIMARY_TINT);
                strokePaint.setStrokeWidth(dp(2.5f));
                canvas.drawRoundRect(node.rect,radius,radius,strokePaint);
            }
        }else if(node.depth==1){
            fillPaint.setColor(soft);
            canvas.drawRoundRect(node.rect,radius,radius,fillPaint);
            strokePaint.setColor(ink);
            strokePaint.setStrokeWidth(dp(selected?2.4f:1.5f));
            canvas.drawRoundRect(node.rect,radius,radius,strokePaint);
        }else{
            fillPaint.setColor(Theme.SURFACE);
            canvas.drawRoundRect(node.rect,radius,radius,fillPaint);
            strokePaint.setColor(selected?ink:Theme.alpha(ink,.55f));
            strokePaint.setStrokeWidth(dp(selected?2.2f:1.15f));
            canvas.drawRoundRect(node.rect,radius,radius,strokePaint);
        }

        boolean hasSub=showChinese&&!node.sublabel.isEmpty();
        textPaint.setTypeface(node.isCenter&&node.id.startsWith("__")?Theme.MEDIUM:Theme.SERIF);
        textPaint.setTextSize(sp(node.isCenter?17f:(node.depth==1?15.5f:14f)));
        textPaint.setColor(node.isCenter?Color.WHITE:Theme.INK);
        Paint.FontMetrics fm=textPaint.getFontMetrics();
        if(!hasSub){
            canvas.drawText(node.label,node.cx,node.cy-(fm.ascent+fm.descent)/2f,textPaint);
        }else{
            float topCenter=node.cy-dp(7);
            canvas.drawText(node.label,node.cx,topCenter-(fm.ascent+fm.descent)/2f,textPaint);
            subPaint.setTextSize(sp(node.isCenter?11f:(node.depth==1?10.5f:10f)));
            subPaint.setColor(node.isCenter?Theme.alpha(Color.WHITE,.85f):Theme.INK_2);
            Paint.FontMetrics sfm=subPaint.getFontMetrics();
            float botCenter=node.cy+dp(10);
            canvas.drawText(clip(node.sublabel,node.isCenter?14:12),node.cx,botCenter-(sfm.ascent+sfm.descent)/2f,subPaint);
        }

        if(node.isWrong&&!node.isCenter){
            fillPaint.setColor(Theme.DANGER);
            canvas.drawCircle(node.rect.right-dp(6),node.rect.top+dp(6),dp(3.2f),fillPaint);
        }
    }

    private static String clip(String text,int max){
        if(text==null)return "";
        String clean=text.trim().replace('\n',' ');
        return clean.length()<=max?clean:clean.substring(0,max-1)+"…";
    }

    // ── Tree Builders (Group Mind-Map & Single-Word Focus Mind-Map) ───────────

    /**
     * Builds a textbook-style radial mind-map tree for a relation group (e.g. “优秀的”相关).
     * Core branch roots connect directly to the center topic pill; their connected neighbors branch
     * outward with labeled edges (扩 / 近义 / 同根 / 反义 / 词形).
     */
    static Node buildGroupTree(JSONObject graph,JSONArray catalog,String groupId,Set<String> wrongIds){
        JSONObject group=null;
        JSONArray groups=graph.optJSONArray("groups");
        for(int i=0;groups!=null&&i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);
            if(g!=null&&groupId.equals(g.optString("id"))){group=g;break;}
        }
        if(group==null)return null;

        Map<String,JSONObject> cards=indexCards(catalog);
        JSONArray meta=WordGraph.memberMetaOf(graph,groupId);
        List<String> memberIds=new ArrayList<>();
        Set<String> memberSet=new HashSet<>();
        Map<String,Boolean> rootFlag=new HashMap<>();
        Map<String,Integer> branchFlag=new HashMap<>();
        for(int i=0;i<meta.length();i++){
            JSONObject m=meta.optJSONObject(i);
            if(m==null)continue;
            String wid=m.optString("wordId");
            if(wid.isEmpty()||!memberSet.add(wid))continue;
            memberIds.add(wid);
            rootFlag.put(wid,m.optBoolean("isRoot",false));
            branchFlag.put(wid,m.optInt("branch",0));
        }

        // Index edges touching any member (preferring edges inside the group, plus 1-hop external links).
        Map<String,List<JSONObject>> adj=new HashMap<>();
        Set<String> hasIncomingDerive=new HashSet<>();
        JSONArray links=graph.optJSONArray("links");
        if(links!=null){
            for(int i=0;i<links.length();i++){
                JSONObject l=links.optJSONObject(i);
                if(l==null)continue;
                String a=l.optString("a"),b=l.optString("b");
                if(!memberSet.contains(a)&&!memberSet.contains(b))continue;
                addAdj(adj,a,l);
                addAdj(adj,b,l);
                if(l.optBoolean("directed",false)||WordGraph.DERIVE.equals(l.optString("kind"))){
                    if(memberSet.contains(a)&&memberSet.contains(b))hasIncomingDerive.add(b);
                }
            }
        }

        // Choose core branch roots: explicit isRoot flags first; else members without incoming derive edge.
        List<String> coreRoots=new ArrayList<>();
        for(String wid:memberIds)if(Boolean.TRUE.equals(rootFlag.get(wid)))coreRoots.add(wid);
        if(coreRoots.isEmpty()){
            for(String wid:memberIds)if(!hasIncomingDerive.contains(wid))coreRoots.add(wid);
            if(coreRoots.isEmpty())coreRoots.addAll(memberIds);
        }

        Node center=new Node("__group__"+groupId,group.optString("name","关联图"),"",true,"","", "",0,0);
        Set<String> visited=new HashSet<>();
        List<Node> rootNodes=new ArrayList<>();

        // Assign harmonious color clusters so adjacent branches form clean visual groups.
        int clusterSize=Math.max(1,(int)Math.ceil(coreRoots.size()/3.0));
        for(int i=0;i<coreRoots.size();i++){
            String wid=coreRoots.get(i);
            if(!visited.add(wid))continue;
            int branchColor=branchFlag.containsKey(wid)&&branchFlag.get(wid)>0?branchFlag.get(wid):(i/clusterSize)%BRANCH_INK.length;
            JSONObject card=cards.get(wid);
            Node n=new Node(wid,en(card),zh(card),false,"","","",branchColor,1);
            n.isWrong=wrongIds!=null&&wrongIds.contains(wid);
            center.children.add(n);
            rootNodes.add(n);
        }

        // Multi-source BFS from core roots so every connected child attaches cleanly to its nearest parent!
        Queue<Node> queue=new ArrayDeque<>(rootNodes);
        while(!queue.isEmpty()){
            Node parent=queue.poll();
            if(parent.depth>=4)continue;
            List<JSONObject> edges=adj.get(parent.id);
            if(edges==null)continue;
            // Prioritize outgoing derive edges, then meaning/root/antonym/form.
            List<JSONObject> sorted=new ArrayList<>(edges);
            Collections.sort(sorted,(e1,e2)->edgePriority(e1,parent.id)-edgePriority(e2,parent.id));
            for(JSONObject l:sorted){
                String other=parent.id.equals(l.optString("a"))?l.optString("b"):l.optString("a");
                if(other.isEmpty()||!visited.add(other))continue;
                JSONObject card=cards.get(other);
                Node child=new Node(other,en(card),zh(card),false,WordGraph.edgeLabel(l),l.optString("kind"),l.optString("id"),parent.branch,parent.depth+1);
                child.isWrong=wrongIds!=null&&wrongIds.contains(other);
                parent.children.add(child);
                queue.add(child);
            }
        }

        // Any remaining disconnected group members attach directly to the center hub.
        for(String wid:memberIds){
            if(!visited.add(wid))continue;
            JSONObject card=cards.get(wid);
            int color=center.children.size()%BRANCH_INK.length;
            Node n=new Node(wid,en(card),zh(card),false,"","","",color,1);
            n.isWrong=wrongIds!=null&&wrongIds.contains(wid);
            center.children.add(n);
        }
        return center;
    }

    /**
     * Builds a word-centered mind-map (up to 2 hops out) showing direct relations and group siblings.
     */
    static Node buildWordTree(JSONObject graph,JSONArray catalog,String wordId,String fallbackEnglish,String fallbackChinese,Set<String> wrongIds){
        Map<String,JSONObject> cards=indexCards(catalog);
        JSONObject self=cards.get(wordId);
        String title=self!=null&&!en(self).isEmpty()?en(self):(fallbackEnglish==null||fallbackEnglish.isEmpty()?"单词":fallbackEnglish);
        String sub=self!=null&&!zh(self).isEmpty()?zh(self):(fallbackChinese==null?"":fallbackChinese);
        Node center=new Node(wordId,title,sub,true,"","","",0,0);

        Map<String,List<JSONObject>> adj=new HashMap<>();
        JSONArray links=graph.optJSONArray("links");
        if(links!=null){
            for(int i=0;i<links.length();i++){
                JSONObject l=links.optJSONObject(i);
                if(l==null)continue;
                addAdj(adj,l.optString("a"),l);
                addAdj(adj,l.optString("b"),l);
            }
        }

        Set<String> visited=new HashSet<>();
        visited.add(wordId);

        // 1st hop: direct links.
        List<JSONObject> direct=adj.get(wordId);
        if(direct!=null){
            List<JSONObject> sorted=new ArrayList<>(direct);
            Collections.sort(sorted,(e1,e2)->edgePriority(e1,wordId)-edgePriority(e2,wordId));
            for(JSONObject l:sorted){
                String other=wordId.equals(l.optString("a"))?l.optString("b"):l.optString("a");
                if(other.isEmpty()||!visited.add(other))continue;
                int color=colorForKind(l.optString("kind"));
                JSONObject card=cards.get(other);
                Node child=new Node(other,en(card),zh(card),false,WordGraph.edgeLabel(l),l.optString("kind"),l.optString("id"),color,1);
                child.isWrong=wrongIds!=null&&wrongIds.contains(other);
                center.children.add(child);

                // 2nd hop from this direct neighbor.
                List<JSONObject> hops=adj.get(other);
                if(hops!=null){
                    int added=0;
                    for(JSONObject h:hops){
                        if(added>=4)break;
                        String next=other.equals(h.optString("a"))?h.optString("b"):h.optString("a");
                        if(next.isEmpty()||!visited.add(next))continue;
                        JSONObject nc=cards.get(next);
                        Node grand=new Node(next,en(nc),zh(nc),false,WordGraph.edgeLabel(h),h.optString("kind"),h.optString("id"),color,2);
                        grand.isWrong=wrongIds!=null&&wrongIds.contains(next);
                        child.children.add(grand);
                        added++;
                    }
                }
            }
        }

        // Also show co-members from any groups this word belongs to (grouped under a topic branch node if multiple).
        try{
            JSONArray groups=WordGraph.groupsFor(graph,wordId);
            for(int i=0;i<groups.length();i++){
                JSONObject g=groups.getJSONObject(i);
                JSONArray members=WordGraph.membersOf(graph,g.optString("id"));
                List<String> peers=new ArrayList<>();
                for(int j=0;j<members.length();j++){
                    String peer=members.optString(j);
                    if(!peer.isEmpty()&&!visited.contains(peer))peers.add(peer);
                }
                if(peers.isEmpty())continue;
                Node groupBranch=new Node("__group__"+g.optString("id"),g.optString("name"),"同主题归纳",false,"同组",g.optString("kind"),"",3,1);
                int cap=Math.min(peers.size(),6);
                for(int j=0;j<cap;j++){
                    String peer=peers.get(j);
                    visited.add(peer);
                    JSONObject pc=cards.get(peer);
                    Node pn=new Node(peer,en(pc),zh(pc),false,"","","",3,2);
                    pn.isWrong=wrongIds!=null&&wrongIds.contains(peer);
                    groupBranch.children.add(pn);
                }
                center.children.add(groupBranch);
            }
        }catch(Exception ignored){}
        return center;
    }

    /** Builds a combined mind-map for a task/plan's words (optionally filtered to one category). */
    static Node buildTaskTree(JSONObject graph,JSONArray catalog,String title,JSONArray taskWords,Set<String> wrongIds){
        return buildTaskTree(graph,catalog,title,taskWords,wrongIds,"");
    }

    static Node buildTaskTree(JSONObject graph,JSONArray catalog,String title,JSONArray taskWords,Set<String> wrongIds,String categoryFilter){
        Map<String,JSONObject> cards=indexCards(catalog);
        Set<String> taskWordIds=new HashSet<>();
        List<String> orderedIds=new ArrayList<>();
        String catNeedle=categoryFilter==null?"":categoryFilter.trim();
        if(taskWords!=null){
            for(int i=0;i<taskWords.length();i++){
                JSONObject w=taskWords.optJSONObject(i);
                if(w==null)continue;
                if(!catNeedle.isEmpty()&&!catNeedle.equals(w.optString("category","").trim()))continue;
                String wid=Words.stableId(w);
                if(!wid.isEmpty()&&taskWordIds.add(wid)){
                    orderedIds.add(wid);
                    if(!cards.containsKey(wid))cards.put(wid,w);
                }
            }
        }
        // Prefer an existing structured group matching this category name or covering these words.
        JSONArray groups=graph.optJSONArray("groups");
        String bestGroup=null;int bestOverlap=0;
        if(groups!=null){
            for(int i=0;i<groups.length();i++){
                JSONObject g=groups.optJSONObject(i);
                if(g==null)continue;
                if(!catNeedle.isEmpty()&&catNeedle.equalsIgnoreCase(g.optString("name","").trim())){
                    return buildGroupTree(graph,catalog,g.optString("id"),wrongIds);
                }
                JSONArray members=WordGraph.membersOf(graph,g.optString("id"));
                int overlap=0;
                for(int j=0;j<members.length();j++)if(taskWordIds.contains(members.optString(j)))overlap++;
                if(overlap>bestOverlap){bestOverlap=overlap;bestGroup=g.optString("id");}
            }
        }
        if(bestGroup!=null&&bestOverlap>=2)return buildGroupTree(graph,catalog,bestGroup,wrongIds);

        // Otherwise build a synthetic group tree from the task/category words.
        try{
            JSONObject temp=WordGraph.copy(graph);
            String label=!catNeedle.isEmpty()?catNeedle:(title==null||title.isEmpty()?"词汇关系":title);
            String gid=WordGraph.addGroup(temp,label,WordGraph.MEANING);
            for(String wid:orderedIds)WordGraph.addMember(temp,gid,wid,false,0);
            return buildGroupTree(temp,catalog,gid,wrongIds);
        }catch(Exception e){
            return new Node("__task__",!catNeedle.isEmpty()?catNeedle:title,"",true,"","","",0,0);
        }
    }

    private static int colorForKind(String kind){
        if(WordGraph.DERIVE.equals(kind))return 1;
        if(WordGraph.ROOT.equals(kind))return 3;
        if(WordGraph.ANTONYM.equals(kind))return 2;
        if(WordGraph.FORM.equals(kind))return 4;
        return 0;
    }

    private static int edgePriority(JSONObject link,String fromId){
        String kind=link.optString("kind");
        boolean outgoing=fromId.equals(link.optString("a"));
        if(WordGraph.DERIVE.equals(kind)&&outgoing)return 0;
        if(WordGraph.MEANING.equals(kind))return 1;
        if(WordGraph.ROOT.equals(kind))return 2;
        if(WordGraph.ANTONYM.equals(kind))return 3;
        if(WordGraph.DERIVE.equals(kind))return 4;
        return 5;
    }

    private static void addAdj(Map<String,List<JSONObject>> adj,String id,JSONObject link){
        if(id==null||id.isEmpty())return;
        List<JSONObject> list=adj.get(id);
        if(list==null){list=new ArrayList<>();adj.put(id,list);}
        list.add(link);
    }

    private static Map<String,JSONObject> indexCards(JSONArray catalog){
        Map<String,JSONObject> map=new LinkedHashMap<>();
        if(catalog==null)return map;
        for(int i=0;i<catalog.length();i++){
            JSONObject c=catalog.optJSONObject(i);
            if(c==null)continue;
            String id=Words.stableId(c);
            if(!id.isEmpty()&&!map.containsKey(id))map.put(id,c);
        }
        return map;
    }

    private static String en(JSONObject card){return card==null?"已不在词表":(card.optString("english").trim().isEmpty()?card.optString("chinese"):card.optString("english").trim());}
    private static String zh(JSONObject card){return card==null?"":card.optString("chinese","").trim();}
}
