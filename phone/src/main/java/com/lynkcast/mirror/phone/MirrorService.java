package com.lynkcast.mirror.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 抓屏 -> MediaCodec(H.264) -> TCP 推流 的前台服务。
 */
public class MirrorService extends Service {

    public static final String ACTION_START = "com.lynkcast.mirror.START";
    public static final String ACTION_STOP = "com.lynkcast.mirror.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    /** Activity 轮询这两个静态字段来展示状态 */
    public static volatile String statusText = "空闲";
    public static volatile String localIp = "";

    private static final int NOTIF_ID = 0x1001;
    private static final String CHANNEL_ID = "carmirror_mirror";

    private static final int BITRATE = 8 * 1024 * 1024;
    private static final int FRAME_RATE = 30;
    private static final int MAX_LONG_SIDE = 1920;

    private MediaProjection projection;
    private MediaCodec codec;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;
    private StreamServer server;
    private Thread encodeThread;

    private volatile boolean active;
    private volatile boolean stopping;
    private int encW;
    private int encH;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                startMirror(resultCode, data);
            } else if (ACTION_STOP.equals(action)) {
                stopMirror();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopMirror();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("车镜正在投屏")
                .setContentText("手机画面正在发送给车机")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startMirror(int resultCode, Intent data) {
        if (active || data == null) {
            return;
        }
        statusText = "正在启动…";

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            statusText = "投屏授权失败，请重新授权";
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                statusText = "投屏已被系统停止";
                stopMirror();
            }
        }, new Handler(Looper.getMainLooper()));

        int[] size = pickEncoderSize();
        encW = size[0];
        encH = size[1];

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= 23) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();

            int dpi = getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = projection.createVirtualDisplay(
                    "CarMirror",
                    encW, encH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);

            server = new StreamServer();
            server.onClientConnected = this::requestKeyFrame;
            server.start();
            server.publishVideoInfo(encW, encH, null, null);

            active = true;
            encodeThread = new Thread(this::encodeLoop, "cm-encode");
            encodeThread.start();

            statusText = "投屏中 · 等待车机连接";
        } catch (Exception e) {
            statusText = "编码器启动失败：" + e;
            stopMirror();
        }
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (active && !stopping) {
            try {
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    byte[] s0 = readCsd(of, "csd-0");
                    byte[] s1 = readCsd(of, "csd-1");
                    if (server != null && s0.length > 0) {
                        server.publishVideoInfo(encW, encH, s0, s1);
                    }
                    continue;
                }
                if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] payload = new byte[info.size];
                        buffer.get(payload);
                        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte[] framed = ensureStartCode(payload);
                        if (server != null) {
                            server.pushFrame(framed, keyFrame);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (Exception e) {
                if (active) {
                    statusText = "编码异常：" + e;
                }
                break;
            }
        }
    }

    private void requestKeyFrame() {
        MediaCodec c = codec;
        if (c == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(params);
        } catch (Exception ignored) {
        }
    }

    /** 个别机型输出 AVCC 风格的 NAL，这里统一补上 Annex-B 起始码 */
    private static byte[] ensureStartCode(byte[] data) {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return data;
        }
        if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return data;
        }
        byte[] out = new byte[data.length + 4];
        out[3] = 1;
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    private static byte[] readCsd(MediaFormat format, String key) {
        try {
            if (!format.containsKey(key)) {
                return new byte[0];
            }
            ByteBuffer b = format.getByteBuffer(key);
            if (b == null) {
                return new byte[0];
            }
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 按屏幕长边缩放到 <=1920，并对齐到 16 的倍数 */
    private int[] pickEncoderSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);

        int sw = dm.widthPixels;
        int sh = dm.heightPixels;

        // 手机横着用时交换宽高，画面才不会在车机上被压扁
        int rotation = display.getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            int tmp = sw;
            sw = sh;
            sh = tmp;
        }

        int longSide = Math.max(sw, sh);
        float scale = longSide > MAX_LONG_SIDE ? (float) MAX_LONG_SIDE / longSide : 1f;

        int w = align16((int) (sw * scale));
        int h = align16((int) (sh * scale));
        return new int[]{Math.max(16, w), Math.max(16, h)};
    }

    private static int align16(int v) {
        return Math.max(1, v / 16) * 16;
    }

    private void stopMirror() {
        if (stopping) {
            return;
        }
        stopping = true;
        active = false;

        // 先让编码线程退出，再释放 codec，避免在 dequeue 过程中被 release 造成 native crash
        Thread t = encodeThread;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        encodeThread = null;

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Exception ignored) {
        }
        if (server != null) {
            server.stop();
        }

        virtualDisplay = null;
        inputSurface = null;
        codec = null;
        projection = null;
        server = null;
        encodeThread = null;

        statusText = "空闲";
        stopForegroundCompat();
        stopping = false;
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
    }
}
