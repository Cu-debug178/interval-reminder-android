package com.example.reviewreminder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records mono 16-bit PCM directly into a standard WAV file so reference audio matches MiMo
 * VoiceClone's WAV/MP3 requirement without external codecs.
 */
final class AudioRecorderHelper {
    interface Listener {
        void onProgress(long durationMs, long rawWavBytes, long estimatedBase64Bytes);
        void onStopped(File wavFile, long durationMs, long rawWavBytes, boolean reachedLimit);
        void onError(String message);
    }

    /** Leave headroom below the 10 MB Base64 ceiling (7,864,320 raw bytes) so the header + PCM stay safely under limit. */
    private static final long MAX_RECORD_PCM_BYTES = MimoTtsProtocol.MAX_RAW_AUDIO_BYTES - WavAudio.HEADER_BYTES - 4096L;
    private static final int[] CANDIDATE_RATES = {24000, 16000};

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Thread worker;
    private File currentFile;
    private int activeSampleRate = 16000;

    AudioRecorderHelper(Context context) {
        this.app = context.getApplicationContext();
    }

    boolean isRecording() {
        return recording.get();
    }

    int sampleRate() {
        return activeSampleRate;
    }

    void start(Listener listener) {
        if (recording.get()) return;
        cancelled.set(false);

        int chosenRate = 0;
        int chosenBuffer = 0;
        AudioRecord created = null;
        for (int rate : CANDIDATE_RATES) {
            int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) continue;
            int buf = Math.max(minBuf * 2, 4096);
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buf
                );
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    created = candidate;
                    chosenRate = rate;
                    chosenBuffer = buf;
                    break;
                }
                candidate.release();
            } catch (SecurityException se) {
                if (candidate != null) try { candidate.release(); } catch (Exception ignored) {}
                postError(listener, "未授予麦克风权限，无法录音");
                return;
            } catch (Exception ignored) {
                if (candidate != null) try { candidate.release(); } catch (Exception releaseIgnored) {}
            }
        }
        if (created == null) {
            postError(listener, "当前设备无法初始化录音器，请尝试导入 WAV 或 MP3 音频");
            return;
        }

        File dir = new File(app.getCacheDir(), "voice_rec");
        if (!dir.exists() && !dir.mkdirs()) {
            created.release();
            postError(listener, "无法创建录音临时目录");
            return;
        }
        File target = new File(dir, "rec_" + System.currentTimeMillis() + ".wav");
        currentFile = target;
        activeSampleRate = chosenRate;
        final AudioRecord recorder = created;
        final int sampleRate = chosenRate;
        final int bufferSize = chosenBuffer;
        recording.set(true);

        worker = new Thread(() -> runLoop(recorder, target, sampleRate, bufferSize, listener), "mimo-voice-rec");
        worker.start();
    }

    void stop() {
        recording.set(false);
    }

    void cancel() {
        cancelled.set(true);
        recording.set(false);
        File file = currentFile;
        currentFile = null;
        if (file != null && file.exists()) {
            // Best-effort cleanup if thread already exited; worker also deletes on cancel.
            file.delete();
        }
    }

    private void runLoop(AudioRecord recorder, File target, int sampleRate, int bufferSize, Listener listener) {
        long pcmWritten = 0L;
        boolean reachedLimit = false;
        boolean ok = false;
        try {
            recorder.startRecording();
            byte[] buffer = new byte[bufferSize];
            long lastReport = 0L;
            try (FileOutputStream out = new FileOutputStream(target)) {
                WavAudio.writeHeader(out, sampleRate, 1, 16, 0L);
                while (recording.get() && !cancelled.get()) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read < 0) {
                        throw new IllegalStateException("录音读取失败（错误码 " + read + "）");
                    }
                    if (read == 0) continue;
                    long remaining = MAX_RECORD_PCM_BYTES - pcmWritten;
                    if (remaining <= 0L) {
                        reachedLimit = true;
                        break;
                    }
                    int toWrite = (int) Math.min(read, remaining);
                    out.write(buffer, 0, toWrite);
                    pcmWritten += toWrite;
                    if (pcmWritten >= MAX_RECORD_PCM_BYTES) {
                        reachedLimit = true;
                        break;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 120L) {
                        lastReport = now;
                        final long dur = WavAudio.durationMs(pcmWritten, sampleRate, 1, 16);
                        final long raw = WavAudio.HEADER_BYTES + pcmWritten;
                        final long b64 = MimoTtsProtocol.encodedBase64Length(raw);
                        if (listener != null) {
                            main.post(() -> listener.onProgress(dur, raw, b64));
                        }
                    }
                }
                out.flush();
                out.getFD().sync();
            }
            if (!cancelled.get() && pcmWritten > 0L) {
                try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                    WavAudio.patchHeader(raf, sampleRate, 1, 16, pcmWritten);
                    raf.getFD().sync();
                }
                ok = true;
            }
        } catch (Exception e) {
            target.delete();
            if (!cancelled.get()) {
                postError(listener, e.getMessage() == null ? "录音失败，请重试" : e.getMessage());
            }
        } finally {
            recording.set(false);
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
        }

        if (cancelled.get()) {
            target.delete();
            return;
        }
        if (ok) {
            final long dur = WavAudio.durationMs(pcmWritten, sampleRate, 1, 16);
            final long raw = WavAudio.HEADER_BYTES + pcmWritten;
            final boolean hitLimit = reachedLimit;
            if (listener != null) {
                main.post(() -> listener.onStopped(target, dur, raw, hitLimit));
            }
        } else if (pcmWritten == 0L) {
            target.delete();
            postError(listener, "未录到有效声音，请重试");
        }
    }

    private void postError(Listener listener, String message) {
        if (listener != null) {
            main.post(() -> listener.onError(message));
        }
    }
}
