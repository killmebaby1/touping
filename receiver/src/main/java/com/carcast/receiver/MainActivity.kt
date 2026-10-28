package com.carcast.receiver

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 车机投屏接收端主界面。
 * 强制横屏 + 全屏（1920×1080 目标画幅），将 SurfaceView 交给 ReceiverService 用于解码显示。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃捕获：先安装，再显示上次崩溃原因（无 adb 也能定位）
        CrashHandler.install(applicationContext)
        CrashHandler.toastLastCrash(this)

        setContentView(R.layout.activity_main)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val surfaceView: SurfaceView = findViewById(R.id.surfaceView)
        val statusText: TextView = findViewById(R.id.statusText)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                SurfaceStore.surface = holder.surface
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SurfaceStore.surface = holder.surface
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                SurfaceStore.surface = null
            }
        })

        // 状态文本回调：服务端线程更新到 UI 线程
        StatusBridge.listener = { text ->
            runOnUiThread {
                statusText.text = text ?: ""
            }
        }

        // 启动常驻接收服务
        startReceiverService()
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // 前台服务启动受限，避免闪退
        }
    }

    override fun onResume() {
        super.onResume()
        // 全屏沉浸（低版本/部分 ROM 可能抛异常，兜底避免闪退）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } catch (e: Exception) {
            // 忽略
        }
        try {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } catch (e: Exception) {
            // 忽略
        }
    }

    override fun onDestroy() {
        SurfaceStore.surface = null
        StatusBridge.listener = null
        super.onDestroy()
    }
}
