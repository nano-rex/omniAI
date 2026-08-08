package com.convoy.androidtranscriber.util;

import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class WaveUtil {
    public static final String TAG = "WaveUtil";

    public static void createWaveFile(String filePath, byte[] samples, int sampleRate, int numChannels, int bytesPerSample) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(filePath)) {
            int dataSize = samples.length;
            int audioFormat = (bytesPerSample == 2) ? 1 : (bytesPerSample == 4) ? 3 : 0;

            fileOutputStream.write("RIFF".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write(intToByteArray(36 + dataSize), 0, 4);
            fileOutputStream.write("WAVE".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write("fmt ".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write(intToByteArray(16), 0, 4);
            fileOutputStream.write(shortToByteArray((short) audioFormat), 0, 2);
            fileOutputStream.write(shortToByteArray((short) numChannels), 0, 2);
            fileOutputStream.write(intToByteArray(sampleRate), 0, 4);
            fileOutputStream.write(intToByteArray(sampleRate * numChannels * bytesPerSample), 0, 4);
            fileOutputStream.write(shortToByteArray((short) (numChannels * bytesPerSample)), 0, 2);
            fileOutputStream.write(shortToByteArray((short) (bytesPerSample * 8)), 0, 2);
            fileOutputStream.write("data".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write(intToByteArray(dataSize), 0, 4);
            fileOutputStream.write(samples);
        } catch (IOException e) {
            Log.e(TAG, "Error creating wav", e);
        }
    }

    public static float[] getSamples(String filePath) {
        try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
            byte[] header = new byte[44];
            int headerRead = fileInputStream.read(header);
            if (headerRead < 44) return new float[0];

            String headerStr = new String(header, 0, 4);
            if (!headerStr.equals("RIFF")) {
                Log.e(TAG, "Not a valid WAV file");
                return new float[0];
            }

            int bitsPerSample = byteArrayToNumber(header, 34, 2);
            if (bitsPerSample != 16 && bitsPerSample != 32) {
                Log.e(TAG, "Unsupported bits per sample: " + bitsPerSample);
                return new float[0];
            }

            int dataLength = fileInputStream.available();
            int bytesPerSample = bitsPerSample / 8;
            int numSamples = dataLength / bytesPerSample;

            byte[] audioData = new byte[dataLength];
            int read = fileInputStream.read(audioData);
            if (read <= 0) return new float[0];

            ByteBuffer byteBuffer = ByteBuffer.wrap(audioData);
            byteBuffer.order(ByteOrder.nativeOrder());

            float[] samples = new float[numSamples];
            if (bitsPerSample == 16) {
                for (int i = 0; i < numSamples; i++) {
                    samples[i] = (float) (byteBuffer.getShort() / 32768.0);
                }
            } else {
                for (int i = 0; i < numSamples; i++) {
                    samples[i] = byteBuffer.getFloat();
                }
            }
            return samples;
        } catch (IOException e) {
            Log.e(TAG, "Error reading wav", e);
        }
        return new float[0];
    }

    private static int byteArrayToNumber(byte[] bytes, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value |= (bytes[offset + i] & 0xFF) << (8 * i);
        }
        return value;
    }

    private static byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private static byte[] shortToByteArray(short value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }
}
