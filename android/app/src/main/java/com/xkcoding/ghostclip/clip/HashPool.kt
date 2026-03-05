package com.xkcoding.ghostclip.clip

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * MD5 Hash LRU 池 -- 3s TTL
 *
 * 通过 hash 比对防止 clip 重复发送/接收
 */
class HashPool(private val maxSize: Int = 32) {

    private data class Entry(val hash: String, val expireAt: Long)

    private val pool = object : LinkedHashMap<String, Entry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            return size > maxSize
        }
    }

    /**
     * 检查 hash 是否重复, true=重复, false=新内容
     */
    @Synchronized
    fun isDuplicate(text: String): Boolean {
        evictExpired()
        val hash = md5(text)
        return pool.containsKey(hash)
    }

    /**
     * 记录 hash (给外部预计算好 hash 的场景)
     */
    @Synchronized
    fun recordHash(hash: String) {
        evictExpired()
        pool[hash] = Entry(hash, System.currentTimeMillis() + TTL_MS)
    }

    /**
     * 检查并记录 -- 接受预计算的 hash 避免重复计算
     * @return true=重复(已存在), false=新内容(已记录)
     */
    @Synchronized
    fun checkAndRecord(text: String, hash: String = md5(text)): Boolean {
        evictExpired()
        if (pool.containsKey(hash)) return true
        pool[hash] = Entry(hash, System.currentTimeMillis() + TTL_MS)
        return false
    }

    @Synchronized
    fun clear() {
        pool.clear()
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        pool.entries.removeAll { it.value.expireAt <= now }
    }

    companion object {
        private const val TTL_MS = 3_000L

        fun md5(text: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(text.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
