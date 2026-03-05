package com.xkcoding.ghostclip.spike

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Spike: 验证 AccessibilityService 能否在 Android 16 + HyperOS 3.0 上检测到复制事件。
 *
 * 监听 TYPE_VIEW_TEXT_SELECTION_CHANGED 等事件，当检测到疑似复制操作时，
 * 启动 TransparentClipboardActivity 读取剪贴板内容。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GhostClip_A11y"
        private const val DEBOUNCE_MS = 500L
    }

    private var lastEventTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ AccessibilityService 已连接! Android ${android.os.Build.VERSION.SDK_INT}")
        Log.i(TAG, "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // 文本选择变化可能是复制操作的前置信号
                Log.d(TAG, "📋 TEXT_SELECTION_CHANGED - pkg: ${event.packageName}, text: ${event.text}")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 某些系统（如 HyperOS）复制时会触发 window content changed
                // 过滤: 只关注包含 "复制"/"copy"/"已复制" 的 toast 或 content description
                val desc = event.contentDescription?.toString()?.lowercase() ?: ""
                val texts = event.text?.joinToString()?.lowercase() ?: ""
                if (desc.contains("复制") || desc.contains("copy") ||
                    texts.contains("复制") || texts.contains("copied")
                ) {
                    Log.i(TAG, "🔔 疑似复制事件 (WINDOW_CONTENT) - pkg: ${event.packageName}")
                    if (now - lastEventTime > DEBOUNCE_MS) {
                        lastEventTime = now
                        launchTransparentReader()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 用户点击了可能的"复制"按钮
                val desc = event.contentDescription?.toString()?.lowercase() ?: ""
                val texts = event.text?.joinToString()?.lowercase() ?: ""
                if (desc.contains("复制") || desc.contains("copy") ||
                    texts.contains("复制") || texts.contains("copy")
                ) {
                    Log.i(TAG, "🔔 疑似复制事件 (CLICK) - pkg: ${event.packageName}, text: ${event.text}")
                    if (now - lastEventTime > DEBOUNCE_MS) {
                        lastEventTime = now
                        launchTransparentReader()
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ AccessibilityService 被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "❌ AccessibilityService 已销毁")
    }

    private fun launchTransparentReader() {
        Log.i(TAG, "🚀 启动透明 Activity 读取剪贴板...")
        val intent = Intent(this, TransparentClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }
}
