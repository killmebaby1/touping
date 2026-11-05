package com.lynkcast.mirror.phone;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 推流服务端 + UDP 自动发现。
 *
 * TCP 7801，连接后先发头部（全部大端）：
 *   byte[4] magic = "CMSH"
 *   int32   version = 1
 *   int32   width
 *   int32   height
 *   int32   csd0Len + csd0 (SPS)
 *   int32   csd1Len + csd1 (PPS)
 *
 * 之后统一为：int32 flags / int32 length / byte[length]
 *   flags = 0  普通视频帧，payload 是 Annex-B H.264
 *   flags = 1  关键视频帧
 *   flags = 2  参数更新：int32 w, int32 h, int32 csd0Len+csd0, int32 csd1Len+csd1
 *              （手机转屏、游戏进横屏后编码尺寸变了就发这一条）
 *
 * UDP 7802：车机广播找手机，服务端原路回 "CARMIRROR:7801"。
 */
public class StreamServer {

    public static final int PORT = 7801;
    public static final int DISCOVERY_PORT = 7802;

    private static final byte[] MAGIC = "CMSH".getBytes();
    private static final byte[] DISCOVERY_REPLY = ("CARMIRROR:" + PORT).getBytes();

    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private Thread acceptThread;
    private Thread discoveryThread;
    private volatile boolean running;

    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile byte[] csd0;
    private volatile byte[] csd1;

    private volatile ClientHandler client;
    private volatile String state = "等待车机连接";

    public volatile Runnable onClientConnected;
    public volatile Runnable onNeedKeyFrame;
    /** 车机主动断开时回调，用来在无客户端一段时间后收摊（并恢复手机亮度/音量） */
    public volatile Runnable onClientDisconnected;

    /** 当前是否还有车机连着 */
    public boolean hasClient() {
        ClientHandler h = client;
        return h != null && h.active;
    }

    public String getState() {
        return state;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "cm-accept");
        acceptThread.start();
        discoveryThread = new Thread(this::discoveryLoop, "cm-discovery");
        discoveryThread.start();
    }

    public void stop() {
        running = false;
        ClientHandler c = client;
        if (c != null) {
            c.stop();
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (discoverySocket != null) {
                discoverySocket.close();
            }
        } catch (Exception ignored) {
        }
        serverSocket = null;
        discoverySocket = null;
    }

    public void publishVideoInfo(int w, int h, byte[] s0, byte[] s1) {
        videoWidth = w;
        videoHeight = h;
        csd0 = s0;
        csd1 = s1;
    }

    public void pushFrame(byte[] data, boolean key) {
        ClientHandler c = client;
        if (c != null) {
            c.offer(data, key ? 1 : 0);
        }
    }

    /** 编码尺寸变了：把新尺寸和新的 SPS/PPS 立刻推给车机，它据此重建解码器 */
    public void pushFormatChange(int w, int h, byte[] s0, byte[] s1) {
        publishVideoInfo(w, h, s0, s1);
        ClientHandler c = client;
        if (c == null) {
            return;
        }
        byte[] p0 = s0 == null ? new byte[0] : s0;
        byte[] p1 = s1 == null ? new byte[0] : s1;
        byte[] payload = new byte[16 + p0.length + p1.length];
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.putInt(w);
        bb.putInt(h);
        bb.putInt(p0.length);
        bb.put(p0);
        bb.putInt(p1.length);
        bb.put(p1);
        c.offer(payload, 2);
    }

    public static String describeAddresses() {
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

    private void discoveryLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setBroadcast(true);
            discoverySocket = socket;
            byte[] buffer = new byte[128];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                DatagramPacket reply = new DatagramPacket(
                        DISCOVERY_REPLY, DISCOVERY_REPLY.length,
                        packet.getAddress(), packet.getPort());
                socket.send(reply);
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private void acceptLoop() {
        try {
            ServerSocket socket = new ServerSocket(PORT);
            socket.setReuseAddress(true);
            serverSocket = socket;
            while (running) {
                Socket s = socket.accept();
                s.setTcpNoDelay(true);
                try {
                    s.setSendBufferSize(512 * 1024);
                } catch (Exception ignored) {
                }
                ClientHandler handler = new ClientHandler(s);
                ClientHandler old = client;
                client = handler;
                if (old != null) {
                    old.stop();
                }
                handler.start();
                Runnable hook = onClientConnected;
                if (hook != null) {
                    try {
                        hook.run();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                state = "监听失败：" + e.getMessage();
            }
        }
    }

    private class ClientHandler {

        private final Socket socket;
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        private volatile boolean active = true;
        /** 拥塞或参数变更后，在下一个关键帧到来前不发任何帧，避免缺参考帧花屏 */
        private volatile boolean dropUntilKeyFrame;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        void start() {
            Thread t = new Thread(this::run, "cm-writer");
            t.start();
        }

        void stop() {
            active = false;
            try {
                socket.close();
            }
        catch (IOException ignored) {
            }
        }

        void offer(byte[] data, int flags) {
            if (!active) {
                return;
            }
            if (flags == 2) {
                // 参数更新必须送达：清掉积压的旧帧再放，并且新尺寸下必须等关键帧
                queue.clear();
                dropUntilKeyFrame = true;
                queue.offer(new Frame(data, 2));
                return;
            }
            if (dropUntilKeyFrame) {
                if (flags != 1) {
                    return;
                }
                dropUntilKeyFrame = false;
            }
            if (!queue.offer(new Frame(data, flags))) {
                // 队列积压 = 链路跟不上。此时丢掉单个普通帧会破坏 H.264 参考链，
                // 解码端会花屏直到下一个 I 帧。整队清空并进入"只等关键帧"状态，
                // 再让编码器立刻吐一个 I 帧，花屏窗口最短且不会残缺参考帧。
                queue.clear();
                if (flags == 1) {
                    dropUntilKeyFrame = false;
                    queue.offer(new Frame(data, 1));
                } else {
                    dropUntilKeyFrame = true;
                    Runnable hook = onNeedKeyFrame;
                    if (hook != null) {
                        try {
                            hook.run();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        private void run() {
            try {
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));

                long deadline = System.currentTimeMillis() + 15000;
                while (active && (csd0 == null || csd0.length == 0)) {
                    if (System.currentTimeMillis() > deadline) {
                        break;
                    }
                    Thread.sleep(50);
                }

                byte[] s0 = csd0 == null ? new byte[0] : csd0;
                byte[] s1 = csd1 == null ? new byte[0] : csd1;

                out.write(MAGIC);
                out.writeInt(1);
                out.writeInt(videoWidth);
                out.writeInt(videoHeight);
                out.writeInt(s0.length);
                out.write(s0);
                out.writeInt(s1.length);
                out.write(s1);
                out.flush();

                state = "车机已连接，推流中";

                while (active) {
                    Frame f = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        continue;
                    }
                    out.writeInt(f.flags);
                    out.writeInt(f.data.length);
                    out.write(f.data);
                    out.flush();
                }
            } catch (Exception e) {
                // 断开，正常退出
            } finally {
                active = false;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                if (client == this) {
                    state = "车机已断开，等待重连";
                    Runnable hook = onClientDisconnected;
                    if (hook != null) {
                        try {
                            hook.run();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    private static class Frame {
        final byte[] data;
        final int flags;

        Frame(byte[] data, int flags) {
            this.data = data;
            this.flags = flags;
        }
    }
}