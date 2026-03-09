package com.xkcoding.ghostclip.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 设备在线感知状态机
 *
 * IDLE 模式(无对端):
 * - 每 30s GET /peers 检查是否有 Mac 上线
 * - 不写心跳, KV 中 online 记录 15min TTL 后自然过期
 *
 * POLL 模式(有对端):
 * - 每 3s GET /clip 轮询新数据
 * - 每 10min PUT /register 续命心跳
 */
class PresenceStateMachine(
    private val scope: CoroutineScope,
    private val cloudClient: CloudClient,
) {
    enum class Mode { IDLE, POLL }

    interface Listener {
        fun onPeerOnline()
        fun onPeerOffline()
        fun onNewClip(record: CloudClient.ClipRecord)
    }

    var listener: Listener? = null
    var mode: Mode = Mode.IDLE
        private set

    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastClipHash: String? = null

    fun start() {
        switchToIdle()
    }

    fun stop() {
        pollJob?.cancel()
        heartbeatJob?.cancel()
        pollJob = null
        heartbeatJob = null
    }

    private fun switchToIdle() {
        stop()
        mode = Mode.IDLE
        Log.d(TAG, "进入 IDLE 模式 -- 30s 检查 /peers")

        pollJob = scope.launch {
            while (isActive) {
                val peers = cloudClient.getPeers()
                // 只关心 Mac 类型的对端（Android 端只与 Mac 配对）
                val hasMacPeer = peers.any { it.deviceType == "mac" }
                if (hasMacPeer) {
                    Log.d(TAG, "发现 Mac 对端 -> POLL 模式")
                    listener?.onPeerOnline()
                    switchToPoll()
                    return@launch
                }
                delay(IDLE_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun switchToPoll() {
        stop()
        mode = Mode.POLL
        Log.d(TAG, "进入 POLL 模式 -- 3s 轮询 /clip, 10min 心跳")

        // 立即注册 -- 让对端知道自己在线
        scope.launch { cloudClient.register() }

        // 轮询 3s
        pollJob = scope.launch {
            while (isActive) {
                val record = cloudClient.getClip(lastClipHash)
                if (record != null && record.deviceId != cloudClient.deviceId) {
                    lastClipHash = record.hash
                    listener?.onNewClip(record)
                } else if (record != null) {
                    lastClipHash = record.hash
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        // 心跳 10min
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val peers = cloudClient.getPeers()
                val hasMacPeer = peers.any { it.deviceType == "mac" }
                if (!hasMacPeer) {
                    Log.d(TAG, "Mac 对端下线 -> IDLE 模式")
                    listener?.onPeerOffline()
                    switchToIdle()
                    return@launch
                }
                cloudClient.register()
            }
        }
    }

    companion object {
        private const val TAG = "PresenceSM"
        private const val IDLE_CHECK_INTERVAL_MS = 10_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val HEARTBEAT_INTERVAL_MS = 10 * 60 * 1_000L // 10 min
    }
}
