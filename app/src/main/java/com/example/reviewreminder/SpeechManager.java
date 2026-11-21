package com.example.reviewreminder;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One generation-aware speech path for offline system TTS, MiMo presets,
 * MiMo VoiceDesign (text description), and MiMo VoiceClone (consented reference audio).
 * Any network/key/consent/playback failure falls back to system TTS and surfaces the reason.
 */
final class SpeechManager {
    private static final ExecutorService WORKER = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long CACHE_LIMIT = 64 * 1024 * 1024;

    private final Context app;
    private TextToSpeech tts;
    private MediaPlayer player;
    private Future<?> pending;
    private boolean ttsReady, ttsStarting, active = true;
    private int generation;
    private String pendingText, currentText, pendingSystemVoice = "", speakProvider = VoiceStore.SYSTEM;
    private int pendingGeneration, pendingRate = 2, speakRate = 2;
    private Runnable completionCallback;

    SpeechManager(Context context) {
        app = context.getApplicationContext();
    }

    void speak(String text, String context) {
        speak(text, context, null, null, -1, null);
    }

    /**
     * Review playback uses the saved voice. A preview may pass a provider, voice id and rate index
     * that have not been saved yet; null / -1 keep the saved selection.
     */
    void speak(String text, String context, String providerOverride, String voiceOverride, int rateOverride) {
        speak(text, context, providerOverride, voiceOverride, rateOverride, null);
    }

    void speak(String text, String context, String providerOverride, String voiceOverride, int rateOverride, Runnable onDone) {
        final String value = text == null ? "" : text.trim();
        if (value.isEmpty() || !active) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        stopCurrent();
        final int ticket = ++generation;
        currentText = value;
        completionCallback = onDone;

        final String provider = providerOverride != null ? providerOverride : VoiceStore.provider(app);
        final String voiceId = providerOverride != null ? (voiceOverride == null ? "" : voiceOverride) : VoiceStore.voiceId(app);
        final int rateIndex = rateOverride >= 0 ? SpeechSettings.rateIndex(rateOverride) : Store.prefs(app).getInt("speechRate", 2);
        speakProvider = provider;
        speakRate = rateIndex;

        if (!VoiceStore.MIMO.equals(provider)) {
            systemSpeak(value, ticket, provider, rateIndex);
            return;
        }

        final JSONObject profile = VoiceStore.profile(app, voiceId);
        if (profile == null) {
            if (!voiceId.isEmpty() && !VoiceStore.preset(voiceId)) {
                toast("所选音色不存在，已回退到系统语音");
                systemSpeak(value, ticket, provider, rateIndex);
                return;
            }
        } else if (VoiceStore.isClone(profile) && !VoiceConsent.isConsented(profile)) {
            toast("该克隆音色尚未确认声音授权，已回退到系统语音");
            systemSpeak(value, ticket, provider, rateIndex);
            return;
        }

        File cached = cacheFile(app, provider, voiceId, profile, value, context, rateIndex);
        if (cached.isFile()) {
            playFile(cached, ticket);
            return;
        }
        String key = VoiceStore.speechKey(app);
        if (key.isEmpty()) {
            toast("未配置 MiMo 语音 Key，已回退到系统语音");
            systemSpeak(value, ticket, provider, rateIndex);
            return;
        }
        String baseErr = MimoTtsProtocol.validateBaseUrl(VoiceStore.baseUrl(app));
        if (baseErr != null) {
            toast(baseErr + "，已回退到系统语音");
            systemSpeak(value, ticket, provider, rateIndex);
            return;
        }
        String keyErr = MimoTtsProtocol.validateApiKey(key);
        if (keyErr != null) {
            toast(keyErr + "，已回退到系统语音");
            systemSpeak(value, ticket, provider, rateIndex);
            return;
        }

        toast("正在生成 MiMo 语音…");
        pending = WORKER.submit(() -> {
            try {
                byte[] audio = MimoSpeechClient.synthesize(app, value, voiceId, profile, SpeechSettings.rate(rateIndex));
                writeCache(cached, audio);
                MAIN.post(() -> {
                    if (valid(ticket)) playFile(cached, ticket);
                });
            } catch (Exception e) {
                String reason = e.getMessage() == null || e.getMessage().isEmpty() ? "语音生成失败" : e.getMessage();
                MAIN.post(() -> {
                    if (valid(ticket)) {
                        toast(reason + "，已回退到系统语音");
                        systemSpeak(value, ticket, provider, rateIndex);
                    }
                });
            }
        });
    }

    private void systemSpeak(String text, int ticket, String provider, int rateIndex) {
        if (!valid(ticket)) return;
        String systemVoice = VoiceStore.SYSTEM.equals(provider) ? Store.prefs(app).getString("speechVoice", "") : "";
        if (ttsReady && tts != null) {
            if (configureTtsForText(tts, text, systemVoice, rateIndex)) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "learning-" + ticket);
            } else {
                toast("设备的系统语音不可用");
                notifyDone(ticket);
            }
            return;
        }
        pendingText = text;
        pendingGeneration = ticket;
        pendingRate = rateIndex;
        pendingSystemVoice = systemVoice;
        if (tts == null) {
            ttsStarting = true;
            tts = new TextToSpeech(app, status -> MAIN.post(() -> {
                ttsStarting = false;
                if (!active || tts == null) return;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onDone(String utteranceId) {
                        MAIN.post(() -> notifyDoneForUtterance(utteranceId));
                    }
                    @Override public void onError(String utteranceId) {
                        MAIN.post(() -> notifyDoneForUtterance(utteranceId));
                    }
                });
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && pendingGeneration == generation && pendingText != null) {
                    String value = pendingText;
                    pendingText = null;
                    if (configureTtsForText(tts, value, pendingSystemVoice, pendingRate)) {
                        tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "learning-" + generation);
                    } else {
                        toast("设备的系统语音不可用");
                        notifyDone(generation);
                    }
                } else if (!ttsReady && pendingGeneration == generation) {
                    toast("设备的系统语音不可用");
                    notifyDone(generation);
                }
            }));
        } else if (!ttsStarting) {
            toast("系统语音尚未就绪或当前设备不可用");
            notifyDone(ticket);
        }
    }

    private static boolean configureTtsForText(TextToSpeech engine, String text, String systemVoice, int rateIndex) {
        if (containsCjk(text)) {
            int zh = engine.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (zh != TextToSpeech.LANG_MISSING_DATA && zh != TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setSpeechRate(SpeechSettings.rate(rateIndex));
                return true;
            }
            int def = engine.setLanguage(Locale.getDefault());
            if (def != TextToSpeech.LANG_MISSING_DATA && def != TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setSpeechRate(SpeechSettings.rate(rateIndex));
                return true;
            }
        }
        return SpeechSettings.configure(engine, systemVoice, rateIndex);
    }

    private static boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) return true;
        }
        return false;
    }

    private void playFile(File file, int ticket) {
        if (!valid(ticket)) return;
        releasePlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                if (valid(ticket) && player == mp) mp.start();
            });
            player.setOnCompletionListener(mp -> {
                if (player == mp) {
                    releasePlayer();
                    notifyDone(ticket);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    releasePlayer();
                    file.delete();
                    if (valid(ticket)) {
                        toast("语音缓存播放失败，已回退到系统语音");
                        systemSpeak(currentText, ticket, speakProvider, speakRate);
                    }
                }
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            file.delete();
            if (valid(ticket)) {
                toast("音频缓存无法播放，已回退到系统语音");
                systemSpeak(currentText, ticket, speakProvider, speakRate);
            }
        }
    }

    private void notifyDoneForUtterance(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith("learning-")) return;
        try {
            int ticket = Integer.parseInt(utteranceId.substring("learning-".length()));
            notifyDone(ticket);
        } catch (Exception ignored) {
        }
    }

    private void notifyDone(int ticket) {
        if (!valid(ticket)) return;
        Runnable cb = completionCallback;
        completionCallback = null;
        if (cb != null) cb.run();
    }

    void stop() {
        if (!active) return;
        generation++;
        stopCurrent();
    }

    private void stopCurrent() {
        pendingText = null;
        completionCallback = null;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        releasePlayer();
        if (tts != null) tts.stop();
    }

    void shutdown() {
        active = false;
        generation++;
        stopCurrent();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        ttsReady = false;
    }

    private boolean valid(int ticket) {
        return active && ticket == generation;
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    private void toast(String text) {
        MAIN.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }

    static long clearCache(Context c) {
        long freed = 0;
        File[] files = cacheDir(c).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    freed += file.length();
                    file.delete();
                }
            }
        }
        return freed;
    }

    static long cacheSize(Context c) {
        long size = 0;
        File[] files = cacheDir(c).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) size += file.length();
            }
        }
        return size;
    }

    private static File cacheDir(Context c) {
        File dir = new File(c.getCacheDir(), "speech");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File cacheFile(Context c, String provider, String voiceId, JSONObject profile, String text, String context, int rateIndex) {
        String kind = profile == null ? "preset" : VoiceStore.kindOf(profile);
        String version = profile == null ? "" : profile.optString("version");
        String desc = profile != null && VoiceStore.isDesign(profile) ? profile.optString("description", "") : "";
        String model;
        if (profile == null) {
            model = VoiceStore.model(c);
        } else if (VoiceStore.isDesign(profile)) {
            model = MimoTtsProtocol.MODEL_DESIGN;
        } else {
            model = MimoTtsProtocol.MODEL_CLONE;
        }
        String effectiveVoice = profile == null && (voiceId == null || voiceId.isEmpty())
            ? MimoTtsProtocol.DEFAULT_PRESET_VOICE
            : voiceId;
        String key = provider + "|" + kind + "|" + model + "|" + effectiveVoice + "|" + version + "|" + desc + "|" + text + "|" + SpeechSettings.rate(rateIndex) + "|" + context;
        return new File(cacheDir(c), sha256(key) + ".wav");
    }

    private static void writeCache(File target, byte[] audio) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(audio);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IllegalStateException("语音缓存写入失败");
        }
        trimCache(target.getParentFile());
    }

    private static void trimCache(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= CACHE_LIMIT) break;
            if (file.isFile()) {
                long size = file.length();
                if (file.delete()) total -= size;
            }
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
