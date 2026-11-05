package com.lynkcast.mirror.car;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * 连手机端 7803 拿 AAC 音频，解码后用 AudioTrack 播出来。
 *
 * 握手（大端）：int32 sampleRate / int32 channels / int32 csdLen + csd
 * 之后：int32 length + byte[length]
 *
 * 音频是"锦上添花"通道：连不上或出错都不影响画面，所以这里全部静默处理。
 */
public class AudioPlayer {

    public static final int PORT = 7803;

    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;
    private volatile AudioTrack track;

    public void connect(final String host) {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop(host);
            }
        }, "cm-audio");
        thread.start();
    }

    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    private void runLoop(String host) {
        DataInputStream in = null;
        MediaCodec decoder = null;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, PORT), 4000);
            s.setTcpNoDelay(true);
            socket = s;
            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));

            int sampleRate = in.readInt();
            int channels = in.readInt();
            int csdLen = in.readInt();
            byte[] csd = new byte[Math.max(0, csdLen)];
            if (csdLen > 0) {
                in.readFully(csd);
            }
            if (sampleRate <= 0 || channels <= 0) {
                return;
            }

            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            if (csd.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            decoder.configure(format, null, null, 0);
            decoder.start();

            int channelConfig = channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                minBuf = 4096;
            }
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    channelConfig, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf * 4, 8192), AudioTrack.MODE_STREAM);
            t.play();
            track = t;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long pts = 0;

            while (running) {
                int len = in.readInt();
                if (len <= 0 || len > 1024 * 1024) {
                    break;
                }
                byte[] data = new byte[len];
                in.readFully(data);

                int inIdx = decoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIdx);
                    if (buf != null) {
                        buf.clear();
                        buf.put(data);
                    }
                    decoder.queueInputBuffer(inIdx, 0, data.length, pts, 0);
                    pts += 21000;
                }
                drainPcm(decoder, info);
            }
        } catch (Exception ignored) {
            // 音频失败不影响画面
        } finally {
            running = false;
            socket = null;
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            AudioTrack t = track;
            if (t != null) {
                try {
                    t.stop();
                    t.release();
                } catch (Exception ignored) {
                }
                track = null;
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void drainPcm(MediaCodec decoder, MediaCodec.BufferInfo info) {
        AudioTrack t = track;
        if (t == null) {
            return;
        }
        while (true) {
            int outIdx = decoder.dequeueOutputBuffer(info, 0);
            if (outIdx >= 0) {
                ByteBuffer out = decoder.getOutputBuffer(outIdx);
                if (out != null && info.size > 0) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    try {
                        t.write(out, info.size, AudioTrack.WRITE_BLOCKING);
                    } catch (Exception ignored) {
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            } else {
                break;
            }
        }
    }
}