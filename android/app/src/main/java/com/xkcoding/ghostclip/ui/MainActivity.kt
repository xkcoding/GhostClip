package com.xkcoding.ghostclip.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xkcoding.ghostclip.R
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.SyncBridge
import com.xkcoding.ghostclip.net.NetworkCoordinator
import com.xkcoding.ghostclip.net.PairingInfo
import com.xkcoding.ghostclip.net.PairingManager
import com.xkcoding.ghostclip.service.GhostClipService
import com.xkcoding.ghostclip.util.DebugLog

/**
 * 主界面 -- 连接状态、配对操作、最近同步记录、调试日志
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
    private lateinit var btnPairAction: TextView
    private lateinit var hintUnpaired: TextView
    private lateinit var syncList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var syncCount: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private val syncRecords = mutableListOf<SyncRecord>()
    private var batteryGuideShown = false

    // 扫码结果回调
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val macHash = data.getStringExtra(ScanActivity.EXTRA_MAC_HASH) ?: return@registerForActivityResult
            val token = data.getStringExtra(ScanActivity.EXTRA_TOKEN) ?: return@registerForActivityResult
            val device = data.getStringExtra(ScanActivity.EXTRA_DEVICE_NAME) ?: ""
            val host = data.getStringExtra(ScanActivity.EXTRA_HOST)
            val port = data.getIntExtra(ScanActivity.EXTRA_PORT, 0).takeIf { it > 0 }

            DebugLog.d(TAG, "扫码结果: mac_hash=$macHash, device=$device, host=$host, port=$port")
            val info = PairingInfo(macHash, token, device, host, port)

            // 通知 NetworkCoordinator 启动配对连接
            GhostClipService.onScanResult(info)
        }
    }

    // 监听来自 Service 的状态更新
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONNECTION_CHANGED -> {
                    val stateName = intent.getStringExtra("state") ?: return
                    val device = intent.getStringExtra("device_name") ?: ""
                    val connLabel = intent.getStringExtra("conn_label") ?: ""
                    DebugLog.d(TAG, "收到连接广播: state=$stateName, device=$device")
                    updateConnectionUI(stateName, device, connLabel)
                    updatePairingUI()
                }
                ACTION_CLIP_SYNCED -> {
                    val text = intent.getStringExtra("text") ?: return
                    val direction = intent.getStringExtra("direction") ?: "incoming"
                    val source = intent.getStringExtra("source") ?: "Android"
                    DebugLog.d(TAG, "收到同步广播: dir=$direction, source=$source, len=${text.length}")
                    addSyncRecord(text, direction, source)
                }
                NetworkCoordinator.ACTION_KICKED -> {
                    Toast.makeText(this@MainActivity, R.string.kicked_toast, Toast.LENGTH_LONG).show()
                    updatePairingUI()
                }
                NetworkCoordinator.ACTION_DISCOVERY_TIMEOUT -> {
                    Toast.makeText(this@MainActivity, R.string.discovery_timeout_toast, Toast.LENGTH_LONG).show()
                    updatePairingUI()
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
        updatePairingUI()
    }

    override fun onResume() {
        super.onResume()
        DebugLog.d(TAG, "onResume")

        // 首次启动检测电池优化引导
        if (!batteryGuideShown && BatteryGuideActivity.shouldShow(this)) {
            batteryGuideShown = true
            startActivity(Intent(this, BatteryGuideActivity::class.java))
        }

        // 注册广播接收
        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECTION_CHANGED)
            addAction(ACTION_CLIP_SYNCED)
            addAction(NetworkCoordinator.ACTION_KICKED)
            addAction(NetworkCoordinator.ACTION_DISCOVERY_TIMEOUT)
        }
        ContextCompat.registerReceiver(
            this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 主动查询当前连接状态
        val state = NetworkCoordinator.lastState
        DebugLog.d(TAG, "查询连接状态: ${state.name}, device=${NetworkCoordinator.lastDeviceName}")
        updateConnectionUI(state.name, NetworkCoordinator.lastDeviceName, NetworkCoordinator.lastConnLabel)
        updatePairingUI()

        // 从同步历史恢复列表
        val history = NetworkCoordinator.getSyncHistory()
        if (history.isNotEmpty()) {
            DebugLog.d(TAG, "恢复 ${history.size} 条同步记录")
            syncRecords.clear()
            for (record in history) {
                syncRecords.add(SyncRecord(record.text, record.direction, record.source, record.timestamp))
            }
            refreshSyncList()
        }

        // 检查后台收到的远端剪贴板 -- 无条件写入（pendingClip 来自远端，后台写入可能静默失败）
        NetworkCoordinator.consumePendingClip()?.let { pendingText ->
            DebugLog.d(TAG, "前台写入 pending 远端剪贴板: ${pendingText.take(80)}")
            ClipboardHelper.write(this, pendingText)
            GhostClipService.hashPool.checkAndRecord(pendingText)
        }

        // 延迟读取剪贴板
        handler.postDelayed({ readClipboard("onResume") }, 300)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            DebugLog.d(TAG, "onWindowFocusChanged: hasFocus=true")

            NetworkCoordinator.consumePendingClip()?.let { pendingText ->
                DebugLog.d(TAG, "focus 写入 pending 远端剪贴板: ${pendingText.take(80)}")
                ClipboardHelper.write(this, pendingText)
                GhostClipService.hashPool.checkAndRecord(pendingText)
            }

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
        btnPairAction = findViewById(R.id.btn_pair_action)
        hintUnpaired = findViewById(R.id.hint_unpaired)
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

        findViewById<TextView>(R.id.btn_copy_logs).setOnClickListener {
            val logs = DebugLog.getAll()
            if (logs.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GhostClip Logs", logs))
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.btn_clear_logs).setOnClickListener {
            DebugLog.clear()
            logText.text = ""
        }

        btnPairAction.setOnClickListener {
            when (PairingManager.state) {
                PairingManager.State.UNPAIRED -> {
                    // 打开扫码页
                    scanLauncher.launch(Intent(this, ScanActivity::class.java))
                }
                PairingManager.State.CONNECTED, PairingManager.State.RECONNECTING -> {
                    // 解除配对
                    GhostClipService.unpair()
                    Toast.makeText(this, R.string.unpaired_toast, Toast.LENGTH_SHORT).show()
                    updatePairingUI()
                }
                else -> {} // CONNECTING 时不操作
            }
        }
    }

    /**
     * 更新配对按钮 UI
     */
    private fun updatePairingUI() {
        when (PairingManager.state) {
            PairingManager.State.UNPAIRED -> {
                // 红色主题卡片 + 绿色扫码按钮
                statusCard.setBackgroundResource(R.drawable.bg_status_card_disconnected)
                statusDot.setBackgroundResource(R.drawable.circle_error)
                statusText.text = getString(R.string.status_unpaired)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
                deviceInfo.visibility = View.GONE
                hintUnpaired.visibility = View.VISIBLE
                btnPairAction.text = getString(R.string.btn_scan_pair)
                btnPairAction.setBackgroundResource(R.drawable.bg_btn_accent)
                btnPairAction.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent))
                btnPairAction.visibility = View.VISIBLE
            }
            PairingManager.State.CONNECTING -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_disconnected)
                statusDot.setBackgroundResource(R.drawable.circle_warning)
                statusText.text = getString(R.string.status_connecting)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
                deviceInfo.visibility = View.GONE
                hintUnpaired.visibility = View.GONE
                btnPairAction.visibility = View.GONE
            }
            PairingManager.State.CONNECTED -> {
                hintUnpaired.visibility = View.GONE
                btnPairAction.text = getString(R.string.btn_unpair)
                btnPairAction.setBackgroundResource(R.drawable.bg_btn_unpair)
                btnPairAction.setTextColor(ContextCompat.getColor(this, R.color.error))
                btnPairAction.visibility = View.VISIBLE
            }
            PairingManager.State.RECONNECTING -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_disconnected)
                statusDot.setBackgroundResource(R.drawable.circle_warning)
                statusText.text = getString(R.string.status_reconnecting)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
                hintUnpaired.visibility = View.GONE
                deviceInfo.visibility = View.VISIBLE
                deviceName.text = PairingManager.macDeviceName.ifEmpty { "Mac" }
                connType.text = "重连中…"
                btnPairAction.text = getString(R.string.btn_unpair)
                btnPairAction.setBackgroundResource(R.drawable.bg_btn_unpair)
                btnPairAction.setTextColor(ContextCompat.getColor(this, R.color.error))
                btnPairAction.visibility = View.VISIBLE
            }
        }
    }

    private fun setupDebugLog() {
        logText.text = DebugLog.getAll()
        DebugLog.onNewLog = { line ->
            logText.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun readClipboard(source: String) {
        // 未连接时不读取剪贴板，避免无意义的操作
        if (PairingManager.state != PairingManager.State.CONNECTED) {
            DebugLog.d(TAG, "[$source] 跳过(未连接)")
            return
        }

        val text = ClipboardHelper.read(this)
        if (text.isNullOrBlank()) {
            DebugLog.d(TAG, "[$source] 剪贴板为空")
            return
        }
        DebugLog.d(TAG, "[$source] 读取剪贴板: ${text.take(80)}")

        val lastReceived = NetworkCoordinator.lastReceivedClip
        if (lastReceived != null && text == lastReceived) {
            DebugLog.d(TAG, "[$source] 跳过回传(内容来自远端)")
            return
        }

        if (text == NetworkCoordinator.lastSentClip) {
            DebugLog.d(TAG, "[$source] 跳过(内容与上次上报相同)")
            return
        }

        val sent = SyncBridge.sendClip(text, GhostClipService.hashPool, this)
        if (sent) {
            NetworkCoordinator.lastSentClip = text
            DebugLog.d(TAG, "[$source] 已投递同步")
        } else {
            DebugLog.d(TAG, "[$source] 未发送(重复或服务未就绪)")
        }
    }

    /**
     * 更新连接状态 UI
     */
    private fun updateConnectionUI(stateName: String, device: String, connLabel: String) {
        DebugLog.d(TAG, "UI 更新连接状态: $stateName")
        when (stateName) {
            "LAN" -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_connected)
                statusDot.setBackgroundResource(R.drawable.circle_accent)
                statusText.text = getString(R.string.status_connected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_dark))
                deviceInfo.visibility = View.VISIBLE
                deviceName.text = device.ifEmpty { PairingManager.macDeviceName.ifEmpty { "Mac" } }
                connType.text = connLabel.ifEmpty { "局域网直连" }
            }
            "CLOUD" -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_cloud)
                statusDot.setBackgroundResource(R.drawable.circle_warning)
                statusText.text = getString(R.string.status_cloud)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
                deviceInfo.visibility = View.VISIBLE
                deviceName.text = device.ifEmpty { "Mac" }
                connType.text = connLabel.ifEmpty { "云端同步" }
            }
            "CONNECTING" -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_disconnected)
                statusDot.setBackgroundResource(R.drawable.circle_warning)
                statusText.text = getString(R.string.status_connecting)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
                deviceInfo.visibility = View.GONE
            }
            "RECONNECTING" -> {
                statusCard.setBackgroundResource(R.drawable.bg_status_card_disconnected)
                statusDot.setBackgroundResource(R.drawable.circle_warning)
                statusText.text = getString(R.string.status_reconnecting)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
                deviceInfo.visibility = View.VISIBLE
                deviceName.text = device.ifEmpty { PairingManager.macDeviceName.ifEmpty { "Mac" } }
                connType.text = "重连中…"
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

            textContent.text = record.text.trim().replace(Regex("\\s+"), " ")
            val timeAgo = formatTimeAgo(record.timestamp)
            val sourceLabel = if (isIncoming) {
                getString(R.string.from_device, record.source)
            } else {
                getString(R.string.send_to_device, record.source)
            }
            textTime.text = "$timeAgo \u00b7 $sourceLabel"

            // 7.6: 点击复制到剪贴板
            itemView.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GhostClip", record.text))
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
            }

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
