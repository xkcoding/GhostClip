package com.xkcoding.ghostclip.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.HashPool
import com.xkcoding.ghostclip.clip.SyncBridge
import com.xkcoding.ghostclip.service.GhostClipService
import com.xkcoding.ghostclip.ui.MainActivity
import com.xkcoding.ghostclip.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 网络协调器
 *
 * 配对模式下：扫码后才启动 mDNS 发现 + WebSocket 连接
 * UNPAIRED 状态不启动任何网络连接
 * WiFi 切换（SSID/BSSID 变化）→ 清除 token → UNPAIRED
 * 同 WiFi 断开 → RECONNECTING → mDNS 重发现 → 同 token 重连
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
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdRefreshJob: Job? = null
    private var discoveryTimeoutJob: Job? = null

    // 记录当前连接的 Mac 服务信息
    private var connectedMacName: String = ""
    private var connectedHost: String = ""
    private var connectedPort: Int = 0

    // 缓存扫码得到的配对信息（含 IP fallback）
    private var currentPairingInfo: PairingInfo? = null

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
        DebugLog.d(TAG, "NetworkCoordinator 启动, deviceId=$deviceId")

        // 注册 SyncBridge callback -- 发送剪贴板时走这里
        SyncBridge.callback = object : SyncBridge.SyncCallback {
            override fun onSend(text: String, hash: String) {
                scope.launch(Dispatchers.IO) {
                    // 优先 LAN
                    val sentViaLan = lanClient?.sendClip(text, hash) == true
                    if (sentViaLan) {
                        DebugLog.d(TAG, "已通过 LAN 发送 (hash=$hash)")
                        broadcastClipSynced(text, "outgoing", connectedMacName.ifEmpty { "Mac" })
                        return@launch
                    }
                    // 回退云端
                    val cloud = cloudClient
                    if (cloud != null) {
                        cloud.register()
                        val ok = cloud.postClip(text, hash)
                        DebugLog.d(TAG, "云端发送 ${if (ok) "成功" else "失败"} (hash=$hash)")
                        if (ok) broadcastClipSynced(text, "outgoing", "Mac (Cloud)")
                    } else {
                        DebugLog.w(TAG, "无可用发送通道 -- LAN 未连接且云端未配置")
                    }
                }
            }
        }

        // 监听配对状态变化
        PairingManager.listener = object : PairingManager.Listener {
            override fun onStateChanged(old: PairingManager.State, new: PairingManager.State) {
                DebugLog.d(TAG, "配对状态变化: $old -> $new")
                when (new) {
                    PairingManager.State.CONNECTING -> onPairingConnecting()
                    PairingManager.State.UNPAIRED -> onPairingUnpaired()
                    PairingManager.State.RECONNECTING -> onPairingReconnecting()
                    PairingManager.State.CONNECTED -> onPairingConnected()
                }
                updateServiceState()
            }
        }

        // 获取 MulticastLock
        acquireMulticastLock()

        // WiFi 变化监听
        registerNetworkCallback()

        // 记录当前 WiFi 信息
        snapshotWifi()

        // UNPAIRED 状态不启动 mDNS 和云端
        DebugLog.d(TAG, "初始配对状态: ${PairingManager.state}, 等待扫码")
    }

    fun stop() {
        DebugLog.d(TAG, "NetworkCoordinator 停止")
        SyncBridge.callback = null
        PairingManager.listener = null
        discoveryTimeoutJob?.cancel()
        nsdRefreshJob?.cancel()
        nsdRefreshJob = null
        nsdDiscovery?.stopDiscovery()
        lanClient?.shutdown()
        cloudClient?.shutdown()
        presenceSM?.stop()
        unregisterNetworkCallback()
        releaseMulticastLock()
    }

    /**
     * 扫码成功后由外部调用 -- 触发 mDNS 发现
     */
    fun onScanResult(info: PairingInfo) {
        currentPairingInfo = info
        snapshotWifi() // 记录扫码时的 WiFi 信息
        PairingManager.onScanned(info)
    }

    /**
     * 用户主动解除配对
     */
    fun unpair() {
        DebugLog.d(TAG, "用户主动解除配对")
        // 直接 reset，由 onPairingUnpaired() 统一清理 lanClient（避免 disconnect 触发异步重连）
        PairingManager.reset("用户主动解除")
    }

    // ── 配对状态回调 ──────────────────────────────────────

    private fun onPairingConnecting() {
        val macHash = PairingManager.macHash ?: return
        DebugLog.d(TAG, "开始 mDNS 发现 (mac_hash=$macHash)")

        // 初始化 NSD 并设置过滤
        if (nsdDiscovery == null) {
            nsdDiscovery = NsdDiscovery(context)
        }
        nsdDiscovery!!.filterMacHash = macHash
        nsdDiscovery!!.listener = object : NsdDiscovery.Listener {
            override fun onServiceFound(host: String, port: Int, serviceName: String) {
                // 已连接到相同目标时跳过
                if (lanClient?.isConnected == true && host == connectedHost && port == connectedPort) {
                    DebugLog.d(TAG, "已连接到 $serviceName @ $host:$port, 跳过重复发现")
                    return
                }
                DebugLog.d(TAG, "发现匹配的 Mac 服务: $serviceName @ $host:$port")
                discoveryTimeoutJob?.cancel()
                connectedMacName = PairingManager.macDeviceName.ifEmpty { serviceName }
                connectedHost = host
                connectedPort = port
                connectLan(host, port)
            }

            override fun onServiceLost(serviceName: String) {
                DebugLog.d(TAG, "Mac 服务丢失: $serviceName")
                connectedMacName = ""
                connectedHost = ""
                connectedPort = 0
                lanClient?.disconnect()
                // 同 WiFi 内断开 → RECONNECTING
                if (PairingManager.state == PairingManager.State.CONNECTED) {
                    PairingManager.onDisconnectedSameWifi()
                }
            }
        }
        nsdDiscovery!!.restartDiscovery()

        // 启动发现超时 (6.4)
        startDiscoveryTimeout()

        // 启动 NSD 刷新循环
        startNsdRefreshLoop()
    }

    private fun onPairingUnpaired() {
        DebugLog.d(TAG, "回到未配对状态 -- 停止所有网络")
        discoveryTimeoutJob?.cancel()
        nsdRefreshJob?.cancel()
        nsdDiscovery?.stopDiscovery()
        nsdDiscovery?.filterMacHash = null
        // 先清除 listener 防止异步回调触发 scheduleReconnect，再 shutdown
        lanClient?.listener = null
        lanClient?.shutdown()
        lanClient = null
        connectedMacName = ""
        connectedHost = ""
        connectedPort = 0
        currentPairingInfo = null
    }

    private fun onPairingReconnecting() {
        DebugLog.d(TAG, "RECONNECTING -- 重新 mDNS 发现，保留 token")
        connectedHost = ""
        connectedPort = 0
        nsdDiscovery?.restartDiscovery()
        startDiscoveryTimeout()
    }

    private fun onPairingConnected() {
        DebugLog.d(TAG, "已配对连接成功")
        discoveryTimeoutJob?.cancel()
    }

    // ── LAN 连接 ──────────────────────────────────────

    private fun connectLan(host: String, port: Int) {
        if (lanClient == null) {
            lanClient = LanClient(scope, deviceId)
        }
        // 设置 token
        lanClient!!.pairingToken = PairingManager.token
        lanClient!!.listener = object : LanClient.Listener {
            override fun onConnected() {
                DebugLog.d(TAG, "LAN WebSocket 已连接 -> $host:$port")
                // 等 pair_ok 消息确认（Task #8 处理），此处先更新状态
                if (PairingManager.state == PairingManager.State.RECONNECTING) {
                    PairingManager.onReconnected()
                }
                updateServiceState()
            }

            override fun onDisconnected() {
                DebugLog.d(TAG, "LAN WebSocket 断开")
                if (PairingManager.state == PairingManager.State.CONNECTED) {
                    if (isWifiUnchanged()) {
                        PairingManager.onDisconnectedSameWifi()
                    } else {
                        PairingManager.reset("WiFi 网络变更")
                    }
                }
                updateServiceState()
            }

            override fun onClipReceived(content: String, hash: String, deviceId: String) {
                if (!hashPool.checkAndRecord(content)) {
                    DebugLog.d(TAG, "LAN 收到剪贴板: hash=$hash, len=${content.length}")
                    lastReceivedClip = content
                    lastSentClip = null
                    pendingClip = content
                    // 尝试直接写入（前台宽限期内可成功，后台可能静默失败）
                    ClipboardHelper.write(context, content)
                    broadcastClipSynced(content, "incoming", connectedMacName.ifEmpty { "Mac" })
                    // 弹出临时通知 (7.8)
                    broadcastClipNotification(content)
                } else {
                    DebugLog.d(TAG, "LAN 收到重复内容, 跳过: hash=$hash")
                }
            }

            override fun onPairOk(deviceName: String) {
                DebugLog.d(TAG, "pair_ok -> CONNECTED, device=$deviceName")
                PairingManager.onPairOk(deviceName)
                connectedMacName = deviceName.ifEmpty { connectedMacName }
                updateServiceState()
            }

            override fun onKicked(reason: String) {
                DebugLog.w(TAG, "被踢: $reason")
                // 由 PairingManager.reset → onPairingUnpaired 统一清理 lanClient
                PairingManager.reset("被踢: $reason")
                // 广播通知 UI 层弹 Toast
                context.sendBroadcast(Intent(ACTION_KICKED).apply {
                    setPackage(context.packageName)
                    putExtra("reason", reason)
                })
            }

            override fun onUnpaired() {
                DebugLog.d(TAG, "对方解除配对")
                // 由 PairingManager.reset → onPairingUnpaired 统一清理 lanClient
                PairingManager.reset("对方解除配对")
            }

            override fun onReconnectExhausted() {
                DebugLog.w(TAG, "LAN 重连耗尽 -> 重新启动 mDNS 发现")
                connectedMacName = ""
                connectedHost = ""
                connectedPort = 0
                updateServiceState()
                if (PairingManager.state != PairingManager.State.UNPAIRED) {
                    nsdDiscovery?.restartDiscovery()
                }
            }

            override fun onAuthRejected() {
                DebugLog.w(TAG, "Token 鉴权被拒 (401) -> 回到 UNPAIRED")
                PairingManager.reset("Token 鉴权被拒")
            }
        }
        lanClient!!.connect(host, port)
    }

    // ── 发现超时 (6.4) ──────────────────────────────────

    private fun startDiscoveryTimeout() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (PairingManager.state == PairingManager.State.CONNECTING
                || PairingManager.state == PairingManager.State.RECONNECTING
            ) {
                // mDNS 超时 → 尝试 QR 码中的 IP:Port 直连
                val info = currentPairingInfo
                if (info != null && info.hasDirectConnect) {
                    DebugLog.w(TAG, "mDNS 发现超时, 尝试 QR 直连 ${info.host}:${info.port}")
                    connectedMacName = info.deviceName.ifEmpty { "Mac" }
                    connectedHost = info.host!!
                    connectedPort = info.port!!
                    connectLan(info.host, info.port)
                    return@launch
                }
                DebugLog.w(TAG, "mDNS 发现超时 (${DISCOVERY_TIMEOUT_MS}ms), 无 fallback")
                // 广播超时提示
                context.sendBroadcast(Intent(ACTION_DISCOVERY_TIMEOUT).apply {
                    setPackage(context.packageName)
                })
                PairingManager.onConnectTimeout()
            }
        }
    }

    // ── NSD 刷新循环 ──────────────────────────────────

    private fun startNsdRefreshLoop() {
        nsdRefreshJob?.cancel()
        nsdRefreshJob = scope.launch {
            while (isActive) {
                delay(NSD_REFRESH_INTERVAL_MS)
                if (PairingManager.state == PairingManager.State.UNPAIRED) break
                if (lanClient?.isConnected != true) {
                    DebugLog.d(TAG, "NSD 定时刷新: LAN 未连接, 重启 mDNS 发现")
                    nsdDiscovery?.restartDiscovery()
                }
            }
        }
    }

    // ── WiFi 网络切换检测 (6.7) ──────────────────────────

    private fun snapshotWifi() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            PairingManager.wifiSsid = info?.ssid
            PairingManager.wifiBssid = info?.bssid
            DebugLog.d(TAG, "WiFi 快照: ssid=${info?.ssid}, bssid=${info?.bssid}")
        } catch (e: Exception) {
            DebugLog.e(TAG, "WiFi 快照失败: ${e.message}")
        }
    }

    /**
     * 检查当前 WiFi 是否与扫码时一致
     */
    private fun isWifiUnchanged(): Boolean {
        val savedSsid = PairingManager.wifiSsid
        val savedBssid = PairingManager.wifiBssid
        if (savedSsid == null && savedBssid == null) return true // 未记录，保守视为相同

        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val currentSsid = info?.ssid
            val currentBssid = info?.bssid

            // SSID 或 BSSID 变化视为网络切换
            val changed = (savedSsid != null && savedSsid != currentSsid)
                || (savedBssid != null && savedBssid != currentBssid)

            if (changed) {
                DebugLog.d(TAG, "WiFi 变化检测: ssid $savedSsid -> $currentSsid, bssid $savedBssid -> $currentBssid")
            }
            return !changed
        } catch (e: Exception) {
            return true // 读取失败，保守视为相同
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DebugLog.d(TAG, "WiFi 连接事件")
                if (PairingManager.state == PairingManager.State.UNPAIRED) return

                if (!isWifiUnchanged()) {
                    // WiFi 网络切换 → 清除配对
                    DebugLog.d(TAG, "WiFi 切换 → 清除配对")
                    lanClient?.disconnect()
                    PairingManager.reset("WiFi 网络切换")
                    return
                }

                // 同 WiFi 重连场景
                if (lanClient?.isConnected == true) {
                    DebugLog.d(TAG, "WiFi 事件但 LAN 已连接, 跳过")
                    return
                }
                DebugLog.d(TAG, "同 WiFi 重连 -- 重新启动 mDNS 发现")
                nsdDiscovery?.restartDiscovery()
            }

            override fun onLost(network: Network) {
                DebugLog.d(TAG, "WiFi 断开")
                if (PairingManager.state == PairingManager.State.UNPAIRED) return
                connectedMacName = ""
                connectedHost = ""
                connectedPort = 0
                lanClient?.disconnect()
                // WiFi 丢失但可能会重新连接到同一网络，由 onDisconnected 判断
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

    // ── 云端同步（保留但不主动启动） ──────────────────

    private fun setupCloudSync() {
        val cloudUrl = prefs.getString("cloud_url", null)
        val cloudToken = prefs.getString("cloud_token", null)
        val cloudEnabled = prefs.getBoolean("cloud_enabled", false)

        if (!cloudEnabled || cloudUrl.isNullOrBlank() || cloudToken.isNullOrBlank()) {
            DebugLog.d(TAG, "云端同步未启用 (enabled=$cloudEnabled)")
            return
        }

        DebugLog.d(TAG, "启动云端同步: $cloudUrl")
        cloudClient = CloudClient(cloudUrl, cloudToken, deviceId)
        presenceSM = PresenceStateMachine(scope, cloudClient!!).apply {
            listener = object : PresenceStateMachine.Listener {
                override fun onPeerOnline() {
                    DebugLog.d(TAG, "云端: Mac 上线")
                    updateServiceState()
                }

                override fun onPeerOffline() {
                    DebugLog.d(TAG, "云端: Mac 离线")
                    updateServiceState()
                }

                override fun onNewClip(record: CloudClient.ClipRecord) {
                    if (!hashPool.checkAndRecord(record.text)) {
                        DebugLog.d(TAG, "云端收到剪贴板: len=${record.text.length}")
                        lastReceivedClip = record.text
                        lastSentClip = null
                        pendingClip = record.text
                        ClipboardHelper.write(context, record.text)
                        broadcastClipSynced(record.text, "incoming", "Mac (Cloud)")
                    }
                }
            }
            start()
        }
    }

    // ── 状态更新 ──────────────────────────────────────

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
                deviceLabel = connectedMacName.ifEmpty { PairingManager.macDeviceName.ifEmpty { "Mac" } }
                connLabel = "局域网直连"
            }
            presenceSM?.mode == PresenceStateMachine.Mode.POLL -> {
                state = GhostClipService.ConnectionState.CLOUD
                deviceLabel = "Mac"
                connLabel = "云端同步"
            }
            else -> {
                state = GhostClipService.ConnectionState.DISCONNECTED
                deviceLabel = ""
                connLabel = ""
            }
        }

        // 缓存最新状态
        lastState = state
        lastDeviceName = deviceLabel
        lastConnLabel = connLabel

        DebugLog.d(TAG, "连接状态更新: ${state.name}")

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
     * 广播剪贴板通知 (7.8) -- 收到远端剪贴板时弹临时通知
     */
    private fun broadcastClipNotification(text: String) {
        context.sendBroadcast(Intent(ACTION_CLIP_NOTIFICATION).apply {
            setPackage(context.packageName)
            putExtra("text", text)
        })
    }

    /**
     * 广播同步事件给 UI -- 显示同步记录
     */
    private fun broadcastClipSynced(text: String, direction: String, source: String) {
        addSyncRecord(text, direction, source)
        context.sendBroadcast(Intent(MainActivity.ACTION_CLIP_SYNCED).apply {
            setPackage(context.packageName)
            putExtra("text", text)
            putExtra("direction", direction)
            putExtra("source", source)
        })
    }

    /** 重新加载云端配置 */
    fun reloadCloudConfig() {
        DebugLog.d(TAG, "重新加载云端配置")
        cloudClient?.shutdown()
        presenceSM?.stop()
        cloudClient = null
        presenceSM = null
        setupCloudSync()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("ghostclip_mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            DebugLog.d(TAG, "MulticastLock 已获取")
        } catch (e: Exception) {
            DebugLog.e(TAG, "获取 MulticastLock 失败: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                DebugLog.d(TAG, "MulticastLock 已释放")
            }
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "NetCoordinator"
        private const val NSD_REFRESH_INTERVAL_MS = 30_000L
        private const val DISCOVERY_TIMEOUT_MS = 5_000L

        const val ACTION_DISCOVERY_TIMEOUT = "com.xkcoding.ghostclip.DISCOVERY_TIMEOUT"
        const val ACTION_KICKED = "com.xkcoding.ghostclip.KICKED"
        const val ACTION_CLIP_NOTIFICATION = "com.xkcoding.ghostclip.CLIP_NOTIFICATION"

        /** 最后的连接状态，供 MainActivity 在 onResume 时查询 */
        @Volatile
        var lastState: GhostClipService.ConnectionState = GhostClipService.ConnectionState.DISCONNECTED
            private set
        @Volatile
        var lastDeviceName: String = ""
            private set
        @Volatile
        var lastConnLabel: String = ""
            private set

        /** 后台收到的剪贴板内容，等前台 Activity 写入 */
        @Volatile
        var pendingClip: String? = null

        /** 最近一次从远端接收的剪贴板内容（防止 echo 回传） */
        @Volatile
        var lastReceivedClip: String? = null

        /** 最近一次上报给 Mac 的剪贴板内容（防止相同内容重复上报） */
        @Volatile
        var lastSentClip: String? = null

        /** 同步历史 */
        private val syncHistory = mutableListOf<SyncRecord>()

        data class SyncRecord(val text: String, val direction: String, val source: String, val timestamp: Long = System.currentTimeMillis())

        fun addSyncRecord(text: String, direction: String, source: String) {
            synchronized(syncHistory) {
                syncHistory.add(0, SyncRecord(text, direction, source))
                if (syncHistory.size > 20) syncHistory.removeAt(syncHistory.size - 1)
            }
        }

        fun getSyncHistory(): List<SyncRecord> {
            synchronized(syncHistory) {
                return syncHistory.toList()
            }
        }

        fun consumePendingClip(): String? {
            val clip = pendingClip
            pendingClip = null
            return clip
        }
    }
}
