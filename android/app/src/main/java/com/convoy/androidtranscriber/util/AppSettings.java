package com.convoy.androidtranscriber.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

public final class AppSettings {
    private static final String PREFS = "transcriber_settings";
    private static final String KEY_AI_ENHANCE = "ai_enhance_enabled";
    private static final String KEY_TRIM = "trim_enabled";
    private static final String KEY_SELECTED_MODEL_ID = "selected_model_id";
    private static final String KEY_STORAGE_MODE = "storage_mode";
    private static final String KEY_TRIM_CHUNK_MINUTES = "trim_chunk_minutes";
    private static final String KEY_DARK_MODE = "dark_mode";

    public static final String STORAGE_DOCUMENTS = "documents";
    public static final String STORAGE_DOWNLOADS = "downloads";
    public static final String STORAGE_INTERNAL = "internal";

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAiEnhanceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AI_ENHANCE, true);
    }

    public static void setAiEnhanceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AI_ENHANCE, enabled).apply();
    }

    public static boolean isTrimEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TRIM, true);
    }

    public static void setTrimEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TRIM, enabled).apply();
    }

    public static int getTrimChunkMinutes(Context context) {
        return Math.max(1, prefs(context).getInt(KEY_TRIM_CHUNK_MINUTES, 3));
    }

    public static void setTrimChunkMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_TRIM_CHUNK_MINUTES, Math.max(1, minutes)).apply();
    }

    public static String getSelectedModelId(Context context) {
        return prefs(context).getString(KEY_SELECTED_MODEL_ID, null);
    }

    public static void setSelectedModelId(Context context, String modelId) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply();
    }

    public static String getStorageMode(Context context) {
        return prefs(context).getString(KEY_STORAGE_MODE, null);
    }

    public static void setStorageMode(Context context, String storageMode) {
        prefs(context).edit().putString(KEY_STORAGE_MODE, storageMode).apply();
    }

    public static boolean isDarkModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DARK_MODE, false);
    }

    public static void setDarkModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply();
    }

    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(
                isDarkModeEnabled(context) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
    }
}
