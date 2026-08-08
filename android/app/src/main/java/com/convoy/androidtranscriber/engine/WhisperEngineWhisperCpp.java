package com.convoy.androidtranscriber.engine;

import com.convoy.androidtranscriber.util.WaveUtil;
import com.convoy.androidtranscriber.util.DiarizationUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WhisperEngineWhisperCpp implements WhisperEngine {
    private long contextPtr = 0L;
    private boolean initialized = false;
    private String languageHint = null;
    private final List<DiarizationUtils.TextSegment> lastSegments = new ArrayList<>();
    private String lastError = null;

    @Override
    public boolean isInitialized() {
        return initialized && contextPtr != 0L;
    }

    @Override
    public boolean initialize(String modelPath, String vocabPath, String languageHint) throws IOException {
        deinitialize();
        if (!WhisperCppLib.ensureLoaded()) {
            throw new IOException("Failed to load native whisper runtime: " + WhisperCppLib.getLoadError());
        }
        contextPtr = WhisperCppLib.initContext(modelPath);
        if (contextPtr == 0L) {
            throw new IOException("Failed to load whisper.cpp model: " + modelPath);
        }
        this.languageHint = (languageHint == null || languageHint.trim().isEmpty()) ? "en" : languageHint.trim();
        initialized = true;
        lastError = null;
        return true;
    }

    @Override
    public void deinitialize() {
        if (contextPtr != 0L) {
            WhisperCppLib.freeContext(contextPtr);
            contextPtr = 0L;
        }
        initialized = false;
        languageHint = null;
        lastSegments.clear();
        lastError = null;
    }

    @Override
    public String transcribeFile(String wavePath) {
        float[] samples = WaveUtil.getSamples(wavePath);
        return transcribeBuffer(samples);
    }

    @Override
    public String transcribeBuffer(float[] samples) {
        if (!isInitialized() || samples == null || samples.length == 0) return "";
        lastSegments.clear();
        lastError = null;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int rc = WhisperCppLib.fullTranscribe(contextPtr, threads, samples, languageHint);
        if (rc != 0) {
            lastError = "whisper_full failed with status " + rc;
            throw new IllegalStateException(lastError);
        }
        int count = WhisperCppLib.getTextSegmentCount(contextPtr);
        if (count <= 0) {
            lastError = "No speech segments decoded";
            throw new IllegalStateException(lastError);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String segment = WhisperCppLib.getTextSegment(contextPtr, i);
            long startMs = WhisperCppLib.getTextSegmentStartMs(contextPtr, i);
            long endMs = WhisperCppLib.getTextSegmentEndMs(contextPtr, i);
            if (segment != null && !segment.trim().isEmpty()) {
                lastSegments.add(new DiarizationUtils.TextSegment(startMs / 1000.0, endMs / 1000.0, segment.trim()));
                if (out.length() > 0) out.append(' ');
                out.append(segment.trim());
            }
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    @Override
    public List<DiarizationUtils.TextSegment> getTextSegments() {
        return new ArrayList<>(lastSegments);
    }

    public String getLastError() {
        return lastError;
    }
}
