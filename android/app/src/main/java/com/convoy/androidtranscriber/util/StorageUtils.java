package com.convoy.androidtranscriber.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;
public final class StorageUtils {
    private static final String APP_FOLDER = "TranscriberAndroid";
    private static final String INTERNAL_FOLDER = "transcriber_private";

    private StorageUtils() {}

    public static File baseDir(Context context) {
        String mode = AppSettings.getStorageMode(context);
        if (mode == null || mode.trim().isEmpty()) return null;
        return baseDirForMode(context, mode);
    }

    public static File baseDirForMode(Context context, String mode) {
        File root;
        switch (mode) {
            case AppSettings.STORAGE_DOWNLOADS:
                root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (root == null) root = new File(context.getFilesDir(), "downloads");
                break;
            case AppSettings.STORAGE_INTERNAL:
                root = new File(context.getFilesDir(), "workspace");
                break;
            case AppSettings.STORAGE_DOCUMENTS:
            default:
                root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (root == null) root = new File(context.getFilesDir(), "documents");
                break;
        }
        File dir = new File(root, APP_FOLDER);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File outputsDir(Context context) {
        return childDir(context, "outputs");
    }

    public static File importsDir(Context context) {
        return childDir(context, "imports");
    }

    public static File modelsDir(Context context) {
        return internalChildDir(context, "models");
    }

    public static File customModelsDir(Context context) {
        return internalChildDir(context, "models-custom");
    }

    public static File denoiseDir(Context context) {
        return internalChildDir(context, "denoise");
    }

    public static File chunksDir(Context context) {
        return internalChildDir(context, "chunks");
    }

    public static String describeBaseDir(Context context) {
        File base = baseDir(context);
        return base == null ? "Not set" : base.getAbsolutePath();
    }

    public static String describeBaseDirForMode(Context context, String mode) {
        return baseDirForMode(context, mode).getAbsolutePath();
    }

    public static boolean isWorkspaceConfigured(Context context) {
        return baseDir(context) != null;
    }

    private static File childDir(Context context, String name) {
        File base = baseDir(context);
        if (base == null) {
            throw new IllegalStateException("Workspace folder is not set");
        }
        File dir = new File(base, name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File internalBaseDir(Context context) {
        File dir = new File(context.getFilesDir(), INTERNAL_FOLDER);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File internalChildDir(Context context, String name) {
        File dir = new File(internalBaseDir(context), name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
