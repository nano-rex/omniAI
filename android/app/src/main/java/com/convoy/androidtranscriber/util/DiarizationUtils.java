package com.convoy.androidtranscriber.util;

import java.util.List;
import java.util.Locale;

public final class DiarizationUtils {
    public static final int SAMPLE_RATE = 16000;

    private DiarizationUtils() {}

    public static String buildTimestampedTranscript(List<TextSegment> segments) {
        StringBuilder out = new StringBuilder();
        for (TextSegment seg : segments) {
            if (seg.text.isEmpty()) continue;
            out.append('[')
                    .append(formatTimestamp(seg.startSeconds))
                    .append(" - ")
                    .append(formatTimestamp(seg.endSeconds))
                    .append("]\n")
                    .append(seg.text)
                    .append("\n\n");
        }
        return out.toString().trim();
    }

    public static String buildDiarizedTranscript(float[] samples, List<TextSegment> segments) {
        if (samples == null || samples.length == 0 || segments == null || segments.isEmpty()) return "";
        if (segments.size() == 1) {
            TextSegment seg = segments.get(0);
            return buildSrtBlock(1, seg.startSeconds, seg.endSeconds, "Speaker 1: " + seg.text);
        }

        double[][] features = new double[segments.size()][2];
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            int start = Math.max(0, (int) Math.floor(seg.startSeconds * SAMPLE_RATE));
            int end = Math.min(samples.length, (int) Math.ceil(seg.endSeconds * SAMPLE_RATE));
            features[i][0] = rms(samples, start, end);
            features[i][1] = zeroCrossingRate(samples, start, end);
        }

        normalizeFeatureColumn(features, 0);
        normalizeFeatureColumn(features, 1);

        double[] c1 = new double[]{features[0][0], features[0][1]};
        double[] c2 = new double[]{features[features.length - 1][0], features[features.length - 1][1]};
        if (distanceSq(c1, c2) < 1e-9) c2 = new double[]{c1[0] + 1e-3, c1[1] + 1e-3};
        int[] assignments = new int[features.length];

        for (int iter = 0; iter < 8; iter++) {
            double s1a = 0.0, s1b = 0.0, s2a = 0.0, s2b = 0.0;
            int n1 = 0, n2 = 0;
            for (int i = 0; i < features.length; i++) {
                double[] f = features[i];
                boolean cluster1 = distanceSq(f, c1) <= distanceSq(f, c2);
                assignments[i] = cluster1 ? 0 : 1;
                if (cluster1) {
                    s1a += f[0];
                    s1b += f[1];
                    n1++;
                } else {
                    s2a += f[0];
                    s2b += f[1];
                    n2++;
                }
            }
            if (n1 > 0) c1 = new double[]{s1a / n1, s1b / n1};
            if (n2 > 0) c2 = new double[]{s2a / n2, s2b / n2};
        }

        boolean cluster1IsSpeaker1 = c1[0] <= c2[0];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            if (seg.text.isEmpty()) continue;
            int speaker = cluster1IsSpeaker1 ? (assignments[i] == 0 ? 1 : 2) : (assignments[i] == 0 ? 2 : 1);
            if (out.length() > 0) out.append("\n\n");
            out.append(buildSrtBlock(i + 1, seg.startSeconds, seg.endSeconds, "Speaker " + speaker + ": " + seg.text));
        }
        return out.toString().trim();
    }

    public static String formatTimestamp(double seconds) {
        int total = Math.max(0, (int) Math.floor(seconds));
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int secs = total % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
    }

    public static String formatSrtTimestamp(double seconds) {
        long totalMillis = Math.max(0L, Math.round(seconds * 1000.0));
        long hours = totalMillis / 3600000L;
        long minutes = (totalMillis % 3600000L) / 60000L;
        long secs = (totalMillis % 60000L) / 1000L;
        long millis = totalMillis % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    private static String buildSrtBlock(int index, double startSeconds, double endSeconds, String text) {
        return index + "\n"
                + formatSrtTimestamp(startSeconds) + " --> " + formatSrtTimestamp(endSeconds) + "\n"
                + text;
    }

    private static double rms(float[] samples, int start, int end) {
        if (start >= end) return 0.0;
        double sumSq = 0.0;
        for (int i = start; i < end; i++) {
            double s = samples[i];
            sumSq += s * s;
        }
        return Math.sqrt(sumSq / (end - start));
    }

    private static double zeroCrossingRate(float[] samples, int start, int end) {
        if (start >= end) return 0.0;
        int crossings = 0;
        for (int i = Math.max(start + 1, 1); i < end; i++) {
            if ((samples[i - 1] >= 0f && samples[i] < 0f) || (samples[i - 1] < 0f && samples[i] >= 0f)) {
                crossings++;
            }
        }
        return crossings / (double) Math.max(1, end - start);
    }

    private static void normalizeFeatureColumn(double[][] features, int column) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] feature : features) {
            min = Math.min(min, feature[column]);
            max = Math.max(max, feature[column]);
        }
        double range = Math.max(1e-9, max - min);
        for (double[] feature : features) {
            feature[column] = (feature[column] - min) / range;
        }
    }

    private static double distanceSq(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    public static final class TextSegment {
        public final double startSeconds;
        public final double endSeconds;
        public final String text;

        public TextSegment(double startSeconds, double endSeconds, String text) {
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
            this.text = text == null ? "" : text;
        }
    }
}
