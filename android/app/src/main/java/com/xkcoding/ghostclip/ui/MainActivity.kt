package com.xkcoding.ghostclip.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xkcoding.ghostclip.R
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.SyncBridge
import com.xkcoding.ghostclip.net.NetworkCoordinator
import com.xkcoding.ghostclip.service.GhostClipService
import com.xkcoding.ghostclip.util.DebugLog

/**
 * 主界面 -- 连接状态、最近同步记录、调试日志
 *
 * Intent auto_sync=true 时转发到 QuickSyncActivity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var deviceInfo: LinearLayout
    private lateinit var deviceName: TextView
    private lateinit var connType: TextView
    private lateinit var syncList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var syncCount: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private val syncRecords = mutableListOf<SyncRecord>()
    private var batteryGuideShown = false

    // 监听来自 Service 的状态更新
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONNECTION_CHANGED -> {
                    val stateName = intent.getStringExtra("state") ?: return
                    val device = intent.getStringExtra("device_name") ?: ""
                    val connLabel = intent.getStringExtra("conn_label") ?: ""
                    updateConnectionUI(stateName, device, connLabel)
                }
                ACTION_CLIP_SYNCED -> {
                    val text = intent.getStringExtra("text") ?: return
                    val direction = intent.getStringExtra("direction") ?: "incoming"
                    val source = intent.getStringExtra("source") ?: "Android"
                    addSyncRecord(text, direction, source)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // auto_sync 时转发到 QuickSyncActivity
        if (intent?.getBooleanExtra(EXTRA_AUTO_SYNC, false) == true) {
            startActivity(Intent(this, QuickSyncActivity::class.java))
            finish()
            return
        }

        // 启动 Foreground Service
        GhostClipService.start(this)

        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        setupDebugLog()

        // 初始状态
        updateConnectionUI("DISCONNECTED", "", "")
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ readClipboard("onResume") }, 200)

        // 首次启动检测电池优化引导 (每次 Activity 生命周期只弹一次)
        if (!batteryGuideShown && BatteryGuideActivity.shouldShow(this)) {
            batteryGuideShown = true
            startActivity(Intent(this, BatteryGuideActivity::class.java))
        }

        // 注册广播接收
        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECTION_CHANGED)
            addAction(ACTION_CLIP_SYNCED)
        }
        ContextCompat.registerReceiver(
            this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 主动查询当前连接状态（补偿注册前可能错过的广播）
        updateConnectionUI(
            NetworkCoordinator.lastState.name,
            NetworkCoordinator.lastDeviceName,
            NetworkCoordinator.lastConnLabel
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            handler.postDelayed({ readClipboard("onFocus") }, 100)
        }
    }

    override fun onDestroy() {
        DebugLog.onNewLog = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindViews() {
        statusCard = findViewById(R.id.status_card)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        deviceInfo = findViewById(R.id.device_info)
        deviceName = findViewById(R.id.device_name)
        connType = findViewById(R.id.conn_type)
        syncList = findViewById(R.id.sync_list)
        emptyState = findViewById(R.id.empty_state)
        syncCount = findViewById(R.id.sync_count)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
    }

    private fun setupListeners() {
        findViewById<FrameLayout>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.btn_clear_logs).setOnClickListener {
            DebugLog.clear()
            logText.text = ""
        }
    }

    private fun setupDebugLog() {
        // 显示已有日志
        logText.text = DebugLog.getAll()

        // 监听新日志
        DebugLog.onNewLog = { line ->
            logText.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        // 初始滚动到底部
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun readClipboard(source: String) {
        val text = ClipboardHelper.read(this)
        if (text.isNullOrBlank()) {
            DebugLog.d(TAG, "[$source] 剪贴板为空")
            return
        }
        DebugLog.d(TAG, "[$source] 读取剪贴板: ${text.take(80)}")

        // 前台自动发送
        val sent = SyncBridge.sendClip(text, GhostClipService.hashPool, this)
        if (sent) {
            DebugLog.d(TAG, "[$source] 已投递同步")
        } else {
            DebugLog.d(TAG, "[$source] 未发送(重复或服务未就绪)")
        }
    }

    /**
     * 更新连接状态 UI
     */
    private fun updateConnectionUI(stateName: String, device: String, connLabel: String) {
        when (stateName) {
            "LAN" -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_connected)
                statusDot.setBackgroundResource(R.drawable.circle_accent)
                statusText.text = getString(R.string.status_connected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_dark))
                deviceInfo.visibility = View.VISIBLE
                deviceName.text = device.ifEmpty { "MacBook Pro" }
                connType.text = connLabel.ifEmpty { "局域网直连" }
            }
            "CLOUD" -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_cloud)
                statusDot.setBackgroundResource(R.drawable.circle_warning)
                statusText.text = getString(R.string.status_cloud)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
                deviceInfo.visibility = View.VISIBLE
                deviceName.text = device.ifEmpty { "MacBook Pro" }
                connType.text = connLabel.ifEmpty { "云端同步" }
            }
            else -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_disconnected)
                statusDot.setBackgroundResource(R.drawable.circle_error)
                statusText.text = getString(R.string.status_disconnected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
                deviceInfo.visibility = View.GONE
            }
        }
    }

    /**
     * 添加同步记录到列表
     */
    private fun addSyncRecord(text: String, direction: String, source: String) {
        syncRecords.add(0, SyncRecord(text, direction, source, System.currentTimeMillis()))
        if (syncRecords.size > 20) syncRecords.removeAt(syncRecords.size - 1)
        refreshSyncList()
    }

    private fun refreshSyncList() {
        if (syncRecords.isEmpty()) {
            syncList.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            syncCount.text = ""
            return
        }

        syncList.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        syncCount.text = getString(R.string.today_count, syncRecords.size)

        syncList.removeAllViews()
        for ((index, record) in syncRecords.withIndex()) {
            if (index > 0) {
                // 分割线
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        marginStart = 16.dp
                        marginEnd = 16.dp
                    }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border))
                }
                syncList.addView(divider)
            }

            val itemView = LayoutInflater.from(this).inflate(R.layout.item_sync, syncList, false)
            val iconBg = itemView.findViewById<FrameLayout>(R.id.icon_bg)
            val iconDir = itemView.findViewById<ImageView>(R.id.icon_direction)
            val textContent = itemView.findViewById<TextView>(R.id.text_content)
            val textTime = itemView.findViewById<TextView>(R.id.text_time)

            val isIncoming = record.direction == "incoming"

            if (isIncoming) {
                iconBg.setBackgroundResource(R.drawable.bg_icon_accent)
                iconDir.setImageResource(R.drawable.ic_arrow_down_left)
                iconDir.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            } else {
                iconBg.setBackgroundResource(R.drawable.bg_icon_warning)
                iconDir.setImageResource(R.drawable.ic_arrow_up_right)
                iconDir.setColorFilter(ContextCompat.getColor(this, R.color.warning))
            }

            textContent.text = record.text
            val timeAgo = formatTimeAgo(record.timestamp)
            val sourceLabel = if (isIncoming) {
                getString(R.string.from_device, record.source)
            } else {
                getString(R.string.send_to_device, record.source)
            }
            textTime.text = "$timeAgo \u00b7 $sourceLabel"

            syncList.addView(itemView)
        }
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            seconds < 10 -> getString(R.string.time_just_now)
            seconds < 60 -> getString(R.string.time_seconds_ago, seconds.toInt())
            minutes < 60 -> getString(R.string.time_minutes_ago, minutes.toInt())
            else -> getString(R.string.time_hours_ago, hours.toInt())
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    data class SyncRecord(
        val text: String,
        val direction: String,
        val source: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_AUTO_SYNC = "auto_sync"
        const val ACTION_CONNECTION_CHANGED = "com.xkcoding.ghostclip.CONNECTION_CHANGED"
        const val ACTION_CLIP_SYNCED = "com.xkcoding.ghostclip.CLIP_SYNCED"
    }
}
