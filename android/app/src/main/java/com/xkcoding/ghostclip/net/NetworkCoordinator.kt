package com.xkcoding.ghostclip.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.HashPool
import com.xkcoding.ghostclip.clip.SyncBridge
import com.xkcoding.ghostclip.service.GhostClipService
import com.xkcoding.ghostclip.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络协调器
 *
 * 优先: 同一 WiFi -> LAN(mDNS + WebSocket)
 * 回退: LAN 不可用 -> 云端轮询
 * WiFi 切换: 重新 mDNS 发现
 */
class NetworkCoordinator(
    private val context: Context,
    private val hashPool: HashPool,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var nsdDiscovery: NsdDiscovery? = null
    private var lanClient: LanClient? = null
    private var cloudClient: CloudClient? = null
    private var presenceSM: PresenceStateMachine? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 记录当前连接的 Mac 服务名
    private var connectedMacName: String = ""

    private val prefs by lazy {
        context.getSharedPreferences("ghostclip_settings", Context.MODE_PRIVATE)
    }

    // 持久化 deviceId
    private val deviceId: String by lazy {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "android_${android.os.Build.MODEL.replace(" ", "_")}_${System.currentTimeMillis().toString(36)}"
            prefs.edit().putString("device_id", id).apply()
        }
        id!!
    }

    /**
     * 启动全部网络 -- 由 GhostClipService.onCreate 调用
     */
    fun start() {
        // 注册 SyncBridge callback -- 发送剪贴板时走这里
        SyncBridge.callback = object : SyncBridge.SyncCallback {
            override fun onSend(text: String, hash: String) {
                scope.launch(Dispatchers.IO) {
                    // 优先 LAN
                    val sentViaLan = lanClient?.sendClip(text, hash) == true
                    if (sentViaLan) {
                        Log.d(TAG, "已通过 LAN 发送")
                        broadcastClipSynced(text, "outgoing", connectedMacName.ifEmpty { "Mac" })
                        return@launch
                    }
                    // 回退云端
                    val cloud = cloudClient
                    if (cloud != null) {
                        val ok = cloud.postClip(text, hash)
                        Log.d(TAG, "云端发送 ${if (ok) "成功" else "失败"}")
                        if (ok) broadcastClipSynced(text, "outgoing", "Mac (Cloud)")
                    } else {
                        Log.w(TAG, "无可用发送通道 -- 云端未配置")
                    }
                }
            }
        }

        // 1. mDNS 局域网发现
        setupNsdDiscovery()

        // 2. 云端同步 (如果已配置)
        setupCloudSync()

        // 3. WiFi 变化监听 -> 重新 mDNS 发现
        registerNetworkCallback()
    }

    fun stop() {
        SyncBridge.callback = null
        nsdDiscovery?.stopDiscovery()
        lanClient?.shutdown()
        cloudClient?.shutdown()
        presenceSM?.stop()
        unregisterNetworkCallback()
    }

    private fun setupNsdDiscovery() {
        nsdDiscovery = NsdDiscovery(context).apply {
            listener = object : NsdDiscovery.Listener {
                override fun onServiceFound(host: String, port: Int, serviceName: String) {
                    Log.d(TAG, "发现 Mac 服务: $host:$port")
                    connectedMacName = serviceName
                    connectLan(host, port)
                }

                override fun onServiceLost(serviceName: String) {
                    Log.d(TAG, "Mac 服务丢失: $serviceName")
                    connectedMacName = ""
                    lanClient?.disconnect()
                    updateServiceState()
                }
            }
            startDiscovery()
        }
    }

    private fun connectLan(host: String, port: Int) {
        if (lanClient == null) {
            lanClient = LanClient(scope, deviceId)
        }
        lanClient!!.listener = object : LanClient.Listener {
            override fun onConnected() {
                Log.d(TAG, "LAN WebSocket 已连接")
                updateServiceState()
            }

            override fun onDisconnected() {
                Log.d(TAG, "LAN WebSocket 断开")
                updateServiceState()
            }

            override fun onClipReceived(content: String, hash: String, deviceId: String) {
                if (!hashPool.checkAndRecord(content)) {
                    ClipboardHelper.write(context, content)
                    Log.d(TAG, "LAN 收到: ${content.take(50)}...")
                    broadcastClipSynced(content, "incoming", connectedMacName.ifEmpty { "Mac" })
                }
            }
        }
        lanClient!!.connect(host, port)
    }

    private fun setupCloudSync() {
        val cloudUrl = prefs.getString("cloud_url", null)
        val cloudToken = prefs.getString("cloud_token", null)
        val cloudEnabled = prefs.getBoolean("cloud_enabled", false)

        if (!cloudEnabled || cloudUrl.isNullOrBlank() || cloudToken.isNullOrBlank()) {
            Log.d(TAG, "云端同步未启用")
            return
        }

        cloudClient = CloudClient(cloudUrl, cloudToken, deviceId)
        presenceSM = PresenceStateMachine(scope, cloudClient!!).apply {
            listener = object : PresenceStateMachine.Listener {
                override fun onPeerOnline() {
                    updateServiceState()
                }

                override fun onPeerOffline() {
                    updateServiceState()
                }

                override fun onNewClip(record: CloudClient.ClipRecord) {
                    if (!hashPool.checkAndRecord(record.text)) {
                        ClipboardHelper.write(context, record.text)
                        Log.d(TAG, "云端收到: ${record.text.take(50)}...")
                        broadcastClipSynced(record.text, "incoming", "Mac (Cloud)")
                    }
                }
            }
            start()
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WiFi 连接 -- mDNS 重新发现")
                nsdDiscovery?.stopDiscovery()
                nsdDiscovery?.startDiscovery()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "WiFi 断开 -- LAN 不可用")
                connectedMacName = ""
                lanClient?.disconnect()
                updateServiceState()
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        networkCallback = null
    }

    /**
     * 更新连接状态: 通知栏 + 广播给 UI
     */
    private fun updateServiceState() {
        val state: GhostClipService.ConnectionState
        val deviceLabel: String
        val connLabel: String

        when {
            lanClient?.isConnected == true -> {
                state = GhostClipService.ConnectionState.LAN
                deviceLabel = connectedMacName.ifEmpty { "Mac" }
                connLabel = "\u5c40\u57df\u7f51\u76f4\u8fde"
            }
            presenceSM?.mode == PresenceStateMachine.Mode.POLL -> {
                state = GhostClipService.ConnectionState.CLOUD
                deviceLabel = "Mac"
                connLabel = "\u4e91\u7aef\u540c\u6b65"
            }
            else -> {
                state = GhostClipService.ConnectionState.DISCONNECTED
                deviceLabel = ""
                connLabel = ""
            }
        }

        // 更新通知栏
        val serviceIntent = Intent(context, GhostClipService::class.java).apply {
            putExtra(GhostClipService.EXTRA_CONNECTION_STATE, state.name)
        }
        context.startForegroundService(serviceIntent)

        // 广播给 MainActivity UI
        context.sendBroadcast(Intent(MainActivity.ACTION_CONNECTION_CHANGED).apply {
            setPackage(context.packageName)
            putExtra("state", state.name)
            putExtra("device_name", deviceLabel)
            putExtra("conn_label", connLabel)
        })
    }

    /**
     * 广播同步事件给 UI -- 显示同步记录
     */
    private fun broadcastClipSynced(text: String, direction: String, source: String) {
        context.sendBroadcast(Intent(MainActivity.ACTION_CLIP_SYNCED).apply {
            setPackage(context.packageName)
            putExtra("text", text)
            putExtra("direction", direction)
            putExtra("source", source)
        })
    }

    /** 重新加载云端配置 */
    fun reloadCloudConfig() {
        cloudClient?.shutdown()
        presenceSM?.stop()
        cloudClient = null
        presenceSM = null
        setupCloudSync()
    }

    companion object {
        private const val TAG = "NetCoordinator"
    }
}
