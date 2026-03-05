package com.xkcoding.ghostclip.spike

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spike 主界面：每次回到前台自动读取剪贴板。
 *
 * 配合小米悬浮球使用：复制 → 点悬浮球打开 App → 自动读取+展示。
 * 后续接入云端投递后，可加自动退回逻辑。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var clipContentView: TextView
    private lateinit var scrollView: ScrollView
    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private var lastClipText: String? = null

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
            text = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            textSize = 13f
            setPadding(0, 8, 0, 24)
            setTextColor(0xFF999999.toInt())
        })

        // 最新剪贴板内容卡片
        clipContentView = TextView(this).apply {
            textSize = 16f
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFFF0FDF4.toInt())
            setTextColor(0xFF166534.toInt())
            text = "等待读取剪贴板..."
            setTextIsSelectable(true)
        }
        rootLayout.addView(clipContentView)

        // 按钮区域
        val btnLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
        }

        btnLayout.addView(Button(this).apply {
            text = "手动读取"
            setOnClickListener { readClipboard("手动触发") }
        })
        rootLayout.addView(btnLayout)

        // 日志标题
        rootLayout.addView(TextView(this).apply {
            text = "日志"
            textSize = 14f
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
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setTextColor(0xFF666666.toInt())
        }
        scrollView.addView(logView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        appendLog("App 启动")
    }

    override fun onResume() {
        super.onResume()
        // 延迟 200ms 读取，确保 window focus 完全建立（悬浮球唤起场景需要）
        handler.postDelayed({ readClipboard("自动(onResume)") }, 200)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // window focus 变化时也尝试读取（覆盖悬浮球等特殊唤起方式）
        if (hasFocus) {
            handler.postDelayed({ readClipboard("自动(onFocus)") }, 100)
        }
    }

    private fun readClipboard(source: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    if (text != lastClipText) {
                        lastClipText = text
                        clipContentView.text = text
                        appendLog("[$source] 新内容: \"${text.take(80)}${if (text.length > 80) "..." else ""}\"")
                        // TODO: 后续在这里接入云端投递
                    } else {
                        appendLog("[$source] 内容未变化")
                    }
                } else {
                    appendLog("[$source] 剪贴板无 item")
                }
            } else {
                appendLog("[$source] 剪贴板为空")
            }
        } catch (e: Exception) {
            appendLog("[$source] 读取失败: ${e.message}")
        }
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        logBuilder.appendLine("[$timestamp] $message")
        logView.text = logBuilder.toString()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
