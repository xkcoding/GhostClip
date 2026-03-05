package com.xkcoding.ghostclip.spike

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Spike: 验证透明 Activity 能否在 Android 16 + HyperOS 3.0 上读取剪贴板。
 *
 * 0dp x 0dp 透明 Activity，启动后立即读取 ClipboardManager，读完自毁。
 * 整个过程用户无感知。
 */
class TransparentClipboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GhostClip_Clip"
        const val ACTION_CLIPBOARD_READ = "com.xkcoding.ghostclip.spike.CLIPBOARD_READ"
        const val EXTRA_CLIP_TEXT = "clip_text"
        const val EXTRA_TIMESTAMP = "timestamp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 不设置 content view，保持 0dp x 0dp

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    Log.i(TAG, "✅ 剪贴板读取成功! 内容: \"$text\" (长度: ${text.length})")

                    // 通知 MainActivity 更新 UI
                    val intent = android.content.Intent(ACTION_CLIPBOARD_READ).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_CLIP_TEXT, text)
                        putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                    }
                    sendBroadcast(intent)
                } else {
                    Log.w(TAG, "⚠️ 剪贴板有 clip 但无 item")
                }
            } else {
                Log.w(TAG, "⚠️ 剪贴板为空")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: 无法读取剪贴板 - ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 读取剪贴板异常: ${e.message}", e)
        }

        // 立即关闭
        finish()
    }

    override fun finish() {
        super.finish()
        // 禁用关闭动画
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
