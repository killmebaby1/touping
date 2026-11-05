package com.lynkcast.mirror.car;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.nio.ByteBuffer;

/**
 * 连接手机端 7801 端口，拉裸 H.264 流，用 MediaCodec 硬解后渲染到 SurfaceView。
 */
public class MirrorClient {

    public static final int PORT = 7801;
    public static final int DISCOVERY_PORT = 7802;

    /**
     * UDP 广播找手机端，返回手机在当前网段的 IP，找不到返回 null。
     * 用应答包的源地址定位，手机端不需要自己猜热点 IP。
     */
    public static String discover(int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);
            byte[] req = "CARMIRROR?".getBytes();
            DatagramPacket packet = new DatagramPacket(req, req.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(packet);

            byte[] buffer = new byte[128];
            while (true) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String host = reply.getAddress().getHostAddress();
                if (host != null && !host.startsWith("0.")) {
                    return host;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return null;
    }

    /** 车机自己的 IPv4（带网卡名），排查网段用 */
    public static String describeLocalAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.indexOf(58) >= 0) {
                        continue;
                    }
                    int pct = ip.indexOf(37);
                    if (pct > 0) {
                        ip = ip.substring(0, pct);
                    }
                    if (ip.startsWith("0.") || "127.0.0.1".equals(ip)) {
                        continue;
                    }
                    sb.append(ni.getName()).append("  ").append(ip).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString().trim();
    }

    public interface Listener {
        void onState(String state);

        void onVideoSize(int width, int height);

        void onDisconnected(String reason);
    }

    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;

    public boolean isRunning() {
        return running;
    }

    public void connect(final String host, final int port, final Surface surface, final Listener listener) {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop(host, port, surface, listener);
            }
        }, "cm-decode");
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
    }

    private void runLoop(String host, int port, Surface surface, Listener listener) {
        DataInputStream in = null;
        MediaCodec codec = null;
        try {
            listener.onState("连接中…");
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setReceiveBufferSize(512 * 1024);
            s.setTcpNoDelay(true);
            socket = s;

            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256 * 1024));

            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!(magic[0] == 'C' && magic[1] == 'M' && magic[2] == 'S' && magic[3] == 'H')) {
                throw new IOException("对方不是车镜手机端");
            }

            int version = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            byte[] csd0 = readBytes(in);
            byte[] csd1 = readBytes(in);

            if (width <= 0 || height <= 0) {
                throw new IOException("分辨率无效");
            }

            listener.onVideoSize(width, height);
            listener.onState("已连接 " + width + "x" + height + " v" + version);

            codec = createDecoder(width, height, csd0, csd1, surface);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long pts = 0;
            boolean firstFrame = true;

            while (running) {
                int flags = in.readInt();
                int length = in.readInt();
                if (length <= 0 || length > 16 * 1024 * 1024) {
                    throw new IOException("帧长度异常 " + length);
                }
                byte[] data = new byte[length];
                in.readFully(data);

                if (flags == 2) {
                    // 手机转屏了：按新尺寸重建解码器
                    int w2 = 0;
                    int h2 = 0;
                    byte[] s0 = new byte[0];
                    byte[] s1 = new byte[0];
                    try {
                        ByteBuffer bb = ByteBuffer.wrap(data);
                        w2 = bb.getInt();
                        h2 = bb.getInt();
                        int l0 = bb.getInt();
                        s0 = new byte[l0];
                        bb.get(s0);
                        int l1 = bb.getInt();
                        s1 = new byte[l1];
                        bb.get(s1);
                    } catch (Exception ignored) {
                    }
                    if (w2 > 0 && h2 > 0) {
                        MediaCodec old = codec;
                        codec = createDecoder(w2, h2, s0, s1, surface);
                        try {
                            if (old != null) {
                                old.stop();
                                old.release();
                            }
                        } catch (Exception ignored) {
                        }
                        pts = 0;
                        firstFrame = true;
                        listener.onVideoSize(w2, h2);
                        listener.onState("已切到 " + w2 + "x" + h2);
                    }
                    continue;
                }

                int inputIndex = codec.dequeueInputBuffer(20000);
                if (inputIndex >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                    if (buffer != null) {
                        buffer.clear();
                        buffer.put(data);
                        int bufferFlags = (flags == 1) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        codec.queueInputBuffer(inputIndex, 0, data.length, pts, bufferFlags);
                        pts += 33333;
                    }
                }

                if (drain(codec, info)) {
                    if (firstFrame) {
                        firstFrame = false;
                        listener.onState("投屏中");
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                listener.onDisconnected(describe(e));
            }
        } finally {
            running = false;
            socket = null;
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (Exception ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

private static MediaCodec createDecoder(int width, int height,
                                            byte[] csd0, byte[] csd1,
                                            Surface surface) {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        if (csd0 != null && csd0.length > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            if (csd1 != null && csd1.length > 0) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            }
        }
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, surface, null, 0);
            codec.start();
            return codec;
        } catch (Exception e) {
            throw new RuntimeException("创建解码器失败：" + e);
        }
    }

    private boolean drain(MediaCodec codec, MediaCodec.BufferInfo info) {
        boolean rendered = false;
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, 0);
            if (outIndex >= 0) {
                boolean doRender = info.size > 0;
                codec.releaseOutputBuffer(outIndex, doRender);
                rendered |= doRender;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            } else {
                break;
            }
        }
        return rendered;
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1024 * 1024) {
            throw new IOException("csd 长度异常 " + len);
        }
        byte[] out = new byte[len];
        in.readFully(out);
        return out;
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时，检查手机 IP 和 Wi-Fi";
        }
        if (e instanceof java.net.ConnectException) {
            return "连接被拒绝，确认手机端已点“开始投屏”";
        }
        return msg == null ? e.getClass().getSimpleName() : msg;
    }
}
