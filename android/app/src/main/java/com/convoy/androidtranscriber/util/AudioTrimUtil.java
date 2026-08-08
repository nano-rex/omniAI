package com.convoy.androidtranscriber.util;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class AudioTrimUtil {
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int KEEP_PADDING_MS = 200;

    private AudioTrimUtil() {}

    public static File trimQuietSections(File inputWav, File outputWav) throws IOException {
        byte[] inputBytes = Files.readAllBytes(inputWav.toPath());
        int dataOffset = findDataOffset(inputBytes);
        if (dataOffset < 0) throw new IOException("Invalid WAV file");
        byte[] header = new byte[dataOffset];
        System.arraycopy(inputBytes, 0, header, 0, dataOffset);
        byte[] pcm = new byte[inputBytes.length - dataOffset];
        System.arraycopy(inputBytes, dataOffset, pcm, 0, pcm.length);

        byte[] trimmed = trimPcm(pcm);
        WaveUtil.createWaveFile(outputWav.getAbsolutePath(), trimmed, SAMPLE_RATE, 1, 2);
        return outputWav;
    }

    private static byte[] trimPcm(byte[] pcm) throws IOException {
        int totalFrames = Math.max(1, (int) Math.ceil(pcm.length / (double) FRAME_BYTES));
        boolean[] keep = new boolean[totalFrames];
        int paddingFrames = Math.max(1, KEEP_PADDING_MS / 20);
        boolean foundSpeech = false;

        try (VadWebRTC vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_320)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(300)
                .setSpeechDurationMs(50)
                .build()) {
            for (int frame = 0; frame < totalFrames; frame++) {
                byte[] chunk = new byte[FRAME_BYTES];
                int start = frame * FRAME_BYTES;
                int len = Math.min(FRAME_BYTES, Math.max(0, pcm.length - start));
                if (len > 0) System.arraycopy(pcm, start, chunk, 0, len);
                if (vad.isSpeech(chunk)) {
                    foundSpeech = true;
                    int from = Math.max(0, frame - paddingFrames);
                    int to = Math.min(totalFrames - 1, frame + paddingFrames);
                    for (int i = from; i <= to; i++) keep[i] = true;
                }
            }
        } catch (Exception e) {
            throw new IOException("VAD trim failed", e);
        }

        if (!foundSpeech) return pcm;

        List<byte[]> chunks = new ArrayList<>();
        int totalBytes = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            if (!keep[frame]) continue;
            int start = frame * FRAME_BYTES;
            int end = Math.min(pcm.length, start + FRAME_BYTES);
            int len = Math.max(0, end - start);
            if (len == 0) continue;
            byte[] chunk = new byte[len];
            System.arraycopy(pcm, start, chunk, 0, len);
            chunks.add(chunk);
            totalBytes += len;
        }

        if (totalBytes == 0) return pcm;
        byte[] trimmed = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, trimmed, offset, chunk.length);
            offset += chunk.length;
        }
        return trimmed;
    }

    private static int findDataOffset(byte[] wavBytes) {
        if (wavBytes.length < 44) return -1;
        int offset = 12;
        while (offset + 8 <= wavBytes.length) {
            String chunkId = new String(wavBytes, offset, 4);
            int chunkSize = littleEndianInt(wavBytes, offset + 4);
            int chunkDataOffset = offset + 8;
            if ("data".equals(chunkId)) return chunkDataOffset;
            offset = chunkDataOffset + chunkSize + (chunkSize % 2);
        }
        return -1;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
