package com.convoy.androidtranscriber;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidtranscriber.util.AppSettings;
import com.convoy.androidtranscriber.util.ModelUtils;
import com.convoy.androidtranscriber.util.StorageUtils;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch switchAiEnhance = findViewById(R.id.switchAiEnhance);
        Switch switchTrim = findViewById(R.id.switchTrim);
        Switch switchDarkMode = findViewById(R.id.switchDarkMode);
        TextView tvFolderPath = findViewById(R.id.tvFolderPath);
        TextView tvTrimRecommendation = findViewById(R.id.tvTrimRecommendation);
        LinearLayout layoutTrimMinutes = findViewById(R.id.layoutTrimMinutes);
        EditText etTrimMinutes = findViewById(R.id.etTrimMinutes);
        Button btnFolderSetup = findViewById(R.id.btnFolderSetup);
        Button btnManageModels = findViewById(R.id.btnManageModels);
        Button btnTabHome = findViewById(R.id.btnTabHome);
        Button btnTabFolder = findViewById(R.id.btnTabFolder);
        Button btnTabSettings = findViewById(R.id.btnTabSettings);

        switchAiEnhance.setChecked(AppSettings.isAiEnhanceEnabled(this));
        switchTrim.setChecked(AppSettings.isTrimEnabled(this));
        switchDarkMode.setChecked(AppSettings.isDarkModeEnabled(this));
        etTrimMinutes.setText(String.valueOf(AppSettings.getTrimChunkMinutes(this)));
        refreshFolderPath(tvFolderPath);
        refreshTrimControls(layoutTrimMinutes, tvTrimRecommendation, switchTrim.isChecked());

        switchAiEnhance.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                AppSettings.setAiEnhanceEnabled(this, isChecked));
        switchTrim.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
                AppSettings.setTrimEnabled(this, isChecked);
                refreshTrimControls(layoutTrimMinutes, tvTrimRecommendation, isChecked);
        });
        switchDarkMode.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
                AppSettings.setDarkModeEnabled(this, isChecked);
                AppSettings.applyNightMode(this);
                recreate();
        });
        etTrimMinutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        etTrimMinutes.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String raw = s == null ? "" : s.toString().trim();
                if (raw.isEmpty()) return;
                try {
                    AppSettings.setTrimChunkMinutes(SettingsActivity.this, Integer.parseInt(raw));
                } catch (NumberFormatException ignored) {
                }
            }
        });
        btnFolderSetup.setOnClickListener(v -> showFolderModeDialog(tvFolderPath));
        btnManageModels.setOnClickListener(v -> startActivity(new Intent(this, ManageModelsActivity.class)));
        btnTabHome.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
        btnTabFolder.setOnClickListener(v -> startActivity(new Intent(this, SavedOutputsActivity.class)));
        btnTabSettings.setEnabled(false);
    }

    private void refreshTrimControls(LinearLayout layoutTrimMinutes, TextView tvTrimRecommendation, boolean enabled) {
        layoutTrimMinutes.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
        tvTrimRecommendation.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
        int recommended = recommendedChunkMinutes();
        tvTrimRecommendation.setText("Recommended chunk length based on current hardware: " + recommended + " minute(s).");
    }

    private int recommendedChunkMinutes() {
        String tier = ModelUtils.recommendModelTier(this);
        ModelUtils.HardwareAssessment assessment = ModelUtils.assessHardware(this, tier);
        if (assessment.cpuThreads >= 8 && assessment.availRamGb >= 4.0) return 4;
        if (assessment.cpuThreads >= 4 && assessment.availRamGb >= 2.5) return 3;
        if (assessment.cpuThreads >= 2 && assessment.availRamGb >= 1.5) return 2;
        return 1;
    }

    private void showFolderModeDialog(TextView tvFolderPath) {
        String[] labels = new String[]{
                "Documents folder",
                "Downloads folder",
                "Internal app folder"
        };
        String[] values = new String[]{
                AppSettings.STORAGE_DOCUMENTS,
                AppSettings.STORAGE_DOWNLOADS,
                AppSettings.STORAGE_INTERNAL
        };
        new AlertDialog.Builder(this)
                .setTitle("Choose base folder")
                .setItems(labels, (dialog, which) -> {
                    String nextMode = values[which];
                    String currentMode = AppSettings.getStorageMode(this);
                    if (nextMode.equals(currentMode)) {
                        refreshFolderPath(tvFolderPath);
                        return;
                    }
                    AppSettings.setStorageMode(this, nextMode);
                    refreshFolderPath(tvFolderPath);
                })
                .show();
    }

    private void refreshFolderPath(TextView tvFolderPath) {
        tvFolderPath.setText("Current base folder:\n" + StorageUtils.describeBaseDir(this));
    }
}
