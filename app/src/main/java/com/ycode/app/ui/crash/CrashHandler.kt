package com.ycode.app.ui.crash

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

object CrashHandler {
    private const val REPORT_FILE = "last-crash.txt"

    fun isMainProcess(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        return name == null || name == context.packageName
    }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = buildReport(thread, throwable)
                File(context.filesDir, REPORT_FILE).writeText(report)
                context.startActivity(Intent(context, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_REPORT, report.take(16000))
                })
                Thread.sleep(700)
            }.onFailure { previous?.uncaughtException(thread, throwable) }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    fun readLastReport(context: Context): String = runCatching { File(context.filesDir, REPORT_FILE).readText() }.getOrDefault("没有可用的错误信息")

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return buildString {
            appendLine("Ycode Android 崩溃报告")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("线程：${thread.name}")
            appendLine("Android：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("应用：${com.ycode.app.BuildConfig.VERSION_NAME} (${com.ycode.app.BuildConfig.VERSION_CODE})")
            appendLine()
            append(writer.toString())
        }
    }
}
