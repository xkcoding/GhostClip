package com.xkcoding.ghostclip.spike

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Spike: 验证后台剪贴板变化检测。
 *
 * 核心策略: ClipboardManager.addPrimaryClipChangedListener() 检测变化
 * → 启动透明 Activity 前台读取内容（Android 10+ 仅前台 App 能读剪贴板）
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GhostClip_A11y"
        private const val DEBOUNCE_MS = 1000L
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastTriggerTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = System.currentTimeMillis()
        Log.i(TAG, "🔔 ClipboardManager.onPrimaryClipChanged 触发!")

        if (now - lastTriggerTime > DEBOUNCE_MS) {
            lastTriggerTime = now
            // 延迟 150ms 启动，确保剪贴板写入完成
            handler.postDelayed({ launchTransparentReader() }, 150)
        } else {
            Log.d(TAG, "⏳ 防抖: 距上次触发 ${now - lastTriggerTime}ms，跳过")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ AccessibilityService 已连接!")
        Log.i(TAG, "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Log.i(TAG, "系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")

        // 注册剪贴板变化监听
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        Log.i(TAG, "📋 ClipboardManager 监听器已注册")

        SpikeEventBus.post("✅ 无障碍服务已连接，剪贴板监听已注册")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不再依赖 accessibility event 检测复制
        // 仅用于保持服务活跃和调试
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ AccessibilityService 被中断")
        SpikeEventBus.post("⚠️ 无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        Log.i(TAG, "❌ AccessibilityService 已销毁，监听器已移除")
        SpikeEventBus.post("❌ 无障碍服务已销毁")
    }

    private fun launchTransparentReader() {
        Log.i(TAG, "🚀 启动透明 Activity 读取剪贴板...")
        SpikeEventBus.post("🚀 检测到剪贴板变化，启动读取...")
        val intent = Intent(this, TransparentClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }
}
