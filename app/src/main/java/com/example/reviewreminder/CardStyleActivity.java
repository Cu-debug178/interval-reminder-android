package com.example.reviewreminder;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline immersive flashcard customization screen:
 * live preview (front/back/long-word/dark), restrained backgrounds (default/solid/gradient/photo
 * with crop + one legibility scrim control), bottom slot (none/quote/pattern/photo),
 * plus Save / Cancel / Reset with private downsampled image lifecycle management.
 */
public final class CardStyleActivity extends ScreenActivity {
    private static final int PICK_BG_IMAGE = 61;
    private static final int PICK_BOTTOM_IMAGE = 62;
    private static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";

    private WordUi ui;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CardStyle initialStyle;
    private CardStyle draft;
    private final ArrayList<String> sessionImported = new ArrayList<>();
    private boolean saved;

    // Preview state
    private boolean previewBack = true;
    private boolean previewLong = false;
    private boolean previewDark = false;
    private LinearLayout previewHost;
    private LinearLayout bgDetailHost;
    private LinearLayout bottomDetailHost;
    private boolean busyPicking;

    // Remember last-chosen image names during this session so switching tabs back & forth doesn't lose them.
    private String rememberBgImage = "";
    private String rememberBottomImage = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new WordUi(this);
        initialStyle = CardStyleStore.load(this);
        draft = initialStyle;
        rememberBgImage = initialStyle.backgroundImage;
        rememberBottomImage = initialStyle.bottomImage;
        previewDark = CardStyleStore.isNightMode(this);
        buildScreen();
    }

    private void buildScreen() {
        ui.start("复习卡片外观");
        ui.note(ui.body, "自定义沉浸式背词卡的背景与底部装饰。全部素材仅保存在本机私有目录，离线可用，不上传图片。");

        // ── Live preview ──────────────────────────────────────────────────────
        ui.section(ui.body, "实时预览", null);
        previewHost = ui.col();
        ui.body.addView(previewHost, WordUi.lp());
        ui.gap(ui.body, Theme.SPACE_12);

        LinearLayout previewTools = ui.row();
        previewTools.addView(previewPill(previewBack ? "看正面" : "看背面", v -> {
            previewBack = !previewBack;
            ((Button) v).setText(previewBack ? "看正面" : "看背面");
            renderPreview();
        }), new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams longLp = new LinearLayout.LayoutParams(-2, -2);
        longLp.leftMargin = ui.dp(Theme.SPACE_8);
        previewTools.addView(previewPill(previewLong ? "标准词" : "长词测试", v -> {
            previewLong = !previewLong;
            ((Button) v).setText(previewLong ? "标准词" : "长词测试");
            renderPreview();
        }), longLp);

        LinearLayout.LayoutParams darkLp = new LinearLayout.LayoutParams(-2, -2);
        darkLp.leftMargin = ui.dp(Theme.SPACE_8);
        previewTools.addView(previewPill(previewDark ? "浅色预览" : "暗色预览", v -> {
            previewDark = !previewDark;
            ((Button) v).setText(previewDark ? "浅色预览" : "暗色预览");
            renderPreview();
        }), darkLp);
        ui.body.addView(previewTools, WordUi.lp());

        renderPreview();

        // ── Layout & progress style section ───────────────────────────────────
        ui.section(ui.body, "卡片布局", null);
        int layoutIdx = layoutModeIndex(draft.layoutMode);
        Segmented layoutSeg = ui.segmented(new String[]{"经典留边", "堆叠卡片", "全屏沉浸"},
            layoutIdx, index -> {
                String nextLayout = index == 1 ? CardStyle.LAYOUT_STACKED
                    : (index == 2 ? CardStyle.LAYOUT_IMMERSIVE : CardStyle.LAYOUT_CLASSIC);
                draft = draft.withLayoutMode(nextLayout);
                renderPreview();
            });
        ui.body.addView(layoutSeg, WordUi.lp());
        ui.note(ui.body, "经典留边保留既有卡片留白；堆叠卡片呈现多层景深，支持右滑记住、左滑忘记与底部评分按钮；全屏沉浸铺满屏幕并避让状态栏与导航条。");

        ui.section(ui.body, "批次进度样式", null);
        int progIdx = progressStyleIndex(draft.progressStyle);
        Segmented progSeg = ui.segmented(new String[]{"克制细线", "分段刻度", "紧凑环形"}, progIdx, index -> {
            String next = index == 1 ? CardStyle.PROGRESS_SEGMENTS
                : (index == 2 ? CardStyle.PROGRESS_RING : CardStyle.PROGRESS_LINE);
            draft = draft.withProgressStyle(next);
            renderPreview();
        });
        ui.body.addView(progSeg, WordUi.lp());
        ui.note(ui.body, "顶部仅表示本批复习进度（x / y），与翻面后单词熟练度（0–5）分开呈现。");

        // ── Background section ────────────────────────────────────────────────
        ui.section(ui.body, "卡片背景", null);
        int bgIdx = bgModeIndex(draft.backgroundMode);
        Segmented bgSeg = ui.segmented(new String[]{"默认", "纯色", "渐变", "图片"}, bgIdx, index -> {
            if (index == 0) draft = draft.withBackgroundDefault();
            else if (index == 1) draft = draft.withSolid(draft.backgroundPreset);
            else if (index == 2) draft = draft.withGradient(draft.backgroundPreset);
            else {
                if (!rememberBgImage.isEmpty() && CardStyleImages.exists(this, rememberBgImage)) {
                    draft = draft.withBackgroundImage(rememberBgImage);
                } else {
                    draft = draft.withBackgroundDefault();
                    renderBgDetails(true);
                    renderPreview();
                    return;
                }
            }
            renderBgDetails(index == 3);
            renderPreview();
        });
        ui.body.addView(bgSeg, WordUi.lp());

        bgDetailHost = ui.col();
        ui.body.addView(bgDetailHost, ui.lpMargin(Theme.SPACE_12, 0));
        renderBgDetails(bgIdx == 3);

        // ── Bottom slot section ───────────────────────────────────────────────
        ui.section(ui.body, "底部区域", null);
        int btIdx = bottomModeIndex(draft.bottomMode);
        Segmented btSeg = ui.segmented(new String[]{"无", "短句", "纹样", "图片"}, btIdx, index -> {
            if (index == 0) draft = draft.withBottomNone();
            else if (index == 1) {
                String q = draft.quoteText.isEmpty() ? CardStyle.SAMPLE_QUOTES[0].text : draft.quoteText;
                draft = draft.withBottomQuote(q, draft.quoteCredit);
            } else if (index == 2) {
                draft = draft.withBottomPattern(draft.bottomPattern);
            } else {
                if (!rememberBottomImage.isEmpty() && CardStyleImages.exists(this, rememberBottomImage)) {
                    draft = draft.withBottomImage(rememberBottomImage);
                } else {
                    draft = draft.withBottomNone();
                    renderBottomDetails(true);
                    renderPreview();
                    return;
                }
            }
            renderBottomDetails(index == 3);
            renderPreview();
        });
        ui.body.addView(btSeg, WordUi.lp());

        bottomDetailHost = ui.col();
        ui.body.addView(bottomDetailHost, ui.lpMargin(Theme.SPACE_12, 0));
        renderBottomDetails(btIdx == 3);

        // ── Reset action ──────────────────────────────────────────────────────
        ui.gap(ui.body, Theme.SPACE_20);
        ui.body.addView(ui.dangerTextButton("恢复默认外观", v -> confirmReset()));
        ui.note(ui.body, "保存后立即应用于背词卡片；若点「取消」则返回原样，不会改动已保存设置。");

        // ── Pinned Save / Cancel ──────────────────────────────────────────────
        ui.pinPair(
            ui.secondaryButton("取消", v -> cancelAndClose()),
            ui.primaryButton("保存", v -> saveAndClose())
        );
    }

    private Button previewPill(String label, View.OnClickListener click) {
        return ui.quietPill(label, 0, click);
    }

    private void renderPreview() {
        if (previewHost == null) return;
        previewHost.removeAllViews();

        boolean immersive = draft.isImmersiveLayout();
        boolean stacked = draft.isStackedLayout();
        boolean isRing = CardStyle.PROGRESS_RING.equals(draft.progressStyle);

        LinearLayout stage = ui.col();
        int stagePad = ui.dp(immersive ? 0 : Theme.SPACE_12);
        stage.setPadding(stagePad, stagePad, stagePad, stagePad);
        stage.setBackground(Theme.shape(this, previewDark ? CardSurfaceView.DARK_PAGE_BG : Theme.SUNK, 20));
        if (immersive) stage.setClipToOutline(true);

        CardSurfaceView card = new CardSurfaceView(this, immersive ? 20 : 18);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.dp(Theme.SPACE_16), ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_16), ui.dp(Theme.SPACE_16));
        card.setMinimumHeight(ui.dp(256));
        card.applyStyle(draft, previewDark);

        CardSurfaceView.Palette palette = card.palette();
        boolean compact = getResources().getConfiguration().fontScale >= 1.25f;

        // Preview header bar showing batch progress ("本批 3 / 12") + selected indicator + speaker/more icons.
        LinearLayout previewTop = ui.row();
        previewTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView batchTag = ui.type("本批", Theme.T_CAPTION);
        batchTag.setTextColor(immersive ? palette.inkMuted : (previewDark ? 0xFF98A19D : Theme.INK_3));
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(-2, -2);
        btp.rightMargin = ui.dp(4);
        previewTop.addView(batchTag, btp);

        TextView batchNum = ui.type("3 / 12", Theme.T_CAPTION);
        batchNum.setTypeface(Theme.MEDIUM);
        batchNum.setTextColor(immersive ? palette.ink : (previewDark ? 0xFFF1F4F2 : Theme.INK_2));
        previewTop.addView(batchNum, new LinearLayout.LayoutParams(-2, -2));

        ProgressLine previewProg = new ProgressLine(this)
            .style(draft.progressStyle)
            .colors(immersive ? palette.accent : (previewDark ? 0xFF74B8A4 : Theme.PRIMARY),
                immersive ? palette.hairline : (previewDark ? 0xFF2B3532 : Theme.HAIRLINE));
        previewProg.setStep(3, 12, false);
        if (isRing) {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
            rlp.leftMargin = ui.dp(6);
            previewTop.addView(previewProg, rlp);
        }
        previewTop.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        int iconTint = immersive ? palette.inkSecondary : (previewDark ? 0xFFC5CBC7 : Theme.INK_2);
        ImageView spIcon = ui.icon(R.drawable.ic_volume_2, iconTint);
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
        sip.rightMargin = ui.dp(12);
        previewTop.addView(spIcon, sip);
        previewTop.addView(ui.icon(R.drawable.ic_ellipsis, iconTint), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));

        if (immersive) {
            card.addView(previewTop, WordUi.lp());
            if (!isRing) {
                card.addView(previewProg, ui.lpMargin(Theme.SPACE_6, Theme.SPACE_8));
            }
        } else {
            LinearLayout classicBar = ui.col();
            classicBar.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(Theme.SPACE_8));
            classicBar.addView(previewTop, WordUi.lp());
            if (!isRing) {
                classicBar.addView(previewProg, ui.lpMargin(Theme.SPACE_6, 0));
            }
            stage.addView(classicBar, WordUi.lp());
        }

        LinearLayout center = ui.col();
        center.setGravity(Gravity.CENTER);

        String headword = previewLong ? "counterproductive" : "ambition";
        String meaning = previewLong ? "adj. 事与愿违的；产生相反效果的；适得其反的" : "n. 雄心；野心；抱负";
        String example = previewLong
            ? "Pushing too hard without spaced rest can be counterproductive when memorizing vocabulary."
            : "Small daily steps turn ambition into habit.";

        TextView front = new TextView(this);
        front.setText(headword);
        front.setGravity(Gravity.CENTER);
        front.setTypeface(Theme.SERIF);
        front.setTextSize(previewLong ? 24 : 32);
        front.setTextColor(palette.ink);
        front.setLineSpacing(0, 1.12f);
        center.addView(front, WordUi.lp());

        if (previewBack) {
            View line = new View(this);
            line.setBackgroundColor(palette.hairline);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ui.dp(32), Math.max(1, ui.dp(1)));
            hp.topMargin = ui.dp(14);
            hp.bottomMargin = ui.dp(14);
            center.addView(line, hp);

            TextView back = new TextView(this);
            back.setText(meaning);
            back.setGravity(Gravity.CENTER);
            back.setTypeface(Theme.REGULAR);
            back.setTextSize(previewLong ? 16 : 18);
            back.setTextColor(palette.ink);
            back.setLineSpacing(0, 1.3f);
            center.addView(back, WordUi.lp());

            TextView ex = new TextView(this);
            ex.setText(example);
            ex.setGravity(Gravity.CENTER);
            ex.setTypeface(Theme.SERIF_ITALIC);
            ex.setTextSize(13);
            ex.setTextColor(palette.inkSecondary);
            ex.setLineSpacing(0, 1.3f);
            ex.setMaxLines(3);
            ex.setEllipsize(android.text.TextUtils.TruncateAt.END);
            center.addView(ex, ui.lpMargin(8, 0));

            LinearLayout mastery = ui.row();
            mastery.setGravity(Gravity.CENTER);
            mastery.addView(new StageDots(this, 5, 3, false).ringNext(false).color(palette.dotActive).offColor(palette.dotInactive));
            TextView ml = ui.type("熟练度 3/5", Theme.T_CAPTION);
            ml.setTextColor(palette.inkMuted);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.leftMargin = ui.dp(6);
            mastery.addView(ml, mlp);
            center.addView(mastery, ui.lpMargin(12, 0));
        }

        card.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        CardSurfaceView.attachBottomSlot(this, card, draft, palette, compact);

        if (stacked) {
            android.widget.FrameLayout stackFrame = new android.widget.FrameLayout(this);
            stackFrame.setClipChildren(false);
            int stepPx = ui.dp(9);
            int layer1Bg = previewDark ? 0xFF232B28 : Theme.SURFACE;
            int layer2Bg = previewDark ? 0xFF1A211F : Theme.SUNK;
            int layerStroke = previewDark ? 0xFF333F3A : Theme.HAIRLINE;
            View back2 = new View(this);
            back2.setBackground(Theme.shape(this, layer2Bg, 18, layerStroke, 1));
            back2.setScaleX(0.88f);
            back2.setAlpha(0.76f);
            android.widget.FrameLayout.LayoutParams lp2 = new android.widget.FrameLayout.LayoutParams(-1, -1);
            lp2.topMargin = stepPx * 2;
            stackFrame.addView(back2, lp2);

            View back1 = new View(this);
            back1.setBackground(Theme.shape(this, layer1Bg, 18, layerStroke, 1));
            back1.setScaleX(0.94f);
            back1.setAlpha(0.94f);
            android.widget.FrameLayout.LayoutParams lp1 = new android.widget.FrameLayout.LayoutParams(-1, -1);
            lp1.topMargin = stepPx;
            lp1.bottomMargin = stepPx;
            stackFrame.addView(back1, lp1);

            android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = stepPx * 2;
            stackFrame.addView(card, cardLp);
            stage.addView(stackFrame, WordUi.lp());

            // Preview accessible fallback scoring row (忘记 / 记得; no fake undo)
            LinearLayout scorePreview = ui.row();
            TextView fTag = ui.type("← 忘记", Theme.T_CAPTION);
            fTag.setGravity(Gravity.CENTER);
            fTag.setTextColor(Theme.DANGER);
            fTag.setBackground(Theme.shape(this, previewDark ? 0xFF3B241E : Theme.DANGER_SOFT, 10));
            fTag.setPadding(0, ui.dp(8), 0, ui.dp(8));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1);
            flp.rightMargin = ui.dp(8);
            scorePreview.addView(fTag, flp);

            TextView rTag = ui.type("记得 →", Theme.T_CAPTION);
            rTag.setGravity(Gravity.CENTER);
            rTag.setTextColor(Theme.ON_PRIMARY);
            rTag.setBackground(Theme.shape(this, Theme.PRIMARY, 10));
            rTag.setPadding(0, ui.dp(8), 0, ui.dp(8));
            scorePreview.addView(rTag, new LinearLayout.LayoutParams(0, -2, 1));
            stage.addView(scorePreview, ui.lpMargin(Theme.SPACE_12, 0));
        } else {
            stage.addView(card, WordUi.lp());
        }
        previewHost.addView(stage, WordUi.lp());
    }

    private void renderBgDetails(boolean imageTabSelected) {
        bgDetailHost.removeAllViews();
        if (imageTabSelected || CardStyle.BG_IMAGE.equals(draft.backgroundMode)) {
            boolean hasImg = CardStyle.BG_IMAGE.equals(draft.backgroundMode) && !draft.backgroundImage.isEmpty();
            LinearLayout box = ui.card();
            LinearLayout actions = ui.row();
            Button pick = ui.tonalButton(hasImg ? "更换背景图片" : "从相册选择背景图片", R.drawable.ic_images, v -> openPhotoPicker(PICK_BG_IMAGE));
            actions.addView(pick, WordUi.weight());
            box.addView(actions, WordUi.lp());

            if (hasImg) {
                ui.gap(box, Theme.SPACE_16);
                TextView cropLabel = ui.type("构图裁切位置", Theme.T_CALLOUT);
                cropLabel.setTypeface(Theme.MEDIUM);
                box.addView(cropLabel);
                ui.gap(box, Theme.SPACE_8);
                int cropIdx = CardStyle.CROP_TOP.equals(draft.imageCrop) ? 1 : CardStyle.CROP_BOTTOM.equals(draft.imageCrop) ? 2 : 0;
                Segmented cropSeg = ui.segmented(new String[]{"居中", "偏上", "偏下"}, cropIdx, idx -> {
                    String next = idx == 1 ? CardStyle.CROP_TOP : idx == 2 ? CardStyle.CROP_BOTTOM : CardStyle.CROP_CENTER;
                    draft = draft.withImageCrop(next);
                    renderPreview();
                });
                box.addView(cropSeg, WordUi.lp());

                ui.gap(box, Theme.SPACE_16);
                LinearLayout overlayRow = ui.row();
                TextView overlayLabel = ui.type("文字可读遮罩", Theme.T_CALLOUT);
                overlayLabel.setTypeface(Theme.MEDIUM);
                overlayRow.addView(overlayLabel, WordUi.weight());
                TextView overlayVal = ui.type(draft.overlayAlpha + "%", Theme.T_CAPTION);
                overlayVal.setTextColor(Theme.PRIMARY);
                overlayRow.addView(overlayVal);
                box.addView(overlayRow, WordUi.lp());

                SeekBar seek = new SeekBar(this);
                seek.setMax(CardStyle.OVERLAY_MAX);
                seek.setProgress(draft.overlayAlpha);
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        draft = draft.withOverlayAlpha(progress);
                        overlayVal.setText(draft.overlayAlpha + "%");
                        renderPreview();
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) {}
                    @Override public void onStopTrackingTouch(SeekBar bar) { Feedback.tick(bar); }
                });
                box.addView(seek, ui.lpMargin(Theme.SPACE_6, 0));

                TextView tip = ui.type("提高遮罩浓度可在明亮照片上保持单词与释义清晰可读。", Theme.T_CAPTION);
                box.addView(tip, ui.lpMargin(Theme.SPACE_4, 0));

                ui.gap(box, Theme.SPACE_8);
                box.addView(ui.dangerTextButton("移除背景图片", v -> {
                    rememberBgImage = "";
                    draft = draft.withBackgroundDefault();
                    renderBgDetails(true);
                    renderPreview();
                    ui.toast("已移除背景图片");
                }));
            } else {
                TextView hint = ui.type("通过系统照片选择器离线导入并自动压缩，仅保存在应用私有目录，不需要宽泛存储权限。", Theme.T_CAPTION);
                box.addView(hint, ui.lpMargin(Theme.SPACE_12, 0));
            }
            bgDetailHost.addView(box, WordUi.lp());
            return;
        }

        if (CardStyle.BG_SOLID.equals(draft.backgroundMode)) {
            LinearLayout row = ui.row();
            for (CardStyle.SolidPreset p : CardStyle.SOLIDS) {
                boolean selected = p.id.equals(draft.backgroundPreset);
                View item = swatchChip(p.label, p.color, p.color, selected, v -> {
                    draft = draft.withSolid(p.id);
                    renderBgDetails(false);
                    renderPreview();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (row.getChildCount() > 0) lp.leftMargin = ui.dp(Theme.SPACE_8);
                row.addView(item, lp);
            }
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(row);
            bgDetailHost.addView(hsv, WordUi.lp());
            return;
        }

        if (CardStyle.BG_GRADIENT.equals(draft.backgroundMode)) {
            LinearLayout row = ui.row();
            for (CardStyle.GradientPreset g : CardStyle.GRADIENTS) {
                boolean selected = g.id.equals(draft.backgroundPreset);
                View item = swatchChip(g.label, g.startColor, g.endColor, selected, v -> {
                    draft = draft.withGradient(g.id);
                    renderBgDetails(false);
                    renderPreview();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (row.getChildCount() > 0) lp.leftMargin = ui.dp(Theme.SPACE_8);
                row.addView(item, lp);
            }
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(row);
            bgDetailHost.addView(hsv, WordUi.lp());
            return;
        }

        ui.note(bgDetailHost, "默认纸面随系统浅色/暗色模式自动适配，保持最高对比度。");
    }

    private void renderBottomDetails(boolean imageTabSelected) {
        bottomDetailHost.removeAllViews();
        if (imageTabSelected || CardStyle.BOTTOM_IMAGE.equals(draft.bottomMode)) {
            boolean hasImg = CardStyle.BOTTOM_IMAGE.equals(draft.bottomMode) && !draft.bottomImage.isEmpty();
            LinearLayout box = ui.card();
            Button pick = ui.tonalButton(hasImg ? "更换底部图片" : "从相册选择底部图片", R.drawable.ic_images, v -> openPhotoPicker(PICK_BOTTOM_IMAGE));
            box.addView(pick, WordUi.lp());
            if (hasImg) {
                ui.gap(box, Theme.SPACE_8);
                box.addView(ui.dangerTextButton("移除底部图片", v -> {
                    rememberBottomImage = "";
                    draft = draft.withBottomNone();
                    renderBottomDetails(true);
                    renderPreview();
                    ui.toast("已移除底部图片");
                }));
            }
            TextView tip = ui.type("以横向圆角条显示在卡片底部，横屏或小屏下会自动收窄高度，不遮挡词义。", Theme.T_CAPTION);
            box.addView(tip, ui.lpMargin(Theme.SPACE_8, 0));
            bottomDetailHost.addView(box, WordUi.lp());
            return;
        }

        if (CardStyle.BOTTOM_QUOTE.equals(draft.bottomMode)) {
            LinearLayout box = ui.card();
            TextView sampleTitle = ui.type("内置原创短句（点选填入）", Theme.T_CAPTION);
            box.addView(sampleTitle);
            ui.gap(box, Theme.SPACE_8);

            EditText quoteInput = ui.input("写一句陪伴复习的话（最多 120 字）", draft.quoteText);
            quoteInput.setMinLines(2);
            quoteInput.setMaxLines(4);
            quoteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            quoteInput.setGravity(Gravity.TOP | Gravity.START);
            quoteInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CardStyle.MAX_QUOTE_CHARS)});

            EditText creditInput = ui.input("署名或备注（可选，最多 36 字）", draft.quoteCredit);
            creditInput.setSingleLine(true);
            creditInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CardStyle.MAX_CREDIT_CHARS)});

            LinearLayout sampleList = ui.col();
            for (CardStyle.SampleQuote sample : CardStyle.SAMPLE_QUOTES) {
                LinearLayout row = ui.row();
                row.setMinimumHeight(ui.dp(44));
                row.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
                row.setBackground(Theme.shape(this, Theme.SUNK, Theme.RADIUS_FIELD));
                TextView t = ui.type(sample.text, Theme.T_CAPTION);
                t.setTextColor(Theme.INK);
                row.addView(t, WordUi.weight());
                row.setClickable(true);
                row.setFocusable(true);
                Theme.applyRipple(row, row.getBackground(), Theme.RIPPLE_LIGHT);
                Motion.press(row, .985f);
                row.setOnClickListener(v -> {
                    quoteInput.setText(sample.text);
                    creditInput.setText(sample.credit);
                    draft = draft.withBottomQuote(sample.text, sample.credit);
                    renderPreview();
                });
                LinearLayout.LayoutParams rlp = WordUi.lp();
                if (sampleList.getChildCount() > 0) rlp.topMargin = ui.dp(Theme.SPACE_6);
                sampleList.addView(row, rlp);
            }
            box.addView(sampleList, WordUi.lp());
            ui.gap(box, Theme.SPACE_16);

            TextView ql = ui.type("自定义短句", Theme.T_CALLOUT);
            ql.setTypeface(Theme.MEDIUM);
            box.addView(ql);
            ui.gap(box, Theme.SPACE_6);
            box.addView(quoteInput, WordUi.lp());
            ui.gap(box, Theme.SPACE_12);

            TextView cl = ui.type("署名 / 备注（可选）", Theme.T_CALLOUT);
            cl.setTypeface(Theme.MEDIUM);
            box.addView(cl);
            ui.gap(box, Theme.SPACE_6);
            box.addView(creditInput, WordUi.lp());

            TextWatcher watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    draft = draft.withBottomQuote(quoteInput.getText().toString(), creditInput.getText().toString());
                    renderPreview();
                }
            };
            quoteInput.addTextChangedListener(watcher);
            creditInput.addTextChangedListener(watcher);

            bottomDetailHost.addView(box, WordUi.lp());
            return;
        }

        if (CardStyle.BOTTOM_PATTERN.equals(draft.bottomMode)) {
            LinearLayout row = ui.row();
            for (CardStyle.PatternPreset p : CardStyle.PATTERNS) {
                boolean selected = p.id.equals(draft.bottomPattern);
                TextView chip = ui.chip(p.label, selected ? Theme.PRIMARY_SOFT : Theme.SURFACE, selected ? Theme.PRIMARY : Theme.INK);
                chip.setTextSize(Theme.CALLOUT_SIZE);
                chip.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
                if (!selected) chip.setBackground(Theme.shape(this, Theme.SURFACE, Theme.RADIUS_BUTTON, Theme.HAIRLINE, 1));
                else chip.setBackground(Theme.shape(this, Theme.PRIMARY_SOFT, Theme.RADIUS_BUTTON, Theme.PRIMARY, 1.2f));
                chip.setClickable(true);
                chip.setFocusable(true);
                Theme.applyRipple(chip, chip.getBackground(), selected ? Theme.RIPPLE_ACCENT : Theme.RIPPLE_LIGHT);
                Motion.press(chip, .96f);
                chip.setOnClickListener(v -> {
                    draft = draft.withBottomPattern(p.id);
                    renderBottomDetails(false);
                    renderPreview();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (row.getChildCount() > 0) lp.leftMargin = ui.dp(Theme.SPACE_8);
                row.addView(chip, lp);
            }
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(row);
            bottomDetailHost.addView(hsv, WordUi.lp());
            ui.note(bottomDetailHost, "克制的低透明度纸面底纹，随浅色/暗色表面自动调整墨色。");
            return;
        }

        ui.note(bottomDetailHost, "保持卡片底部留白，最专注的默认布局。");
    }

    private View swatchChip(String label, int startColor, int endColor, boolean selected, View.OnClickListener click) {
        LinearLayout chip = ui.row();
        chip.setPadding(ui.dp(12), ui.dp(10), ui.dp(14), ui.dp(10));
        chip.setBackground(selected
            ? Theme.shape(this, Theme.PRIMARY_SOFT, Theme.RADIUS_BUTTON, Theme.PRIMARY, 1.5f)
            : Theme.shape(this, Theme.SURFACE, Theme.RADIUS_BUTTON, Theme.HAIRLINE, 1));

        View dot = new View(this);
        GradientDrawable fill = startColor == endColor
            ? Theme.oval(startColor)
            : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{startColor, endColor});
        fill.setShape(GradientDrawable.OVAL);
        fill.setStroke(Math.max(1, ui.dp(1)), Theme.HAIRLINE);
        dot.setBackground(fill);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
        dp.rightMargin = ui.dp(8);
        chip.addView(dot, dp);

        TextView text = ui.type(label, Theme.T_CALLOUT);
        text.setTypeface(selected ? Theme.MEDIUM : Theme.REGULAR);
        text.setTextColor(selected ? Theme.PRIMARY : Theme.INK);
        chip.addView(text);

        if (selected) {
            ImageView check = ui.icon(R.drawable.ic_check, Theme.PRIMARY);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14));
            cp.leftMargin = ui.dp(6);
            chip.addView(check, cp);
        }

        chip.setClickable(true);
        chip.setFocusable(true);
        Theme.applyRipple(chip, chip.getBackground(), selected ? Theme.RIPPLE_ACCENT : Theme.RIPPLE_LIGHT);
        Motion.press(chip, .96f);
        chip.setOnClickListener(click);
        return chip;
    }

    // ── System photo picker (no broad storage permission) ─────────────────────

    private void openPhotoPicker(int requestCode) {
        if (busyPicking) return;
        if (Build.VERSION.SDK_INT >= 33) {
            Intent pick = new Intent(ACTION_PICK_IMAGES).setType("image/*");
            try {
                startActivityForResult(pick, requestCode);
                return;
            } catch (Exception ignored) {
            }
        }
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*");
        try {
            startActivityForResult(doc, requestCode);
        } catch (Exception e) {
            ui.error("当前设备没有可用的系统选图器");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_BG_IMAGE && request != PICK_BOTTOM_IMAGE) return;
        // Picker cancelled: preserve current draft and previous image untouched.
        if (result != RESULT_OK || data == null || data.getData() == null) return;

        final Uri uri = data.getData();
        final boolean isBg = request == PICK_BG_IMAGE;
        busyPicking = true;
        ui.toast("正在离线压缩并保存预览图片…");
        worker.execute(() -> {
            try {
                String savedName = CardStyleImages.importUri(
                    getApplicationContext(),
                    uri,
                    isBg ? "bg_" : "bt_",
                    isBg ? CardStyleImages.MAX_BG_SIDE : CardStyleImages.MAX_BOTTOM_SIDE
                );
                runOnUiThread(() -> {
                    busyPicking = false;
                    if (isFinishing() || isDestroyed()) {
                        CardStyleImages.delete(getApplicationContext(), savedName);
                        return;
                    }
                    sessionImported.add(savedName);
                    if (isBg) {
                        rememberBgImage = savedName;
                        draft = draft.withBackgroundImage(savedName);
                        renderBgDetails(true);
                    } else {
                        rememberBottomImage = savedName;
                        draft = draft.withBottomImage(savedName);
                        renderBottomDetails(true);
                    }
                    renderPreview();
                    ui.toast("图片已载入预览，点击「保存」生效");
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null || e.getMessage().isEmpty() ? "图片导入失败" : e.getMessage();
                runOnUiThread(() -> {
                    busyPicking = false;
                    if (!isFinishing() && !isDestroyed()) ui.error(msg);
                });
            }
        });
    }

    // ── Save / Cancel / Reset ─────────────────────────────────────────────────

    private void confirmReset() {
        Sheet.confirm(
            this,
            "恢复默认卡片外观？",
            "将背景恢复为默认纸面，并清空底部装饰。点击下方「保存」后生效。",
            "保留当前",
            "恢复默认",
            true,
            () -> {
                rememberBgImage = "";
                rememberBottomImage = "";
                draft = CardStyle.defaults();
                buildScreen();
                ui.toast("已恢复默认预览，点击「保存」生效");
            }
        );
    }

    private void saveAndClose() {
        if (busyPicking) {
            ui.toast("图片正在处理，请稍候");
            return;
        }
        if (!CardStyleStore.save(this, draft)) {
            ui.error("卡片外观未能保存，请检查存储空间");
            return;
        }
        saved = true;
        sessionImported.clear();
        setResult(RESULT_OK);
        ui.toast("卡片外观已保存");
        finish();
    }

    private void cancelAndClose() {
        cleanupUnsavedSessionImages();
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override public void onBackPressed() {
        cancelAndClose();
    }

    private void cleanupUnsavedSessionImages() {
        if (saved) return;
        for (String name : sessionImported) {
            if (!name.equals(initialStyle.backgroundImage) && !name.equals(initialStyle.bottomImage)) {
                CardStyleImages.delete(getApplicationContext(), name);
            }
        }
        sessionImported.clear();
    }

    @Override protected void onDestroy() {
        cleanupUnsavedSessionImages();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static int layoutModeIndex(String mode) {
        if (CardStyle.LAYOUT_STACKED.equals(mode)) return 1;
        if (CardStyle.LAYOUT_IMMERSIVE.equals(mode)) return 2;
        return 0;
    }

    private static int progressStyleIndex(String style) {
        if (CardStyle.PROGRESS_SEGMENTS.equals(style)) return 1;
        if (CardStyle.PROGRESS_RING.equals(style)) return 2;
        return 0;
    }

    private static int bgModeIndex(String mode) {
        if (CardStyle.BG_SOLID.equals(mode)) return 1;
        if (CardStyle.BG_GRADIENT.equals(mode)) return 2;
        if (CardStyle.BG_IMAGE.equals(mode)) return 3;
        return 0;
    }

    private static int bottomModeIndex(String mode) {
        if (CardStyle.BOTTOM_QUOTE.equals(mode)) return 1;
        if (CardStyle.BOTTOM_PATTERN.equals(mode)) return 2;
        if (CardStyle.BOTTOM_IMAGE.equals(mode)) return 3;
        return 0;
    }
}
