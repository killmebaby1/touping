package com.carcast.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 1001
        private const val REQ_POST_NOTIF = 1002
    }

    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var resInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        resInput = findViewById(R.id.resInput)
        statusText = findViewById(R.id.statusText)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { startCasting() }
        btnStop.setOnClickListener {
            stopService(Intent(this, CastService::class.java))
            restoreScreen()
            statusText.text = "已停止投屏"
        }

        // 接收服务状态回显
        StatusRelay.listener = { text ->
            runOnUiThread {
                statusText.text = text ?: ""
            }
        }
    }

    private fun startCasting() {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            statusText.text = "请先填写车机接收端 IP"
            return
        }
        // 保存输入到 CastService
        CastConfig.ip = ip
        CastConfig.port = portInput.text.toString().trim().toIntOrNull() ?: 2026
        val res = resInput.text.toString().trim()
        if (res.isNotEmpty() && res.contains("x")) {
            val parts = res.lowercase().split("x")
            if (parts.size == 2) {
                CastConfig.width = parts[0].toIntOrNull() ?: 1920
                CastConfig.height = parts[1].toIntOrNull() ?: 1080
            }
        }

        // 申请通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
        }

        // 申请屏幕采集权限
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                statusText.text = "正在启动投屏到 ${CastConfig.ip}:${CastConfig.port}…"
                applyLowBrightness()
                val intent = Intent(this, CastService::class.java)
                intent.putExtra("code", resultCode)
                intent.putExtra("data", data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                statusText.text = "未获得屏幕采集权限"
            }
        }
    }

    /**
     * 投屏期间"最低亮度保屏"：屏幕几乎全黑（省电），但不触发锁屏，
     * 从而避免安卓15+锁屏自动停止投屏。
     */
    private fun applyLowBrightness() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attrs = window.attributes
            attrs.screenBrightness = 0.02f // 接近全黑
            window.attributes = attrs
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 投屏结束：恢复系统亮度，去掉防锁屏。 */
    private fun restoreScreen() {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attrs = window.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        } catch (e: Exception) {
            // 忽略
        }
    }

    override fun onDestroy() {
        restoreScreen()
        super.onDestroy()
    }

    /** 供 CastService 读取采集授权 */
    object CastConfig {
        var ip: String = ""
        var port: Int = 2026
        var width: Int = 1920
        var height: Int = 1080
    }
}
