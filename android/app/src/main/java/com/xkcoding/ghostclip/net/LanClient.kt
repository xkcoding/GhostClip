package com.xkcoding.ghostclip.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 局域网 WebSocket Client
 *
 * Mac 端 WebSocket Server 通过 mDNS 发现后, Server 端发起 WebSocket 连接
 *
 * 消息格式 JSON (与 Mac ws_server.rs ClipMessage 对齐):
 * - 发送: {"device_id":"...","text":"...","hash":"...","timestamp":...}
 * - 接收: {"device_id":"...","text":"...","hash":"...","timestamp":...}
 */
class LanClient(
    private val scope: CoroutineScope,
    private val deviceId: String,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onClipReceived(content: String, hash: String, deviceId: String)
    }

    var listener: Listener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // WebSocket 长连接
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentHost: String? = null
    private var currentPort: Int = 0

    @Volatile
    var isConnected = false
        private set

    /**
     * 建立 WebSocket 连接到 Mac
     */
    fun connect(host: String, port: Int) {
        disconnect()
        currentHost = host
        currentPort = port

        val url = "ws://$host:$port"
        Log.d(TAG, "连接 WebSocket: $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接")
                isConnected = true
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    // Mac 端 ClipMessage: { device_id, text, hash, timestamp }
                    val clipText = json.getString("text")
                    val hash = json.getString("hash")
                    val senderDeviceId = json.getString("device_id")
                    listener?.onClipReceived(clipText, hash, senderDeviceId)
                } catch (e: Exception) {
                    Log.e(TAG, "解析 WebSocket 消息失败: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket 失败: ${t.message}")
                isConnected = false
                listener?.onDisconnected()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 关闭: $reason")
                isConnected = false
                listener?.onDisconnected()
            }
        })
    }

    /**
     * 发送剪贴板内容 -- 与 Mac ClipMessage 格式对齐
     */
    fun sendClip(content: String, hash: String): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected) return false

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("text", content)
            put("hash", hash)
            put("timestamp", System.currentTimeMillis())
        }
        return ws.send(json.toString())
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        isConnected = false
    }

    /**
     * 断线重连 -- 3s 后重试, 失败则重新 mDNS 发现
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(RECONNECT_DELAY_MS)
            if (!isActive) return@launch
            val host = currentHost ?: return@launch
            Log.d(TAG, "重连 WebSocket...")
            connect(host, currentPort)
        }
    }

    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val TAG = "LanClient"
        private const val RECONNECT_DELAY_MS = 3_000L
    }
}
