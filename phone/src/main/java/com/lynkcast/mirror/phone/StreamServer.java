package com.lynkcast.mirror.phone;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 极简推流服务端。
 *
 * 协议（全部大端）：
 *   连接建立后，服务端先发头部：
 *     byte[4] magic = "CMSH"
 *     int32   version = 1
 *     int32   width
 *     int32   height
 *     int32   csd0Len + csd0 bytes   (SPS)
 *     int32   csd1Len + csd1 bytes   (PPS)
 *   之后每帧：
 *     int32   flags   (1 = 关键帧)
 *     int32   length
 *     byte[length]    (Annex-B 格式 H.264)
 */
public class StreamServer {

    public static final int PORT = 7801;

    private static final byte[] MAGIC = new byte[]{'C', 'M', 'S', 'H'};

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile byte[] csd0;
    private volatile byte[] csd1;

    private volatile ClientHandler client;
    private volatile String state = "等待车机连接";

    /** 新客户端接入时回调，用于让编码器立刻吐一个关键帧 */
    public volatile Runnable onClientConnected;

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
        serverSocket = null;
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
            c.offer(data, key);
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
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(6);
        private volatile boolean active = true;

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
            } catch (IOException ignored) {
            }
        }

        void offer(byte[] data, boolean key) {
            if (!active) {
                return;
            }
            if (!queue.offer(new Frame(data, key))) {
                if (key) {
                    queue.poll();
                    queue.offer(new Frame(data, key));
                }
            }
        }

        private void run() {
            try {
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));

                // 等编码器吐出 csd（SPS/PPS）再发头部
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
                    out.writeInt(f.key ? 1 : 0);
                    out.writeInt(f.data.length);
                    out.write(f.data);
                    out.flush();
                }
            } catch (Exception e) {
                // 连接断开，正常退出
            } finally {
                active = false;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                if (client == this) {
                    state = "车机已断开，等待重连";
                }
            }
        }
    }

    private static class Frame {
        final byte[] data;
        final boolean key;

        Frame(byte[] data, boolean key) {
            this.data = data;
            this.key = key;
        }
    }
}
