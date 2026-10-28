package com.carcast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.view.Surface
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 车机投屏接收服务。
 * 作为 TCP 服务器监听端口，接收手机发送端推来的 H.264 裸流，
 * 用 MediaCodec 硬件解码后渲染到 SurfaceStore.surface（强制全屏 1920×1080）。
 */
class ReceiverService : Service() {

    companion object {
        const val PORT = 2026
        private const val CHANNEL_ID = "carcast_receiver"
        private const val NOTIF_ID = 2026

        /** 目标画幅：强制 1920×1080 */
        const val TARGET_WIDTH = 1920
        const val TARGET_HEIGHT = 1080
    }

    private var serverThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        // 崩溃捕获：写入文件，供无 adb 环境定位
        CrashHandler.install(applicationContext)
        try {
            createNotificationChannel()
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 安卓9：startForegroundService 后必须在 5 秒内调用 startForeground，否则抛
        // RemoteServiceException 闪退。这里用 try/catch 兜底，前台失败也绝不中断服务。
        try {
            startForeground(NOTIF_ID, buildNotification("等待手机连接…"))
        } catch (e: Exception) {
            // 前台服务受限时静默降级，服务照常运行，避免安卓9的5秒限制崩溃
        }
        if (!running) {
            running = true
            serverThread = Thread { runServer() }.apply { start() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runServer() {
        var server: ServerSocket? = null
        try {
            server = ServerSocket(PORT)
            StatusBridge.post("监听中 端口 $PORT")
            while (running) {
                val client: Socket
                try {
                    client = server.accept()
                } catch (e: Exception) {
                    if (!running) break
                    continue
                }
                Thread { handleClient(client) }.apply { start() }
            }
        } catch (e: Exception) {
            StatusBridge.post("服务错误: ${e.message}")
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: Socket) {
        var codec: MediaCodec? = null
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            StatusBridge.post("手机已连接，正在投屏…")

            // 等待解码 Surface 就绪
            var wait = 0
            while (SurfaceStore.surface == null && wait < 200) {
                Thread.sleep(50)
                wait++
            }
            val surface: Surface = SurfaceStore.surface ?: run {
                StatusBridge.post("解码 Surface 未就绪")
                return
            }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, TARGET_WIDTH, TARGET_HEIGHT)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()

            StatusBridge.post("投屏中 1920×1080")

            // H.264 Annex-B 流分帧喂给解码器
            val buffer = ByteArray(64 * 1024)
            var accumulated = java.io.ByteArrayOutputStream()
            var pts = 0L

            fun feedNalu(nal: ByteArray) {
                if (nal.isEmpty()) return
                // 去掉起始码
                var off = 0
                while (off < nal.size && nal[off].toInt() == 0) off++
                val body = if (off < nal.size) nal.copyOfRange(off, nal.size) else nal
                if (body.isEmpty()) return

                val idx = codec!!.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec!!.getInputBuffer(idx) ?: return
                    buf.clear()
                    buf.put(body)
                    codec!!.queueInputBuffer(idx, 0, body.size, pts, 0)
                    pts += 33_333
                }
            }

            while (running) {
                val read = input.read(buffer)
                if (read <= 0) break
                accumulated.write(buffer, 0, read)
                val data = accumulated.toByteArray()
                accumulated.reset()

                // 按起始码 00 00 00 01 / 00 00 01 切分 NAL
                var i = 0
                var n = data.size
                var lastStart = -1
                while (i + 3 < n) {
                    if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                        if (lastStart >= 0) {
                            feedNalu(data.copyOfRange(lastStart + 3, i))
                        }
                        lastStart = i
                        i += 3
                    } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                        if (lastStart >= 0) {
                            feedNalu(data.copyOfRange(lastStart + 4, i))
                        }
                        lastStart = i
                        i += 4
                    } else {
                        i++
                    }
                }
                // 保留末尾不完整的 NAL
                if (lastStart >= 0 && lastStart < n) {
                    accumulated.write(data, lastStart, n - lastStart)
                }
            }
        } catch (e: Exception) {
            StatusBridge.post("连接中断: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            StatusBridge.post("等待手机连接…")
        }
    }

    override fun onDestroy() {
        running = false
        try { serverThread?.interrupt() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "车机投屏接收", NotificationManager.IMPORTANCE_LOW
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
                .setContentTitle("车机投屏接收")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("车机投屏接收")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }
}
