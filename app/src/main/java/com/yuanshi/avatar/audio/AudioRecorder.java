package com.yuanshi.avatar.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.yuanshi.avatar.util.StringUtils;

public class AudioRecorder {

    private final String TAG = getClass().getSimpleName();

    private final Context mContext;
    private AudioRecord mAudioRecorder; //录音器
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final RecorderCallback callback;

    public AudioRecorder(Context context, RecorderCallback callback){
        this.mContext = context;
        this.callback = callback;
    }

    @SuppressLint("MissingPermission")
    public void start(){
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        //20ms audio for 16k/16bit/mono
//        int WAVE_FRAM_SIZE = 20 * 2 * 1 * SAMPLE_RATE / 1000;
        int minBufferSize = AudioRecord.getMinBufferSize(
                sampleRateInHz,
                channelConfig,
                audioFormat
        );
        Log.d(TAG, "minBufferSize: " + minBufferSize);
        mAudioRecorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateInHz, channelConfig,
                audioFormat,
                minBufferSize);
        if (mAudioRecorder.getState() != AudioRecord.STATE_UNINITIALIZED){
            mAudioRecorder.startRecording();
            mExecutor.execute(() -> {
                long startTime = System.currentTimeMillis();
                File cacheDir = mContext.getExternalCacheDir();
                if (!cacheDir.exists()){
                    if (!cacheDir.mkdirs()) Log.e(TAG, "mkdirs fail path: " + cacheDir.getAbsolutePath());
                }
                String pcmName = StringUtils.createFileName("record_", ".pcm");
                File pcmFile = new File(cacheDir, pcmName);
                try (FileOutputStream outputStream = new FileOutputStream(pcmFile)) {
                    byte[] data = new byte[minBufferSize];
                    while (mAudioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING){
                        int length = mAudioRecorder.read(data, 0, minBufferSize);
                        if (length > 0){
                            outputStream.write(data, 0, length);
                            if (callback != null){
                                callback.onReadData(data, 0, length);
                            }
                        }
                    }
                    Log.d(TAG, "Record done.");
                    long diff = System.currentTimeMillis() - startTime;
                    if (callback != null){
                        if (diff > 200){
                            callback.onFinish(pcmFile.getAbsolutePath());
                        } else {
                            callback.onRecordError(-2, "too short!");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Record error: " + e);
                    if (callback != null){
                        callback.onRecordError(-1, "Record error: " + e);
                    }
                }
            });
        }
    }

    public void stop(){
        if (mAudioRecorder != null){
            if (mAudioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING){
                mAudioRecorder.stop();
            }
        }
    }

    public void release(){
        if (mAudioRecorder != null){
            mAudioRecorder.release();
            mAudioRecorder = null;
        }
        mExecutor.shutdownNow();
    }

    public interface RecorderCallback{
        void onReadData(byte[] data, int offsetInBytes, int length);

        void onRecordError(int code, String message);

        void onFinish(String path);
    }
}
