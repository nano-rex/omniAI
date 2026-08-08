package com.convoy.androidtranscriber;

import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidtranscriber.asr.Whisper;
import com.convoy.androidtranscriber.util.AppSettings;
import com.convoy.androidtranscriber.util.AssetUtils;
import com.convoy.androidtranscriber.util.AudioImportUtil;
import com.convoy.androidtranscriber.util.DiarizationUtils;
import com.convoy.androidtranscriber.util.ModelUtils;
import com.convoy.androidtranscriber.util.ModelUtils.HardwareAssessment;
import com.convoy.androidtranscriber.util.ModelUtils.ModelSpec;
import com.convoy.androidtranscriber.util.StorageUtils;
import com.convoy.androidtranscriber.util.WaveUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_SAMPLE_ASSET = "audio/jfk.wav";

    private TextView tvHardwarePanel;
    private TextView tvSelectedFile;
    private Spinner spinnerModel;
    private Button btnViewResults;
    private Button btnTranscribe;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Whisper whisper;
    private File currentImportedWav;
    private String recommendedTier;
    private ModelSpec selectedModel;
    private final List<ModelSpec> availableModels = new ArrayList<>();
    private long startTimeMs;
    private String latestTranscript = "";
    private String latestDiarized = "";
    private List<DiarizationUtils.TextSegment> latestSegments = new ArrayList<>();
    private int defaultStatusColor;
    private String statusMessage = "Status: idle";
    private boolean statusWarning = false;
    private final Runnable hardwarePanelUpdater = new Runnable() {
        @Override
        public void run() {
            refreshHardwarePanel();
            handler.postDelayed(this, 1500);
        }
    };
    private final Runnable transcriptionProgressUpdater = new Runnable() {
        @Override
        public void run() {
            if (whisper == null || !whisper.isInProgress()) return;
            long elapsed = System.currentTimeMillis() - startTimeMs;
            int percent = estimateTranscriptionPercent(elapsed);
            setStatusMessage(String.format(Locale.US, "Status: transcribing... %d%%", percent), false);
            handler.postDelayed(this, 700);
        }
    };

    private final ActivityResultLauncher<String[]> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onMediaPicked);
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvHardwarePanel = findViewById(R.id.tvHardwarePanel);
        tvSelectedFile = findViewById(R.id.tvSelectedFile);
        defaultStatusColor = tvHardwarePanel.getCurrentTextColor();
        spinnerModel = findViewById(R.id.spinnerModel);
        btnViewResults = findViewById(R.id.btnViewResults);
        Button btnPickFile = findViewById(R.id.btnPickFile);
        btnTranscribe = findViewById(R.id.btnTranscribe);
        Button btnSettings = findViewById(R.id.btnSettings);
        Button btnTabHome = findViewById(R.id.btnTabHome);
        Button btnTabFolder = findViewById(R.id.btnTabFolder);
        Button btnTabSettings = findViewById(R.id.btnTabSettings);

        recommendedTier = ModelUtils.recommendModelTier(this);
        setupModelSpinner();
        ensureBundledSampleReady();
        refreshHardwarePanel();

        btnPickFile.setOnClickListener(v -> {
            if (!StorageUtils.isWorkspaceConfigured(this)) {
                setStatusMessage("Status: set the workspace folder in Settings first", true);
                return;
            }
            pickMediaLauncher.launch(new String[]{"audio/*", "video/*"});
        });
        btnTranscribe.setOnClickListener(v -> startTranscription());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnViewResults.setOnClickListener(v -> openResultsWindow());
        btnTabHome.setEnabled(false);
        btnTabFolder.setOnClickListener(v -> startActivity(new Intent(this, SavedOutputsActivity.class)));
        btnTabSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position >= 0 && position < availableModels.size()) {
                    AppSettings.setSelectedModelId(MainActivity.this, availableModels.get(position).id);
                }
                refreshHardwareStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private boolean ensureWhisper() {
        if (whisper != null) return true;
        try {
            whisper = new Whisper(this);
            whisper.setListener(new Whisper.WhisperListener() {
                @Override
                public void onUpdateReceived(String message) {
                    handler.post(() -> {
                        if (Whisper.MSG_PROCESSING_DONE.equals(message)) {
                            handler.removeCallbacks(transcriptionProgressUpdater);
                            long elapsed = System.currentTimeMillis() - startTimeMs;
                            setStatusMessage("Status: processing done in " + elapsed + " ms (100%)", false);
                        } else {
                            setStatusMessage("Status: " + message, false);
                        }
                    });
                }

                @Override
                public void onResultReceived(String result) {
                    handler.post(() -> {
                        String safeResult = result == null ? "" : result.trim();
                        latestSegments = new ArrayList<>(whisper.getLastSegments());
                        String diarizedText = buildDiarizedText(latestSegments);
                        latestTranscript = safeResult;
                        latestDiarized = diarizedText;
                        btnViewResults.setEnabled(true);
                        writeOutputsIfPossible(safeResult, latestSegments, diarizedText);
                        openResultsWindow();
                    });
                }
            });
            return true;
        } catch (Throwable t) {
            whisper = null;
            setStatusMessage("Status: transcription runtime unavailable on this device", true);
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupModelSpinner();
        handler.post(hardwarePanelUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hardwarePanelUpdater);
    }

    private void setupModelSpinner() {
        availableModels.clear();
        availableModels.addAll(ModelUtils.availableModels(this));

        List<String> labels = new ArrayList<>();
        int recommendedIndex = 0;
        String savedModelId = AppSettings.getSelectedModelId(this);
        if (availableModels.isEmpty()) {
            labels.add("No model installed");
        }
        for (int i = 0; i < availableModels.size(); i++) {
            ModelSpec spec = availableModels.get(i);
            labels.add(spec.label + (spec.multilingual ? " [multilingual]" : " [english-only]"));
            if (savedModelId != null && savedModelId.equals(spec.id)) {
                recommendedIndex = i;
            } else if (savedModelId == null && spec.tierHint().equals(recommendedTier)) {
                recommendedIndex = i;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);
        spinnerModel.setSelection(Math.max(0, Math.min(recommendedIndex, labels.size() - 1)));
        spinnerModel.setEnabled(!availableModels.isEmpty());
        btnTranscribe.setEnabled(!availableModels.isEmpty());
        refreshHardwareStatus();
    }

    private String buildRecommendationText(String tier) {
        HardwareAssessment assessment = ModelUtils.assessHardware(this, tier);
        if (availableModels.isEmpty()) {
            return "Recommended model: install tiny or tiny-en in Settings";
        }
        return String.format(Locale.US,
                "Recommended model: %s (%d threads, %.1f/%.1f GB free RAM, load %.2f)",
                tier, assessment.cpuThreads, assessment.availRamGb, assessment.totalRamGb, assessment.systemLoad);
    }

    private void ensureBundledSampleReady() {
        if (!StorageUtils.isWorkspaceConfigured(this)) {
            currentImportedWav = null;
            tvSelectedFile.setText("Workspace folder not set");
            return;
        }
        try {
            File sampleOut = new File(StorageUtils.importsDir(this), "jfk.wav");
            AssetUtils.copyAssetToFile(this, DEFAULT_SAMPLE_ASSET, sampleOut);
            currentImportedWav = sampleOut;
            tvSelectedFile.setText("Selected file: bundled sample jfk.wav");
        } catch (IOException e) {
            setStatusMessage("Status: failed to prepare bundled sample: " + e.getMessage(), true);
        }
    }

    private void onMediaPicked(Uri uri) {
        if (uri == null) return;
        if (!StorageUtils.isWorkspaceConfigured(this)) {
            setStatusMessage("Status: set the workspace folder in Settings first", true);
            return;
        }
        setStatusMessage("Status: importing media...", false);
        new Thread(() -> {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            try {
                AudioImportUtil.ImportedAudio imported = AudioImportUtil.importToWav(this, uri, (percent, stage) ->
                        handler.post(() -> setStatusMessage("Status: importing media... " + percent + "% (" + stage + ")", false)));
                currentImportedWav = imported.wavFile;
                handler.post(() -> {
                    tvSelectedFile.setText("Selected file: " + imported.displayName + "\nEnhanced WAV: " + imported.wavFile.getAbsolutePath());
                    setStatusMessage("Status: media imported and normalized to WAV (100%)", false);
                });
            } catch (IOException e) {
                handler.post(() -> setStatusMessage("Status: import failed - " + e.getMessage(), true));
            }
        }).start();
    }

    private void startTranscription() {
        if (!ensureWhisper()) {
            return;
        }
        if (!StorageUtils.isWorkspaceConfigured(this)) {
            setStatusMessage("Status: set the workspace folder in Settings first", true);
            return;
        }
        if (currentImportedWav == null || !currentImportedWav.exists()) {
            setStatusMessage("Status: pick a media file first", true);
            return;
        }
        int modelIndex = spinnerModel.getSelectedItemPosition();
        if (availableModels.isEmpty()) {
            setStatusMessage("Status: no model installed. Open Settings and download tiny or tiny-en first", true);
            return;
        }
        if (modelIndex < 0 || modelIndex >= availableModels.size()) {
            setStatusMessage("Status: pick a model first", true);
            return;
        }
        selectedModel = availableModels.get(modelIndex);
        HardwareAssessment assessment = ModelUtils.assessHardware(this, selectedModel.tierHint());
        startTimeMs = System.currentTimeMillis();
        latestTranscript = "";
        latestDiarized = "";
        btnViewResults.setEnabled(false);
        if (assessment.canRun) {
            setStatusMessage("Status: preparing model...", false);
        } else {
            setStatusMessage("Status: warning - " + assessment.message, true);
        }

        new Thread(() -> {
            List<File> wavParts = null;
            try {
                File modelFile = ensureModelFile(selectedModel);
                whisper.unloadModel();
                String languageHint = ModelUtils.defaultLanguageForModel(selectedModel);
                if (!whisper.loadModel(modelFile.getAbsolutePath(), "", languageHint)) {
                    throw new IllegalStateException(whisper.getLastError() == null
                            ? "Model initialization failed"
                            : whisper.getLastError());
                }
                wavParts = AudioImportUtil.splitWavForTranscription(this, currentImportedWav);
                handler.post(transcriptionProgressUpdater);
                TranscriptionBundle bundle = transcribeWavParts(wavParts);
                handler.removeCallbacks(transcriptionProgressUpdater);
                long elapsed = System.currentTimeMillis() - startTimeMs;
                handler.post(() -> {
                    setStatusMessage("Status: processing done in " + elapsed + " ms (100%)", false);
                    String safeResult = bundle.text == null ? "" : bundle.text.trim();
                    latestSegments = new ArrayList<>(bundle.segments);
                    String diarizedText = buildDiarizedText(latestSegments);
                    latestTranscript = safeResult;
                    latestDiarized = diarizedText;
                    btnViewResults.setEnabled(true);
                    writeOutputsIfPossible(safeResult, latestSegments, diarizedText);
                    openResultsWindow();
                });
            } catch (Exception e) {
                handler.removeCallbacks(transcriptionProgressUpdater);
                handler.post(() -> setStatusMessage("Status: failed to start transcription - " + e.getMessage(), true));
            } finally {
                AudioImportUtil.cleanupSplitWavParts(currentImportedWav, wavParts);
            }
        }).start();
    }

    private TranscriptionBundle transcribeWavParts(List<File> wavParts) {
        if (wavParts == null || wavParts.isEmpty()) {
            String text = whisper.transcribeBlocking(currentImportedWav.getAbsolutePath());
            return new TranscriptionBundle(text == null ? "" : text, whisper.getLastSegments());
        }

        StringBuilder merged = new StringBuilder();
        List<DiarizationUtils.TextSegment> mergedSegments = new ArrayList<>();
        double offsetSeconds = 0.0;
        for (int i = 0; i < wavParts.size(); i++) {
            File part = wavParts.get(i);
            int partIndex = i + 1;
            int basePercent = (int) Math.round((i * 100.0) / wavParts.size());
            handler.post(() -> setStatusMessage(String.format(Locale.US,
                    "Status: transcribing part %d/%d... %d%%",
                    partIndex, wavParts.size(), Math.min(95, basePercent)), false));
            String partText = whisper.transcribeBlocking(part.getAbsolutePath());
            for (DiarizationUtils.TextSegment seg : whisper.getLastSegments()) {
                mergedSegments.add(new DiarizationUtils.TextSegment(
                        seg.startSeconds + offsetSeconds,
                        seg.endSeconds + offsetSeconds,
                        seg.text
                ));
            }
            if (partText != null) {
                partText = partText.trim();
                if (!partText.isEmpty()) {
                    if (merged.length() > 0) merged.append(' ');
                    merged.append(partText);
                }
            }
            offsetSeconds += WaveUtil.getSamples(part.getAbsolutePath()).length / 16000.0;
        }
        return new TranscriptionBundle(merged.toString(), mergedSegments);
    }

    private File ensureModelFile(ModelSpec spec) throws IOException {
        if (spec.bundled) {
            File outFile = new File(StorageUtils.modelsDir(this), new File(spec.assetPath).getName());
            return AssetUtils.copyAssetToFile(this, spec.assetPath, outFile);
        }
        File file = new File(spec.assetPath);
        if (!file.exists()) throw new IOException("Model file not found: " + file.getName());
        return file;
    }

    private String buildDiarizedText(List<DiarizationUtils.TextSegment> segments) {
        if (currentImportedWav == null || !currentImportedWav.exists()) return "";
        if (segments == null || segments.isEmpty()) return "";
        float[] samples = WaveUtil.getSamples(currentImportedWav.getAbsolutePath());
        return DiarizationUtils.buildDiarizedTranscript(samples, segments);
    }

    private void writeOutputsIfPossible(String transcript, List<DiarizationUtils.TextSegment> segments, String diarizedText) {
        if (currentImportedWav == null) return;
        try {
            File outputsDir = StorageUtils.outputsDir(this);
            if (!outputsDir.exists()) outputsDir.mkdirs();
            String base = currentImportedWav.getName();
            if (base.endsWith(".wav")) base = base.substring(0, base.length() - 4);
            float[] samples = WaveUtil.getSamples(currentImportedWav.getAbsolutePath());
            String timestampedTranscript = DiarizationUtils.buildTimestampedTranscript(segments);
            File enhancedOutput = new File(outputsDir, base + ".enhanced.wav");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Files.copy(currentImportedWav.toPath(), enhancedOutput.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                copyFileCompat(currentImportedWav, enhancedOutput);
            }
            writeTextFile(new File(outputsDir, base + ".transcript.txt"), timestampedTranscript);
            writeTextFile(new File(outputsDir, base + ".diarized.srt"), diarizedText == null ? "" : diarizedText);
            writeMetadataFile(new File(outputsDir, base + ".meta.json"), transcript, diarizedText, samples.length, segments == null ? 0 : segments.size());
        } catch (Exception e) {
            setStatusMessage("Status: transcript done, but failed to write outputs: " + e.getMessage(), true);
        }
    }

    private void writeTextFile(File file, String content) throws IOException {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void openResultsWindow() {
        Intent intent = new Intent(this, ResultsActivity.class);
        intent.putExtra(ResultsActivity.EXTRA_TITLE, currentImportedWav == null ? "Latest Result" : currentImportedWav.getName());
        intent.putExtra(ResultsActivity.EXTRA_TRANSCRIPT, latestTranscript);
        intent.putExtra(ResultsActivity.EXTRA_DIARIZED, latestDiarized);
        startActivity(intent);
    }

    private void refreshHardwareStatus() {
        if (!StorageUtils.isWorkspaceConfigured(this)) {
            setStatusMessage("Status: workspace folder not set. Open Settings to choose a folder", true);
            btnTranscribe.setEnabled(false);
            return;
        }
        if (availableModels.isEmpty()) {
            setStatusMessage("Status: no model installed. Open Settings to download tiny or tiny-en", true);
            btnTranscribe.setEnabled(false);
            return;
        }
        int modelIndex = spinnerModel.getSelectedItemPosition();
        if (modelIndex < 0 || modelIndex >= availableModels.size()) {
            btnTranscribe.setEnabled(false);
            refreshHardwarePanel();
            return;
        }
        ModelSpec spec = availableModels.get(modelIndex);
        HardwareAssessment assessment = ModelUtils.assessHardware(this, spec.tierHint());
        if (assessment.canRun) {
            setStatusMessage(String.format(Locale.US,
                    "Status: ready. Using %.1f GB RAM, %.1f GB free, model needs %.1f GB",
                    assessment.usedRamGb, assessment.availRamGb, assessment.estimatedModelRamGb), false);
        } else {
            setStatusMessage("Status: warning - " + assessment.message, true);
        }
    }

    private int estimateTranscriptionPercent(long elapsedMs) {
        if (currentImportedWav == null || selectedModel == null) return 5;
        int sampleCount = WaveUtil.getSamples(currentImportedWav.getAbsolutePath()).length;
        double durationSeconds = sampleCount / 16000.0;
        double factor = estimatedRealtimeFactor(selectedModel.tierHint());
        long estimatedTotalMs = Math.max(6000L, Math.round(durationSeconds * factor * 1000.0));
        int percent = (int) Math.min(95, Math.max(1, (elapsedMs * 100) / estimatedTotalMs));
        return percent;
    }

    private double estimatedRealtimeFactor(String tier) {
        String normalized = tier == null ? "" : tier.toLowerCase(Locale.US);
        if (normalized.contains("small")) return 1.7;
        return 1.1;
    }

    private void refreshHardwarePanel() {
        String tier = selectedTierForPanel();
        HardwareAssessment assessment = ModelUtils.assessHardware(this, tier);
        String recommendation = buildRecommendationText(recommendedTier);
        String selected = selectedModelLabel();
        int cpuPercent = Math.min(999, (int) Math.round((assessment.systemLoad / Math.max(1, assessment.cpuThreads)) * 100.0));
        String details = String.format(Locale.US,
                "Hardware\nThreads: %d\nRAM: %.1f / %.1f GB used, %.1f GB free\nLoad: %.2f (%d%% of %d threads)\nModel budget: %.1f GB\nSelected: %s\n\n%s\n%s",
                assessment.cpuThreads,
                assessment.usedRamGb,
                assessment.totalRamGb,
                assessment.availRamGb,
                assessment.systemLoad,
                cpuPercent,
                assessment.cpuThreads,
                assessment.estimatedModelRamGb,
                selected,
                recommendation,
                statusMessage);
        tvHardwarePanel.setText(details);
        boolean highUsage = !assessment.canRun || cpuPercent >= 90 || assessment.availRamGb < (assessment.estimatedModelRamGb + 0.4);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(18f);
        box.setStroke(2, highUsage || statusWarning ? Color.RED : Color.DKGRAY);
        box.setColor(highUsage || statusWarning ? Color.parseColor("#FFF0F0") : Color.parseColor("#F2F2F2"));
        tvHardwarePanel.setBackground(box);
        tvHardwarePanel.setTextColor(highUsage || statusWarning ? Color.RED : defaultStatusColor);
    }

    private void setStatusMessage(String message, boolean warning) {
        statusMessage = message;
        statusWarning = warning;
        refreshHardwarePanel();
    }

    private String selectedTierForPanel() {
        int modelIndex = spinnerModel.getSelectedItemPosition();
        if (modelIndex >= 0 && modelIndex < availableModels.size()) {
            return availableModels.get(modelIndex).tierHint();
        }
        return recommendedTier == null ? ModelUtils.TINY : recommendedTier;
    }

    private String selectedModelLabel() {
        int modelIndex = spinnerModel.getSelectedItemPosition();
        if (modelIndex >= 0 && modelIndex < availableModels.size()) {
            return availableModels.get(modelIndex).label;
        }
        return "none";
    }

    private void copyFileCompat(File source, File target) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void writeMetadataFile(File file, String transcript, String diarizedText, int sampleCount, int segmentCount) throws IOException {
        double durationSeconds = sampleCount / 16000.0;
        String json = "{\n"
                + "  \"input\": \"" + escapeJson(currentImportedWav.getAbsolutePath()) + "\",\n"
                + "  \"model\": \"" + escapeJson(selectedModel == null ? "" : selectedModel.label) + "\",\n"
                + "  \"duration_seconds\": " + String.format(Locale.US, "%.2f", durationSeconds) + ",\n"
                + "  \"segment_count\": " + segmentCount + ",\n"
                + "  \"transcript_chars\": " + (transcript == null ? 0 : transcript.length()) + ",\n"
                + "  \"diarized_chars\": " + (diarizedText == null ? 0 : diarizedText.length()) + "\n"
                + "}\n";
        writeTextFile(file, json);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class TranscriptionBundle {
        final String text;
        final List<DiarizationUtils.TextSegment> segments;

        TranscriptionBundle(String text, List<DiarizationUtils.TextSegment> segments) {
            this.text = text == null ? "" : text;
            this.segments = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(transcriptionProgressUpdater);
        handler.removeCallbacks(hardwarePanelUpdater);
    }
}
