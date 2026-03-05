package com.xkcoding.ghostclip.spike

import android.os.Handler
import android.os.Looper

/**
 * Spike: 简单的静态事件总线，用于 Service/Activity 间通信。
 * 替代 BroadcastReceiver，避免 Android 16 上的投递问题。
 */
object SpikeEventBus {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: ((String) -> Unit)? = null

    fun register(callback: (String) -> Unit) {
        listener = callback
    }

    fun unregister() {
        listener = null
    }

    fun post(message: String) {
        mainHandler.post {
            listener?.invoke(message)
        }
    }
}
