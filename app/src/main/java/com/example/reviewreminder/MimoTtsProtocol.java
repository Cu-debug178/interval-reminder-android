package com.example.reviewreminder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java Xiaomi MiMo TTS protocol helpers (OpenAI-compatible Chat Completions).
 * Covers the three verified models:
 *   1. mimo-v2.5-tts (preset voices)
 *   2. mimo-v2.5-tts-voicedesign (text-described custom voice; no audio.voice field)
 *   3. mimo-v2.5-tts-voiceclone (reference WAV/MP3 base64 <= 10 MB; requires explicit consent)
 */
public final class MimoTtsProtocol {
    public static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
    public static final String MODEL_PRESET = "mimo-v2.5-tts";
    public static final String MODEL_DESIGN = "mimo-v2.5-tts-voicedesign";
    public static final String MODEL_CLONE = "mimo-v2.5-tts-voiceclone";
    public static final String DEFAULT_PRESET_VOICE = "mimo_default";
    public static final String DEFAULT_FORMAT = "wav";

    /** Official limit: encoded Base64 string must not exceed 10 MB (10 * 1024 * 1024 bytes/chars). */
    public static final int MAX_BASE64_BYTES = 10 * 1024 * 1024;
    /** Exact maximum raw audio bytes whose Base64 encoding fits within {@link #MAX_BASE64_BYTES}. */
    public static final int MAX_RAW_AUDIO_BYTES = (MAX_BASE64_BYTES / 4) * 3;

    public static final class Preset {
        public final String id;
        public final String label;
        public final String detail;
        Preset(String id, String label, String detail) {
            this.id = id;
            this.label = label;
            this.detail = detail;
        }
    }

    public static final Preset[] PRESETS = {
        new Preset("mimo_default", "MiMo 默认", "预置音色 · 国内默认冰糖，海外默认 Mia"),
        new Preset("冰糖", "冰糖", "MiMo 预置中文音色"),
        new Preset("茉莉", "茉莉", "MiMo 预置中文音色"),
        new Preset("苏打", "苏打", "MiMo 预置中文音色"),
        new Preset("白桦", "白桦", "MiMo 预置中文音色"),
        new Preset("Mia", "Mia", "MiMo 预置英文女声"),
        new Preset("Chloe", "Chloe", "MiMo 预置英文女声"),
        new Preset("Milo", "Milo", "MiMo 预置英文男声"),
        new Preset("Dean", "Dean", "MiMo 预置英文男声")
    };

    private MimoTtsProtocol() {}

    public static boolean isPresetVoice(String voiceId) {
        if (voiceId == null || voiceId.isEmpty()) return false;
        for (Preset preset : PRESETS) {
            if (preset.id.equals(voiceId)) return true;
        }
        return false;
    }

    public static String normalizeBase(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/models")) {
            value = value.substring(0, value.length() - "/models".length());
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** Returns null when valid, or a Chinese user-facing error message. */
    public static String validateBaseUrl(String baseUrl) {
        String root = normalizeBase(baseUrl);
        if (root.isEmpty()) return "请填写 Base URL（默认 " + DEFAULT_BASE_URL + "）";
        if (!root.startsWith("https://") && !root.startsWith("http://")) {
            return "Base URL 无效，请以 https:// 开头";
        }
        try {
            URL parsed = new URL(root);
            String host = parsed.getHost();
            if (host == null || host.isEmpty()) return "Base URL 缺少有效域名";
            String lowerHost = host.toLowerCase(Locale.US);
            if (lowerHost.contains("token-plan")) {
                return "MiMo 语音合成需使用按量付费地址 " + DEFAULT_BASE_URL + "，不能使用 Token Plan 地址";
            }
        } catch (Exception e) {
            return "Base URL 格式无效";
        }
        return null;
    }

    /** Returns null when valid, or a Chinese user-facing error message. */
    public static String validateApiKey(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) return "请先填写 MiMo API Key";
        if (key.startsWith("tp-") || key.startsWith("ttp-")) {
            return "检测到 Token Plan 密钥前缀（tp-/ttp-），MiMo 语音合成需使用按量付费密钥（sk- 开头），二者不可混用";
        }
        return null;
    }

    public static String modelsEndpoint(String baseUrl) {
        String root = normalizeBase(baseUrl);
        return (root.isEmpty() ? DEFAULT_BASE_URL : root) + "/models";
    }

    public static String completionsEndpoint(String baseUrl) {
        String root = normalizeBase(baseUrl);
        return (root.isEmpty() ? DEFAULT_BASE_URL : root) + "/chat/completions";
    }

    /** Exact Base64 string length (RFC 4648 with padding) for {@code rawBytes}. */
    public static long encodedBase64Length(long rawBytes) {
        if (rawBytes <= 0L) return 0L;
        return 4L * ((rawBytes + 2L) / 3L);
    }

    public static void checkBase64Limit(long rawBytes) {
        if (rawBytes <= 0L) throw new IllegalArgumentException("参考音频内容为空");
        if (encodedBase64Length(rawBytes) > MAX_BASE64_BYTES) {
            throw new IllegalArgumentException("参考音频转换为 Base64 后超过 10 MB 限制，请缩短录音或压缩音频后再试");
        }
    }

    public static void checkEncodedBase64Limit(String base64) {
        if (base64 == null || base64.isEmpty()) throw new IllegalArgumentException("参考音频编码结果为空");
        if (base64.length() > MAX_BASE64_BYTES) {
            throw new IllegalArgumentException("参考音频转换为 Base64 后超过 10 MB 限制，请缩短录音或压缩音频后再试");
        }
    }

    /**
     * Normalizes reference audio MIME to {@code audio/wav} or {@code audio/mpeg} using magic bytes,
     * declared MIME type, and file name. Rejects unsupported formats clearly.
     */
    public static String normalizeAudioMime(String declaredMime, String fileName, byte[] leadingBytes) {
        if (WavAudio.isWavHeader(leadingBytes)) return "audio/wav";
        if (WavAudio.isMp3Header(leadingBytes)) return "audio/mpeg";

        String lowerMime = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.US);
        String lowerName = fileName == null ? "" : fileName.trim().toLowerCase(Locale.US);

        boolean wavHint = lowerMime.contains("wav") || lowerName.endsWith(".wav");
        boolean mp3Hint = lowerMime.contains("mpeg") || lowerMime.contains("mp3") || lowerName.endsWith(".mp3");
        if (wavHint) return "audio/wav";
        if (mp3Hint) return "audio/mpeg";
        throw new IllegalArgumentException("MiMo 声音克隆仅支持 WAV 或 MP3 格式参考音频");
    }

    public static String normalizeFormat(String format) {
        String f = format == null ? "" : format.trim().toLowerCase(Locale.US);
        if (f.isEmpty()) return DEFAULT_FORMAT;
        if ("wav".equals(f) || "mp3".equals(f) || "pcm".equals(f) || "pcm16".equals(f)) return f;
        throw new IllegalArgumentException("不支持的输出音频格式：" + format);
    }

    /**
     * Builds the reference audio data URI required by {@code mimo-v2.5-tts-voiceclone}:
     * {@code data:{MIME_TYPE};base64,$BASE64_AUDIO}, enforcing WAV/MP3 and <= 10 MB Base64.
     */
    public static String buildCloneVoiceDataUri(String mime, byte[] referenceAudio) {
        if (referenceAudio == null || referenceAudio.length < 4) {
            throw new IllegalArgumentException("参考音频内容无效");
        }
        String normalizedMime = normalizeAudioMime(mime, null, referenceAudio);
        checkBase64Limit(referenceAudio.length);
        String encoded = Base64Compat.encode(referenceAudio);
        checkEncodedBase64Limit(encoded);
        return "data:" + normalizedMime + ";base64," + encoded;
    }

    /**
     * Builds request JSON for {@code mimo-v2.5-tts} (preset voice).
     * Target speech text is always placed in {@code role: assistant}; optional style/pace in {@code user}.
     */
    public static JSONObject buildPresetRequest(
        String modelOrNull,
        String voiceIdOrNull,
        String text,
        String styleInstructionOrNull,
        float rate,
        String formatOrNull
    ) throws Exception {
        String targetText = requireText(text);
        String model = modelOrNull == null || modelOrNull.trim().isEmpty() ? MODEL_PRESET : modelOrNull.trim();
        String voice = voiceIdOrNull == null || voiceIdOrNull.trim().isEmpty() ? DEFAULT_PRESET_VOICE : voiceIdOrNull.trim();
        if (!isPresetVoice(voice)) {
            throw new IllegalArgumentException("未知的 MiMo 预置音色：" + voice);
        }
        JSONObject audio = new JSONObject()
            .put("format", normalizeFormat(formatOrNull))
            .put("voice", voice);

        JSONArray messages = new JSONArray();
        String userPrompt = resolveStyleInstruction(styleInstructionOrNull, targetText, rate, true);
        if (!userPrompt.isEmpty()) {
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        }
        messages.put(new JSONObject().put("role", "assistant").put("content", targetText));

        return new JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("audio", audio)
            .put("messages", messages);
    }

    /**
     * Builds request JSON for {@code mimo-v2.5-tts-voicedesign}.
     * Per official docs: {@code user} is required (voice description), {@code audio.voice} is NOT supported,
     * and {@code assistant} can be omitted only when {@code audio.optimize_text_preview=true}.
     */
    public static JSONObject buildVoiceDesignRequest(
        String voiceDescription,
        String text,
        String formatOrNull,
        boolean optimizeTextPreview
    ) throws Exception {
        String desc = voiceDescription == null ? "" : voiceDescription.trim();
        if (desc.isEmpty()) {
            throw new IllegalArgumentException("请填写音色描述（VoiceDesign 必填）");
        }
        String targetText = text == null ? "" : text.trim();
        if (targetText.isEmpty() && !optimizeTextPreview) {
            throw new IllegalArgumentException("待朗读文本不能为空");
        }
        JSONObject audio = new JSONObject().put("format", normalizeFormat(formatOrNull));
        if (optimizeTextPreview) {
            audio.put("optimize_text_preview", true);
        }

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", desc));
        if (!targetText.isEmpty()) {
            messages.put(new JSONObject().put("role", "assistant").put("content", targetText));
        }

        return new JSONObject()
            .put("model", MODEL_DESIGN)
            .put("stream", false)
            .put("audio", audio)
            .put("messages", messages);
    }

    /**
     * Builds request JSON for {@code mimo-v2.5-tts-voiceclone}.
     * Refuses to construct a payload unless {@code consentConfirmed} is explicitly true.
     */
    public static JSONObject buildVoiceCloneRequest(
        String mime,
        byte[] referenceAudio,
        boolean consentConfirmed,
        String text,
        String styleInstructionOrNull,
        float rate,
        String formatOrNull
    ) throws Exception {
        if (!consentConfirmed) {
            throw new IllegalStateException("尚未确认声音克隆与上传授权，已阻止上传参考音频");
        }
        String targetText = requireText(text);
        String voiceDataUri = buildCloneVoiceDataUri(mime, referenceAudio);

        JSONObject audio = new JSONObject()
            .put("format", normalizeFormat(formatOrNull))
            .put("voice", voiceDataUri);

        JSONArray messages = new JSONArray();
        String userPrompt = resolveStyleInstruction(styleInstructionOrNull, targetText, rate, false);
        if (!userPrompt.isEmpty()) {
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        }
        messages.put(new JSONObject().put("role", "assistant").put("content", targetText));

        return new JSONObject()
            .put("model", MODEL_CLONE)
            .put("stream", false)
            .put("audio", audio)
            .put("messages", messages);
    }

    /** Parses {@code GET /v1/models} response ({@code object=list}, {@code data[].id}). */
    public static List<String> parseModelList(String responseBody) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try {
            String raw = responseBody == null ? "" : responseBody.trim();
            if (raw.startsWith("[")) {
                collectModelIds(ids, new JSONArray(raw));
            } else if (!raw.isEmpty()) {
                JSONObject root = new JSONObject(raw);
                collectModelIds(ids, root.optJSONArray("data"));
            }
        } catch (Exception ignored) {
        }
        List<String> list = new ArrayList<>(ids);
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public static String summarizeTtsModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return "连接成功，但接口未返回模型列表";
        }
        List<String> found = new ArrayList<>();
        if (models.contains(MODEL_PRESET)) found.add("预置朗读");
        if (models.contains(MODEL_DESIGN)) found.add("音色设计");
        if (models.contains(MODEL_CLONE)) found.add("声音克隆");
        if (!found.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < found.size(); i++) {
                if (i > 0) joined.append("、");
                joined.append(found.get(i));
            }
            return "连接成功：共 " + models.size() + " 个模型，可用语音能力：" + joined;
        }
        return "连接成功（共 " + models.size() + " 个模型，列表中未显示 mimo-v2.5-tts*，请检查账号权限或区域）";
    }

    /** Parses non-streaming chat completion audio response and decodes the Base64 audio payload. */
    public static byte[] parseAudioResponse(String responseBody) throws Exception {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new IllegalStateException("MiMo 未返回响应内容");
        }
        JSONObject root = new JSONObject(responseBody);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("MiMo 未返回音频结果");
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            throw new IllegalStateException("MiMo 响应格式无效");
        }
        String finishReason = choice.optString("finish_reason", "");
        JSONObject message = choice.optJSONObject("message");
        JSONObject audio = message == null ? null : message.optJSONObject("audio");
        String data = audio == null ? "" : audio.optString("data", "").trim();
        if (data.isEmpty()) {
            if ("content_filter".equals(finishReason)) {
                throw new IllegalStateException("内容未通过安全审核，未能生成语音");
            }
            if ("length".equals(finishReason)) {
                throw new IllegalStateException("生成内容超出长度限制，未能返回完整音频");
            }
            throw new IllegalStateException("MiMo 未返回音频数据");
        }
        byte[] decoded = Base64Compat.decode(data);
        if (decoded.length == 0) {
            throw new IllegalStateException("MiMo 返回的音频数据为空");
        }
        return decoded;
    }

    public static boolean isRetryableStatus(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    /** Maps MiMo HTTP status codes into clear Chinese messages and always redacts secrets. */
    public static String explainHttpError(int code, String responseBody, String apiKey) {
        String detail = extractErrorDetail(responseBody, apiKey);
        String base;
        switch (code) {
            case 400:
                base = "请求参数或参考音频格式/大小不符合要求（400）";
                break;
            case 401:
                base = "API Key 无效、已过期，或混用了按量付费与 Token Plan 密钥/地址（401）";
                break;
            case 402:
                base = "账号余额不足，请前往 MiMo 控制台检查账单与余额（402）";
                break;
            case 403:
                base = "当前地区不可用或 API Key 受风控限制（403）";
                break;
            case 404:
                base = "接口地址或模型能力不支持，请检查 Base URL 与模型名称（404）";
                break;
            case 421:
                base = "文本或参考音频未通过内容审核（421）";
                break;
            case 429:
                base = "请求过于频繁或额度已用尽，请稍后重试（429）";
                break;
            case 500:
            case 502:
            case 503:
            case 504:
                base = "MiMo 语音服务暂时不可用，请稍后重试（" + code + "）";
                break;
            default:
                base = "MiMo 语音请求失败（HTTP " + code + "）";
                break;
        }
        return detail.isEmpty() ? base : base + "：" + detail;
    }

    public static String redactSecrets(String text, String apiKey) {
        String value = text == null ? "" : text;
        if (apiKey != null && apiKey.trim().length() >= 4) {
            value = value.replace(apiKey.trim(), "••••");
        }
        value = value.replaceAll("(sk|tp|ttp)-[A-Za-z0-9_\\-]{6,}", "••••");
        return value;
    }

    private static String requireText(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("待朗读文本不能为空");
        return value;
    }

    private static String resolveStyleInstruction(String customStyle, String targetText, float rate, boolean allowSinging) {
        if (customStyle != null && !customStyle.trim().isEmpty()) {
            return customStyle.trim();
        }
        if (allowSinging && startsWithSingingTag(targetText)) {
            return "";
        }
        // Only attach a gentle pace hint when the user picked a non-1.0x reading speed.
        if (rate > 0f && Math.abs(rate - 1.0f) >= 0.05f) {
            return "自然清晰地朗读，语速约 " + String.format(Locale.US, "%.2f", rate) + " 倍";
        }
        return "";
    }

    private static boolean startsWithSingingTag(String text) {
        String lower = text.trim().toLowerCase(Locale.US);
        return lower.startsWith("(唱歌)") || lower.startsWith("（唱歌）") || lower.startsWith("[唱歌]")
            || lower.startsWith("(sing)") || lower.startsWith("(singing)")
            || lower.startsWith("[sing]") || lower.startsWith("[singing]");
    }

    private static void collectModelIds(LinkedHashSet<String> out, JSONArray data) throws Exception {
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            Object item = data.get(i);
            String id = "";
            if (item instanceof String) {
                id = ((String) item).trim();
            } else if (item instanceof JSONObject) {
                JSONObject obj = (JSONObject) item;
                id = obj.optString("id", "").trim();
            }
            if (!id.isEmpty()) out.add(id);
        }
    }

    private static String extractErrorDetail(String body, String apiKey) {
        String detail = "";
        try {
            if (body != null && !body.trim().isEmpty()) {
                JSONObject root = new JSONObject(body);
                JSONObject err = root.optJSONObject("error");
                if (err != null) {
                    detail = err.optString("message", "").trim();
                } else {
                    detail = root.optString("message", "").trim();
                }
            }
        } catch (Exception ignored) {
        }
        detail = redactSecrets(detail, apiKey).replace('\n', ' ').replace('\r', ' ').trim();
        String lower = detail.toLowerCase(Locale.US);
        if (lower.contains("<html") || lower.contains("<!doctype")) return "";
        if (detail.length() > 80) detail = detail.substring(0, 80) + "…";
        return detail;
    }
}
