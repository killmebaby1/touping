package com.lynkcast.mirror.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * 抓屏 + 内录 -> MediaCodec -> TCP 推流 的前台服务。
 *
 * 屏幕方向一变（比如游戏进横屏），编码分辨率就重建一次，
 * 并通过参数更新消息把新的宽高和 SPS/PPS 推给车机。
 */
public class MirrorService extends Service {

    public static final String ACTION_START = "com.lynkcast.mirror.START";
    public static final String ACTION_STOP = "com.lynkcast.mirror.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public static volatile String statusText = "空闲";
    public static volatile String localIp = "";

    private static final int NOTIF_ID = 0x1001;
    private static final String CHANNEL_ID = "carmirror_mirror";

    private static final int BITRATE = 8 * 1024 * 1024;
    private static final int FRAME_RATE = 30;
    private static final int MAX_LONG_SIDE = 1920;

    private MediaProjection projection;
    private volatile MediaCodec codec;
    private VirtualDisplay virtualDisplay;
    private volatile Surface inputSurface;
    private StreamServer server;
    private AudioStream audioStream;
    private Thread encodeThread;

    private volatile boolean active;
    private volatile boolean stopping;
    private volatile boolean pendingFormatChange;
    private volatile int encW;
    private volatile int encH;
    private int lastRotation = -1;

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
                .setContentText("手机画面与声音正在发送给车机")
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
        lastRotation = currentRotation();

        try {
            MediaCodec c = createEncoder(encW, encH);
            inputSurface = c.createInputSurface();
            c.start();
            codec = c;

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

            if (hasRecordPermission()) {
                audioStream = new AudioStream(this, projection);
                audioStream.start();
            }

            active = true;
            encodeThread = new Thread(this::encodeLoop, "cm-encode");
            encodeThread.start();

            statusText = "投屏中 · 等待车机连接";
        } catch (Exception e) {
            statusText = "编码器启动失败：" + e;
            stopMirror();
        }
    }

    private boolean hasRecordPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission("android.permission.RECORD_AUDIO")
                == PackageManager.PERMISSION_GRANTED;
    }

    private MediaCodec createEncoder(int w, int h) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return c;
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int tick = 0;
        while (active && !stopping) {
            MediaCodec c = codec;
            if (c == null) {
                break;
            }
            try {
                if (++tick % 15 == 0 && rotationChanged()) {
                    rebuildEncoder();
                    continue;
                }

                int index = c.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = c.getOutputFormat();
                    byte[] s0 = readCsd(of, "csd-0");
                    byte[] s1 = readCsd(of, "csd-1");
                    if (server != null && s0.length > 0) {
                        if (pendingFormatChange) {
                            server.pushFormatChange(encW, encH, s0, s1);
                            pendingFormatChange = false;
                        } else {
                            server.publishVideoInfo(encW, encH, s0, s1);
                        }
                    }
                    continue;
                }
                if (index >= 0) {
                    ByteBuffer buffer = c.getOutputBuffer(index);
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
                    c.releaseOutputBuffer(index, false);
                }
            } catch (Exception e) {
                if (active) {
                    statusText = "编码异常：" + e;
                }
                break;
            }
        }
    }

    /** 转屏了就把编码器按新尺寸重建，车机那边会跟着换解码尺寸 */
    private void rebuildEncoder() {
        int[] size = pickEncoderSize();
        int w = size[0];
        int h = size[1];
        lastRotation = currentRotation();
        if (w == encW && h == encH) {
            return;
        }
        MediaCodec newCodec = null;
        Surface newSurface = null;
        try {
            newCodec = createEncoder(w, h);
            newSurface = newCodec.createInputSurface();
            newCodec.start();

            MediaCodec oldCodec = codec;
            Surface oldSurface = inputSurface;

            codec = newCodec;
            inputSurface = newSurface;
            encW = w;
            encH = h;
            pendingFormatChange = true;

            if (virtualDisplay != null) {
                virtualDisplay.setSurface(newSurface);
                virtualDisplay.resize(w, h, getResources().getDisplayMetrics().densityDpi);
            }

            try {
                if (oldCodec != null) {
                    oldCodec.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                if (oldCodec != null) {
                    oldCodec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (oldSurface != null) {
                    oldSurface.release();
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            statusText = "转屏后重建编码器失败：" + e;
            try {
                if (newCodec != null) {
                    newCodec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (newSurface != null) {
                    newSurface.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private int currentRotation() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            return wm.getDefaultDisplay().getRotation();
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean rotationChanged() {
        int r = currentRotation();
        if (r < 0 || lastRotation < 0) {
            return false;
        }
        return r != lastRotation;
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

    /**
     * 按当前屏幕尺寸算编码分辨率。
     * 不同 ROM 的 getRealMetrics 是否随旋转变化不一致，这里两种情况都兜住：
     * 只有"已经是横屏方向、但读出来的尺寸仍是竖的"才交换宽高。
     */
    private int[] pickEncoderSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);

        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        int rotation = display.getRotation();
        boolean rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        if (rotated && sh > sw) {
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

        Thread t = encodeThread;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        encodeThread = null;

        if (audioStream != null) {
            audioStream.stop();
            audioStream = null;
        }
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
            MediaCodec c = codec;
            if (c != null) {
                c.stop();
                c.release();
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
        pendingFormatChange = false;
        lastRotation = -1;

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