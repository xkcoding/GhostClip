package com.xkcoding.ghostclip.clip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.xkcoding.ghostclip.util.DebugLog

/**
 * ClipboardManager
 */
object ClipboardHelper {

    private const val TAG = "Clipboard"

    /**
     * 读取剪贴板，返回 null 表示无内容
     */
    fun read(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!cm.hasPrimaryClip()) return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) {
                DebugLog.d(TAG, "读取剪贴板: ${text.take(80)}")
            }
            text
        } catch (e: Exception) {
            DebugLog.e(TAG, "读取剪贴板异常: ${e.message}")
            null
        }
    }

    /**
     * 写入剪贴板（接收远端内容时调用）
     * 注意: Android 10+ 后台 Service 调用会静默失败，需要在前台 Activity 中调用
     */
    fun write(context: Context, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GhostClip", text)
            cm.setPrimaryClip(clip)
            DebugLog.d(TAG, "写入剪贴板: ${text.take(80)}")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "写入剪贴板失败(可能后台限制): ${e.message}")
            false
        }
    }
}
