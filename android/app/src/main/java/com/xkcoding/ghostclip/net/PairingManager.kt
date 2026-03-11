package com.xkcoding.ghostclip.net

import com.xkcoding.ghostclip.util.DebugLog

/**
 * 配对状态管理器 -- 仅内存存储，App 退出后丢失
 *
 * 状态机:
 * UNPAIRED ──(扫码)──► CONNECTING
 * CONNECTING ──(pair_ok)──► CONNECTED
 * CONNECTING ──(token 拒绝/超时)──► UNPAIRED
 * CONNECTED ──(断开+同WiFi)──► RECONNECTING
 * RECONNECTING ──(mDNS 重发现+重连)──► CONNECTED
 * CONNECTED ──(被踢/网络变更/解除)──► UNPAIRED
 */
object PairingManager {

    private const val TAG = "PairingManager"

    enum class State {
        UNPAIRED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
    }

    interface Listener {
        fun onStateChanged(old: State, new: State)
    }

    @Volatile
    var state: State = State.UNPAIRED
        private set

    @Volatile
    var macHash: String? = null
        private set

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var macDeviceName: String = ""
        private set

    /** WiFi SSID 快照 -- 用于检测网络切换 */
    @Volatile
    var wifiSsid: String? = null

    /** WiFi BSSID 快照 */
    @Volatile
    var wifiBssid: String? = null

    var listener: Listener? = null

    /**
     * 扫码成功 -- 保存配对信息，进入 CONNECTING
     */
    fun onScanned(info: PairingInfo) {
        macHash = info.macHash
        token = info.token
        macDeviceName = info.deviceName
        transition(State.CONNECTING)
    }

    /**
     * 收到 pair_ok -- 进入 CONNECTED
     */
    fun onPairOk(deviceName: String) {
        if (deviceName.isNotEmpty()) macDeviceName = deviceName
        transition(State.CONNECTED)
    }

    /**
     * 连接断开（同 WiFi 内）-- 进入 RECONNECTING，保留 token
     */
    fun onDisconnectedSameWifi() {
        if (state == State.CONNECTED || state == State.CONNECTING) {
            transition(State.RECONNECTING)
        }
    }

    /**
     * 重连成功 -- 回到 CONNECTED
     */
    fun onReconnected() {
        transition(State.CONNECTED)
    }

    /**
     * 被踢/网络变更/用户解除/token 拒绝 -- 清除配对信息，回到 UNPAIRED
     */
    fun reset(reason: String) {
        DebugLog.d(TAG, "配对重置: $reason")
        macHash = null
        token = null
        macDeviceName = ""
        transition(State.UNPAIRED)
    }

    /**
     * 连接超时（CONNECTING 阶段）-- 回到 UNPAIRED
     */
    fun onConnectTimeout() {
        if (state == State.CONNECTING) {
            reset("连接超时")
        }
    }

    private fun transition(newState: State) {
        val old = state
        if (old == newState) return
        state = newState
        DebugLog.d(TAG, "配对状态: $old -> $newState")
        listener?.onStateChanged(old, newState)
    }
}
