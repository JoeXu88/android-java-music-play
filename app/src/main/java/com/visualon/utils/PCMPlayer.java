package com.visualon.utils;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Created by david on 25/05/2017.
 */

public class PCMPlayer {
    final static String TAG = "PCMPlayer";
    final int MAX_SMP_RATE = 48000;
    final int MAX_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO;
    final int MAX_BIT_DEPTH = AudioFormat.ENCODING_PCM_16BIT;
    private AudioTrack mAudioTrack;
    private int mSampleRate = 8000;
    private int mChannel = AudioFormat.CHANNEL_OUT_MONO;
    private int mBitFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int mBufferSize;

    public PCMPlayer(int samplerate, int channel, int bitformat) {
        mSampleRate = samplerate;
        mChannel = channel;
        mBitFormat = bitformat;

        // 获得缓冲流大小
        mBufferSize = AudioTrack.getMinBufferSize(MAX_SMP_RATE, MAX_CHANNEL, MAX_BIT_DEPTH);
        init();
    }

    public PCMPlayer() {

        // 获得缓冲流大小
        mBufferSize = AudioTrack.getMinBufferSize(MAX_SMP_RATE, MAX_CHANNEL, MAX_BIT_DEPTH);
        init();
    }

    public  int getbuffersize() {
        return mBufferSize * 2;
    }

    public void start() {
        if(mAudioTrack == null)
            init();

        // 播放 后续你直接write数据就行
        if(mAudioTrack != null) {
            Log.d(TAG, "audio track start");
            mAudioTrack.play();
        }
        else
            Log.e(TAG, "audio track null");
    }

    public void stop() {
        if(mAudioTrack != null) {
            Log.d(TAG, "audio track stop");
            mAudioTrack.stop();
        }
    }

    public void destroy() {
        if(mAudioTrack != null) {
            mAudioTrack.release();
            Log.d(TAG, "audio track release");
            mAudioTrack = null;
        }
    }

    public void init() {
        Log.d(TAG, "audio track init...");
        /**
         * AudioTrack支持4K-48K采样率
         * (sampleRateInHz< 4000) || (sampleRateInHz > 48000) )
         */

        //release old audio track
        stop();
        destroy();

        // 初始化AudioTrack
        /**
         * 参数:
         * 1.streamType
         *   STREAM_ALARM：警告声
         *   STREAM_MUSCI：音乐声，例如music等
         *   STREAM_RING：铃声
         *   STREAM_SYSTEM：系统声音
         *   STREAM_VOCIE_CALL：电话声音
         *
         * 2.采样率
         * 3.声道数
         * 4.采样精度
         * 5.每次播放的数据大小
         * 6.AudioTrack中有MODE_STATIC和MODE_STREAM两种分类。
         *   STREAM的意思是由用户在应用程序通过write方式把数据一次一次得写到audiotrack中。
         *   意味着你只需要开启播放后 后续使用write方法(AudioTrack的方法)写入buffer就行
         *
         *   STATIC的意思是一开始创建的时候，就把音频数据放到一个固定的buffer，然后直接传给audiotrack，
         *   后续就不用一次次得write了。AudioTrack会自己播放这个buffer中的数据。
         *   这种方法对于铃声等内存占用较小，延时要求较高的声音来说很适用。
         */

        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, mChannel, mBitFormat,
                    mBufferSize, AudioTrack.MODE_STREAM);
    }

    public void writeSample(byte[] sample, int len) {
        if(mAudioTrack != null) {
            mAudioTrack.write(sample, 0, (len < sample.length) ? len:sample.length);
            Log.v(TAG, "audio track write len: "+(len < sample.length? len:sample.length));
        }
    }

    public void formatChange(int samplerate, int channel, int bitformat) {
        mSampleRate = samplerate;
        mChannel = channel;
        mBitFormat = bitformat;

        Log.d(TAG, "audio fmt change=> smprate: "+mSampleRate
                    +" channel: "+mChannel
                    +" bitformt: "+mBitFormat);

        init();
        start();
    }
}
