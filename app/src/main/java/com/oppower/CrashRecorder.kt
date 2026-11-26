package com.oppower

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常的本地记录。
 *
 * 用户报「闪退」时手上除了「点了一下就没了」什么都留不下，而设置页的崩溃往往
 * 依赖机型和 ROM（某个 `ACTION_*` 没有实现、某个系统页面被裁掉），靠读代码猜
 * 效率极低。这里把**最近一次**崩溃的栈写到 `files/last-crash.txt`，设置里的
 * 「数据管理」页会显示出来，长按即可复制。
 *
 * 只记录，不吞：处理完仍然交回上一个默认 handler，进程照常退出，不会把崩溃
 * 变成"卡住不动"这种更难查的状态。
 */
internal object CrashRecorder {
    private const val FILE_NAME = "last-crash.txt"

    /** 崩溃时所在的页面。只留最后一次，够定位用，不做历史。 */
    @Volatile
    private var location: String = "启动"

    /** 页面切换时标记一下，崩溃栈里就能带上「崩在哪一页」。 */
    fun markLocation(value: String) {
        location = value
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 读出上一次崩溃的记录；没有就返回 null。 */
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.isFile }
            ?.readText()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { buffer ->
            PrintWriter(buffer).use(error::printStackTrace)
        }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("时间：$stamp")
            appendLine("线程：${thread.name}")
            appendLine("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}")
            appendLine("位置：$location")
            appendLine()
            append(stack)
        }
        runCatching { File(context.filesDir, FILE_NAME).writeText(body) }
    }
}
