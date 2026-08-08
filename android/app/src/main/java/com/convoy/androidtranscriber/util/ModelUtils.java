package com.convoy.androidtranscriber.util;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModelUtils {
    private ModelUtils() {}

    public static final String TINY_EN = "tiny-en";
    public static final String TINY = "tiny";
    public static final String SMALL = "small";
    public static final String SMALL_MANDARIN = "small-mandarin";
    public static final String SMALL_MALAY = "small-malay";
    public static final String SMALL_CANTONESE = "small-cantonese";
    public static final String SMALL_HOKKIEN = "small-hokkien";

    public static String recommendModelTier(Context context) {
        HardwareAssessment tiny = assessHardware(context, TINY);
        HardwareAssessment small = assessHardware(context, SMALL);
        if (small.canRun) return SMALL;
        if (tiny.canRun) return TINY;
        return TINY;
    }

    public static List<ModelSpec> bundledModelSpecs() {
        return new ArrayList<>();
    }

    public static List<ModelSpec> availableModels(Context context) {
        List<ModelSpec> specs = new ArrayList<>();
        for (ModelSpec spec : bundledModelSpecs()) {
            if (assetExists(context, spec.assetPath)) specs.add(spec);
        }

        File customDir = customModelsDir(context);
        File[] files = customDir.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".bin"));
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                String id = "custom:" + fileName;
                boolean multilingual = !looksEnglishOnly(fileName);
                String label = knownModelLabel(fileName);
                specs.add(new ModelSpec(id, label, file.getAbsolutePath(), false, multilingual));
            }
        }
        return specs;
    }

    public static ModelSpec findModel(Context context, String id) {
        for (ModelSpec spec : availableModels(context)) {
            if (spec.id.equals(id)) return spec;
        }
        return null;
    }

    public static boolean isMultilingual(String tier) {
        return !TINY_EN.equals(tier);
    }

    public static String defaultLanguageForModel(ModelSpec spec) {
        if (spec == null) return "en";
        switch (spec.label) {
            case TINY_EN:
            case TINY:
            case SMALL:
                return "en";
            case SMALL_MANDARIN:
                return "zh";
            case SMALL_MALAY:
                return "ms";
            case SMALL_CANTONESE:
                return "yue";
            case SMALL_HOKKIEN:
                return "zh";
            default:
                return "en";
        }
    }

    public static File customModelsDir(Context context) {
        return StorageUtils.customModelsDir(context);
    }

    public static HardwareAssessment assessHardware(Context context, String tier) {
        int cpuThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        double totalRamGb = memoryInfo.totalMem / 1024.0 / 1024.0 / 1024.0;
        double availRamGb = memoryInfo.availMem / 1024.0 / 1024.0 / 1024.0;
        double usedRamGb = Math.max(0.0, totalRamGb - availRamGb);
        double modelRamGb = estimatedModelRamGb(tier);
        double reserveGb = 0.6;
        double headroomGb = availRamGb - modelRamGb - reserveGb;
        double load = currentSystemLoad();
        boolean cpuOk = cpuThreads >= minThreadsForTier(tier);
        boolean ramOk = headroomGb >= 0.0;
        boolean systemBusy = load > Math.max(1.0, cpuThreads * 1.15);
        boolean canRun = cpuOk && ramOk && !systemBusy;
        String reason;
        if (!cpuOk) {
            reason = "Hardware not available: insufficient CPU threads for " + tier;
        } else if (!ramOk) {
            reason = String.format(Locale.US,
                    "Hardware not available: %.1f GB free RAM is below the %.1f GB needed for %s",
                    availRamGb, modelRamGb + reserveGb, tier);
        } else if (systemBusy) {
            reason = String.format(Locale.US,
                    "Hardware not available: system load %.2f is too high for %d CPU threads",
                    load, cpuThreads);
        } else {
            reason = String.format(Locale.US,
                    "%s can run. Free RAM %.1f GB, estimated model use %.1f GB, load %.2f",
                    tier, availRamGb, modelRamGb, load);
        }

        return new HardwareAssessment(cpuThreads, totalRamGb, availRamGb, usedRamGb, modelRamGb, load, canRun, reason);
    }

    public static double estimatedModelRamGb(String tier) {
        String normalized = tier == null ? "" : tier.toLowerCase(Locale.US);
        if (normalized.contains(SMALL)) return 4.2;
        if (normalized.contains(TINY)) return normalized.contains("en") ? 1.0 : 1.3;
        return 2.5;
    }

    private static int minThreadsForTier(String tier) {
        String normalized = tier == null ? "" : tier.toLowerCase(Locale.US);
        if (normalized.contains(SMALL)) return 4;
        return 2;
    }

    private static double currentSystemLoad() {
        try {
            String raw = new String(Files.readAllBytes(Path.of("/proc/loadavg")), StandardCharsets.UTF_8).trim();
            String[] parts = raw.split("\\s+");
            if (parts.length > 0) return Double.parseDouble(parts[0]);
        } catch (IOException | NumberFormatException ignored) {
        }
        return 0.0;
    }

    private static boolean assetExists(Context context, String path) {
        try {
            context.getAssets().open(path).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksEnglishOnly(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        return lower.contains(".en.") || lower.contains("-en.") || lower.endsWith(".en.bin");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String knownModelLabel(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        if ("ggml-small-mandarin.bin".equals(lower)) return SMALL_MANDARIN;
        if ("ggml-small-malay.bin".equals(lower)) return SMALL_MALAY;
        if ("ggml-small-cantonese.bin".equals(lower)) return SMALL_CANTONESE;
        if ("ggml-small-hokkien.bin".equals(lower)) return SMALL_HOKKIEN;
        if ("ggml-small.bin".equals(lower)) return SMALL;
        if ("ggml-tiny.bin".equals(lower)) return TINY;
        if ("ggml-tiny.en.bin".equals(lower)) return TINY_EN;
        return stripExtension(fileName) + " (custom)";
    }

    public static final class ModelSpec {
        public final String id;
        public final String label;
        public final String assetPath;
        public final boolean bundled;
        public final boolean multilingual;

        public ModelSpec(String id, String label, String assetPath, boolean bundled, boolean multilingual) {
            this.id = id;
            this.label = label;
            this.assetPath = assetPath;
            this.bundled = bundled;
            this.multilingual = multilingual;
        }

        public String tierHint() {
            String normalized = label.toLowerCase(Locale.US);
            if (normalized.contains("small")) return SMALL;
            if (normalized.contains("tiny-en")) return TINY_EN;
            if (normalized.contains("tiny")) return TINY;
            return TINY;
        }
    }

    public static final class HardwareAssessment {
        public final int cpuThreads;
        public final double totalRamGb;
        public final double availRamGb;
        public final double usedRamGb;
        public final double estimatedModelRamGb;
        public final double systemLoad;
        public final boolean canRun;
        public final String message;

        public HardwareAssessment(int cpuThreads, double totalRamGb, double availRamGb, double usedRamGb,
                                  double estimatedModelRamGb, double systemLoad, boolean canRun, String message) {
            this.cpuThreads = cpuThreads;
            this.totalRamGb = totalRamGb;
            this.availRamGb = availRamGb;
            this.usedRamGb = usedRamGb;
            this.estimatedModelRamGb = estimatedModelRamGb;
            this.systemLoad = systemLoad;
            this.canRun = canRun;
            this.message = message;
        }
    }
}
