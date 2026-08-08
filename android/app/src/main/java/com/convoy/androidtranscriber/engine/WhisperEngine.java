package com.convoy.androidtranscriber.engine;

import com.convoy.androidtranscriber.util.DiarizationUtils;

import java.io.IOException;
import java.util.List;

public interface WhisperEngine {
    boolean isInitialized();
    boolean initialize(String modelPath, String vocabPath, String languageHint) throws IOException;
    void deinitialize();
    String transcribeFile(String wavePath);
    String transcribeBuffer(float[] samples);
    List<DiarizationUtils.TextSegment> getTextSegments();
}
