package com.example.reviewreminder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SSLException;

/**
 * Xiaomi MiMo TTS HTTP client:
 * - Read-only connectivity probe via {@code GET /v1/models}
 * - Non-streaming synthesis via {@code POST /v1/chat/completions} for preset, voicedesign and voiceclone
 * Never logs API keys or reference audio payloads.
 */
final class MimoSpeechClient {
    private static final ExecutorService WORKER = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface ModelsCallback {
        void onResult(boolean ok, String summary, List<String> models);
    }

    static final class ModelsResult {
        final boolean ok;
        final String summary;
        final List<String> models;

        ModelsResult(boolean ok, String summary, List<String> models) {
            this.ok = ok;
            this.summary = summary;
            this.models = models == null ? Collections.<String>emptyList() : models;
        }
    }

    private MimoSpeechClient() {}

    /** Async read-only connectivity check using {@code GET /v1/models}. */
    static Future<?> checkConnectionAsync(String baseUrl, String apiKey, ModelsCallback callback) {
        return WORKER.submit(() -> {
            ModelsResult result = listModels(baseUrl, apiKey);
            MAIN.post(() -> callback.onResult(result.ok, result.summary, result.models));
        });
    }

    /** Blocking read-only model list check ({@code GET /v1/models}). */
    static ModelsResult listModels(String baseUrl, String apiKey) {
        String baseErr = MimoTtsProtocol.validateBaseUrl(baseUrl);
        if (baseErr != null) return new ModelsResult(false, baseErr, Collections.<String>emptyList());
        String keyErr = MimoTtsProtocol.validateApiKey(apiKey);
        if (keyErr != null) return new ModelsResult(false, keyErr, Collections.<String>emptyList());

        String key = apiKey.trim();
        String endpoint = MimoTtsProtocol.modelsEndpoint(baseUrl);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("api-key", key);
            int code = conn.getResponseCode();
            String body = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                return new ModelsResult(false, MimoTtsProtocol.explainHttpError(code, body, key), Collections.<String>emptyList());
            }
            List<String> models = MimoTtsProtocol.parseModelList(body);
            return new ModelsResult(true, MimoTtsProtocol.summarizeTtsModels(models), models);
        } catch (Exception e) {
            return new ModelsResult(false, explainNetwork(e), Collections.<String>emptyList());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Synthesizes audio using saved MiMo settings (preset, VoiceDesign, or consented VoiceClone). */
    static byte[] synthesize(Context c, String text, String voiceId, JSONObject profile, float rate) throws Exception {
        return synthesize(c, text, voiceId, profile, rate, VoiceStore.baseUrl(c), VoiceStore.speechKey(c), VoiceStore.model(c), null);
    }

    /** Synthesizes audio with explicit baseUrl/apiKey/model overrides (e.g. unsaved settings preview). */
    static byte[] synthesize(
        Context c,
        String text,
        String voiceId,
        JSONObject profile,
        float rate,
        String baseUrl,
        String apiKey,
        String presetModel,
        String styleInstruction
    ) throws Exception {
        String baseErr = MimoTtsProtocol.validateBaseUrl(baseUrl);
        if (baseErr != null) throw new IllegalArgumentException(baseErr);
        String keyErr = MimoTtsProtocol.validateApiKey(apiKey);
        if (keyErr != null) throw new IllegalStateException(keyErr);
        String key = apiKey.trim();

        JSONObject body;
        if (profile != null && VoiceStore.isDesign(profile)) {
            body = MimoTtsProtocol.buildVoiceDesignRequest(
                profile.optString("description", ""),
                text,
                MimoTtsProtocol.DEFAULT_FORMAT,
                false
            );
        } else if (profile != null) {
            boolean consented = VoiceConsent.isConsented(profile);
            if (!consented) {
                throw new IllegalStateException("该克隆音色尚未确认声音授权，请先在音色管理中确认授权");
            }
            byte[] sample = VoiceStore.sample(c, profile);
            body = MimoTtsProtocol.buildVoiceCloneRequest(
                profile.optString("mime", ""),
                sample,
                true,
                text,
                styleInstruction,
                rate,
                MimoTtsProtocol.DEFAULT_FORMAT
            );
        } else {
            String targetPreset = (voiceId == null || voiceId.trim().isEmpty())
                ? MimoTtsProtocol.DEFAULT_PRESET_VOICE
                : voiceId.trim();
            body = MimoTtsProtocol.buildPresetRequest(
                presetModel,
                targetPreset,
                text,
                styleInstruction,
                rate,
                MimoTtsProtocol.DEFAULT_FORMAT
            );
        }

        String endpoint = MimoTtsProtocol.completionsEndpoint(baseUrl);
        String payload = body.toString();
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return postAudio(endpoint, key, payload);
            } catch (RetryableError e) {
                last = e;
                if (attempt == 2) throw new IOException(e.getMessage(), e);
                long delayMs = e.retryAfterMs > 0L ? Math.min(e.retryAfterMs, 5000L) : (600L * (1 << attempt));
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("语音请求已取消", interrupted);
                }
            }
        }
        throw last == null ? new IOException("语音生成失败") : last;
    }

    private static byte[] postAudio(String endpoint, String key, String json) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(90000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("api-key", key);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            String response = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                String msg = MimoTtsProtocol.explainHttpError(code, response, key);
                if (MimoTtsProtocol.isRetryableStatus(code)) {
                    throw new RetryableError(msg, parseRetryAfterMs(conn.getHeaderField("Retry-After")));
                }
                throw new NonRetryableHttpError(msg);
            }
            return MimoTtsProtocol.parseAudioResponse(response);
        } catch (RetryableError | NonRetryableHttpError e) {
            throw e;
        } catch (IOException e) {
            throw new RetryableError(explainNetwork(e), 0L);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long parseRetryAfterMs(String header) {
        if (header == null || header.trim().isEmpty()) return 0L;
        try {
            long sec = Long.parseLong(header.trim());
            return sec > 0L ? sec * 1000L : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String explainNetwork(Exception e) {
        if (e instanceof UnknownHostException) return "网络不可用或域名无法解析";
        if (e instanceof SocketTimeoutException) return "连接 MiMo 服务超时";
        if (e instanceof SSLException) return "安全连接（HTTPS）失败";
        if (e instanceof ConnectException) return "无法连接到 MiMo 服务器";
        return "网络连接失败";
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = source.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static final class RetryableError extends IOException {
        final long retryAfterMs;
        RetryableError(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final class NonRetryableHttpError extends IOException {
        NonRetryableHttpError(String message) {
            super(message);
        }
    }
}
