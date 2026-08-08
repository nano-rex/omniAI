package com.convoy.androidtranscriber.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AudioImportUtil {
    private static final String TAG = "AudioImportUtil";
    private static final long TIMEOUT_US = 10000;
    private static final String AI_DENOISE_MODEL_ASSET = "denoise/std.rnnn";

    private AudioImportUtil() {}

    public interface ProgressListener {
        void onProgress(int percent, String stage);
    }

    public static ImportedAudio importToWav(Context context, Uri uri) throws IOException {
        return importToWav(context, uri, null);
    }

    public static ImportedAudio importToWav(Context context, Uri uri, ProgressListener listener) throws IOException {
        String displayName = queryDisplayName(context, uri);
        if (displayName == null) displayName = "imported_audio";
        notifyProgress(listener, 5, "preparing import");

        File importsDir = StorageUtils.importsDir(context);

        String lower = displayName.toLowerCase(Locale.US);
        File outFile = new File(importsDir, sanitizeBaseName(displayName) + ".wav");

        boolean aiEnhance = AppSettings.isAiEnhanceEnabled(context);
        boolean trimEnabled = AppSettings.isTrimEnabled(context);

        if (lower.endsWith(".wav")) {
            File rawCopy = new File(importsDir, sanitizeBaseName(displayName) + ".source.wav");
            copyUriToFile(context, uri, rawCopy, listener);
            normalizeWavTo16kMono(context, rawCopy, outFile, listener, aiEnhance);
            if (trimEnabled) {
                notifyProgress(listener, 98, "trimming speech");
                outFile = trimIfEnabled(outFile);
            }
            notifyProgress(listener, 100, "import complete");
            return new ImportedAudio(displayName, outFile);
        }

        decodeMediaTo16kMonoWav(context, uri, outFile, listener, aiEnhance);
        if (trimEnabled) {
            notifyProgress(listener, 98, "trimming speech");
            outFile = trimIfEnabled(outFile);
        }
        notifyProgress(listener, 100, "import complete");
        return new ImportedAudio(displayName, outFile);
    }

    private static void copyUriToFile(Context context, Uri uri, File target, ProgressListener listener) throws IOException {
        long totalBytes = -1L;
        try (var descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) totalBytes = descriptor.getLength();
        } catch (Exception ignored) {
        }
        try (var in = context.getContentResolver().openInputStream(uri);
             var out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Unable to open input stream");
            byte[] buffer = new byte[8192];
            int read;
            long copied = 0L;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                if (totalBytes > 0) {
                    int percent = 5 + (int) Math.min(45, (copied * 45) / totalBytes);
                    notifyProgress(listener, percent, "copying media");
                }
            }
        }
    }

    private static void normalizeWavTo16kMono(Context context, File source, File target, ProgressListener listener, boolean aiEnhance) throws IOException {
        notifyProgress(listener, 55, "enhancing audio");
        float[] sourceSamples = WaveUtil.getSamples(source.getAbsolutePath());
        if (sourceSamples.length == 0) throw new IOException("Unable to decode wav samples");
        short[] pcm16 = floatToPcm16(enhanceForSpeech(resampleLinear(sourceSamples, 16000, 16000)));
        File staged = new File(target.getParentFile(), target.getName() + ".pre_ai.wav");
        WaveUtil.createWaveFile(staged.getAbsolutePath(), shortsToBytes(pcm16), 16000, 1, 2);
        notifyProgress(listener, 90, "ai denoise");
        finalizeWithAiDenoise(context, staged, target, listener, aiEnhance);
    }

    private static void decodeMediaTo16kMonoWav(Context context, Uri uri, File outFile, ProgressListener listener, boolean aiEnhance) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            notifyProgress(listener, 10, "reading media");
            extractor.setDataSource(context, uri, null);
            int audioTrack = selectAudioTrack(extractor);
            if (audioTrack < 0) throw new IOException("No audio track found in selected media");
            extractor.selectTrack(audioTrack);

            MediaFormat format = extractor.getTrackFormat(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("Audio mime type missing");

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int sourceSampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 16000;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int decodePercent = 15;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        outputBuffer.get(chunk);
                        pcmOut.write(chunk);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if (decodePercent < 68) {
                        decodePercent += 2;
                        notifyProgress(listener, decodePercent, "decoding media");
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            codec.stop();
            codec.release();

            short[] decoded = bytesToShorts(pcmOut.toByteArray());
            short[] mono = downmixToMono(decoded, channelCount);
            float[] monoFloat = pcm16ToFloat(mono);
            notifyProgress(listener, 75, "enhancing audio");
            float[] resampled = enhanceForSpeech(resampleLinear(monoFloat, sourceSampleRate, 16000));
            short[] finalPcm = floatToPcm16(resampled);
            File staged = new File(outFile.getParentFile(), outFile.getName() + ".pre_ai.wav");
            WaveUtil.createWaveFile(staged.getAbsolutePath(), shortsToBytes(finalPcm), 16000, 1, 2);
            notifyProgress(listener, 92, "ai denoise");
            finalizeWithAiDenoise(context, staged, outFile, listener, aiEnhance);
        } catch (Exception e) {
            throw new IOException("Failed to import media: " + e.getMessage(), e);
        } finally {
            extractor.release();
        }
    }

    private static void finalizeWithAiDenoise(Context context, File stagedWav, File outFile, ProgressListener listener, boolean aiEnhance) throws IOException {
        try {
            if (aiEnhance) {
                File modelFile = ensureAiDenoiseModel(context);
                aiDenoiseWav(stagedWav, outFile, modelFile);
            } else {
                java.nio.file.Files.copy(stagedWav.toPath(), outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Log.w(TAG, "AI denoise failed, keeping staged wav", e);
            try {
                java.nio.file.Files.copy(stagedWav.toPath(), outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception copyError) {
                throw new IOException("Failed to finalize wav after AI denoise error", copyError);
            }
        } finally {
            stagedWav.delete();
        }
        notifyProgress(listener, 97, aiEnhance ? "writing enhanced wav" : "writing wav");
    }

    private static File trimIfEnabled(File wavFile) throws IOException {
        File trimmed = new File(wavFile.getParentFile(), wavFile.getName().replace(".wav", ".trimmed.wav"));
        AudioTrimUtil.trimQuietSections(wavFile, trimmed);
        return trimmed;
    }

    private static File ensureAiDenoiseModel(Context context) throws IOException {
        File modelFile = new File(StorageUtils.denoiseDir(context), "std.rnnn");
        return AssetUtils.copyAssetToFile(context, AI_DENOISE_MODEL_ASSET, modelFile);
    }

    private static void aiDenoiseWav(File inputWav, File outputWav, File modelFile) throws IOException {
        String filter = "arnndn=m=" + ffmpegQuote(modelFile) + ",highpass=f=100,lowpass=f=7200,acompressor=threshold=-22dB:ratio=2.0:attack=10:release=180:makeup=2dB:knee=2,alimiter=limit=0.92";
        String command = "-y -i " + ffmpegQuote(inputWav) + " -ac 1 -ar 16000 -af " + ffmpegQuote(filter) + " " + ffmpegQuote(outputWav);
        FFmpegSession session = FFmpegKit.execute(command);
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            String detail = session.getFailStackTrace();
            if (detail == null || detail.isBlank()) detail = session.getOutput();
            throw new IOException("AI denoise failed" + (detail == null || detail.isBlank() ? "" : ": " + detail));
        }
    }

    private static String ffmpegQuote(File file) {
        return ffmpegQuote(file.getAbsolutePath());
    }

    private static String ffmpegQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) return cursor.getString(nameIndex);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query display name", e);
        }
        return null;
    }

    private static String sanitizeBaseName(String displayName) {
        String base = displayName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static short[] bytesToShorts(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] shorts = new short[bytes.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = buffer.getShort();
        }
        return shorts;
    }

    private static byte[] shortsToBytes(short[] shorts) {
        ByteBuffer buffer = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : shorts) buffer.putShort(s);
        return buffer.array();
    }

    private static short[] downmixToMono(short[] pcm, int channelCount) {
        if (channelCount <= 1) return pcm;
        int frames = pcm.length / channelCount;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channelCount; c++) {
                sum += pcm[i * channelCount + c];
            }
            mono[i] = (short) (sum / channelCount);
        }
        return mono;
    }

    private static float[] pcm16ToFloat(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768f;
        return out;
    }

    private static short[] floatToPcm16(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1f, Math.min(1f, samples[i]));
            out[i] = (short) (clamped * 32767f);
        }
        return out;
    }

    private static float[] resampleLinear(float[] input, int sourceRate, int targetRate) {
        if (sourceRate <= 0 || sourceRate == targetRate) return input;
        int outputLength = (int) Math.max(1, Math.round(input.length * (targetRate / (double) sourceRate)));
        float[] output = new float[outputLength];
        double ratio = sourceRate / (double) targetRate;
        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i * ratio;
            int left = (int) Math.floor(srcIndex);
            int right = Math.min(left + 1, input.length - 1);
            double frac = srcIndex - left;
            output[i] = (float) ((1.0 - frac) * input[left] + frac * input[right]);
        }
        return output;
    }

    private static float[] enhanceForSpeech(float[] input) {
        if (input.length == 0) return input;

        float[] filtered = new float[input.length];
        float previousInput = 0f;
        float previousOutput = 0f;
        for (int i = 0; i < input.length; i++) {
            float current = input[i];
            float highPass = (float) (0.97 * (previousOutput + current - previousInput));
            filtered[i] = highPass;
            previousInput = current;
            previousOutput = highPass;
        }

        float[] leveled = applyAdaptiveSpeechGain(filtered);
        return softLimit(leveled);
    }

    private static float[] applyAdaptiveSpeechGain(float[] input) {
        int window = 1600;
        float[] output = new float[input.length];
        double avgAbs = 0.0;
        for (float sample : input) avgAbs += Math.abs(sample);
        avgAbs = avgAbs / Math.max(1, input.length);
        double gate = Math.max(0.004, avgAbs * 0.55);

        for (int start = 0; start < input.length; start += window) {
            int end = Math.min(input.length, start + window);
            double rms = 0.0;
            for (int i = start; i < end; i++) {
                rms += input[i] * input[i];
            }
            rms = Math.sqrt(rms / Math.max(1, end - start));

            float gain;
            if (rms < gate * 0.8) {
                gain = 1.1f;
            } else if (rms < 0.025) {
                gain = 6.5f;
            } else if (rms < 0.06) {
                gain = 3.8f;
            } else if (rms < 0.12) {
                gain = 2.2f;
            } else {
                gain = 1.2f;
            }

            for (int i = start; i < end; i++) {
                float sample = input[i];
                if (Math.abs(sample) < gate) {
                    sample *= 0.45f;
                }
                output[i] = sample * gain;
            }
        }
        return output;
    }

    private static float[] softLimit(float[] input) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            float sample = (float) Math.tanh(input[i] * 1.5f) / 1.05f;
            output[i] = Math.max(-0.98f, Math.min(0.98f, sample));
        }
        return output;
    }

    private static void notifyProgress(ProgressListener listener, int percent, String stage) {
        if (listener != null) listener.onProgress(percent, stage);
    }

    public static List<File> splitWavForTranscription(Context context, File wavFile) throws IOException {
        float[] samples = WaveUtil.getSamples(wavFile.getAbsolutePath());
        if (samples.length == 0) return List.of(wavFile);

        int sampleRate = 16000;
        int chunkSamples = AppSettings.getTrimChunkMinutes(context) * 60 * sampleRate;
        if (samples.length <= chunkSamples) return List.of(wavFile);

        File chunksDir = StorageUtils.chunksDir(context);

        List<File> parts = new ArrayList<>();
        int partIndex = 0;
        for (int start = 0; start < samples.length; start += chunkSamples) {
            int end = Math.min(samples.length, start + chunkSamples);
            float[] chunkSamplesArray = new float[end - start];
            System.arraycopy(samples, start, chunkSamplesArray, 0, chunkSamplesArray.length);
            File chunkFile = new File(chunksDir, wavFile.getName().replace(".wav", "") + ".part" + (++partIndex) + ".wav");
            WaveUtil.createWaveFile(chunkFile.getAbsolutePath(), shortsToBytes(floatToPcm16(chunkSamplesArray)), 16000, 1, 2);
            parts.add(chunkFile);
        }
        return parts;
    }

    public static void cleanupSplitWavParts(File originalWav, List<File> wavParts) {
        if (wavParts == null || wavParts.isEmpty()) return;
        for (File part : wavParts) {
            if (part == null) continue;
            if (originalWav != null && part.equals(originalWav)) continue;
            try {
                if (part.exists()) part.delete();
            } catch (Exception ignored) {
            }
        }
    }

    public static final class ImportedAudio {
        public final String displayName;
        public final File wavFile;

        public ImportedAudio(String displayName, File wavFile) {
            this.displayName = displayName;
            this.wavFile = wavFile;
        }
    }
}
