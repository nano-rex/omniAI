package com.convoy.androidtranscriber;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidtranscriber.util.ModelUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ManageModelsActivity extends AppCompatActivity {
    private static final String MODEL_RELEASE_BASE_URL =
            "https://github.com/nano-rex/transcriber-android/releases/download/android-whispercpp-models-2026-03-27/";
    private static final String LEGACY_TINY_RELEASE_BASE_URL =
            "https://github.com/nano-rex/transcriber-android/releases/download/android-model-ggml-tiny/";
    private static final String LEGACY_SMALL_RELEASE_BASE_URL =
            "https://github.com/nano-rex/transcriber-android/releases/download/android-model-ggml-small/";
    private static final String LEGACY_SPECIALIST_RELEASE_BASE_URL =
            "https://github.com/nano-rex/transcriber-android/releases/download/android-model-ggml-small-specialist/";

    private EditText etSearch;
    private TextView tvStatus;
    private ListView listModels;

    private final List<ModelRow> allRows = new ArrayList<>();
    private final List<ModelRow> filteredRows = new ArrayList<>();
    private ModelListAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        com.convoy.androidtranscriber.util.AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_models);

        etSearch = findViewById(R.id.etSearchModels);
        tvStatus = findViewById(R.id.tvModelStatus);
        listModels = findViewById(R.id.listModels);

        adapter = new ModelListAdapter(this, filteredRows, this::handleRowAction);
        listModels.setAdapter(adapter);

        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        loadRows();
    }

    private void loadRows() {
        allRows.clear();
        allRows.add(buildHostedRow("tiny-en", "ASR", "ggml-tiny.en.bin",
                hostedUrl("ggml-tiny.en.bin"), LEGACY_TINY_RELEASE_BASE_URL + "ggml-tiny.en.bin", false));
        allRows.add(buildHostedRow("tiny", "ASR", "ggml-tiny.bin",
                hostedUrl("ggml-tiny.bin"), LEGACY_TINY_RELEASE_BASE_URL + "ggml-tiny.bin", true));
        allRows.add(buildHostedRow("small", "ASR", "ggml-small.bin",
                hostedUrl("ggml-small.bin"), LEGACY_SMALL_RELEASE_BASE_URL + "ggml-small.bin", true));
        allRows.add(buildHostedRow("small-mandarin", "ASR", "ggml-small-mandarin.bin",
                hostedUrl("ggml-small-mandarin.bin"), LEGACY_SPECIALIST_RELEASE_BASE_URL + "ggml-small-mandarin.bin", true));
        allRows.add(buildHostedRow("small-malay", "ASR", "ggml-small-malay.bin",
                hostedUrl("ggml-small-malay.bin"), LEGACY_SPECIALIST_RELEASE_BASE_URL + "ggml-small-malay.bin", true));
        allRows.add(buildHostedRow("small-cantonese", "ASR", "ggml-small-cantonese.bin",
                hostedUrl("ggml-small-cantonese.bin"), LEGACY_SPECIALIST_RELEASE_BASE_URL + "ggml-small-cantonese.bin", true));
        allRows.add(buildHostedRow("small-hokkien", "ASR", "ggml-small-hokkien.bin",
                hostedUrl("ggml-small-hokkien.bin"), LEGACY_SPECIALIST_RELEASE_BASE_URL + "ggml-small-hokkien.bin", true));

        applyFilter(etSearch.getText() == null ? "" : etSearch.getText().toString());
    }

    private static String hostedUrl(String fileName) {
        return MODEL_RELEASE_BASE_URL + fileName;
    }

    private ModelRow buildHostedRow(String displayName, String category, String fileName, String url,
                                    @Nullable String fallbackUrl, boolean multilingual) {
        File localFile = new File(ModelUtils.customModelsDir(this), fileName);
        boolean downloaded = localFile.exists();
        return new ModelRow(displayName, category, downloaded ? "Downloaded" : "Not downloaded", multilingual,
                localFile.getAbsolutePath(), downloaded, false, downloaded, downloaded ? "Remove" : "Download",
                true, url, fallbackUrl);
    }

    private void applyFilter(String query) {
        String normalized = query.trim().toLowerCase(Locale.US);
        filteredRows.clear();
        for (ModelRow row : allRows) {
            if (normalized.isEmpty() || row.displayName.toLowerCase(Locale.US).contains(normalized)) {
                filteredRows.add(row);
            }
        }
        adapter.notifyDataSetChanged();
        tvStatus.setText(filteredRows.isEmpty() ? "No models found." : "Models listed: " + filteredRows.size());
    }

    private void handleRowAction(ModelRow row) {
        if (!row.actionEnabled) {
            tvStatus.setText(row.displayName + " is already built in.");
            return;
        }
        if (row.customFile) {
            confirmRemove(row);
            return;
        }
        if (row.downloadUrl != null) {
            downloadModel(row);
        }
    }

    private void confirmRemove(ModelRow row) {
        new AlertDialog.Builder(this)
                .setTitle("Remove model")
                .setMessage("Remove " + row.displayName + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    File file = new File(row.location);
                    boolean deleted = !file.exists() || file.delete();
                    tvStatus.setText(deleted ? "Removed " + row.displayName : "Failed to remove " + row.displayName);
                    loadRows();
                })
                .show();
    }

    private void downloadModel(ModelRow row) {
        tvStatus.setText("Downloading " + row.displayName + "...");
        new Thread(() -> {
            File target = new File(row.location);
            File tmp = new File(target.getParentFile(), target.getName() + ".part");
            try {
                downloadToFile(row.downloadUrl, row.fallbackUrl, tmp, row.displayName);
                if (target.exists() && !target.delete()) {
                    throw new IOException("Unable to replace existing model");
                }
                if (!tmp.renameTo(target)) {
                    throw new IOException("Unable to finalize downloaded model");
                }
                runOnUiThread(() -> {
                    tvStatus.setText("Downloaded " + row.displayName);
                    loadRows();
                });
            } catch (Exception e) {
                if (tmp.exists()) tmp.delete();
                runOnUiThread(() -> tvStatus.setText("Download failed: " + e.getMessage()));
            }
        }).start();
    }

    private void downloadToFile(String primaryUrl, @Nullable String fallbackUrl, File target, String displayName) throws IOException {
        IOException primaryFailure = null;
        try {
            downloadToFileOnce(primaryUrl, target, displayName);
            return;
        } catch (IOException e) {
            primaryFailure = e;
        }
        if (fallbackUrl != null && !fallbackUrl.equals(primaryUrl)) {
            downloadToFileOnce(fallbackUrl, target, displayName);
            return;
        }
        throw primaryFailure;
    }

    private void downloadToFileOnce(String urlText, File target, String displayName) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        int contentLength = connection.getContentLength();
        try (InputStream in = connection.getInputStream();
             OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            long totalRead = 0L;
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
                totalRead += read;
                if (contentLength > 0) {
                    int percent = (int) Math.min(100L, (totalRead * 100L) / contentLength);
                    runOnUiThread(() -> tvStatus.setText("Downloading " + displayName + "... " + percent + "%"));
                } else {
                    long mb = totalRead / (1024L * 1024L);
                    runOnUiThread(() -> tvStatus.setText("Downloading " + displayName + "... " + mb + " MB"));
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    public static final class ModelRow {
        public final String displayName;
        public final String category;
        public final String state;
        public final boolean multilingual;
        public final String location;
        public final boolean available;
        public final boolean bundled;
        public final boolean customFile;
        public final String actionLabel;
        public final boolean actionEnabled;
        public final String downloadUrl;
        public final String fallbackUrl;

        public ModelRow(String displayName, String category, String state, boolean multilingual, String location,
                        boolean available, boolean bundled, boolean customFile, String actionLabel,
                        boolean actionEnabled, String downloadUrl, @Nullable String fallbackUrl) {
            this.displayName = displayName;
            this.category = category;
            this.state = state;
            this.multilingual = multilingual;
            this.location = location;
            this.available = available;
            this.bundled = bundled;
            this.customFile = customFile;
            this.actionLabel = actionLabel;
            this.actionEnabled = actionEnabled;
            this.downloadUrl = downloadUrl;
            this.fallbackUrl = fallbackUrl;
        }

        public String statusLine() {
            return category + " | " + state + " | " + (multilingual ? "multilingual" : "english-only")
                    + " | " + (bundled ? "built-in" : "custom");
        }
    }
}
