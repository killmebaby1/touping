package com.carcast.receiver

import android.content.Context
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃捕获：把未捕获异常写进文件，下次启动时在界面显示原因，
 * 方便无 adb 环境下定位闪退（尤其老车机）。
 */
object CrashHandler {
    private const val FILE_NAME = "carcast_crash.log"

    /** 在入口处安装，崩溃时把堆栈写入 filesDir。 */
    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = context.filesDir
                if (dir != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    val f = File(dir, FILE_NAME)
                    f.writeText(
                        "time=${System.currentTimeMillis()}\n" +
                            "thread=${thread.name}\n" + sw.toString()
                    )
                }
            } catch (_: Exception) {
            }
            // 继续交给系统处理
            prev?.uncaughtException(thread, throwable)
        }
    }

    /** 读取上次崩溃原因，供界面显示。 */
    fun readLastCrash(context: Context): String? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    /** 清除崩溃记录。 */
    fun clear(context: Context) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.delete()
        } catch (_: Exception) {
        }
    }

    /** 在界面上 toast 显示上次崩溃原因（截断，避免过长）。 */
    fun toastLastCrash(context: Context) {
        val crash = readLastCrash(context)
        if (crash != null) {
            try {
                val msg = crash.take(1500)
                Toast.makeText(context, "上次崩溃原因:\n$msg", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }
}
