package com.xkcoding.ghostclip.net

import android.net.Uri

/**
 * 配对信息 -- 从 QR 码 URI 解析
 *
 * URI 格式: ghostclip://pair?mac_hash={mac_hash}&token={token}&device={device_name}&host={ip}&port={port}
 * - mac_hash: 12 字符 hex，用于 mDNS 过滤匹配
 * - token: 64 字符 hex，用于 WebSocket 鉴权
 * - device: URL-encoded 设备名称，仅展示用
 * - host: Mac 端局域网 IP（mDNS fallback）
 * - port: Mac 端 WebSocket 端口（mDNS fallback）
 */
data class PairingInfo(
    val macHash: String,
    val token: String,
    val deviceName: String,
    /** Mac 端 IP，mDNS 超时后直连用 */
    val host: String? = null,
    /** Mac 端 WebSocket 端口，mDNS 超时后直连用 */
    val port: Int? = null,
) {
    /** 是否包含直连信息（host + port） */
    val hasDirectConnect: Boolean get() = !host.isNullOrBlank() && port != null && port > 0

    companion object {
        private const val SCHEME = "ghostclip"
        private const val HOST = "pair"

        /**
         * 从 QR 码内容解析配对信息
         * @return PairingInfo 或 null（非合法 GhostClip URI）
         */
        fun parse(raw: String): PairingInfo? {
            val uri = try {
                Uri.parse(raw)
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != SCHEME || uri.host != HOST) return null

            val macHash = uri.getQueryParameter("mac_hash") ?: return null
            val token = uri.getQueryParameter("token") ?: return null
            val device = uri.getQueryParameter("device") ?: ""
            val host = uri.getQueryParameter("host")
            val port = uri.getQueryParameter("port")?.toIntOrNull()

            // 基本校验
            if (macHash.length != 12 || token.length != 64) return null
            if (!macHash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            if (!token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null

            return PairingInfo(macHash, token, device, host, port)
        }
    }
}
