package com.visualon.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import static android.os.SystemClock.sleep;


/**
 * Created by david on 26/05/2017.
 */

public class MediaCodecAudioDecoder {

    private static final String TAG = "MediaCodecAudioDecoder";
    private static final int TIMEOUT_US = 5000;
    private static final int DEFLT_SMP_RATE = 48000;
    private static final int DEFLT_CHANNEL_NUM = 2;
    private static final int DEFLT_SAMPLE_BIT = 16;
    private static final long THREAD_JOIN_TIMEOUT_MS = 2000;
    private static final int CIRCUL_BUFF_NUM = 20;
    private static final int BITS_PER_SAMPLE = 16;

    private MediaCodec mDecoder;
    private MediaExtractor mMediaExtractor;
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private MediaCodec.BufferInfo mDecBuffInfo;
    private boolean msrcEnd = false;
    private boolean moutEnd = false;
    private MediaFormat mDecForamt;
    private MediaFormat mSrcForamt;
    private int mSampleRate = DEFLT_SMP_RATE;
    private int mChannelNum = DEFLT_CHANNEL_NUM;
    private String mMime = null;
    private FileOutputStream mFout = null;
    private DecOutData mDecOutData;
    private boolean mDecoderStarted = false;
    private Object mLock = new Object();
    private AudioDecThread mDecThread = null;

    private CustomCircularBuffer<DecOutData> m10msDataBuff;

    private String mFilePath = "/sdcard/music.mp3";

    public final class DecOutData {
        public void setData(@NonNull byte[] src, int srcoffset, int size) {
            if(mData != null) {
                System.arraycopy(src, srcoffset, mData, mSize, size < src.length ? size : src.length);
                mSize = mSize + ((size < src.length) ? size : src.length);
            }
        }

        public DecOutData(int samplerate, int channel) {
            mSize = 0;
            mData = new byte[samplerate * channel * DEFLT_SAMPLE_BIT / 8];
        }
        public DecOutData() {
            mSize = 0;
            mData = new byte[DEFLT_SMP_RATE * DEFLT_CHANNEL_NUM * DEFLT_SAMPLE_BIT / 8];
        }

        public DecOutData(int len) {
            if(len > 0) {
                mData = new byte[len];
                mSize = 0;
            }
            else {
                mData = null;
                mSize = 0;
            }
        }

        public byte[] mData = null;
        public int mSize = 0;
        public int mFlags = 0;
    }

    private void dumpData(DecOutData data) {
        if(mFout != null) {
            try {
                mFout.write(data.mData, 0, data.mSize);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class CustomCircularBuffer<T> {

        private T[] buffer;
        private volatile int tail;
        private volatile int head;
        private Object mBuffLock = new Object();

        public CustomCircularBuffer(int n) {
            buffer = (T[]) new Object[n];
            tail = 1;
            head = 1;
        }

        public void add(T toAdd) {
            synchronized (mBuffLock) {
                if (head != (tail - 1)) {
                    buffer[head++] = toAdd;
                } else {
                    throw new BufferOverflowException();
                }
                head = head % buffer.length;
            }
        }

        public T get() {
            T t = null;
            synchronized (mBuffLock) {
                int adjTail = tail > head ? tail - buffer.length : tail;
                if (adjTail < head) {
                    t = (T) buffer[tail++];
                    tail = tail % buffer.length;
                } else {
                    throw new BufferUnderflowException();
                }
            }
            return t;
        }


        public boolean empty() {
            //synchronized (mBuffLock) {
            {
                if (head == tail)
                    return true;
                else
                    return false;
            }
        }

        public boolean full() {
            //synchronized (mBuffLock) {
            {
                if (tail > head) {
                    if (head == (tail - 1))
                        return true;
                    else
                        return false;
                } else {
                    if (head == (tail + buffer.length - 1))
                        return true;
                    else
                        return false;
                }
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

    private class Process10msData {
        private final int m1sDatalen = mSampleRate * mChannelNum * BITS_PER_SAMPLE / 8;
        private final int m10msDatalen = m1sDatalen * 10 / 1000;
        private volatile boolean mLive = true;
        private DecOutData mTmpBuff = null;
        private ByteBuffer mNewData;
        private int mNewDataLen = 0;
        private volatile boolean mNewDataReady = false;
        private FileOutputStream mFout10ms = null;

        private void dumpData(DecOutData data) {
            if(mFout10ms != null) {
                try {
                    mFout10ms.write(data.mData, 0, data.mSize);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public ByteBuffer deepCopy(ByteBuffer source, ByteBuffer target) {

            int sourceP = source.position();
            int sourceL = source.limit();

            if (null == target) {
                target = ByteBuffer.allocate(source.remaining());
            }
            else {
                target.position(0);
            }
            target.put(source);
            target.flip();

            source.position(sourceP);
            source.limit(sourceL);
            return target;
        }

        public ByteBuffer deepCopy(byte[] source, int len, ByteBuffer target) {
            if (null == target) {
                target = ByteBuffer.allocate(len);
            }
            else {
                target.position(0);
            }
            target.put(source, 0, len);
            target.flip();

            return target;
        }

        private boolean waitForFreeBuff(long timeoutMs) {
            if(timeoutMs <= 0)
                return true;

            final long startTimeMs = SystemClock.elapsedRealtime();
            long timeRemainingMs = timeoutMs;
            boolean timeout = false;
            Log.w(TAG, "waiting for free buff");
            while (m10msDataBuff.full()) {
                if(!mLive) {
                    break;
                }

                timeRemainingMs = timeoutMs - (SystemClock.elapsedRealtime() - startTimeMs);
                if(timeRemainingMs <= 0) {
                    timeout = true;
                    break;
                }

                sleep(3);
            }

            return timeout;
        }

        public Process10msData() {
            Log.d(TAG, "10ms data len:"+m10msDatalen);
            mNewData = mNewData.allocate(m1sDatalen);
            mNewDataReady = false;

            if(false) {
                try {
                    mFout10ms = new FileOutputStream("/sdcard/dumpAudio10ms.pcm");
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }

        private void process() {
            int srcOffset = 0;
            if(mTmpBuff != null) {
                int lenLeft = m10msDatalen - mTmpBuff.mSize;
                Log.v(TAG, "len left for last buff: "+lenLeft);
                mTmpBuff.setData(mNewData.array(), srcOffset, lenLeft);

                //when buffer is full during one decoding frame, we need to wait
                if(m10msDataBuff.full()) {
                    //if timeout, we remove one buffer manually
                    if(waitForFreeBuff(0))
                        m10msDataBuff.get();
                }

                m10msDataBuff.add(mTmpBuff);
                dumpData(mTmpBuff);

                //create new buff for next adding
                mTmpBuff = new DecOutData(m10msDatalen);

                srcOffset = lenLeft;
                if(mNewDataLen - srcOffset < m10msDatalen) {
                    mTmpBuff.setData(mNewData.array(), srcOffset, mNewDataLen - srcOffset);
                    Log.v(TAG, "new 10ms data process done");
                    return;
                }
            }
            else {
                mTmpBuff = new DecOutData(m10msDatalen);
                Log.d(TAG, "create one new buff");
            }

            while (srcOffset < mNewDataLen - m10msDatalen) {
                mTmpBuff.setData(mNewData.array(), srcOffset, m10msDatalen);

                //when buffer is full during one decoding frame, we need to wait
                if(m10msDataBuff.full()) {
                    //if timeout, we remove one buffer manually
                    if(waitForFreeBuff(0))
                        m10msDataBuff.get();
                }

                m10msDataBuff.add(mTmpBuff);
                dumpData(mTmpBuff);

                //create new buff for next adding
                mTmpBuff = new DecOutData(m10msDatalen);
                srcOffset = srcOffset + m10msDatalen;
            }

            //for data left
            Log.v(TAG, "data last len left:"+(mNewDataLen - srcOffset));
            mTmpBuff.setData(mNewData.array(), srcOffset, mNewDataLen - srcOffset);
            Log.v(TAG, "new 10ms data process done");
        }

        public void newDataReady(ByteBuffer buffer, int len) {
            Log.d(TAG, "new data ready for 10ms processing, len:"+len);
            mNewData.clear();
            mNewData = deepCopy(buffer, mNewData);
            mNewDataLen = len;
            mNewDataReady = true;
            process();
        }

        public void newDataReady(byte[] buffer, int len) {
            Log.d(TAG, "new data ready for 10ms processing, len:"+len);
            mNewData.clear();
            mNewData = deepCopy(buffer,len,mNewData);
            mNewDataLen = len;
            mNewDataReady = true;
            process();
        }
    }

    public static boolean joinUninterruptibly(final Thread thread, long timeoutMs) {
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

    private class AudioDecThread extends Thread {
        private final int m10msDatalen = mSampleRate * mChannelNum * DEFLT_SAMPLE_BIT / 8 * 10 / 1000;
        private volatile boolean mLive = true;
        private DecOutData mTmpBuff = null;

        private void waitForFreeBuff() {
            while (m10msDataBuff.full()) {
                if(!mLive)
                    break;

                try {
                    sleep(3);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void run() {
            while(mLive) {
                synchronized (mLock) {
                    if (!mDecoderStarted)
                        continue;
                }
                if (!m10msDataBuff.full()) {
                    int ret = setInput();
                    if (ret == 0) {
                        getOutput(mDecOutData);
                        if (mDecOutData.mFlags == 0 || mDecOutData.mFlags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            int srcOffset = 0;
                            if(mTmpBuff != null) {
                                int lenLeft = m10msDatalen - mTmpBuff.mSize;
                                Log.d(TAG, "len left for last buff: "+lenLeft);
                                mTmpBuff.setData(mDecOutData.mData, srcOffset, lenLeft);
                                m10msDataBuff.add(mTmpBuff);
                                //dumpData(mTmpBuff);

                                //when buffer is full during one decoding frame, we need to wait
                                if(m10msDataBuff.full()) {
                                    waitForFreeBuff();
                                }

                                //create new buff for next adding
                                mTmpBuff = new DecOutData(m10msDatalen);

                                srcOffset = lenLeft;
                                if(mDecOutData.mSize - srcOffset < m10msDatalen) {
                                    mTmpBuff.setData(mDecOutData.mData, srcOffset, mDecOutData.mSize - srcOffset);
                                    continue;
                                }
                            }
                            else {
                                mTmpBuff = new DecOutData(m10msDatalen);
                                Log.d(TAG, "create one new buff");
                            }

                            while (srcOffset < mDecOutData.mSize - m10msDatalen) {
                                mTmpBuff.setData(mDecOutData.mData, srcOffset, m10msDatalen);
                                mTmpBuff.mFlags = mDecOutData.mFlags;
                                m10msDataBuff.add(mTmpBuff);
                                //dumpData(mTmpBuff);

                                //when buffer is full during one decoding frame, we need to wait
                                if(m10msDataBuff.full()) {
                                    waitForFreeBuff();
                                }

                                //create new buff for next adding
                                mTmpBuff = new DecOutData(m10msDatalen);
                                srcOffset = srcOffset + m10msDatalen;
                            }

                            //for data left
                            mTmpBuff.setData(mDecOutData.mData, srcOffset, mDecOutData.mSize - srcOffset);

                            if(mDecOutData.mFlags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                mTmpBuff.mFlags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                //when buffer is full during one decoding frame, we need to wait
                                if(m10msDataBuff.full()) {
                                    waitForFreeBuff();
                                }
                                m10msDataBuff.add(mTmpBuff);
                                mLive = false;
                            }
                        }
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
            mLive = false;
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

        for(int i=0; i<mMediaExtractor.getTrackCount(); i++) {
            mSrcForamt = mMediaExtractor.getTrackFormat(i);
            mime = mSrcForamt.getString(MediaFormat.KEY_MIME);

            if(mime.startsWith("audio")) {
                mSampleRate = mSrcForamt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                mChannelNum = mSrcForamt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                Log.i(TAG, "got mime type: " + mime);
                Log.i(TAG, "samprate: "+mSampleRate
                          +" channel: "+mChannelNum
                );
                mMediaExtractor.selectTrack(i);

                break;
            }
        }

        mDecForamt = mSrcForamt;

        if(mime != null) {
            mMime = mime;
            if(mDecOutData == null) {
                mDecOutData = new DecOutData(mSampleRate, mChannelNum);
            }
        }
        else {
            if(mDecOutData == null) {
                mDecOutData = new DecOutData();
            }
        }
    }
    public int init() {
        //release last decoder first if any
        destroy();

        if(mMime == null) {
            Log.e(TAG, "no correct mime type");
            return -5;
        }
        try {
            Log.d(TAG, "create decoder with mime: "+mMime);
            mDecoder = MediaCodec.createDecoderByType(mMime);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(mDecoder == null) {
            Log.e(TAG, "create audio decoder failed");
            return -5;
        }

        synchronized (mLock) {
            if (!mDecoderStarted) {
                Log.d(TAG, "config decoder with mime: "+mSrcForamt.getString(MediaFormat.KEY_MIME));
                mDecoder.configure(mSrcForamt, null, null, 0);
                mDecoder.start();

                mInputBuffers = mDecoder.getInputBuffers();
                mOutputBuffers = mDecoder.getOutputBuffers();
                Log.i(TAG, "inputbuff num: " + mInputBuffers.length + "; outputbuff num: " + mOutputBuffers.length);
                mDecoderStarted = true;
            }
        }

        mDecBuffInfo = new MediaCodec.BufferInfo();

        m10msDataBuff = new CustomCircularBuffer<>(CIRCUL_BUFF_NUM);


        if(false) {
            try {
                mFout = new FileOutputStream("/sdcard/dumpAudioBuff.pcm");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }


        return 0;
    }

    public MediaFormat getFormat() {
        return mDecForamt;
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
            mDecForamt = mDecoder.getOutputFormat();
            mSampleRate = mDecForamt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            mChannelNum = mDecForamt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            outdata.mFlags = MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            return ret;
        }
        return 0;
    }

    public DecOutData getDecData() {
        if(mDecoder == null)
            return null;


        if(msrcEnd == true && moutEnd == true) {
            Log.d(TAG, "decout end, msrcEnd:"+msrcEnd+" moutEnd:"+moutEnd);
            mDecOutData.mFlags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            return null;
        }

        synchronized (mLock) {

            int ret = setInput();
            if (ret == 0) {
                getOutput(mDecOutData);
            }
            return mDecOutData;
        }
    }

    public DecOutData getDecData_Buff() {
        if(m10msDataBuff != null && !m10msDataBuff.empty()) {
            return m10msDataBuff.get();
        }
        else
            return null;
    }

    public void destroy() {
        if(mDecThread != null) {
            mDecThread.stopthread();
            if (!joinUninterruptibly(mDecThread, THREAD_JOIN_TIMEOUT_MS)) {
                Log.e(TAG, "Join of AudioRecordJavaThread timed out");
            }
            mDecThread = null;
        }

        try {
            synchronized (mLock) {
                if (mDecoder != null) {
                    mDecoder.stop();
                    mDecoder.release();
                    mDecoder = null;
                }
                mDecoderStarted = false;
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void start() {
        if(mDecoder == null)
            return;

        if(msrcEnd == true && moutEnd == true && mMediaExtractor != null) {
            init();
            mMediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            msrcEnd = false;
            moutEnd = false;
        }

        synchronized (mLock) {
            if (!mDecoderStarted) {
                mDecoder.configure(mSrcForamt, null, null, 0);
                mDecoder.start();

                mInputBuffers = mDecoder.getInputBuffers();
                mOutputBuffers = mDecoder.getOutputBuffers();
                Log.i(TAG, "inputbuff num: " + mInputBuffers.length + "; outputbuff num: " + mOutputBuffers.length);
                mDecoderStarted = true;
            }
        }

        if(mDecThread == null) {
            mDecThread = new AudioDecThread();
        }
        mDecThread.start();
    }

    public void stop() {
        if(mDecThread != null) {
            mDecThread.stopthread();
            if (!joinUninterruptibly(mDecThread, THREAD_JOIN_TIMEOUT_MS)) {
                Log.e(TAG, "Join of AudioRecordJavaThread timed out");
            }
            mDecThread = null;
        }
        if(mDecoder != null) {
            synchronized (mLock) {
                mDecoder.stop();
                mDecoderStarted = false;
            }
        }
    }

}
