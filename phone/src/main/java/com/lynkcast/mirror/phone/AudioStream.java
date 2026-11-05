package com.lynkcast.mirror.phone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * 内录手机正在播放的声音（游戏/媒体），编成 AAC，从 TCP 7803 发给车机。
 *
 * 用 AudioPlaybackCapture：抓的是"系统播放出来的音频"，不是麦克风，
 * 所以游戏 BGM、音效都能过去，也不会把车里说话录进去。
 *
 * 端口 7803，握手（大端）：
 *   int32 sampleRate
 *   int32 channels
 *   int32 csdLen + csd（AudioSpecificConfig）
 * 之后：
 *   int32 length + byte[length]（AAC 裸帧，无 ADTS 头）
 */
public class AudioStream {

    public static final int PORT = 7803;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BITRATE = 128000;
    private static final String MIME = "audio/mp4a-latm";

    private final MediaProjection projection;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread captureThread;

    private volatile DataOutputStream out;
    private volatile Socket clientSocket;
    private volatile byte[] csd = new byte[0];

    public volatile String state = "音频未启动";

    public AudioStream(Context context, MediaProjection projection) {
        this.projection = projection;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "cm-audio-accept");
        acceptThread.start();
        captureThread = new Thread(this::captureLoop, "cm-audio-enc");
        captureThread.start();
    }

    public void stop() {
        running = false;
        closeClient();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        Thread t = captureThread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        serverSocket = null;
        state = "音频未启动";
    }

    private void closeClient() {
        DataOutputStream o = out;
        out = null;
        try {
            if (o != null) {
                o.close();
            }
        } catch (Exception ignored) {
        }
        Socket s = clientSocket;
        clientSocket = null;
        try {
            if (s != null) {
                s.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void acceptLoop() {
        try {
            ServerSocket ss = new ServerSocket(PORT);
            ss.setReuseAddress(true);
            serverSocket = ss;
            while (running) {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                try {
                    s.setSendBufferSize(128 * 1024);
                } catch (Exception ignored) {
                }
                DataOutputStream o = new DataOutputStream(
                        new BufferedOutputStream(s.getOutputStream(), 64 * 1024));

                long deadline = System.currentTimeMillis() + 5000;
                while (running && csd.length == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                byte[] c = csd;
                o.writeInt(SAMPLE_RATE);
                o.writeInt(CHANNELS);
                o.writeInt(c.length);
                o.write(c);
                o.flush();

                closeClient();
                out = o;
                clientSocket = s;
                state = "音频已连接";
            }
        } catch (Exception ignored) {
        }
    }

    private void captureLoop() {
        if (Build.VERSION.SDK_INT < 29) {
            state = "系统版本低于 Android 10，无法内录";
            return;
        }
        AudioRecord record = null;
        MediaCodec encoder = null;
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                minBuf = 4096;
            }

            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 8192))
                    .build();

            MediaFormat f = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                state = "内录启动失败（可能未授予录音权限，或被系统策略拦下）";
                return;
            }
            state = "内录中";

            byte[] pcm = new byte[minBuf * 2];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (running) {
                int read = record.read(pcm, 0, pcm.length);
                if (read <= 0) {
                    continue;
                }
                int inIdx = encoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(pcm, 0, read);
                    }
                    encoder.queueInputBuffer(inIdx, 0, read,
                            System.nanoTime() / 1000, 0);
                }
                drainEncoder(encoder, info);
            }
        } catch (Exception e) {
            state = "音频异常：" + e.getClass().getSimpleName();
        } finally {
            try {
                if (record != null) {
                    record.stop();
                    record.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo info) {
        while (true) {
            int outIdx = encoder.dequeueOutputBuffer(info, 0);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = encoder.getOutputFormat();
                ByteBuffer b = of.getByteBuffer("csd-0");
                if (b != null) {
                    byte[] arr = new byte[b.remaining()];
                    b.get(arr);
                    csd = arr;
                }
                continue;
            }
            if (outIdx < 0) {
                return;
            }
            ByteBuffer ob = encoder.getOutputBuffer(outIdx);
            if (ob != null && info.size > 0) {
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                ob.get(data);
                writeFrame(data);
            }
            encoder.releaseOutputBuffer(outIdx, false);
        }
    }

    private void writeFrame(byte[] data) {
        DataOutputStream o = out;
        if (o == null) {
            return;
        }
        try {
            o.writeInt(data.length);
            o.write(data);
            o.flush();
        } catch (Exception e) {
            closeClient();
        }
    }
}