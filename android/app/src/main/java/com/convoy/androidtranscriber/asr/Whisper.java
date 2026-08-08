package com.convoy.androidtranscriber.asr;

import android.content.Context;
import android.util.Log;

import com.convoy.androidtranscriber.engine.WhisperEngine;
import com.convoy.androidtranscriber.engine.WhisperEngineWhisperCpp;
import com.convoy.androidtranscriber.util.DiarizationUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Whisper {
    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(String result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done";
    public static final String MSG_FILE_NOT_FOUND = "Input file does not exist";

    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private final WhisperEngine whisperEngine;
    private String wavFilePath;
    private WhisperListener listener;
    private String lastError = null;

    public Whisper(Context context) {
        whisperEngine = new WhisperEngineWhisperCpp();
    }

    public void setListener(WhisperListener listener) {
        this.listener = listener;
    }

    public boolean loadModel(String modelPath, String vocabPath, String languageHint) {
        try {
            lastError = null;
            whisperEngine.initialize(modelPath, vocabPath, languageHint);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model", e);
            lastError = e.getMessage();
            sendUpdate("Model initialization failed: " + e.getMessage());
            return false;
        }
    }

    public void unloadModel() {
        whisperEngine.deinitialize();
        lastError = null;
    }

    public boolean isInProgress() {
        return inProgress.get();
    }

    public void setFilePath(String wavFilePath) {
        this.wavFilePath = wavFilePath;
    }

    public void start() {
        if (!inProgress.compareAndSet(false, true)) return;
        new Thread(this::transcribeFile).start();
    }

    public List<DiarizationUtils.TextSegment> getLastSegments() {
        return whisperEngine.getTextSegments();
    }

    public String getLastError() {
        return lastError;
    }

    public String transcribeBlocking(String wavFilePath) {
        if (!inProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Transcription already in progress");
        }
        try {
            if (!whisperEngine.isInitialized() || wavFilePath == null) {
                throw new IllegalStateException("Engine not initialized or file path not set");
            }
            File waveFile = new File(wavFilePath);
            if (!waveFile.exists()) {
                throw new IllegalStateException(MSG_FILE_NOT_FOUND);
            }
            sendUpdate(MSG_PROCESSING);
            String result = whisperEngine.transcribeFile(wavFilePath);
            sendUpdate(MSG_PROCESSING_DONE);
            return result;
        } finally {
            inProgress.set(false);
        }
    }

    private void transcribeFile() {
        try {
            if (!whisperEngine.isInitialized() || wavFilePath == null) {
                sendUpdate("Engine not initialized or file path not set");
                return;
            }
            File waveFile = new File(wavFilePath);
            if (!waveFile.exists()) {
                sendUpdate(MSG_FILE_NOT_FOUND);
                return;
            }
            sendUpdate(MSG_PROCESSING);
            String result = whisperEngine.transcribeFile(wavFilePath);
            sendResult(result);
            sendUpdate(MSG_PROCESSING_DONE);
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            inProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (listener != null) listener.onUpdateReceived(message);
    }

    private void sendResult(String message) {
        if (listener != null) listener.onResultReceived(message);
    }
}
