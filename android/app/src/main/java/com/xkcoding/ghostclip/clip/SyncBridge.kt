package com.xkcoding.ghostclip.clip

import android.content.Context
import android.widget.Toast

/**
 * 同步桥接 -- 连接 UI 层与网络层
 * QuickSyncActivity / GhostClipService 调用
 */
object SyncBridge {

    interface SyncCallback {
        fun onSend(text: String, hash: String)
    }

    var callback: SyncCallback? = null

    /**
     * 发送剪贴板 -- hashPool 做 dedup, 返回是否实际发送
     * @param context 用于在 callback 为 null 时给出 Toast 提示
     */
    fun sendClip(text: String, hashPool: HashPool, context: Context? = null): Boolean {
        val hash = HashPool.md5(text)
        if (hashPool.checkAndRecord(text, hash)) return false

        val cb = callback
        if (cb == null) {
            context?.let {
                Toast.makeText(it, "\u540c\u6b65\u670d\u52a1\u672a\u5c31\u7eea\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5", Toast.LENGTH_SHORT).show()
            }
            return false
        }
        cb.onSend(text, hash)
        return true
    }
}
