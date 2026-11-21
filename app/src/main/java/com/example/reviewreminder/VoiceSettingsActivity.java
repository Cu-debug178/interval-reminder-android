package com.example.reviewreminder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Future;

/**
 * Unified voice & speech settings screen:
 * - Offline system TTS (always available without network or API key)
 * - Xiaomi MiMo preset voices (mimo-v2.5-tts)
 * - Xiaomi MiMo VoiceDesign (mimo-v2.5-tts-voicedesign, text description)
 * - Xiaomi MiMo VoiceClone (mimo-v2.5-tts-voiceclone, recorded or imported WAV/MP3 + explicit consent)
 */
public final class VoiceSettingsActivity extends ScreenActivity {
    private static final int PICK_AUDIO = 41;
    private static final int REQ_RECORD_AUDIO = 42;
    private static final String[] RATE_SHORT = {"0.6×", "0.75×", "0.85×", "1.0×", "1.15×"};
    private static final String SAMPLE_EN = "Learning a little every day makes a difference.";
    private static final String SAMPLE_ZH = "你好，现在到了复习时间，一起来回顾今天的新词吧。";
    private static final String SAMPLE_REMINDER = "Lesson 1，第 1 次复习到时间了。";

    private WordUi ui;
    private SpeechManager speech;
    private Segmented rate;
    private TextView rateCaption, connectionStatus;
    private EditText key, base, model, previewText;
    private Button testBtn;
    private Future<?> pendingProbe;
    private MediaPlayer localPlayer;
    private AudioRecorderHelper activeRecorder;
    private int voiceIndex;
    private String draftPreviewText = SAMPLE_EN;
    private String lastCheckMessage = "";
    private boolean lastCheckOk;
    private final ArrayList<Option> options = new ArrayList<>();

    private static final class Option {
        final String provider, id, label, detail, kind;
        Option(String provider, String id, String label, String detail, String kind) {
            this.provider = provider;
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.kind = kind;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new WordUi(this);
        speech = new SpeechManager(this);
        show();
    }

    private void captureDrafts() {
        if (previewText != null) {
            String value = previewText.getText().toString();
            if (!value.trim().isEmpty()) draftPreviewText = value;
        }
    }

    private void show() {
        captureDrafts();
        ui.start("朗读声音与语速");
        ui.note(ui.body, "单词、例句、复习和语音提醒共用这里的音色。系统语音支持离线使用；MiMo 预置、设计与克隆音色需配置按量付费 API Key。");

        buildOptions();
        Option current = options.get(voiceIndex);

        // ── 1. Default voice ──────────────────────────────────────────────────
        ui.section(ui.body, "默认音色", null);
        LinearLayout voiceGroup = ui.group();
        ui.item(voiceGroup, R.drawable.ic_volume_2, "当前音色", current.detail, current.label, true, v -> pickVoice());
        if (VoiceStore.SYSTEM.equals(current.provider)) {
            ui.item(voiceGroup, 0, "系统英文声音", "从这台设备已安装的离线/系统语音里选", null, true, v -> SpeechSettings.show(this));
        }
        ui.body.addView(voiceGroup, WordUi.lp());

        // ── 2. Rate ───────────────────────────────────────────────────────────
        ui.section(ui.body, "朗读速度", null);
        int savedRate = SpeechSettings.rateIndex(Store.prefs(this).getInt("speechRate", 2));
        rate = ui.segmented(RATE_SHORT, savedRate, index -> rateCaption.setText(SpeechSettings.RATE_LABELS[SpeechSettings.rateIndex(index)]));
        ui.body.addView(rate, WordUi.lp());
        rateCaption = ui.type(SpeechSettings.RATE_LABELS[savedRate], Theme.T_CAPTION);
        rateCaption.setPadding(ui.dp(Theme.SPACE_4), ui.dp(Theme.SPACE_8), ui.dp(Theme.SPACE_4), 0);
        ui.body.addView(rateCaption, WordUi.lp());

        // ── 3. VoiceDesign (text description) ─────────────────────────────────
        ui.section(ui.body, "我的设计音色", "文字描述");
        JSONArray designs = VoiceStore.profilesOfKind(this, VoiceStore.KIND_DESIGN);
        if (designs.length() == 0) {
            ui.body.addView(ui.empty(
                R.drawable.ic_sparkles,
                "还没有设计音色",
                "用 1–4 句文字描述你想要的声音，无需参考音频即可生成专属音色。",
                "新建设计音色",
                v -> openDesignEditor(null)
            ));
        } else {
            LinearLayout designList = ui.group();
            for (int i = 0; i < designs.length(); i++) {
                JSONObject profile = designs.optJSONObject(i);
                if (profile == null) continue;
                final JSONObject item = profile;
                boolean selected = isSelectedProfile(profile);
                String desc = clipLine(profile.optString("description", ""), 42);
                String sub = desc + (selected ? " · 当前默认" : "");
                ui.item(designList, R.drawable.ic_sparkles, profile.optString("name"), sub, null, true, v -> designActions(item));
            }
            ui.body.addView(designList, WordUi.lp());
            ui.gap(ui.body, Theme.SPACE_12);
            ui.body.addView(ui.secondaryButton("新建设计音色", v -> openDesignEditor(null)), WordUi.lp());
        }
        ui.note(ui.body, "使用 mimo-v2.5-tts-voicedesign。建议 1–4 句清晰描述性别、年龄、音色和语气；避免矛盾特征、后期效果词和空泛词，朗读文本宜与音色描述匹配。");

        // ── 4. VoiceClone (record or import WAV/MP3) ──────────────────────────
        ui.section(ui.body, "我的克隆音色", "参考音频");
        JSONArray clones = VoiceStore.profilesOfKind(this, VoiceStore.KIND_CLONE);
        if (clones.length() == 0) {
            ui.body.addView(ui.empty(
                R.drawable.ic_user_round,
                "还没有克隆音色",
                "录制或导入一段你有权使用的 WAV / MP3 参考音频。音频保存在本机，仅在朗读时发送给小米 MiMo。",
                null,
                null
            ));
        } else {
            LinearLayout cloneList = ui.group();
            for (int i = 0; i < clones.length(); i++) {
                JSONObject profile = clones.optJSONObject(i);
                if (profile == null) continue;
                final JSONObject item = profile;
                boolean selected = isSelectedProfile(profile);
                boolean consented = VoiceConsent.isConsented(profile);
                String format = "audio/wav".equals(profile.optString("mime")) ? "WAV" : "MP3";
                String sub = format + " · " + (consented ? "已确认授权" : "待确认授权") + (selected ? " · 当前默认" : "");
                int icon = consented ? R.drawable.ic_user_round : R.drawable.ic_circle_alert;
                ui.item(cloneList, icon, profile.optString("name"), sub, null, true, v -> cloneActions(item));
            }
            ui.body.addView(cloneList, WordUi.lp());
        }
        ui.gap(ui.body, Theme.SPACE_12);
        LinearLayout cloneButtons = ui.row();
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
        leftLp.rightMargin = ui.dp(Theme.SPACE_8);
        cloneButtons.addView(ui.secondaryButton("录制声音", v -> startRecordingFlow()), leftLp);
        cloneButtons.addView(ui.secondaryButton("导入音频", v -> pickAudio()), new LinearLayout.LayoutParams(0, -2, 1f));
        ui.body.addView(cloneButtons, WordUi.lp());
        ui.note(ui.body, "仅限克隆本人或已获合法授权的声音。支持 WAV/MP3，转换后 Base64 不超过 10 MB；MiMo 开放接口不保存永久音色 ID。");

        // ── 5. MiMo API settings & connectivity check ─────────────────────────
        ui.section(ui.body, "MiMo 语音服务", "按量付费");
        LinearLayout keyCard = ui.card();
        TextView keyStatusTitle = ui.type("密钥状态", Theme.T_CAPTION);
        keyCard.addView(keyStatusTitle);
        ui.gap(keyCard, Theme.SPACE_4);
        TextView keyStatusValue = ui.type(VoiceStore.keyStatusSummary(this), Theme.T_BODY);
        keyStatusValue.setTypeface(Theme.MEDIUM);
        keyCard.addView(keyStatusValue);
        ui.gap(keyCard, Theme.SPACE_12);
        testBtn = ui.tonalButton("测试连接", R.drawable.ic_rotate_cw, v -> runConnectionTest());
        keyCard.addView(testBtn, WordUi.lp());
        TextView probeHint = ui.type("只读请求 GET /v1/models，验证按量付费地址与密钥是否可用。", Theme.T_CAPTION);
        probeHint.setPadding(0, ui.dp(Theme.SPACE_8), 0, 0);
        keyCard.addView(probeHint, WordUi.lp());
        connectionStatus = ui.type(lastCheckMessage, Theme.T_CAPTION);
        connectionStatus.setPadding(0, ui.dp(Theme.SPACE_8), 0, 0);
        if (lastCheckMessage.isEmpty()) {
            connectionStatus.setVisibility(View.GONE);
        } else {
            connectionStatus.setVisibility(View.VISIBLE);
            connectionStatus.setTextColor(lastCheckOk ? Theme.PRIMARY : Theme.DANGER);
        }
        keyCard.addView(connectionStatus, WordUi.lp());
        ui.body.addView(keyCard, WordUi.lp());
        ui.gap(ui.body, Theme.SPACE_16);

        key = ui.field("API Key（sk- 开头，加密存在本机，留空则保留）", "", 1);
        key.setHint("留空保留已保存密钥");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);

        base = ui.field("Base URL", VoiceStore.baseUrl(this), 1);
        base.setSingleLine(true);

        model = ui.field("预置音色模型", VoiceStore.model(this), 1);
        model.setSingleLine(true);
        ui.note(ui.body, "按量付费地址为 " + MimoTtsProtocol.DEFAULT_BASE_URL + "，需配合 sk- 密钥；请勿混用 Token Plan 地址或 tp-/ttp- 密钥。预置音色支持 mimo_default、冰糖、茉莉、苏打、白桦、Mia、Chloe、Milo、Dean。");
        ui.gap(ui.body, Theme.SPACE_16);

        previewText = ui.field("试听文本（支持中英文与音频标签）", draftPreviewText, 2);
        LinearLayout samples = ui.row();
        samples.addView(ui.quietPill("英文示例", 0, v -> previewText.setText(SAMPLE_EN)), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(-2, -2);
        midLp.leftMargin = ui.dp(Theme.SPACE_8);
        samples.addView(ui.quietPill("中文示例", 0, v -> previewText.setText(SAMPLE_ZH)), midLp);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(-2, -2);
        rightLp.leftMargin = ui.dp(Theme.SPACE_8);
        samples.addView(ui.quietPill("提醒示例", 0, v -> previewText.setText(SAMPLE_REMINDER)), rightLp);
        ui.body.addView(samples, WordUi.lp());

        ui.gap(ui.body, Theme.SPACE_16);
        ui.body.addView(ui.textButton(
            "清空语音缓存（" + String.format(Locale.CHINA, "%.1f", SpeechManager.cacheSize(this) / 1024f / 1024f) + " MB）",
            v -> {
                long freed = SpeechManager.clearCache(this);
                ui.toast("已释放 " + String.format(Locale.CHINA, "%.1f", freed / 1024f / 1024f) + " MB");
                show();
            }
        ));
        ui.body.addView(ui.textButton("查看声音授权与隐私说明", v -> showConsentInfoSheet()));
        ui.body.addView(ui.dangerTextButton("清除 MiMo 语音 Key", v -> {
            AiSecrets.clearVoice(this);
            key.setText("");
            lastCheckMessage = "";
            ui.toast("本机 MiMo 语音 Key 已清除");
            show();
        }));

        ui.pinPair(
            ui.secondaryButton("试听", v -> previewCurrent()),
            ui.primaryButton("保存", v -> {
                if (save()) {
                    ui.toast("朗读设置已保存");
                    show();
                }
            })
        );
    }

    private boolean isSelectedProfile(JSONObject profile) {
        return VoiceStore.MIMO.equals(VoiceStore.provider(this))
            && profile.optString("id").equals(VoiceStore.voiceId(this));
    }

    private void buildOptions() {
        options.clear();
        options.add(new Option(VoiceStore.SYSTEM, "", "系统语音", "离线，使用设备文字转语音", "system"));
        for (MimoTtsProtocol.Preset preset : MimoTtsProtocol.PRESETS) {
            options.add(new Option(VoiceStore.MIMO, preset.id, preset.label, preset.detail, "preset"));
        }
        JSONArray designs = VoiceStore.profilesOfKind(this, VoiceStore.KIND_DESIGN);
        for (int i = 0; i < designs.length(); i++) {
            JSONObject profile = designs.optJSONObject(i);
            if (profile != null) {
                options.add(new Option(
                    VoiceStore.MIMO,
                    profile.optString("id"),
                    profile.optString("name"),
                    "设计音色 · " + clipLine(profile.optString("description", ""), 26),
                    VoiceStore.KIND_DESIGN
                ));
            }
        }
        JSONArray clones = VoiceStore.profilesOfKind(this, VoiceStore.KIND_CLONE);
        for (int i = 0; i < clones.length(); i++) {
            JSONObject profile = clones.optJSONObject(i);
            if (profile != null) {
                boolean consented = VoiceConsent.isConsented(profile);
                String format = "audio/wav".equals(profile.optString("mime")) ? "WAV" : "MP3";
                options.add(new Option(
                    VoiceStore.MIMO,
                    profile.optString("id"),
                    profile.optString("name"),
                    "克隆音色 · " + format + (consented ? "" : " · 待确认授权"),
                    VoiceStore.KIND_CLONE
                ));
            }
        }

        voiceIndex = 0;
        String provider = VoiceStore.provider(this);
        String id = VoiceStore.voiceId(this);
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            if (option.provider.equals(provider) && (VoiceStore.SYSTEM.equals(option.provider) || option.id.equals(id))) {
                voiceIndex = i;
                break;
            }
        }
    }

    private void pickVoice() {
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            labels[i] = o.label + " · " + o.detail;
        }
        Sheet.pick(this, "选择默认音色", labels, voiceIndex, index -> {
            Option option = options.get(index);
            if (VoiceStore.KIND_CLONE.equals(option.kind)) {
                JSONObject profile = VoiceStore.profile(this, option.id);
                if (profile != null && !VoiceConsent.isConsented(profile)) {
                    promptConsentForExistingClone(profile, () -> applyVoiceSelection(index));
                    return;
                }
            }
            applyVoiceSelection(index);
        });
    }

    private void applyVoiceSelection(int index) {
        voiceIndex = index;
        if (save()) {
            ui.toast("默认音色已改为「" + options.get(index).label + "」");
            show();
        }
    }

    private void selectOption(String provider, String id) {
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            if (option.provider.equals(provider) && option.id.equals(id == null ? "" : id)) {
                voiceIndex = i;
                return;
            }
        }
    }

    // ── Connection Test (GET /v1/models) ──────────────────────────────────────

    private void runConnectionTest() {
        String baseUrl = base == null ? VoiceStore.baseUrl(this) : base.getText().toString().trim();
        String typedKey = key == null ? "" : key.getText().toString().trim();
        String effectiveKey = typedKey.isEmpty() ? VoiceStore.speechKey(this) : typedKey;

        String baseErr = MimoTtsProtocol.validateBaseUrl(baseUrl);
        if (baseErr != null) {
            updateConnectionStatus(false, baseErr);
            ui.error(baseErr);
            return;
        }
        String keyErr = MimoTtsProtocol.validateApiKey(effectiveKey);
        if (keyErr != null) {
            updateConnectionStatus(false, keyErr);
            ui.error(keyErr);
            return;
        }

        if (pendingProbe != null) pendingProbe.cancel(true);
        if (testBtn != null) {
            WordUi.enable(testBtn, false);
            testBtn.setText("正在测试连接…");
        }
        updateConnectionStatus(true, "正在请求只读模型列表 GET /v1/models…");

        pendingProbe = MimoSpeechClient.checkConnectionAsync(baseUrl, effectiveKey, (ok, summary, models) -> {
            if (isFinishing() || isDestroyed()) return;
            if (testBtn != null) {
                WordUi.enable(testBtn, true);
                testBtn.setText("测试连接");
            }
            updateConnectionStatus(ok, summary);
            if (ok) ui.toast(summary);
            else ui.error(summary);
        });
    }

    private void updateConnectionStatus(boolean ok, String message) {
        lastCheckOk = ok;
        lastCheckMessage = message == null ? "" : message;
        if (connectionStatus != null) {
            connectionStatus.setText(lastCheckMessage);
            connectionStatus.setTextColor(ok ? Theme.PRIMARY : Theme.DANGER);
            connectionStatus.setVisibility(lastCheckMessage.isEmpty() ? View.GONE : View.VISIBLE);
            Motion.enter(connectionStatus);
        }
    }

    // ── VoiceDesign create / edit / actions ───────────────────────────────────

    private void designActions(JSONObject profile) {
        new Sheet(this)
            .title(profile.optString("name"))
            .message(clipLine(profile.optString("description", ""), 90))
            .item(R.drawable.ic_volume_2, "试听", () -> previewProfile(profile))
            .item(R.drawable.ic_check, "设为默认", () -> {
                selectOption(VoiceStore.MIMO, profile.optString("id"));
                if (save()) {
                    ui.toast("已设为默认音色");
                    show();
                }
            })
            .item(R.drawable.ic_pencil, "编辑音色描述", () -> openDesignEditor(profile))
            .dangerItem(R.drawable.ic_trash_2, "删除", () -> confirmDelete(profile, "删除设计音色？", "将删除这条本地音色描述配置。"))
            .secondary("关闭", null)
            .show();
    }

    private void openDesignEditor(JSONObject existing) {
        boolean editing = existing != null;
        LinearLayout form = ui.col();
        TextView nameLabel = ui.type("音色名称", Theme.T_CALLOUT);
        nameLabel.setTypeface(Theme.MEDIUM);
        form.addView(nameLabel);
        ui.gap(form, Theme.SPACE_6);
        EditText nameInput = ui.input("例如：温柔女声、沉稳新闻播音", editing ? existing.optString("name", "") : "");
        nameInput.setSingleLine(true);
        form.addView(nameInput, WordUi.lp());
        ui.gap(form, Theme.SPACE_16);

        TextView descLabel = ui.type("音色描述（VoiceDesign 必填）", Theme.T_CALLOUT);
        descLabel.setTypeface(Theme.MEDIUM);
        form.addView(descLabel);
        ui.gap(form, Theme.SPACE_6);
        EditText descInput = ui.input(
            "例如：清澈温柔的年轻女声，普通话标准，语速舒缓自然，像耐心的学习伙伴。",
            editing ? existing.optString("description", "") : ""
        );
        descInput.setMinLines(3);
        descInput.setMaxLines(6);
        descInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        descInput.setGravity(Gravity.TOP | Gravity.START);
        form.addView(descInput, WordUi.lp());
        ui.gap(form, Theme.SPACE_8);

        TextView tip = ui.type("提示：1–4 句更清晰；避免矛盾特征、后期效果词和空泛词。试听和朗读文本应与描述的语言和风格匹配。", Theme.T_CAPTION);
        form.addView(tip, WordUi.lp());

        new Sheet(this)
            .title(editing ? "编辑设计音色" : "新建设计音色")
            .message("仅保存在本机，调用 mimo-v2.5-tts-voicedesign 时发送描述与朗读文本。")
            .content(form)
            .secondary("取消", null)
            .primaryIf("保存", () -> {
                String n = nameInput.getText().toString().trim();
                String d = descInput.getText().toString().trim();
                if (n.isEmpty()) {
                    nameInput.setError("请输入音色名称");
                    nameInput.requestFocus();
                    return false;
                }
                if (d.isEmpty()) {
                    descInput.setError("请输入音色描述");
                    descInput.requestFocus();
                    return false;
                }
                try {
                    VoiceStore.saveDesignProfile(this, editing ? existing.optString("id") : null, n, d);
                    ui.toast(editing ? "设计音色已更新" : "设计音色已保存，可试听并设为默认");
                    show();
                    return true;
                } catch (Exception e) {
                    ui.error(e.getMessage() == null ? "保存失败" : e.getMessage());
                    return false;
                }
            })
            .show();
    }

    // ── VoiceClone actions, consent, recording & import ───────────────────────

    private void cloneActions(JSONObject profile) {
        boolean consented = VoiceConsent.isConsented(profile);
        String format = "audio/wav".equals(profile.optString("mime")) ? "WAV" : "MP3";
        Sheet sheet = new Sheet(this)
            .title(profile.optString("name"))
            .message("克隆音色 · " + format + (consented ? " · 已确认授权" : " · 尚未确认上传授权"));

        sheet.item(R.drawable.ic_volume_2, "试听合成效果", () -> {
            if (!VoiceConsent.isConsented(profile)) {
                promptConsentForExistingClone(profile, () -> {
                    JSONObject updated = VoiceStore.profile(this, profile.optString("id"));
                    if (updated != null) previewProfile(updated);
                });
                return;
            }
            previewProfile(profile);
        });
        sheet.item(R.drawable.ic_volume_2, "试听本机参考音频（离线）", () -> playLocalAudioFile(new File(profile.optString("path", ""))));
        sheet.item(R.drawable.ic_check, "设为默认", () -> {
            if (!VoiceConsent.isConsented(profile)) {
                promptConsentForExistingClone(profile, () -> {
                    selectOption(VoiceStore.MIMO, profile.optString("id"));
                    if (save()) {
                        ui.toast("已设为默认音色");
                        show();
                    }
                });
                return;
            }
            selectOption(VoiceStore.MIMO, profile.optString("id"));
            if (save()) {
                ui.toast("已设为默认音色");
                show();
            }
        });
        if (!consented) {
            sheet.item(R.drawable.ic_shield_check, "确认声音授权", () -> promptConsentForExistingClone(profile, null));
        }
        sheet.dangerItem(R.drawable.ic_trash_2, "删除", () -> confirmDelete(
            profile,
            "删除克隆音色？",
            "将删除本机参考音频和配置。已生成的语音缓存仍可另行清空。"
        ));
        sheet.secondary("关闭", null).show();
    }

    private void promptConsentForExistingClone(JSONObject profile, Runnable onConfirmed) {
        LinearLayout box = ui.col();
        box.addView(consentCard());
        ui.gap(box, Theme.SPACE_12);
        CheckBox agree = ui.check(VoiceConsent.checkboxLabel(), false);
        box.addView(agree, WordUi.lp());

        new Sheet(this)
            .title("确认声音克隆授权")
            .message("使用「" + profile.optString("name") + "」进行语音合成前，需先明确同意上传参考音频。")
            .content(box)
            .secondary("暂不使用", null)
            .primaryIf("同意并继续", () -> {
                if (!agree.isChecked()) {
                    ui.toast("请先勾选同意声音授权说明");
                    return false;
                }
                try {
                    VoiceStore.recordConsent(this, profile.optString("id"));
                    ui.toast("已记录声音授权确认");
                    show();
                    if (onConfirmed != null) onConfirmed.run();
                    return true;
                } catch (Exception e) {
                    ui.error(e.getMessage() == null ? "未能保存授权状态" : e.getMessage());
                    return false;
                }
            })
            .show();
    }

    private void showConsentInfoSheet() {
        LinearLayout content = ui.col();
        content.addView(consentCard());
        ui.gap(content, Theme.SPACE_12);
        content.addView(ui.textButton("在浏览器中查看小米 MiMo 用户协议", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(VoiceConsent.USER_AGREEMENT_URL)));
            } catch (Exception e) {
                ui.error("无法打开浏览器，请手动访问 " + VoiceConsent.USER_AGREEMENT_URL);
            }
        }));
        new Sheet(this)
            .title("声音授权与隐私说明")
            .content(content)
            .secondary("关闭", null)
            .show();
    }

    private LinearLayout consentCard() {
        LinearLayout card = ui.col();
        card.setPadding(ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_12));
        card.setBackground(Theme.shape(this, Theme.SUNK, Theme.RADIUS_FIELD));
        TextView text = ui.type(VoiceConsent.disclosureSummary(), Theme.T_CAPTION);
        text.setTextColor(Theme.INK_2);
        card.addView(text, WordUi.lp());
        return card;
    }

    private void startRecordingFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        openRecorderSheet();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openRecorderSheet();
            } else {
                ui.error("未授予麦克风权限，可在系统设置中开启，或直接导入本地 WAV/MP3 音频");
            }
        }
    }

    private void openRecorderSheet() {
        stopLocalPlayer();
        if (activeRecorder != null) {
            activeRecorder.cancel();
            activeRecorder = null;
        }
        final AudioRecorderHelper recorder = new AudioRecorderHelper(this);
        activeRecorder = recorder;
        final File[] recordedFile = {null};
        final boolean[] saved = {false};

        LinearLayout form = ui.col();
        TextView nameLabel = ui.type("音色名称", Theme.T_CALLOUT);
        nameLabel.setTypeface(Theme.MEDIUM);
        form.addView(nameLabel);
        ui.gap(form, Theme.SPACE_6);
        EditText nameInput = ui.input("例如：我的声音", "");
        nameInput.setSingleLine(true);
        form.addView(nameInput, WordUi.lp());
        ui.gap(form, Theme.SPACE_16);

        // Live recorder status panel
        LinearLayout meterBox = ui.col();
        meterBox.setPadding(ui.dp(Theme.SPACE_16), ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_16), ui.dp(Theme.SPACE_12));
        meterBox.setBackground(Theme.shape(this, Theme.SUNK, Theme.RADIUS_FIELD));
        TextView recStatus = ui.type("准备就绪 · 建议在安静环境清晰朗读数秒", Theme.T_CALLOUT);
        recStatus.setTypeface(Theme.MEDIUM);
        recStatus.setTextColor(Theme.INK);
        meterBox.addView(recStatus, WordUi.lp());
        ui.gap(meterBox, Theme.SPACE_4);
        TextView recMeta = ui.type("00:00 · WAV 单声道 · Base64 0.00 / 10.0 MB", Theme.T_CAPTION);
        meterBox.addView(recMeta, WordUi.lp());
        form.addView(meterBox, WordUi.lp());
        ui.gap(form, Theme.SPACE_12);

        // Record / stop + preview / re-record controls
        LinearLayout controls = ui.row();
        final Button[] toggleBtn = {null};
        final Button[] playBtn = {null};
        final Button[] resetBtn = {null};

        playBtn[0] = ui.quietPill("试听录音", R.drawable.ic_volume_2, v -> {
            if (recordedFile[0] == null || !recordedFile[0].isFile()) {
                ui.toast("请先录制参考音频");
                return;
            }
            playLocalAudioFile(recordedFile[0]);
        });
        WordUi.enable(playBtn[0], false);

        resetBtn[0] = ui.quietPill("重录", R.drawable.ic_rotate_cw, v -> {
            stopLocalPlayer();
            if (recorder.isRecording()) recorder.cancel();
            if (recordedFile[0] != null) {
                recordedFile[0].delete();
                recordedFile[0] = null;
            }
            recStatus.setText("准备就绪 · 建议在安静环境清晰朗读数秒");
            recStatus.setTextColor(Theme.INK);
            recMeta.setText("00:00 · WAV 单声道 · Base64 0.00 / 10.0 MB");
            toggleBtn[0].setText("开始录音");
            WordUi.enable(playBtn[0], false);
            WordUi.enable(resetBtn[0], false);
        });
        WordUi.enable(resetBtn[0], false);

        toggleBtn[0] = ui.tonalButton("开始录音", 0, v -> {
            if (recorder.isRecording()) {
                toggleBtn[0].setText("正在保存录音…");
                WordUi.enable(toggleBtn[0], false);
                recorder.stop();
                return;
            }
            stopLocalPlayer();
            if (recordedFile[0] != null) {
                recordedFile[0].delete();
                recordedFile[0] = null;
            }
            WordUi.enable(playBtn[0], false);
            WordUi.enable(resetBtn[0], false);
            recStatus.setText("正在录音… 再次点击停止");
            recStatus.setTextColor(Theme.PRIMARY);
            toggleBtn[0].setText("停止录音");
            recorder.start(new AudioRecorderHelper.Listener() {
                @Override public void onProgress(long durationMs, long rawWavBytes, long estimatedBase64Bytes) {
                    recMeta.setText(formatDuration(durationMs)
                        + " · WAV " + (recorder.sampleRate() / 1000) + "kHz · Base64 "
                        + formatMb(estimatedBase64Bytes) + " / 10.0 MB");
                }

                @Override public void onStopped(File wavFile, long durationMs, long rawWavBytes, boolean reachedLimit) {
                    recordedFile[0] = wavFile;
                    long b64 = MimoTtsProtocol.encodedBase64Length(rawWavBytes);
                    WordUi.enable(toggleBtn[0], true);
                    toggleBtn[0].setText("重新开始");
                    WordUi.enable(playBtn[0], true);
                    WordUi.enable(resetBtn[0], true);
                    recStatus.setText(reachedLimit ? "已接近 10 MB Base64 上限并自动停止" : "录音完成，可先试听确认");
                    recStatus.setTextColor(reachedLimit ? Theme.WARN : Theme.PRIMARY);
                    recMeta.setText(formatDuration(durationMs)
                        + " · WAV " + (recorder.sampleRate() / 1000) + "kHz · Base64 "
                        + formatMb(b64) + " / 10.0 MB");
                }

                @Override public void onError(String message) {
                    WordUi.enable(toggleBtn[0], true);
                    toggleBtn[0].setText("开始录音");
                    recStatus.setText(message);
                    recStatus.setTextColor(Theme.DANGER);
                    ui.error(message);
                }
            });
        });

        form.addView(toggleBtn[0], WordUi.lp());
        ui.gap(form, Theme.SPACE_8);
        controls.addView(playBtn[0], new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, -2);
        resetLp.leftMargin = ui.dp(Theme.SPACE_8);
        controls.addView(resetBtn[0], resetLp);
        form.addView(controls, WordUi.lp());
        ui.gap(form, Theme.SPACE_16);

        form.addView(consentCard());
        ui.gap(form, Theme.SPACE_8);
        CheckBox authorized = ui.check(VoiceConsent.checkboxLabel(), false);
        form.addView(authorized, WordUi.lp());

        new Sheet(this)
            .title("录制克隆音色")
            .message("录音直接保存为本机 WAV 参考音频，不会在保存时自动上传。")
            .content(form)
            .secondary("取消", null)
            .primaryIf("保存音色", () -> {
                if (recorder.isRecording()) {
                    ui.toast("请先点击「停止录音」");
                    return false;
                }
                String value = nameInput.getText().toString().trim();
                if (value.isEmpty()) {
                    nameInput.setError("请输入音色名称");
                    nameInput.requestFocus();
                    return false;
                }
                if (recordedFile[0] == null || !recordedFile[0].isFile()) {
                    ui.toast("请先录制一段参考音频");
                    return false;
                }
                if (!authorized.isChecked()) {
                    ui.toast("请先阅读并勾选声音授权确认");
                    return false;
                }
                try {
                    VoiceStore.saveRecordedAudio(this, recordedFile[0], value, true);
                    saved[0] = true;
                    recordedFile[0].delete();
                    recordedFile[0] = null;
                    ui.toast("录音音色已保存，可试听并设为默认");
                    show();
                    return true;
                } catch (Exception e) {
                    ui.error(e.getMessage() == null ? "录音音色保存失败" : e.getMessage());
                    return false;
                }
            })
            .onDismiss(() -> {
                stopLocalPlayer();
                if (recorder.isRecording()) recorder.cancel();
                if (activeRecorder == recorder) activeRecorder = null;
                if (!saved[0] && recordedFile[0] != null) {
                    recordedFile[0].delete();
                    recordedFile[0] = null;
                }
            })
            .show();
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3"});
        try {
            startActivityForResult(intent, PICK_AUDIO);
        } catch (Exception e) {
            ui.error("没有可用的音频选择器");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_AUDIO || result != RESULT_OK || data == null || data.getData() == null) return;
        openImportSheet(data.getData());
    }

    private void openImportSheet(Uri uri) {
        stopLocalPlayer();
        String displayName = queryDisplayName(uri);
        long rawSize = queryFileSize(uri);
        String suggestedName = stripExtension(displayName);

        LinearLayout form = ui.col();
        LinearLayout infoRow = ui.row();
        infoRow.setPadding(ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_10), ui.dp(Theme.SPACE_12), ui.dp(Theme.SPACE_10));
        infoRow.setBackground(Theme.shape(this, Theme.SUNK, Theme.RADIUS_FIELD));
        String info = displayName.isEmpty() ? "已选择音频文件" : displayName;
        if (rawSize > 0L) {
            long b64 = MimoTtsProtocol.encodedBase64Length(rawSize);
            info += " · Base64 约 " + formatMb(b64) + " MB";
            if (b64 > MimoTtsProtocol.MAX_BASE64_BYTES) {
                info += "（超过 10 MB 限制）";
            }
        }
        TextView fileInfo = ui.type(info, Theme.T_CAPTION);
        fileInfo.setTextColor(rawSize > 0L && MimoTtsProtocol.encodedBase64Length(rawSize) > MimoTtsProtocol.MAX_BASE64_BYTES ? Theme.DANGER : Theme.INK_2);
        infoRow.addView(fileInfo, WordUi.weight());
        infoRow.addView(ui.quietPill("试听", R.drawable.ic_volume_2, v -> playLocalAudioUri(uri)), new LinearLayout.LayoutParams(-2, -2));
        form.addView(infoRow, WordUi.lp());
        ui.gap(form, Theme.SPACE_12);

        TextView nameLabel = ui.type("音色名称", Theme.T_CALLOUT);
        nameLabel.setTypeface(Theme.MEDIUM);
        form.addView(nameLabel);
        ui.gap(form, Theme.SPACE_6);
        EditText name = ui.input("音色名称", suggestedName);
        name.setSingleLine(true);
        form.addView(name, WordUi.lp());
        ui.gap(form, Theme.SPACE_12);

        form.addView(consentCard());
        ui.gap(form, Theme.SPACE_8);
        CheckBox authorized = ui.check(VoiceConsent.checkboxLabel(), false);
        form.addView(authorized, WordUi.lp());

        new Sheet(this)
            .title("导入克隆音色")
            .message("支持 WAV 或 MP3，转换后 Base64 不超过 10 MB。")
            .content(form)
            .secondary("取消", null)
            .primaryIf("导入", () -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) {
                    name.setError("请输入音色名称");
                    name.requestFocus();
                    return false;
                }
                if (!authorized.isChecked()) {
                    ui.toast("请先阅读并勾选声音授权确认");
                    return false;
                }
                try {
                    String mime = getContentResolver().getType(uri);
                    VoiceStore.importAudio(this, uri, value, mime, displayName, true);
                    ui.toast("音色已保存，可试听并设为默认");
                    show();
                    return true;
                } catch (Exception e) {
                    ui.error(e.getMessage() == null ? "导入失败" : e.getMessage());
                    return false;
                }
            })
            .onDismiss(this::stopLocalPlayer)
            .show();
    }

    // ── Preview & Save ────────────────────────────────────────────────────────

    private void previewCurrent() {
        if (!save()) return;
        Option option = options.get(voiceIndex);
        if (VoiceStore.KIND_CLONE.equals(option.kind)) {
            JSONObject profile = VoiceStore.profile(this, option.id);
            if (profile != null && !VoiceConsent.isConsented(profile)) {
                promptConsentForExistingClone(profile, this::previewCurrent);
                return;
            }
        }
        String text = currentPreviewText();
        speech.speak(text, "preview", option.provider, option.id, rate.getSelectedItemPosition());
    }

    private void previewProfile(JSONObject profile) {
        if (VoiceStore.isClone(profile) && !VoiceConsent.isConsented(profile)) {
            promptConsentForExistingClone(profile, () -> {
                JSONObject updated = VoiceStore.profile(this, profile.optString("id"));
                if (updated != null) previewProfile(updated);
            });
            return;
        }
        if (!saveSettingsOnly()) return;
        int rateIdx = rate == null ? Store.prefs(this).getInt("speechRate", 2) : rate.getSelectedItemPosition();
        speech.speak(currentPreviewText(), "preview", VoiceStore.MIMO, profile.optString("id"), rateIdx);
    }

    private String currentPreviewText() {
        String text = previewText == null ? draftPreviewText : previewText.getText().toString().trim();
        return text.isEmpty() ? SAMPLE_EN : text;
    }

    /** Validates baseUrl/key inputs and persists current voice selection + MiMo settings. */
    private boolean save() {
        String baseUrl = base == null ? VoiceStore.baseUrl(this) : base.getText().toString().trim();
        String modelName = model == null ? VoiceStore.model(this) : model.getText().toString().trim();
        String typedKey = key == null ? "" : key.getText().toString().trim();
        if (!baseUrl.isEmpty()) {
            String baseErr = MimoTtsProtocol.validateBaseUrl(baseUrl);
            if (baseErr != null) {
                ui.error(baseErr);
                return false;
            }
        }
        if (!typedKey.isEmpty()) {
            String keyErr = MimoTtsProtocol.validateApiKey(typedKey);
            if (keyErr != null) {
                ui.error(keyErr);
                return false;
            }
        }
        Option option = options.get(Math.max(0, Math.min(voiceIndex, options.size() - 1)));
        int rateIdx = rate == null ? Store.prefs(this).getInt("speechRate", 2) : rate.getSelectedItemPosition();
        boolean ok = VoiceStore.saveSelection(this, option.provider, option.id, rateIdx, baseUrl, modelName, typedKey);
        if (!ok) {
            ui.error("设置未能保存");
        } else if (key != null) {
            key.setText("");
        }
        return ok;
    }

    /** Saves typed MiMo key / baseUrl / model without changing the active default voice. */
    private boolean saveSettingsOnly() {
        String baseUrl = base == null ? VoiceStore.baseUrl(this) : base.getText().toString().trim();
        String modelName = model == null ? VoiceStore.model(this) : model.getText().toString().trim();
        String typedKey = key == null ? "" : key.getText().toString().trim();
        if (!baseUrl.isEmpty()) {
            String baseErr = MimoTtsProtocol.validateBaseUrl(baseUrl);
            if (baseErr != null) {
                ui.error(baseErr);
                return false;
            }
        }
        if (!typedKey.isEmpty()) {
            String keyErr = MimoTtsProtocol.validateApiKey(typedKey);
            if (keyErr != null) {
                ui.error(keyErr);
                return false;
            }
        }
        int rateIdx = rate == null ? Store.prefs(this).getInt("speechRate", 2) : rate.getSelectedItemPosition();
        boolean ok = VoiceStore.saveSelection(this, VoiceStore.provider(this), VoiceStore.voiceId(this), rateIdx, baseUrl, modelName, typedKey);
        if (!ok) {
            ui.error("设置未能保存");
        } else if (key != null && !typedKey.isEmpty()) {
            key.setText("");
        }
        return ok;
    }

    private void confirmDelete(JSONObject profile, String title, String message) {
        Sheet.confirm(this, title, message, "取消", "删除", true, () -> {
            try {
                VoiceStore.delete(this, profile.optString("id"));
                ui.toast("已删除「" + profile.optString("name") + "」");
                show();
            } catch (Exception e) {
                ui.error(e.getMessage() == null ? "删除失败" : e.getMessage());
            }
        });
    }

    // ── Local audio playback (reference audio & recordings) ───────────────────

    private void playLocalAudioFile(File file) {
        if (file == null || !file.isFile()) {
            ui.error("参考音频文件不存在");
            return;
        }
        stopLocalPlayer();
        speech.stop();
        MediaPlayer mp = new MediaPlayer();
        localPlayer = mp;
        try {
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(p -> {
                if (localPlayer == p) stopLocalPlayer();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (localPlayer == p) stopLocalPlayer();
                ui.error("无法播放该本机音频文件");
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopLocalPlayer();
            ui.error("无法播放该本机音频文件");
        }
    }

    private void playLocalAudioUri(Uri uri) {
        stopLocalPlayer();
        speech.stop();
        MediaPlayer mp = new MediaPlayer();
        localPlayer = mp;
        try {
            mp.setDataSource(this, uri);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(p -> {
                if (localPlayer == p) stopLocalPlayer();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (localPlayer == p) stopLocalPlayer();
                ui.error("无法播放所选音频");
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopLocalPlayer();
            ui.error("无法播放所选音频");
        }
    }

    private void stopLocalPlayer() {
        if (localPlayer != null) {
            try { localPlayer.stop(); } catch (Exception ignored) {}
            try { localPlayer.release(); } catch (Exception ignored) {}
            localPlayer = null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null) return value.trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private long queryFileSize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1L;
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        if (slash >= 0 && slash + 1 < trimmed.length()) trimmed = trimmed.substring(slash + 1);
        int dot = trimmed.lastIndexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    private static String clipLine(String text, int maxChars) {
        String clean = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > maxChars ? clean.substring(0, maxChars) + "…" : clean;
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    private static String formatMb(long bytes) {
        return String.format(Locale.US, "%.2f", bytes / (1024.0 * 1024.0));
    }

    @Override protected void onPause() {
        stopLocalPlayer();
        speech.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (pendingProbe != null) {
            pendingProbe.cancel(true);
            pendingProbe = null;
        }
        if (activeRecorder != null) {
            activeRecorder.cancel();
            activeRecorder = null;
        }
        stopLocalPlayer();
        speech.shutdown();
        super.onDestroy();
    }
}
