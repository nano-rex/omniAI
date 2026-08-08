package com.convoy.androidtranscriber;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidtranscriber.util.StorageUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SavedOutputsActivity extends AppCompatActivity {
    private EditText etSearch;
    private TextView tvEmpty;
    private Button btnDelete;
    private ListView listOutputs;

    private final List<SavedOutput> allOutputs = new ArrayList<>();
    private final List<SavedOutput> filteredOutputs = new ArrayList<>();
    private ArrayAdapter<SavedOutput> adapter;
    private int selectedIndex = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        com.convoy.androidtranscriber.util.AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_outputs);

        etSearch = findViewById(R.id.etSearch);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnDelete = findViewById(R.id.btnDelete);
        listOutputs = findViewById(R.id.listOutputs);
        Button btnTabHome = findViewById(R.id.btnTabHome);
        Button btnTabFolder = findViewById(R.id.btnTabFolder);
        Button btnTabSettings = findViewById(R.id.btnTabSettings);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, filteredOutputs);
        listOutputs.setAdapter(adapter);
        listOutputs.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        btnTabHome.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
        btnTabFolder.setEnabled(false);
        btnTabSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        listOutputs.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            listOutputs.setItemChecked(position, true);
            openOutput(filteredOutputs.get(position));
        });

        btnDelete.setOnClickListener(v -> deleteSelected());
        loadOutputs();
    }

    private void loadOutputs() {
        allOutputs.clear();
        filteredOutputs.clear();
        selectedIndex = -1;
        listOutputs.clearChoices();

        if (!StorageUtils.isWorkspaceConfigured(this)) {
            adapter.notifyDataSetChanged();
            tvEmpty.setText("Workspace folder not set.");
            btnDelete.setEnabled(false);
            return;
        }

        File outputsDir = StorageUtils.outputsDir(this);
        Map<String, SavedOutput> grouped = new HashMap<>();
        File[] files = outputsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".transcript.txt")) {
                    SavedOutput entry = grouped.computeIfAbsent(baseName(name, ".transcript.txt"), SavedOutput::new);
                    entry.transcriptFile = file;
                } else if (name.endsWith(".diarized.srt")) {
                    SavedOutput entry = grouped.computeIfAbsent(baseName(name, ".diarized.srt"), SavedOutput::new);
                    entry.diarizedFile = file;
                } else if (name.endsWith(".meta.json")) {
                    SavedOutput entry = grouped.computeIfAbsent(baseName(name, ".meta.json"), SavedOutput::new);
                    entry.metaFile = file;
                } else if (name.endsWith(".enhanced.wav")) {
                    SavedOutput entry = grouped.computeIfAbsent(baseName(name, ".enhanced.wav"), SavedOutput::new);
                    entry.enhancedAudioFile = file;
                }
            }
        }

        allOutputs.addAll(grouped.values());
        Collections.sort(allOutputs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        applyFilter(etSearch.getText() == null ? "" : etSearch.getText().toString());
    }

    private void applyFilter(String query) {
        String normalized = query.trim().toLowerCase(Locale.US);
        filteredOutputs.clear();
        for (SavedOutput output : allOutputs) {
            if (normalized.isEmpty() || output.baseName.toLowerCase(Locale.US).contains(normalized)) {
                filteredOutputs.add(output);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setText(filteredOutputs.isEmpty() ? "No saved transcripts found." : "");
        btnDelete.setEnabled(!filteredOutputs.isEmpty());
    }

    private void openOutput(SavedOutput output) {
        Intent intent = new Intent(this, ResultsActivity.class);
        intent.putExtra(ResultsActivity.EXTRA_TITLE, output.baseName);
        if (output.transcriptFile != null) {
            intent.putExtra(ResultsActivity.EXTRA_TRANSCRIPT_PATH, output.transcriptFile.getAbsolutePath());
        }
        if (output.diarizedFile != null) {
            intent.putExtra(ResultsActivity.EXTRA_DIARIZED_PATH, output.diarizedFile.getAbsolutePath());
        }
        startActivity(intent);
    }

    private void deleteSelected() {
        if (selectedIndex < 0 || selectedIndex >= filteredOutputs.size()) {
            tvEmpty.setText("Select an item to delete.");
            return;
        }
        SavedOutput output = filteredOutputs.get(selectedIndex);
        new AlertDialog.Builder(this)
                .setTitle("Delete saved output")
                .setMessage("Delete " + output.baseName + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteIfExists(output.transcriptFile);
                    deleteIfExists(output.diarizedFile);
                    deleteIfExists(output.metaFile);
                    deleteIfExists(output.enhancedAudioFile);
                    loadOutputs();
                })
                .show();
    }

    private static void deleteIfExists(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String baseName(String value, String suffix) {
        return value.substring(0, value.length() - suffix.length());
    }

    private static final class SavedOutput {
        final String baseName;
        File transcriptFile;
        File diarizedFile;
        File metaFile;
        File enhancedAudioFile;

        SavedOutput(String baseName) {
            this.baseName = baseName;
        }

        long lastModified() {
            long latest = 0L;
            if (transcriptFile != null) latest = Math.max(latest, transcriptFile.lastModified());
            if (diarizedFile != null) latest = Math.max(latest, diarizedFile.lastModified());
            if (metaFile != null) latest = Math.max(latest, metaFile.lastModified());
            if (enhancedAudioFile != null) latest = Math.max(latest, enhancedAudioFile.lastModified());
            return latest;
        }

        @Override
        public String toString() {
            return baseName;
        }
    }
}
