package com.visualon.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;


/**
 * Created by david on 26/05/2017.
 */

public class MediaCodecAudioDecoder {

    final static String TAG = "MediaCodecAudioDecoder";
    final int DEFLT_SMP_RATE = 48000;
    final int DEFLT_CHANNEL_NUM = 2;
    final int CIRCULBUFF_NUM = 10;
    private MediaCodec mDecoder;
    private MediaExtractor mMediaExtractor;
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private MediaCodec.BufferInfo mDecBuffInfo;
    private boolean msrcEnd = false;
    private boolean moutEnd = false;
    private MediaFormat mForamt;
    private int mSampleRate;
    private int mChannelNum;
    private String mMime = null;
    private FileOutputStream mFout = null;
    private boolean mLive = false;
    private DecOutData mDecOutData;
    private DecThread mDecThread;
    private int m10msLen = DEFLT_SMP_RATE * DEFLT_CHANNEL_NUM * 2;
    private CustomCircularBuffer<DecOutData> mDecBuff;
    final int TIMEOUT_US = 5000;
    private String mFilePath = "/sdcard/music.mp3";

    public final class DecOutData {
        public void set(@NonNull byte[] data, int size) {
            if((mData != null) && (mData.length <= data.length)) {
                System.arraycopy(data, 0, mData, 0, size < data.length ? size : data.length);
                mSize = (size < data.length) ? size : data.length;
            }
        }

        public DecOutData(int samplerate, int channel) {
            mData = new byte[samplerate * channel * 2];
            mSize = 0;
        }
        public DecOutData() {
            mData = new byte[DEFLT_SMP_RATE * DEFLT_CHANNEL_NUM * 2];
            mSize = 0;
        }

        public byte[] mData = null;
        public int mSize = 0;
        public int mFlags = 0;
    };

    private static class CustomCircularBuffer<T> {

        private T[] buffer;
        private int tail;
        private int head;

        public CustomCircularBuffer(int n) {
            buffer = (T[]) new Object[n];
            tail = 1;
            head = 1;
        }

        public void add(T toAdd) {
            if (head != (tail - 1)) {
                buffer[head++] = toAdd;
            } else {
                throw new BufferOverflowException();
            }
            head = head % buffer.length;
        }

        public T get() {
            T t = null;
            int adjTail = tail > head ? tail - buffer.length : tail;
            if (adjTail < head) {
                t = (T) buffer[tail++];
                tail = tail % buffer.length;
            } else {
                throw new BufferUnderflowException();
            }
            return t;
        }


        public boolean empty() {
            if(head == tail)
                return  true;
            else
                return  false;
        }

        public boolean full() {
            if(tail > head) {
                if (head == (tail - 1))
                    return true;
                else
                    return false;
            }else
            {
                if (head == (tail + buffer.length - 1))
                    return true;
                else
                    return false;
            }
        }


        public int size() {
            int adjTail = tail > head ? tail - buffer.length : tail;
            return head - adjTail;
        }

        public String toString() {
            return "CustomCircularBuffer(size=" + buffer.length + ", head=" + head + ", tail=" + tail + ")";
        }
    }

    private boolean joinUninterruptibly(final Thread thread, long timeoutMs) {
        final long startTimeMs = SystemClock.elapsedRealtime();
        long timeRemainingMs = timeoutMs;
        boolean wasInterrupted = false;
        while (timeRemainingMs > 0) {
            try {
                thread.join(timeRemainingMs);
                break;
            } catch (InterruptedException e) {
                // Someone is asking us to return early at our convenience. We can't cancel this operation,
                // but we should preserve the information and pass it along.
                wasInterrupted = true;
                final long elapsedTimeMs = SystemClock.elapsedRealtime() - startTimeMs;
                timeRemainingMs = timeoutMs - elapsedTimeMs;
            }
        }
        // Pass interruption information along.
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    public class DecThread extends Thread {
        private volatile boolean keepLive = true;

        public DecThread(String name) {
            super(name);
        }

        @Override
        public void run () {
            while(keepLive) {
                if(mLive) {
                    if(!mDecBuff.full()) {
                        int ret = setInput();
                        if (ret == 0) {
                            DecOutData data = new DecOutData(mSampleRate, mChannelNum);
                            getOutput(data);
                            if (data.mFlags == 0) {
                                mDecBuff.add(data);
                            }
                        }
                    }
                    else {
                        Log.w(TAG, "circul buff full");
                    }
                }

                try {
                    sleep(3);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void stopthread() {
            keepLive = false;
        }
    }

    public MediaCodecAudioDecoder() {
        mMediaExtractor = new MediaExtractor();
        try {
            mMediaExtractor.setDataSource(mFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        getmimeType();
    }

    public MediaCodecAudioDecoder(String filepath) {
        mFilePath = filepath;
        Log.d(TAG, "got new audio file: "+mFilePath);

        mMediaExtractor = new MediaExtractor();
        try {
            mMediaExtractor.setDataSource(mFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        getmimeType();
    }

    private void getmimeType() {
        String mime=null;
        mSampleRate = DEFLT_SMP_RATE;
        mChannelNum = DEFLT_CHANNEL_NUM;

        for(int i=0; i<mMediaExtractor.getTrackCount(); i++) {
            mForamt = mMediaExtractor.getTrackFormat(i);
            mime = mForamt.getString(MediaFormat.KEY_MIME);

            if(mime.startsWith("audio")) {
                mSampleRate = mForamt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                mChannelNum = mForamt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                Log.i(TAG, "got mime type: " + mime);
                Log.i(TAG, "samprate: "+mSampleRate
                          +" channel: "+mChannelNum
                );
                mMediaExtractor.selectTrack(i);

                break;
            }
        }

        if(mime != null) {
            mMime = mime;
            mDecOutData = new DecOutData(mSampleRate, mChannelNum);
        }
        else
            mDecOutData = new DecOutData();
    }
    public int init() {
        //release last decoder first if any
        destroy();

        if(mMime == null) {
            Log.e(TAG, "no correct mime type");
            return -5;
        }
        try {
            mDecoder = MediaCodec.createDecoderByType(mMime);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(mDecoder == null) {
            Log.e(TAG, "create audio decoder failed");
            return -5;
        }

        mDecoder.configure(mForamt, null, null, 0);
        mDecoder.start();

        mInputBuffers = mDecoder.getInputBuffers();
        mOutputBuffers = mDecoder.getOutputBuffers();
        Log.i(TAG, "inputbuff num: "+mInputBuffers.length + "; outputbuff num: "+mOutputBuffers.length);

        mDecBuffInfo = new MediaCodec.BufferInfo();

        mDecBuff = new CustomCircularBuffer<>(CIRCULBUFF_NUM);

        mLive = true;

        m10msLen = mSampleRate * mChannelNum * 2 * 10 / 1000;

        mDecThread = new DecThread("AudioDecoding");
        mDecThread.start();

        /*
        try {
            mFout = new FileOutputStream("/sdcard/dumpAudio.pcm");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        */

        return 0;
    }

    public MediaFormat getFormat() {
        return mForamt;
    }

    public int setInput() {
        if(msrcEnd)
            return 0;

        int index = mDecoder.dequeueInputBuffer(TIMEOUT_US);
        if(index < 0) {
            Log.e(TAG, "can not get input buff");
            return -1;
        }

        mInputBuffers[index].clear();
        int size = mMediaExtractor.readSampleData(mInputBuffers[index], 0);
        long sampleTime = 0;
        if(size < 0) {
            Log.w(TAG, "audio src input reach end");
            msrcEnd = true;
            size = 0;
        }
        else {
            sampleTime = mMediaExtractor.getSampleTime();
        }
        Log.v(TAG, "setinput=> time: "+sampleTime+" size: "+size);
        mDecoder.queueInputBuffer(index, 0,//offset set to 0
                                  size, sampleTime,
                                  msrcEnd ? MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);

        if(!msrcEnd) {
            mMediaExtractor.advance();
        }

        return 0;
    }

    public int getOutput(DecOutData outdata) {
        if(moutEnd)
            return 0;

        int ret = mDecoder.dequeueOutputBuffer(mDecBuffInfo, TIMEOUT_US);
        if(ret >= 0) {
            int index = ret;
            if(outdata.mData.length < mDecBuffInfo.size) {
                Log.e(TAG, "dec out length exeeds! decOutlen: "+mDecBuffInfo.size+", datalen: "+outdata.mData.length);
                return -1;
            }

            outdata.mFlags = 0;
            outdata.mSize = mDecBuffInfo.size;
            mOutputBuffers[index].get(outdata.mData, 0, outdata.mSize);
            mOutputBuffers[index].clear();

            mDecoder.releaseOutputBuffer(index, false);
            if(mFout != null) {
                try {
                    mFout.write(outdata.mData, 0, outdata.mSize);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            Log.v(TAG, "getoutput=> time: "+mDecBuffInfo.presentationTimeUs+" size: "+outdata.mSize);

            if((mDecBuffInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                moutEnd = true;
                outdata.mFlags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                Log.i(TAG, "dec output reach end");
            }

        }
        else if(ret == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mOutputBuffers = mDecoder.getOutputBuffers();
            outdata.mFlags = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED;
            Log.e(TAG, "audio dec output buff change");
            return ret;
        }
        else if(ret == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mForamt = mDecoder.getOutputFormat();
            mSampleRate = mForamt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            mChannelNum = mForamt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            outdata.mFlags = MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            m10msLen = mSampleRate * mChannelNum * 2 * 10 / 1000;
            return ret;
        }
        return 0;
    }

    public DecOutData getDecData_dec() {
        if(mDecoder == null)
            return null;

        if(msrcEnd == true && moutEnd == true) {
            Log.d(TAG, "decout end, msrcEnd:"+msrcEnd+" moutEnd:"+moutEnd);
            mDecOutData.mFlags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            return mDecOutData;
        }

        int ret = setInput();
        if(ret == 0) {
            getOutput(mDecOutData);
        }
        return mDecOutData;
    }

    public DecOutData getDecData() {
        if(!mDecBuff.empty()) {
            return mDecBuff.get();
        }
        else {
            Log.e(TAG, "circul buff empty");
            return null;
        }
    }

    public void destroy() {
        if(mDecThread != null) {
            mDecThread.stopthread();
            if (!joinUninterruptibly(mDecThread, 2000)) {
                Log.e(TAG, "Join of AudioDecodeJavaThread timed out");
            }
            mDecThread = null;
        }
        if(mDecoder != null) {
            mLive = false;
            mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
        }
    }

    public void restart() {
        if(mMediaExtractor != null) {
            mMediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            msrcEnd = false;
            moutEnd = false;
        }

        //init();
    }

}
