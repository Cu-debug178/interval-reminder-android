package com.example.reviewreminder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Pure-Java PCM16LE WAV encoder/header helper and WAV/MP3 format sniffer.
 * Keeps audio formatting deterministic and testable without Android dependencies.
 */
public final class WavAudio {
    public static final int HEADER_BYTES = 44;

    private WavAudio() {}

    /** Wraps raw little-endian 16-bit mono PCM samples in a standard RIFF/WAVE header. */
    public static byte[] encodePcm16Mono(byte[] pcmData, int sampleRate) throws IOException {
        if (pcmData == null) throw new IllegalArgumentException("PCM 数据为空");
        if (sampleRate <= 0) throw new IllegalArgumentException("采样率无效");
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + pcmData.length);
        writeHeader(out, sampleRate, 1, 16, pcmData.length);
        out.write(pcmData);
        return out.toByteArray();
    }

    /** Writes a 44-byte RIFF/WAVE PCM header to {@code out}. */
    public static void writeHeader(OutputStream out, int sampleRate, int channels, int bitsPerSample, long dataLength) throws IOException {
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0 || dataLength < 0) {
            throw new IllegalArgumentException("WAV 参数无效");
        }
        byte[] header = buildHeader(sampleRate, channels, bitsPerSample, dataLength);
        out.write(header);
    }

    /** Patches a previously written placeholder WAV header once recording finishes. */
    public static void patchHeader(RandomAccessFile raf, int sampleRate, int channels, int bitsPerSample, long dataLength) throws IOException {
        byte[] header = buildHeader(sampleRate, channels, bitsPerSample, dataLength);
        raf.seek(0);
        raf.write(header);
    }

    public static byte[] buildHeader(int sampleRate, int channels, int bitsPerSample, long dataLength) {
        long byteRate = (long) sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        long riffSize = 36L + dataLength;
        byte[] h = new byte[HEADER_BYTES];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putIntLE(h, 4, riffSize);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putIntLE(h, 16, 16); // PCM fmt chunk size
        putShortLE(h, 20, 1); // PCM format = 1
        putShortLE(h, 22, channels);
        putIntLE(h, 24, sampleRate);
        putIntLE(h, 28, byteRate);
        putShortLE(h, 32, blockAlign);
        putShortLE(h, 34, bitsPerSample);
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putIntLE(h, 40, dataLength);
        return h;
    }

    /** Returns true if the leading bytes match a RIFF...WAVE header. */
    public static boolean isWavHeader(byte[] data) {
        return data != null && data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /** Returns true if the leading bytes match an ID3 tag or an MPEG audio frame sync header. */
    public static boolean isMp3Header(byte[] data) {
        if (data == null || data.length < 3) return false;
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') return true;
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        // 11-bit frame sync (0xFFE0), MPEG version != 01 (reserved), Layer != 00 (reserved)
        return b0 == 0xFF && (b1 & 0xE0) == 0xE0 && (b1 & 0x18) != 0x08 && (b1 & 0x06) != 0x00;
    }

    /** Computes audio duration in milliseconds from raw PCM byte count. */
    public static long durationMs(long pcmBytes, int sampleRate, int channels, int bitsPerSample) {
        long bytesPerSec = (long) sampleRate * channels * (bitsPerSample / 8);
        if (bytesPerSec <= 0 || pcmBytes <= 0) return 0L;
        return (pcmBytes * 1000L) / bytesPerSec;
    }

    private static void putIntLE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void putShortLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
