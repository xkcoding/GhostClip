package com.xkcoding.ghostclip.spike

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spike 主界面：显示验证日志，引导开启无障碍服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var scrollView: ScrollView
    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 纯代码布局
        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // 标题
        rootLayout.addView(TextView(this).apply {
            text = "GhostClip Spike"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // 设备信息
        rootLayout.addView(TextView(this).apply {
            text = "设备: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            textSize = 14f
            setPadding(0, 16, 0, 24)
            setTextColor(0xFF666666.toInt())
        })

        // 无障碍服务状态
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(statusView)

        // 按钮区域
        val btnLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        btnLayout.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        btnLayout.addView(Button(this).apply {
            text = "手动读取剪贴板"
            setPadding(24, 0, 0, 0)
            setOnClickListener {
                appendLog("手动触发透明 Activity...")
                val intent = Intent(this@MainActivity, TransparentClipboardActivity::class.java)
                startActivity(intent)
            }
        })
        rootLayout.addView(btnLayout)

        // 日志标题
        rootLayout.addView(TextView(this).apply {
            text = "验证日志"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        // 日志滚动区域
        scrollView = ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        logView = TextView(this).apply {
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextIsSelectable(true)
        }
        scrollView.addView(logView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        // 注册 SpikeEventBus 监听
        SpikeEventBus.register { message ->
            appendLog(message)
        }

        appendLog("Spike 应用启动")
        appendLog("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("---")
        appendLog("请先在系统设置中开启 GhostClip Spike 的无障碍服务")
        appendLog("然后去任意 App 复制一段文字，观察此处日志")
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        SpikeEventBus.unregister()
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        statusView.text = if (enabled) {
            "无障碍服务: 已启用"
        } else {
            "无障碍服务: 未启用 (请点击下方按钮开启)"
        }
        statusView.setTextColor(if (enabled) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())

        if (enabled) {
            appendLog("无障碍服务已启用，等待复制事件...")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${ClipboardAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        logBuilder.appendLine("[$timestamp] $message")
        logView.text = logBuilder.toString()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
