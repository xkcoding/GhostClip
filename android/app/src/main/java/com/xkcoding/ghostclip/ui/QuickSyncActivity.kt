package com.xkcoding.ghostclip.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.SyncBridge
import com.xkcoding.ghostclip.service.GhostClipService
import com.xkcoding.ghostclip.util.DebugLog

/**
 * 透明快速同步 Activity
 *
 * 悬浮球/快捷方式/Intent Extra(auto_sync=true) 触发后 finish()
 * 流程: 启动 -> 确保 Service -> 前台读取 -> 投递 -> finish()
 */
class QuickSyncActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.d(TAG, "QuickSyncActivity 启动")

        // 确保 Foreground Service 已启动（保证 SyncBridge.callback 已注册）
        GhostClipService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // 200ms 延迟确保 window focus 完全建立
        handler.postDelayed({ performQuickSync() }, 200)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            handler.postDelayed({ performQuickSync() }, 100)
        }
    }

    private var synced = false

    private fun performQuickSync() {
        if (synced) return
        synced = true

        val text = ClipboardHelper.read(this)
        if (text.isNullOrBlank()) {
            DebugLog.d(TAG, "\u526a\u8d34\u677f\u4e3a\u7a7a")
            Toast.makeText(this, "\u526a\u8d34\u677f\u4e3a\u7a7a", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        DebugLog.d(TAG, "\u8bfb\u53d6\u5230: ${text.take(50)}...")

        val hashPool = GhostClipService.hashPool
        val sent = SyncBridge.sendClip(text, hashPool, this)
        if (!sent) {
            DebugLog.d(TAG, "\u672a\u53d1\u9001(\u91cd\u590d\u6216\u670d\u52a1\u672a\u5c31\u7eea)")
        }

        DebugLog.d(TAG, "\u5b8c\u6210 finish()")
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuickSync"
    }
}
