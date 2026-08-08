package com.convoy.androidtranscriber.util;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class AssetUtils {
    private AssetUtils() {}

    public static File copyAssetToFile(Context context, String assetPath, File outFile) throws IOException {
        if (outFile.exists() && outFile.length() > 0) {
            return outFile;
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        AssetManager assetManager = context.getAssets();
        try (InputStream inputStream = assetManager.open(assetPath);
             OutputStream outputStream = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        return outFile;
    }
}
