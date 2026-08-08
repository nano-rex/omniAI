package com.convoy.androidtranscriber;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ResultsActivity extends AppCompatActivity {
    public static final String EXTRA_TRANSCRIPT = "extra_transcript";
    public static final String EXTRA_DIARIZED = "extra_diarized";
    public static final String EXTRA_TRANSCRIPT_PATH = "extra_transcript_path";
    public static final String EXTRA_DIARIZED_PATH = "extra_diarized_path";
    public static final String EXTRA_TITLE = "extra_title";

    private TextView tvContent;
    private TextView tvSearchInfo;
    private EditText etSearch;
    private String transcriptText = "";
    private String diarizedText = "";
    private String currentTitle = "Results";
    private String currentContent = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        com.convoy.androidtranscriber.util.AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);

        transcriptText = resolveContent(EXTRA_TRANSCRIPT_PATH, EXTRA_TRANSCRIPT);
        diarizedText = resolveContent(EXTRA_DIARIZED_PATH, EXTRA_DIARIZED);
        currentTitle = valueOrEmpty(getIntent().getStringExtra(EXTRA_TITLE));
        if (currentTitle.isEmpty()) currentTitle = "Results";

        Button btnTranscript = findViewById(R.id.btnResultsTranscript);
        Button btnDiarized = findViewById(R.id.btnResultsDiarized);
        Button btnCopy = findViewById(R.id.btnResultsCopy);
        Button btnShare = findViewById(R.id.btnResultsShare);
        Button btnSearch = findViewById(R.id.btnResultsSearch);
        tvContent = findViewById(R.id.tvResultsContent);
        tvSearchInfo = findViewById(R.id.tvResultsSearchInfo);
        etSearch = findViewById(R.id.etResultsSearch);

        btnTranscript.setOnClickListener(v -> showSection("Transcript", transcriptText));
        btnDiarized.setOnClickListener(v -> showSection("Diarized Transcript", diarizedText));
        btnCopy.setOnClickListener(v -> copyCurrentContent());
        btnShare.setOnClickListener(v -> shareCurrentContent());
        btnSearch.setOnClickListener(v -> highlightSearch());

        if (!transcriptText.isEmpty()) {
            showSection("Transcript", transcriptText);
        } else if (!diarizedText.isEmpty()) {
            showSection("Diarized Transcript", diarizedText);
        } else {
            showSection("Transcript", transcriptText);
        }
    }

    private void showSection(String title, String content) {
        currentTitle = title;
        currentContent = content == null ? "" : content;
        setTitle(valueOrEmpty(getIntent().getStringExtra(EXTRA_TITLE)).isEmpty()
                ? title
                : valueOrEmpty(getIntent().getStringExtra(EXTRA_TITLE)) + " - " + title);
        tvSearchInfo.setText("");
        tvContent.setText(currentContent.isEmpty() ? "No content available." : currentContent);
    }

    private String valueOrEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private String resolveContent(String pathExtra, String textExtra) {
        String filePath = valueOrEmpty(getIntent().getStringExtra(pathExtra));
        if (!filePath.isEmpty()) {
            try {
                return new String(Files.readAllBytes(java.nio.file.Path.of(filePath)), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return "Failed to read file: " + e.getMessage();
            }
        }
        return valueOrEmpty(getIntent().getStringExtra(textExtra));
    }

    private void copyCurrentContent() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(currentTitle, currentContent));
        tvSearchInfo.setText("Copied current section.");
    }

    private void shareCurrentContent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, currentTitle);
        intent.putExtra(Intent.EXTRA_TEXT, currentContent);
        startActivity(Intent.createChooser(intent, "Share results"));
    }

    private void highlightSearch() {
        String query = valueOrEmpty(etSearch.getText() == null ? null : etSearch.getText().toString()).trim();
        if (query.isEmpty()) {
            tvSearchInfo.setText("Enter a search term.");
            tvContent.setText(currentContent.isEmpty() ? "No content available." : currentContent);
            return;
        }

        String lowerContent = currentContent.toLowerCase();
        String lowerQuery = query.toLowerCase();
        SpannableString spannable = new SpannableString(currentContent);
        int count = 0;
        int start = 0;
        while (start >= 0) {
            start = lowerContent.indexOf(lowerQuery, start);
            if (start < 0) break;
            int end = start + lowerQuery.length();
            spannable.setSpan(new BackgroundColorSpan(0xFFFFFF66), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            count++;
            start = end;
        }

        tvContent.setText(spannable);
        tvSearchInfo.setText(count == 0 ? "No matches found." : "Matches: " + count);
    }
}
