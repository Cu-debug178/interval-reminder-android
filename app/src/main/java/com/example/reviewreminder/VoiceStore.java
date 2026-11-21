package com.example.reviewreminder;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * Voice profiles stored in the normalized SQLite {@code voice_profile} table.
 * Supports both locally stored cloned voices (reference WAV/MP3 + explicit consent)
 * and text-described designed voices (VoiceDesign prompt).
 */
final class VoiceStore {
    static final String SYSTEM = "system", MIMO = "mimo";
    static final String KIND_CLONE = "clone", KIND_DESIGN = "design";
    static final int MAX_BASE64_BYTES = MimoTtsProtocol.MAX_BASE64_BYTES;

    private static final String COLUMNS = "id,name,path,mime,kind,description,consented_at,consent_version,version,created";

    private VoiceStore() {}

    static JSONArray profiles(Context c) {
        try {
            return LocalStore.inRead(c, () -> {
                JSONArray out = new JSONArray();
                Cursor cur = LocalStore.database().rawQuery("SELECT " + COLUMNS + " FROM voice_profile ORDER BY created ASC", null);
                try {
                    while (cur.moveToNext()) out.put(fromCursor(cur));
                } finally {
                    cur.close();
                }
                return out;
            });
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static JSONArray profilesOfKind(Context c, String kind) {
        JSONArray all = profiles(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && kind.equals(kindOf(item))) out.put(item);
        }
        return out;
    }

    static JSONObject profile(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return LocalStore.inRead(c, () -> {
                Cursor cur = LocalStore.database().rawQuery("SELECT " + COLUMNS + " FROM voice_profile WHERE id=?", new String[]{id});
                try {
                    if (!cur.moveToFirst()) return null;
                    return fromCursor(cur);
                } finally {
                    cur.close();
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject fromCursor(Cursor cur) throws Exception {
        String kind = cur.getString(4);
        if (kind == null || kind.isEmpty()) kind = KIND_CLONE;
        return new JSONObject()
            .put("id", cur.getString(0))
            .put("name", cur.getString(1))
            .put("path", cur.getString(2))
            .put("mime", cur.getString(3))
            .put("kind", kind)
            .put("description", cur.getString(5))
            .put("consentedAt", cur.getLong(6))
            .put("consentVersion", cur.getInt(7))
            .put("version", cur.getLong(8))
            .put("created", cur.getLong(9));
    }

    static String kindOf(JSONObject profile) {
        if (profile == null) return "";
        String kind = profile.optString("kind", "").trim();
        return kind.isEmpty() ? KIND_CLONE : kind;
    }

    static boolean isDesign(JSONObject profile) {
        return KIND_DESIGN.equals(kindOf(profile));
    }

    static boolean isClone(JSONObject profile) {
        return KIND_CLONE.equals(kindOf(profile));
    }

    static String provider(Context c) {
        return Store.prefs(c).getString("voiceProvider", SYSTEM);
    }

    static String voiceId(Context c) {
        return Store.prefs(c).getString("voiceId", "");
    }

    static boolean preset(String voiceId) {
        return MimoTtsProtocol.isPresetVoice(voiceId);
    }

    static String baseUrl(Context c) {
        String value = Store.prefs(c).getString("mimoSpeechBase", MimoTtsProtocol.DEFAULT_BASE_URL).trim();
        return value.isEmpty() ? MimoTtsProtocol.DEFAULT_BASE_URL : value;
    }

    static String model(Context c) {
        String value = Store.prefs(c).getString("mimoSpeechModel", MimoTtsProtocol.MODEL_PRESET).trim();
        return value.isEmpty() ? MimoTtsProtocol.MODEL_PRESET : value;
    }

    static String speechKey(Context c) {
        String key = AiSecrets.readVoiceApiKey(c);
        if (!key.isEmpty()) return key;
        return AiSettings.provider(c) == AiSettings.PROVIDER_MIMO ? AiSettings.apiKey(c) : "";
    }

    static boolean hasDedicatedVoiceKey(Context c) {
        return !AiSecrets.readVoiceApiKey(c).isEmpty();
    }

    /** Masked human-readable key status. Never returns the full secret. */
    static String keyStatusSummary(Context c) {
        String dedicated = AiSecrets.readVoiceApiKey(c);
        if (!dedicated.isEmpty()) {
            return "已保存本机语音 Key（" + AiSecrets.mask(dedicated) + "）";
        }
        if (AiSettings.provider(c) == AiSettings.PROVIDER_MIMO) {
            String shared = AiSettings.apiKey(c);
            if (!shared.isEmpty()) {
                return "当前复用 AI 设置中的 MiMo Key（" + AiSecrets.mask(shared) + "）";
            }
        }
        return "未配置语音 Key（离线系统语音仍可正常使用）";
    }

    static boolean saveSelection(Context c, String provider, String voiceId, int rate, String baseUrl, String model, String keyOrBlank) {
        String cleanBase = baseUrl == null || baseUrl.trim().isEmpty() ? MimoTtsProtocol.DEFAULT_BASE_URL : MimoTtsProtocol.normalizeBase(baseUrl);
        String cleanModel = model == null || model.trim().isEmpty() ? MimoTtsProtocol.MODEL_PRESET : model.trim();
        if (!Store.prefs(c).edit()
            .putString("voiceProvider", provider)
            .putString("voiceId", voiceId == null ? "" : voiceId)
            .putInt("speechRate", SpeechSettings.rateIndex(rate))
            .putString("mimoSpeechBase", cleanBase)
            .putString("mimoSpeechModel", cleanModel)
            .commit()) {
            return false;
        }
        if (keyOrBlank != null && !keyOrBlank.trim().isEmpty()) {
            try {
                AiSecrets.writeVoiceApiKey(c, keyOrBlank.trim());
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** Creates or updates a locally stored VoiceDesign profile (text description only, no audio). */
    static JSONObject saveDesignProfile(Context c, String existingIdOrNull, String displayName, String description) throws Exception {
        String name = displayName == null ? "" : displayName.trim();
        String desc = description == null ? "" : description.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("请输入设计音色名称");
        if (desc.isEmpty()) throw new IllegalArgumentException("请输入音色描述");
        long now = System.currentTimeMillis();
        JSONObject existing = existingIdOrNull == null || existingIdOrNull.isEmpty() ? null : profile(c, existingIdOrNull);
        String id = existing != null ? existing.optString("id") : UUID.randomUUID().toString();
        long created = existing != null ? existing.optLong("created", now) : now;
        JSONObject profile = new JSONObject()
            .put("id", id)
            .put("name", name)
            .put("path", "")
            .put("mime", "")
            .put("kind", KIND_DESIGN)
            .put("description", desc)
            .put("consentedAt", 0L)
            .put("consentVersion", 0)
            .put("version", now)
            .put("created", created);
        LocalStore.inTransaction(c, () -> LocalStore.upsertVoiceProfileLocked(LocalStore.database(), profile));
        return profile;
    }

    static JSONObject importAudio(Context c, Uri uri, String displayName, String mime, String fileName, boolean consentConfirmed) throws Exception {
        if (!consentConfirmed) {
            throw new IllegalStateException("请先阅读并确认声音克隆与上传授权");
        }
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("请输入音色名称");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("无法读取参考音频");
            byte[] buffer = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                MimoTtsProtocol.checkBase64Limit(total);
                bytes.write(buffer, 0, n);
            }
        }
        byte[] data = bytes.toByteArray();
        if (data.length < 12) throw new IllegalArgumentException("参考音频内容无效或过短");
        String normalized = MimoTtsProtocol.normalizeAudioMime(mime, fileName, data);
        return persistCloneBytes(c, name, normalized, data);
    }

    /** Persists a locally recorded WAV file into private app storage with recorded user consent. */
    static JSONObject saveRecordedAudio(Context c, File wavFile, String displayName, boolean consentConfirmed) throws Exception {
        if (!consentConfirmed) {
            throw new IllegalStateException("请先阅读并确认声音克隆与上传授权");
        }
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("请输入音色名称");
        if (wavFile == null || !wavFile.isFile()) throw new IllegalArgumentException("未找到录音文件，请重新录制");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(wavFile)) {
            byte[] buffer = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                MimoTtsProtocol.checkBase64Limit(total);
                bytes.write(buffer, 0, n);
            }
        }
        byte[] data = bytes.toByteArray();
        if (data.length <= WavAudio.HEADER_BYTES || !WavAudio.isWavHeader(data)) {
            throw new IllegalArgumentException("录音文件无效，请重新录制");
        }
        return persistCloneBytes(c, name, "audio/wav", data);
    }

    private static JSONObject persistCloneBytes(Context c, String name, String normalizedMime, byte[] data) throws Exception {
        MimoTtsProtocol.checkBase64Limit(data.length);
        String id = UUID.randomUUID().toString();
        String extension = "audio/wav".equals(normalizedMime) ? ".wav" : ".mp3";
        File dir = new File(c.getFilesDir(), "voices");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建音色目录");
        File target = new File(dir, id + extension);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
            out.getFD().sync();
        }
        long now = System.currentTimeMillis();
        JSONObject profile = new JSONObject()
            .put("id", id)
            .put("name", name)
            .put("path", target.getAbsolutePath())
            .put("mime", normalizedMime)
            .put("kind", KIND_CLONE)
            .put("description", "")
            .put("version", now)
            .put("created", now);
        VoiceConsent.stampConsent(profile, now);
        try {
            LocalStore.inTransaction(c, () -> LocalStore.upsertVoiceProfileLocked(LocalStore.database(), profile));
        } catch (Exception e) {
            target.delete();
            throw new IllegalStateException("音色配置未能保存", e);
        }
        return profile;
    }

    /** Records explicit consent on an existing cloned voice (e.g. migrated or restored from backup). */
    static JSONObject recordConsent(Context c, String profileId) throws Exception {
        JSONObject existing = profile(c, profileId);
        if (existing == null || !isClone(existing)) {
            throw new IllegalArgumentException("未找到对应的克隆音色");
        }
        VoiceConsent.stampConsent(existing, System.currentTimeMillis());
        LocalStore.inTransaction(c, () -> LocalStore.upsertVoiceProfileLocked(LocalStore.database(), existing));
        return existing;
    }

    static byte[] sample(Context c, JSONObject profile) throws Exception {
        if (profile == null) throw new IllegalArgumentException("音色配置无效");
        String path = profile.optString("path", "");
        File file = new File(path);
        if (path.isEmpty() || !file.isFile()) {
            throw new IllegalStateException("参考音频已丢失，请重新录制或导入");
        }
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                MimoTtsProtocol.checkBase64Limit(total);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static void delete(Context c, String id) throws Exception {
        JSONObject existing = profile(c, id);
        String path = existing == null ? "" : existing.optString("path", "");
        boolean selected = id.equals(voiceId(c));
        LocalStore.inTransaction(c, () -> {
            LocalStore.database().delete("voice_profile", "id=?", new String[]{id});
            if (selected && !Store.prefs(c).edit().putString("voiceProvider", SYSTEM).putString("voiceId", "").commit()) {
                throw new IllegalStateException("音色删除失败");
            }
        });
        if (!path.isEmpty()) new File(path).delete();
    }
}
