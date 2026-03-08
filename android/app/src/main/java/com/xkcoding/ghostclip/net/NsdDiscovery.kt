package com.xkcoding.ghostclip.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.xkcoding.ghostclip.util.DebugLog

/**
 * mDNS/NSD 发现
 *
 * 监听 Mac 端 _ghostclip._tcp 服务，解析出 IP + 端口
 */
class NsdDiscovery(context: Context) {

    interface Listener {
        fun onServiceFound(host: String, port: Int, serviceName: String)
        fun onServiceLost(serviceName: String)
    }

    var listener: Listener? = null

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            DebugLog.d(TAG, "mDNS 发现已启动: $serviceType")
            isDiscovering = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            DebugLog.d(TAG, "发现服务: ${serviceInfo.serviceName} (type=${serviceInfo.serviceType})")
            // 解析服务获取 IP + 端口
            nsdManager.resolveService(serviceInfo, resolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            DebugLog.d(TAG, "服务丢失: ${serviceInfo.serviceName}")
            listener?.onServiceLost(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            DebugLog.d(TAG, "mDNS 发现已停止")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            DebugLog.e(TAG, "mDNS 发现启动失败: errorCode=$errorCode")
            isDiscovering = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            DebugLog.e(TAG, "mDNS 停止发现失败: errorCode=$errorCode")
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            DebugLog.e(TAG, "解析 ${serviceInfo.serviceName} 失败: errorCode=$errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            DebugLog.d(TAG, "解析成功: ${serviceInfo.serviceName} -> $host:$port")
            listener?.onServiceFound(host, port, serviceInfo.serviceName)
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        try {
            DebugLog.d(TAG, "正在启动 mDNS 发现 ($SERVICE_TYPE)...")
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            DebugLog.e(TAG, "mDNS 发现异常: ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            DebugLog.e(TAG, "停止 mDNS 发现异常: ${e.message}")
        }
        isDiscovering = false
    }

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_ghostclip._tcp."
    }
}
