package example.joe.helloworld;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.visualon.utils.MediaCodecAudioDecoder;
import com.visualon.utils.PCMPlayer;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import static java.lang.Thread.sleep;

public class MainActivity extends AppCompatActivity {

    final static String TAG = "AudioPlay";
    final int CHANNEL_MONO = 1;
    final int CHANNEL_STEREO = 2;
    /*
    static {
        System.loadLibrary("voVidDec"); //hellojni
    }
    */

    //public native String  stringFromJNI();
    private Object mLock = new Object();
    private boolean mPlay = false;
    private int mSamplerate = 44100;
    private int mChannel = AudioFormat.CHANNEL_OUT_STEREO;
    private int mBitformat = AudioFormat.ENCODING_PCM_16BIT;

    public void playPCMMusic(PCMPlayer player) throws FileNotFoundException {
        synchronized (mLock) {
            if(!mPlay)
                return;
        }
        if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
            //File sdCardDir = Environment.getExternalStorageDirectory();//获取SDCard目录
            //FileInputStream fin = new FileInputStream("/sdcard/music_2ch_48khz.pcm");
            FileInputStream fin = new FileInputStream("/sdcard/dumpAudio.pcm");
            int buffsize = player.getbuffersize();
            Log.d(TAG, "start playing... [audio track size needed: " + buffsize + "]");
            byte[] data = new byte[buffsize];
            int readlen = 0;

            try {
                while ((readlen = fin.read(data)) > 0) {
                    Log.v(TAG, "read audio len: "+readlen);
                    synchronized (mLock) {
                        if (mPlay) {
                            player.writeSample(data, readlen);
                        } else {
                            Log.w(TAG, "play interrupted");
                            break;
                        }
                    }
                }
                fin.close();
                synchronized (mLock) {
                    mPlay = false;
                }
            }catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void playEncodedMusic(PCMPlayer player, MediaCodecAudioDecoder decoder) throws IOException {
        synchronized (mLock) {
            if(!mPlay)
                return;
        }

        MediaCodecAudioDecoder.DecOutData decOutData;
        while(true) {
            decOutData = decoder.getDecData();
            if(decOutData.mFlags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                Log.i(TAG, "audio file end now");
                synchronized (mLock) {
                    mPlay = false;
                }
                break;
            }
            else if(decOutData.mFlags == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat auidoFmt = decoder.getFormat();
                int samplerate = auidoFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channel = auidoFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if ((mSamplerate != samplerate) || (CHANNEL_STEREO != channel)) {
                    Log.d(TAG, "format change=>orig smpr: "+mSamplerate+" mChannel: "+mChannel
                                +"now smpr: "+samplerate+"mChannel: "+channel);
                    mSamplerate = samplerate;
                    if(channel == CHANNEL_MONO)
                        mChannel = AudioFormat.CHANNEL_OUT_MONO;
                    player.formatChange(mSamplerate, mChannel, mBitformat);
                }
                continue;
            }
            else if(decOutData.mFlags == 0) {
                synchronized (mLock) {
                    if (mPlay) {
                        player.writeSample(decOutData.mData, decOutData.mSize);
                    } else {
                        Log.w(TAG, "play interrupted");
                        break;
                    }
                }
            }
            else {
                synchronized (mLock) {
                    if(!mPlay)
                        break;
                }
            }
        }
    }

    public class testc {
        public testc(int a) {
            ma = a;
        }
        public testc() {
            ma = 0;
        }
        int ma;
    }

    private testc mc;
    private String ms;
    public void test(testc c) {
        c.ma = -1;
    }
    public void teststring (String s) {
        ms = s;
        s.concat("test joe");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mc = new testc();
        String ts = new String("heiehi");
        teststring(ts);
        Log.d(TAG,"now string member: "+ms+"; string orig: "+ts);
        ts = new String("new string haha");
        Log.d(TAG,"now string member: "+ms+"; string orig: "+ts);
        testc c = new testc(2);
        test(c);
        Log.d(TAG, "now a: "+c.ma+" ; addr:"+c);

        final PCMPlayer audioPlayer = new PCMPlayer(mSamplerate, mChannel, mBitformat);
        final MediaCodecAudioDecoder audioDecoder = new MediaCodecAudioDecoder();
        if(audioDecoder.init() != 0) {
            audioDecoder.destroy();
        }
        else {
            MediaFormat auidoFmt = audioDecoder.getFormat();
            int samplerate = auidoFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channel = auidoFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if ((mSamplerate != samplerate) || (CHANNEL_STEREO != channel)) {
                Log.d(TAG, "format change=>orig smpr: "+mSamplerate+" mChannel: "+mChannel
                        +"->now smpr: "+samplerate+" mChannel: "+channel);
                mSamplerate = samplerate;
                if(channel == CHANNEL_MONO)
                    mChannel = AudioFormat.CHANNEL_OUT_MONO;
                audioPlayer.formatChange(mSamplerate, mChannel, mBitformat);
            }
        }


        new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        //playPCMMusic(audioPlayer);
                        playEncodedMusic(audioPlayer, audioDecoder);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        sleep(20);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Button startbtn = (Button)findViewById(R.id.btn_start);
        startbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Start to play", Toast.LENGTH_SHORT).show();
                synchronized (mLock) {
                    audioPlayer.start();
                    //audioDecoder.restart();
                    mPlay = true;
                }
            }
        });

        Button stopbtn = (Button)findViewById(R.id.btn_stop);
        stopbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (mLock) {
                    mPlay = false;
                    audioPlayer.stop();
                }
                Toast.makeText(MainActivity.this, "Stop playing", Toast.LENGTH_SHORT).show();
            }
        });

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
