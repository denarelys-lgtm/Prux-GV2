package com.example.detectcamera;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class AudioStreamManager {

    private static final String TAG = "AudioStreamManager";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private PipedOutputStream pipedOutputStream;

    public synchronized InputStream iniciarCaptura(int modo, MediaProjection mediaProjection) {
        detenerCaptura();

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize * 4, 8192);

        try {
            if (modo == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                // Modo 2: Captura de Audio Interno (WhatsApp / Tango / Llamadas / Sistema)
                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build();

                audioRecord = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(config)
                        .build();
            } else {
                // Modo 1: Micrófono Ambiental
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                );
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord no se pudo inicializar.");
                return null;
            }

            audioRecord.startRecording();
            isRecording = true;

            pipedOutputStream = new PipedOutputStream();
            PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream, bufferSize * 2);

            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                try {
                    escribirCabeceraWav(pipedOutputStream, SAMPLE_RATE, 1, 16);

                    while (isRecording && !Thread.currentThread().isInterrupted()) {
                        int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            pipedOutputStream.write(buffer, 0, bytesRead);
                            pipedOutputStream.flush();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error en transmisión de audio: " + e.getMessage());
                }
            }, "AudioStreamThread");

            recordingThread.start();
            return pipedInputStream;

        } catch (Exception e) {
            Log.e(TAG, "Error iniciando AudioRecord: " + e.getMessage());
            detenerCaptura();
            return null;
        }
    }

    public synchronized void detenerCaptura() {
        isRecording = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (pipedOutputStream != null) {
            try {
                pipedOutputStream.close();
            } catch (Exception ignored) {}
            pipedOutputStream = null;
        }
    }

    private void escribirCabeceraWav(OutputStream out, int sampleRate, int channels, int bitsPerSample) throws Exception {
        byte[] header = new byte[44];
        long totalDataLen = 0x7fffffffL;
        long longSampleRate = sampleRate;
        long byteRate = sampleRate * channels * (bitsPerSample / 8);

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * (bitsPerSample / 8)); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalDataLen & 0xff);
        header[41] = (byte) ((totalDataLen >> 8) & 0xff);
        header[42] = (byte) ((totalDataLen >> 16) & 0xff);
        header[43] = (byte) ((totalDataLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }
}
