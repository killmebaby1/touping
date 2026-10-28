package com.carcast.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.BufferedOutputStream
import java.net.Socket

/**
 * 手机投屏发送服务。
 * 用 MediaProjection 采集屏幕 → MediaCodec H.264 硬件编码 → TCP 推流到车机接收端。
 */
class CastService : Service() {

    companion object {
        private const val CHANNEL_ID = "carcast_sender"
        private const val NOTIF_ID = 1
        private const val DEFAULT_BITRATE = 8_000_000
        private const val DEFAULT_FPS = 30
    }

    private var mediaProjection: MediaProjection? = null
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var running = false
    private var workThread: Thread? = null
    private var out: BufferedOutputStream? = null
    private var socket: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onDestroy() {
        releaseWakeLock()
        releaseAll()
        super.onDestroy()
    }

    /** 投屏期间保持 CPU 唤醒（息屏省电但投屏不断）。 */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "carcast::cast"
            )
            wakeLock?.acquire()
        } catch (e: Exception) {
            // 权限/低版本受限，忽略
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("正在投屏…"))
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        if (!running && code != 0 && data != null) {
            running = true
            workThread = Thread {
                runCasting(code, data)
            }.apply { start() }
        }
        return START_STICKY
    }

    private fun runCasting(resultCode: Int, resultData: Intent) {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            val projection = mediaProjection ?: run { stopSelf(); return }

            val width = MainActivity.CastConfig.width
            val height = MainActivity.CastConfig.height
            val ip = MainActivity.CastConfig.ip
            val port = MainActivity.CastConfig.port

            // H.264 编码器
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec!!.createInputSurface()

            // 创建虚拟显示，把屏幕渲染到编码器输入
            virtualDisplay = projection.createVirtualDisplay(
                "carcast",
                width, height, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            codec!!.start()
            postStatus("投屏中 ${width}×${height}")

            // 断线自动重连：黑屏/网络抖动导致 socket 断开时，自动重连继续投屏，不退出
            while (running) {
                try {
                    // 连接车机接收端
                    socket = Socket(ip, port)
                    socket?.tcpNoDelay = true
                    out = BufferedOutputStream(socket!!.getOutputStream(), 64 * 1024)
                    postStatus("已连接车机 ${ip}:${port}")

                    val info = MediaCodec.BufferInfo()
                    val outBuf = ByteArray(64 * 1024)
                    while (running) {
                        val index = codec!!.dequeueOutputBuffer(info, 10_000)
                        when {
                            index >= 0 -> {
                                val byteBuffer = codec!!.getOutputBuffer(index) ?: continue
                                byteBuffer.position(info.offset)
                                byteBuffer.limit(info.offset + info.size)
                                val size = info.size
                                if (size > 0) {
                                    var read = 0
                                    while (read < size) {
                                        val chunk = minOf(size - read, outBuf.size)
                                        byteBuffer.get(outBuf, 0, chunk)
                                        out?.write(outBuf, 0, chunk)
                                        read += chunk
                                    }
                                    out?.flush()
                                }
                                codec!!.releaseOutputBuffer(index, false)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                            }
                            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                // 可忽略，config 数据已随流发出
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 断开连接：关闭 socket，短暂等待后自动重连
                    try { out?.flush() } catch (_: Exception) {}
                    try { out?.close() } catch (_: Exception) {}
                    try { socket?.close() } catch (_: Exception) {}
                    out = null
                    socket = null
                    if (!running) break
                    postStatus("连接中断，自动重连中…")
                    try { Thread.sleep(1000) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            postStatus("投屏结束: ${e.message}")
        } finally {
            releaseAll()
            postStatus("已停止")
            stopSelf()
        }
    }

    private fun releaseAll() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { out?.flush() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        out = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun postStatus(text: String) {
        StatusRelay.post(text)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "车机投屏", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("车机投屏")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("车机投屏")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }
}

/** 跨组件状态回显（服务线程 -> 主界面）。 */
object StatusRelay {
    @Volatile var listener: ((String?) -> Unit)? = null

    fun post(text: String?) {
        listener?.invoke(text)
    }
}
