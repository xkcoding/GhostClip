package com.xkcoding.ghostclip.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.xkcoding.ghostclip.util.DebugLog

/**
 * mDNS/NSD 发现
 *
 * 监听 Mac 端 _ghostclip._tcp 服务，解析出 IP + 端口
 *
 * 注意: NsdManager.stopServiceDiscovery() 是异步的,
 * 必须等 onDiscoveryStopped 回调后才能用同一 listener 重新注册
 */
class NsdDiscovery(context: Context) {

    interface Listener {
        fun onServiceFound(host: String, port: Int, serviceName: String)
        fun onServiceLost(serviceName: String)
    }

    var listener: Listener? = null

    /** 配对 mac_hash -- 设置后仅回调匹配该值的服务，null 时回调所有服务 */
    @Volatile
    var filterMacHash: String? = null

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    // 使用锁保护状态标志，NSD 回调在独立线程中触发，需要线程安全
    private val stateLock = Any()
    @Volatile private var isDiscovering = false
    @Volatile private var isStopping = false
    @Volatile private var pendingRestart = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            DebugLog.d(TAG, "mDNS 发现已启动: $serviceType")
            synchronized(stateLock) {
                isDiscovering = true
                isStopping = false
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            DebugLog.d(TAG, "发现服务: ${serviceInfo.serviceName} (type=${serviceInfo.serviceType})")
            nsdManager.resolveService(serviceInfo, resolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            DebugLog.d(TAG, "服务丢失: ${serviceInfo.serviceName}")
            listener?.onServiceLost(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            DebugLog.d(TAG, "mDNS 发现已停止")
            val shouldRestart: Boolean
            synchronized(stateLock) {
                isDiscovering = false
                isStopping = false
                shouldRestart = pendingRestart
                pendingRestart = false
            }
            // 异步停止完成后，检查是否需要重启（在锁外执行，避免嵌套）
            if (shouldRestart) {
                DebugLog.d(TAG, "执行延迟重启 mDNS 发现")
                startDiscovery()
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            DebugLog.e(TAG, "mDNS 发现启动失败: errorCode=$errorCode")
            synchronized(stateLock) {
                isDiscovering = false
                isStopping = false
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            DebugLog.e(TAG, "mDNS 停止发现失败: errorCode=$errorCode")
            synchronized(stateLock) {
                isDiscovering = false
                isStopping = false
            }
        }
    }

    private fun resolveListener(retryCount: Int = 0): NsdManager.ResolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            DebugLog.e(TAG, "解析 ${serviceInfo.serviceName} 失败: errorCode=$errorCode (retry=$retryCount)")
            // 解析失败时重试（最多 2 次），常见于 Android NSD 内部竞争
            if (retryCount < MAX_RESOLVE_RETRIES) {
                DebugLog.d(TAG, "重试解析 ${serviceInfo.serviceName} ...")
                try {
                    nsdManager.resolveService(serviceInfo, resolveListener(retryCount + 1))
                } catch (e: Exception) {
                    DebugLog.e(TAG, "重试解析异常: ${e.message}")
                }
            }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            DebugLog.d(TAG, "解析成功: ${serviceInfo.serviceName} -> $host:$port")

            // mac_hash 过滤：优先从 TXT 记录读取，回退到服务名 gc-{mac_hash}
            val filter = filterMacHash
            if (filter != null) {
                val txtHash = serviceInfo.attributes?.get("mac_hash")
                    ?.let { String(it, Charsets.UTF_8) }
                val nameHash = serviceInfo.serviceName
                    .takeIf { it.startsWith("gc-") }
                    ?.removePrefix("gc-")
                val matched = filter.equals(txtHash, ignoreCase = true)
                    || filter.equals(nameHash, ignoreCase = true)
                if (!matched) {
                    DebugLog.d(TAG, "mac_hash 不匹配, 跳过: filter=$filter, txt=$txtHash, name=${serviceInfo.serviceName}")
                    return
                }
                DebugLog.d(TAG, "mac_hash 匹配成功: $filter")
            }

            listener?.onServiceFound(host, port, serviceInfo.serviceName)
        }
    }

    fun startDiscovery() {
        synchronized(stateLock) {
            if (isDiscovering || isStopping) {
                DebugLog.d(TAG, "mDNS 发现跳过: isDiscovering=$isDiscovering, isStopping=$isStopping")
                return
            }
        }
        try {
            DebugLog.d(TAG, "正在启动 mDNS 发现 ($SERVICE_TYPE)...")
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            DebugLog.e(TAG, "mDNS 发现异常: ${e.message}")
        }
    }

    fun stopDiscovery() {
        synchronized(stateLock) {
            if (!isDiscovering) return
            isStopping = true
        }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
            // 不立即设置 isDiscovering = false，等 onDiscoveryStopped 回调
        } catch (e: Exception) {
            DebugLog.e(TAG, "停止 mDNS 发现异常: ${e.message}")
            synchronized(stateLock) {
                isDiscovering = false
                isStopping = false
            }
        }
    }

    /**
     * 重启发现 -- 安全处理异步停止
     * 如果正在运行则先停止并标记 pendingRestart，等 onDiscoveryStopped 回调后自动重启
     */
    fun restartDiscovery() {
        val action: Int // 0=直接启动, 1=先停止再启动, 2=等停止回调后自动启动
        synchronized(stateLock) {
            DebugLog.d(TAG, "请求重启 mDNS 发现 (isDiscovering=$isDiscovering, isStopping=$isStopping)")
            action = when {
                isDiscovering -> {
                    pendingRestart = true
                    1
                }
                isStopping -> {
                    pendingRestart = true
                    2
                }
                else -> 0
            }
        }
        // 在锁外执行 NSD API 调用
        when (action) {
            0 -> startDiscovery()
            1 -> stopDiscovery()
            // 2 -> pendingRestart 已设置，onDiscoveryStopped 回调后会自动重启
        }
    }

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_ghostclip._tcp."
        private const val MAX_RESOLVE_RETRIES = 2
    }
}
