package com.example.reviewreminder;

import org.json.JSONObject;

/**
 * Voice cloning authorization copy and consent verification rules.
 * Aligns with Xiaomi MiMo's user agreement & privacy policy without fabricating API fields
 * or claiming official clauses verbatim.
 */
public final class VoiceConsent {
    public static final int CONSENT_VERSION = 1;
    public static final String USER_AGREEMENT_URL = "https://mimo.mi.com/docs/zh-CN/quick-start/terms/user-agreement";
    public static final String CONSOLE_URL = "https://platform.xiaomimimo.com/#/console/api-keys";

    private VoiceConsent() {}

    public static String disclosureSummary() {
        return "声音及其中包含的声纹信息属于敏感个人信息。\n"
            + "• 仅允许克隆你本人的声音，或已获得声音权利人事先明确、合法授权的声音；不得上传可能侵犯他人合法权益或违法违规的内容。\n"
            + "• 参考音频默认只保存在本机；仅在你使用该克隆音色试听或朗读时，应用才会将其发送给小米 MiMo 开放平台以生成语音。\n"
            + "• 小米 MiMo 开放接口不保存永久音色 ID，每次合成都会随请求附带参考音频。\n"
            + "• 未确认同意前，应用不会上传参考音频或发起声音克隆请求。";
    }

    public static String checkboxLabel() {
        return "我已阅读以上说明，确认该音频为本人声音或已获权利人合法授权，并同意在朗读时将参考音频发送至小米 MiMo 以生成语音";
    }

    /** Returns true only when the profile carries an explicit, current consent record. */
    public static boolean isConsented(JSONObject profile) {
        if (profile == null) return false;
        return profile.optLong("consentedAt", 0L) > 0L
            && profile.optInt("consentVersion", 0) >= CONSENT_VERSION;
    }

    /** Stamps a profile JSON with the current consent record. */
    public static JSONObject stampConsent(JSONObject profile, long timestampMs) throws Exception {
        if (profile == null) throw new IllegalArgumentException("音色配置为空");
        long at = timestampMs > 0L ? timestampMs : System.currentTimeMillis();
        profile.put("consentedAt", at);
        profile.put("consentVersion", CONSENT_VERSION);
        return profile;
    }
}
