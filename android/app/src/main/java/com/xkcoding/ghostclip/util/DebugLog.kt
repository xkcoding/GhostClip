package com.xkcoding.ghostclip.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 调试日志收集器
 *
 * 同时输出到 Logcat 和内存缓冲区，供 UI 实时展示。
 */
object DebugLog {
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX = 200
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    var onNewLog: ((String) -> Unit)? = null

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("$tag: $msg")
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append("ERR $tag: $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("WARN $tag: $msg")
    }

    private fun append(line: String) {
        val entry = "${sdf.format(Date())} $line"
        logs.add(entry)
        while (logs.size > MAX) logs.removeAt(0)
        mainHandler.post { onNewLog?.invoke(entry) }
    }

    fun getAll(): String = synchronized(logs) { logs.joinToString("\n") }

    fun clear() = synchronized(logs) { logs.clear() }
}
