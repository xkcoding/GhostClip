package com.xkcoding.ghostclip.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Worker HTTP 客户端
 *
 * 端点 (与 worker/src/index.ts 签名对齐):
 * - POST /clip: body { device_id, text, hash, timestamp } -> { status, hash }
 * - GET /clip?last_hash=xxx: 304 无变化 / 200 { device_id, text, hash, timestamp }
 * - PUT /register: body { device_id, device_type } -> { status: "ok" }
 * - GET /peers: -> { peers: [{ device_id, device_type }] }
 */
class CloudClient(
    private val baseUrl: String,
    private val token: String,
    val deviceId: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class ClipRecord(
        val text: String,
        val hash: String,
        val deviceId: String,
        val timestamp: Long,
    )

    data class Peer(
        val deviceId: String,
        val deviceType: String,
    )

    /**
     * POST /clip -- 发送剪贴板内容到云端
     * @return true 成功, false 失败
     */
    suspend fun postClip(text: String, hash: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("text", text)
                put("hash", hash)
                put("timestamp", System.currentTimeMillis())
            }
            val request = Request.Builder()
                .url("$baseUrl/clip")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->
                // 必须消费 response body，否则连接无法复用
                val respBody = response.body?.string()
                val ok = response.isSuccessful
                if (!ok) Log.w(TAG, "POST /clip 失败: ${response.code}, body=$respBody")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST /clip 异常: ${e.message}")
            false
        }
    }

    /**
     * GET /clip?last_hash=xxx
     * @return 新数据或 null(304/无数据/异常)
     */
    suspend fun getClip(lastHash: String?): ClipRecord? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder("$baseUrl/clip?device_id=$deviceId")
            if (lastHash != null) urlBuilder.append("&last_hash=$lastHash")
            val url = urlBuilder.toString()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@withContext null
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET /clip 失败: ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                ClipRecord(
                    text = json.getString("text"),
                    hash = json.getString("hash"),
                    deviceId = json.getString("device_id"),
                    timestamp = json.getLong("timestamp"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET /clip 异常: ${e.message}")
            null
        }
    }

    /**
     * PUT /register -- 注册设备在线状态
     */
    suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("device_type", "android")
            }
            val request = Request.Builder()
                .url("$baseUrl/register")
                .addHeader("Authorization", "Bearer $token")
                .put(body.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "PUT /register 异常: ${e.message}")
            false
        }
    }

    /**
     * GET /peers -- 获取在线设备列表
     */
    suspend fun getPeers(): List<Peer> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/peers")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
                val peers = json.getJSONArray("peers")
                (0 until peers.length()).map { i ->
                    val p = peers.getJSONObject(i)
                    Peer(
                        deviceId = p.getString("device_id"),
                        deviceType = p.getString("device_type"),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET /peers 异常: ${e.message}")
            emptyList()
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val TAG = "CloudClient"
    }
}
