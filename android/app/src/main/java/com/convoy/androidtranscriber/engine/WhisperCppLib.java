package com.convoy.androidtranscriber.engine;

public final class WhisperCppLib {
    private static boolean loaded = false;
    private static String loadError = null;

    private WhisperCppLib() {}

    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        if (loadError != null) return false;
        try {
            System.loadLibrary("whisper");
            loaded = true;
            return true;
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "native load failed" : t.getMessage());
            return false;
        }
    }

    public static synchronized String getLoadError() {
        return loadError;
    }

    public static native long initContext(String modelPath);
    public static native void freeContext(long contextPtr);
    public static native int fullTranscribe(long contextPtr, int numThreads, float[] audioData, String languageHint);
    public static native int getTextSegmentCount(long contextPtr);
    public static native String getTextSegment(long contextPtr, int index);
    public static native long getTextSegmentStartMs(long contextPtr, int index);
    public static native long getTextSegmentEndMs(long contextPtr, int index);
}
