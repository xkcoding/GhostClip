package com.xkcoding.ghostclip.spike

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Spike: 透明 Activity，前台读取剪贴板内容。
 *
 * Android 10+ 仅前台 App 可读剪贴板，所以需要短暂启动此 Activity。
 * 读取完成后通过 SpikeEventBus 通知 MainActivity，然后立即自毁。
 */
class TransparentClipboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GhostClip_Clip"
        private const val READ_DELAY_MS = 100L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不设置 content view，保持 0dp x 0dp

        // 延迟 100ms 确保 Activity 完全进入前台后再读取
        Handler(Looper.getMainLooper()).postDelayed({ readAndFinish() }, READ_DELAY_MS)
    }

    private fun readAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    Log.i(TAG, "剪贴板读取成功! 内容: \"$text\" (长度: ${text.length})")
                    SpikeEventBus.post("剪贴板内容: \"$text\"")
                } else {
                    Log.w(TAG, "剪贴板有 clip 但无 item")
                    SpikeEventBus.post("剪贴板有 clip 但无 item")
                }
            } else {
                Log.w(TAG, "剪贴板为空")
                SpikeEventBus.post("剪贴板为空")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: 无法读取剪贴板 - ${e.message}")
            SpikeEventBus.post("SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "读取剪贴板异常: ${e.message}", e)
            SpikeEventBus.post("读取异常: ${e.message}")
        }

        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
