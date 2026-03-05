package com.xkcoding.ghostclip.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS/NSD
 *
 * Mac  _ghostclip._tcp  Mac  IP
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
            Log.d(TAG, "mDNS : $serviceType")
            isDiscovering = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, ": ${serviceInfo.serviceName}")
            // mDNS  IP
            nsdManager.resolveService(serviceInfo, resolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, ": ${serviceInfo.serviceName}")
            listener?.onServiceLost(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS ")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS : $errorCode")
            isDiscovering = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS : $errorCode")
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, " ${serviceInfo.serviceName} : $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            Log.d(TAG, ": ${serviceInfo.serviceName} -> $host:$port")
            listener?.onServiceFound(host, port, serviceInfo.serviceName)
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "mDNS : ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "mDNS : ${e.message}")
        }
        isDiscovering = false
    }

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_ghostclip._tcp."
    }
}
