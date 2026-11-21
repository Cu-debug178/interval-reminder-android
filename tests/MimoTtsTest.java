import com.example.reviewreminder.MimoTtsProtocol;
import com.example.reviewreminder.VoiceConsent;
import com.example.reviewreminder.WavAudio;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MimoTtsTest {
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void expectThrows(Class<? extends Throwable> expected, Runnable action, String label) {
        checks++;
        try {
            action.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) return;
            throw new AssertionError(label + " (got " + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " for: " + label);
    }

    public static void main(String[] args) throws Exception {
        // 1. Official presets & URL / Key validation
        String[] officialPresets = {"mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"};
        check(MimoTtsProtocol.PRESETS.length == officialPresets.length, "all 9 official preset IDs are listed");
        for (String id : officialPresets) {
            check(MimoTtsProtocol.isPresetVoice(id), "official preset recognized: " + id);
        }
        check(!MimoTtsProtocol.isPresetVoice("custom_unknown"), "unknown preset id rejected");
        check(MimoTtsProtocol.validateBaseUrl(MimoTtsProtocol.DEFAULT_BASE_URL) == null, "official pay-as-you-go base URL accepted");
        check(MimoTtsProtocol.validateBaseUrl("https://token-plan-cn.xiaomimimo.com/v1") != null, "Token Plan URL rejected for TTS");
        check(MimoTtsProtocol.validateBaseUrl("not-a-url") != null, "invalid URL rejected");
        check(MimoTtsProtocol.modelsEndpoint("https://api.xiaomimimo.com/v1/chat/completions/").equals("https://api.xiaomimimo.com/v1/models"), "models endpoint normalized");
        check(MimoTtsProtocol.completionsEndpoint("https://api.xiaomimimo.com/v1/models").equals("https://api.xiaomimimo.com/v1/chat/completions"), "completions endpoint normalized");

        String fakePaygKey = "sk-" + "example_test_value_123456";
        String fakeTpKey = "tp-" + "example_token_plan_123456";
        String fakeTtpKey = "ttp-" + "example_token_plan_123456";
        check(MimoTtsProtocol.validateApiKey(fakePaygKey) == null, "pay-as-you-go sk- key accepted");
        check(MimoTtsProtocol.validateApiKey(fakeTpKey) != null, "Token Plan tp- key rejected");
        check(MimoTtsProtocol.validateApiKey(fakeTtpKey) != null, "Token Plan ttp- key rejected");
        check(MimoTtsProtocol.validateApiKey("   ") != null, "blank key rejected");

        // 2. Preset request construction (mimo-v2.5-tts)
        JSONObject presetReq = MimoTtsProtocol.buildPresetRequest(null, "冰糖", "你好，开始复习。", null, 0.85f, "wav");
        check(MimoTtsProtocol.MODEL_PRESET.equals(presetReq.getString("model")), "preset uses mimo-v2.5-tts");
        check(!presetReq.getBoolean("stream"), "preset non-streaming flag is false");
        check("wav".equals(presetReq.getJSONObject("audio").getString("format")), "preset format is wav");
        check("冰糖".equals(presetReq.getJSONObject("audio").getString("voice")), "preset voice id set");
        JSONArray presetMsgs = presetReq.getJSONArray("messages");
        check(presetMsgs.length() == 2, "preset with 0.85x pace includes user style + assistant target");
        check("user".equals(presetMsgs.getJSONObject(0).getString("role")), "first message is user style instruction");
        check("assistant".equals(presetMsgs.getJSONObject(1).getString("role")), "target text placed in assistant role");
        check("你好，开始复习。".equals(presetMsgs.getJSONObject(1).getString("content")), "assistant holds exact speech text");

        JSONObject singReq = MimoTtsProtocol.buildPresetRequest(null, "mimo_default", "(唱歌) 一闪一闪亮晶晶", null, 0.85f, "wav");
        check(singReq.getJSONArray("messages").length() == 1 && "assistant".equals(singReq.getJSONArray("messages").getJSONObject(0).getString("role")),
            "singing tag at start of assistant text does not inject conflicting pace user prompt");

        expectThrows(IllegalArgumentException.class, () -> {
            try { MimoTtsProtocol.buildPresetRequest(null, "not_a_preset", "hello", null, 1.0f, "wav"); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "unknown preset voice rejected in request builder");

        // 3. VoiceDesign request construction (mimo-v2.5-tts-voicedesign)
        JSONObject designReq = MimoTtsProtocol.buildVoiceDesignRequest("温柔清澈的年轻女声，语速舒缓自然。", "Learning a little every day.", "wav", false);
        check(MimoTtsProtocol.MODEL_DESIGN.equals(designReq.getString("model")), "design uses mimo-v2.5-tts-voicedesign");
        check(!designReq.getJSONObject("audio").has("voice"), "voicedesign audio MUST NOT include voice field");
        check("wav".equals(designReq.getJSONObject("audio").getString("format")), "voicedesign format is wav");
        JSONArray designMsgs = designReq.getJSONArray("messages");
        check(designMsgs.length() == 2, "voicedesign has user description and assistant text");
        check("user".equals(designMsgs.getJSONObject(0).getString("role")) && designMsgs.getJSONObject(0).getString("content").contains("温柔清澈"), "voicedesign user holds voice description");
        check("assistant".equals(designMsgs.getJSONObject(1).getString("role")) && "Learning a little every day.".equals(designMsgs.getJSONObject(1).getString("content")), "voicedesign assistant holds target text");

        JSONObject designPreviewOpt = MimoTtsProtocol.buildVoiceDesignRequest("沉稳男声，语速平稳。", "", "wav", true);
        check(designPreviewOpt.getJSONObject("audio").optBoolean("optimize_text_preview"), "optimize_text_preview flag preserved");
        check(designPreviewOpt.getJSONArray("messages").length() == 1 && "user".equals(designPreviewOpt.getJSONArray("messages").getJSONObject(0).getString("role")),
            "voicedesign may omit assistant only when optimize_text_preview=true");

        expectThrows(IllegalArgumentException.class, () -> {
            try { MimoTtsProtocol.buildVoiceDesignRequest("   ", "hello", "wav", false); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "voicedesign rejects blank voice description");

        expectThrows(IllegalArgumentException.class, () -> {
            try { MimoTtsProtocol.buildVoiceDesignRequest("温柔女声", "", "wav", false); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "voicedesign rejects empty assistant text when optimize_text_preview is false");

        // 4. WavAudio & VoiceClone request construction + consent enforcement
        byte[] pcm = new byte[4800]; // 100ms at 24kHz mono 16-bit
        byte[] wav = WavAudio.encodePcm16Mono(pcm, 24000);
        check(wav.length == WavAudio.HEADER_BYTES + pcm.length, "WAV output includes 44-byte header + PCM payload");
        check(WavAudio.isWavHeader(wav), "WAV header detected accurately");
        check(!WavAudio.isMp3Header(wav), "WAV bytes not misclassified as MP3");
        check(WavAudio.durationMs(pcm.length, 24000, 1, 16) == 100L, "PCM duration calculation matches 100ms");
        check(WavAudio.isMp3Header(new byte[]{'I', 'D', '3', 3, 0}), "ID3 MP3 header detected");
        check(WavAudio.isMp3Header(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0}), "MPEG frame sync MP3 header detected");

        // Consent MUST be required before constructing clone request
        expectThrows(IllegalStateException.class, () -> {
            try { MimoTtsProtocol.buildVoiceCloneRequest("audio/wav", wav, false, "Hello", null, 1.0f, "wav"); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "voiceclone refuses to build payload without explicit user consent");

        JSONObject unconsentedProfile = new JSONObject().put("id", "c1").put("name", "测试").put("kind", "clone");
        check(!VoiceConsent.isConsented(unconsentedProfile), "profile without consent timestamp is not consented");
        VoiceConsent.stampConsent(unconsentedProfile, 1700000000000L);
        check(VoiceConsent.isConsented(unconsentedProfile), "stamped profile passes consent verification");
        check(VoiceConsent.disclosureSummary().contains("声纹") && VoiceConsent.disclosureSummary().contains("小米 MiMo"),
            "consent disclosure explains sensitive voiceprint upload to Xiaomi MiMo");

        JSONObject cloneReq = MimoTtsProtocol.buildVoiceCloneRequest("audio/wav", wav, true, "Hello world", null, 1.0f, "wav");
        check(MimoTtsProtocol.MODEL_CLONE.equals(cloneReq.getString("model")), "clone uses mimo-v2.5-tts-voiceclone");
        String voiceUri = cloneReq.getJSONObject("audio").getString("voice");
        check(voiceUri.startsWith("data:audio/wav;base64,"), "clone voice uses data:audio/wav;base64 URI");
        check(cloneReq.getJSONArray("messages").length() == 1 && "assistant".equals(cloneReq.getJSONArray("messages").getJSONObject(0).getString("role")),
            "clone at 1.0x places target text in assistant role");

        expectThrows(IllegalArgumentException.class, () ->
            MimoTtsProtocol.normalizeAudioMime("audio/ogg", "clip.ogg", new byte[]{'O', 'g', 'g', 'S', 0, 0, 0, 0, 0, 0, 0, 0}),
            "unsupported audio format (ogg) rejected");

        // 5. Base64 10 MB boundary check (encoded string <= 10 * 1024 * 1024)
        check(MimoTtsProtocol.encodedBase64Length(MimoTtsProtocol.MAX_RAW_AUDIO_BYTES) == MimoTtsProtocol.MAX_BASE64_BYTES,
            "MAX_RAW_AUDIO_BYTES encodes to exactly 10 MB Base64");
        MimoTtsProtocol.checkBase64Limit(MimoTtsProtocol.MAX_RAW_AUDIO_BYTES);
        checks++;
        expectThrows(IllegalArgumentException.class, () ->
            MimoTtsProtocol.checkBase64Limit(MimoTtsProtocol.MAX_RAW_AUDIO_BYTES + 1L),
            "raw bytes whose Base64 exceeds 10 MB are rejected");

        // 6. Model list & audio response parsing
        List<String> models = MimoTtsProtocol.parseModelList(
            "{\"object\":\"list\",\"data\":[{\"id\":\"mimo-v2.5-tts\",\"object\":\"model\"},{\"id\":\"mimo-v2.5-tts-voicedesign\"},{\"id\":\"mimo-v2.5-tts-voiceclone\"}]}"
        );
        check(models.size() == 3, "parses all 3 TTS models from /v1/models");
        String summary = MimoTtsProtocol.summarizeTtsModels(models);
        check(summary.contains("预置朗读") && summary.contains("音色设计") && summary.contains("声音克隆"), "model summary lists all 3 TTS capabilities");

        String audioJson = new JSONObject()
            .put("object", "chat.completion")
            .put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "stop")
                .put("message", new JSONObject().put("audio", new JSONObject()
                    .put("id", "aud_1")
                    .put("data", "UklGRg==")
                    .put("expires_at", JSONObject.NULL)
                    .put("transcript", JSONObject.NULL)))))
            .toString();
        byte[] decoded = MimoTtsProtocol.parseAudioResponse(audioJson);
        check(decoded.length == 4 && decoded[0] == 'R' && decoded[1] == 'I', "parses base64 audio bytes from completion");

        String filteredJson = "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"audio\":{\"data\":\"\"}}}]}";
        expectThrows(IllegalStateException.class, () -> {
            try { MimoTtsProtocol.parseAudioResponse(filteredJson); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "content_filter finish reason surfaces error");

        // 7. HTTP error mapping & secret redaction
        int[] codes = {400, 401, 402, 403, 404, 421, 429, 500, 503};
        for (int code : codes) {
            String err = MimoTtsProtocol.explainHttpError(code, "{\"error\":{\"message\":\"rejected " + fakePaygKey + "\"}}", fakePaygKey);
            check(err.contains(Integer.toString(code)), "error message includes status code " + code);
            check(!err.contains(fakePaygKey), "error message redacts API key for status " + code);
        }
        check(MimoTtsProtocol.isRetryableStatus(429) && MimoTtsProtocol.isRetryableStatus(503), "429/503 are retryable");
        check(!MimoTtsProtocol.isRetryableStatus(400) && !MimoTtsProtocol.isRetryableStatus(401)
            && !MimoTtsProtocol.isRetryableStatus(402) && !MimoTtsProtocol.isRetryableStatus(421), "4xx client/billing/filter errors are not retried");

        // 8. Repository secret scan: ensure no real API keys exist in app source or Gradle files
        Pattern keyPattern = Pattern.compile("(?:sk|tp|ttp)-[A-Za-z0-9_\\-]{16,}");
        Path[] roots = {Paths.get("app/src"), Paths.get("app/build.gradle"), Paths.get("build.gradle"), Paths.get("settings.gradle")};
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String name = file.toString().toLowerCase();
                        if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".wav")) return;
                        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                        if (keyPattern.matcher(content).find()) {
                            throw new AssertionError("Potential hardcoded API key found in " + file);
                        }
                    } catch (AssertionError ae) {
                        throw ae;
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        checks++;

        System.out.println("MimoTtsTest: " + checks + " checks passed");
    }
}
