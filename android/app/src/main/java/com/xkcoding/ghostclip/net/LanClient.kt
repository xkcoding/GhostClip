package com.xkcoding.ghostclip.net

import com.xkcoding.ghostclip.util.DebugLog
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
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 局域网 WebSocket Client
 *
 * Mac 端 WebSocket Server 通过 mDNS 发现后, 发起 WebSocket 连接
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
        /** 连续多次重连失败，建议重新进行 NSD 发现 */
        fun onReconnectExhausted() {}
    }

    var listener: Listener? = null

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY) // 局域网直连，绕过系统代理
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // WebSocket 长连接
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentHost: String? = null
    private var currentPort: Int = 0
    private var reconnectFailCount = 0

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
        DebugLog.d(TAG, "连接 WebSocket: $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.d(TAG, "WebSocket 已连接 -> $url")
                isConnected = true
                reconnectFailCount = 0
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    // Mac 端 ClipMessage: { device_id, text, hash, timestamp }
                    val clipText = json.getString("text")
                    val hash = json.getString("hash")
                    val senderDeviceId = json.getString("device_id")
                    DebugLog.d(TAG, "收到消息: hash=$hash, from=$senderDeviceId, len=${clipText.length}")
                    listener?.onClipReceived(clipText, hash, senderDeviceId)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "解析 WebSocket 消息失败: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.w(TAG, "WebSocket 失败: ${t.message}")
                isConnected = false
                listener?.onDisconnected()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.d(TAG, "WebSocket 关闭: code=$code, reason=$reason")
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
        val ok = ws.send(json.toString())
        DebugLog.d(TAG, "发送剪贴板: hash=$hash, len=${content.length}, ok=$ok")
        return ok
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        isConnected = false
    }

    /**
     * 断线重连 -- 3s 后重试，连续失败 MAX_RECONNECT_ATTEMPTS 次后通知上层刷新 NSD
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectFailCount++
        if (reconnectFailCount > MAX_RECONNECT_ATTEMPTS) {
            DebugLog.w(TAG, "连续 $reconnectFailCount 次重连失败，通知上层刷新 NSD")
            reconnectFailCount = 0
            listener?.onReconnectExhausted()
            return
        }
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(RECONNECT_DELAY_MS)
            if (!isActive) return@launch
            val host = currentHost ?: return@launch
            DebugLog.d(TAG, "重连 WebSocket ($reconnectFailCount/$MAX_RECONNECT_ATTEMPTS) -> $host:$currentPort ...")
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
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }
}
