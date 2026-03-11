package com.xkcoding.ghostclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.xkcoding.ghostclip.R
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.HashPool
import com.xkcoding.ghostclip.net.NetworkCoordinator
import com.xkcoding.ghostclip.net.PairingInfo
import com.xkcoding.ghostclip.net.PairingManager
import com.xkcoding.ghostclip.ui.MainActivity
import com.xkcoding.ghostclip.ui.ScanActivity
import com.xkcoding.ghostclip.util.DebugLog

/**
 * 前台 Service：剪贴板同步 + 通知栏 + 网络协调
 */
class GhostClipService : LifecycleService() {

    private var networkCoordinator: NetworkCoordinator? = null

    // 监听临时剪贴板通知广播
    private val clipNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NetworkCoordinator.ACTION_CLIP_NOTIFICATION) {
                val text = intent.getStringExtra("text") ?: return
                showClipNotification(text)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.d(TAG, "GhostClipService 启动")
        createNotificationChannel()
        createClipNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.DISCONNECTED))

        // 监听临时剪贴板通知
        ContextCompat.registerReceiver(
            this, clipNotificationReceiver,
            IntentFilter(NetworkCoordinator.ACTION_CLIP_NOTIFICATION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 启动网络
        networkCoordinator = NetworkCoordinator(this, hashPool).also { it.start() }
        coordinatorInstance = networkCoordinator
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // 处理远端剪贴板
        intent?.getStringExtra(EXTRA_REMOTE_TEXT)?.let { text ->
            onReceiveRemoteClip(text)
        }

        // 更新连接状态通知
        intent?.getStringExtra(EXTRA_CONNECTION_STATE)?.let { stateName ->
            try {
                val state = ConnectionState.valueOf(stateName)
                updateConnectionState(state)
            } catch (_: IllegalArgumentException) {}
        }

        // 重新加载云端配置
        if (intent?.getBooleanExtra(EXTRA_RELOAD_CLOUD, false) == true) {
            DebugLog.d(TAG, "重新加载云端配置")
            networkCoordinator?.reloadCloudConfig()
        }

        // 扫码结果
        if (intent?.getBooleanExtra(EXTRA_SCAN_RESULT, false) == true) {
            val macHash = intent.getStringExtra("mac_hash") ?: return START_STICKY
            val token = intent.getStringExtra("token") ?: return START_STICKY
            val device = intent.getStringExtra("device_name") ?: ""
            networkCoordinator?.onScanResult(PairingInfo(macHash, token, device))
        }

        // 解除配对
        if (intent?.getBooleanExtra(EXTRA_UNPAIR, false) == true) {
            networkCoordinator?.unpair()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        DebugLog.d(TAG, "GhostClipService 销毁")
        try { unregisterReceiver(clipNotificationReceiver) } catch (_: Exception) {}
        networkCoordinator?.stop()
        networkCoordinator = null
        coordinatorInstance = null
        super.onDestroy()
    }

    private fun onReceiveRemoteClip(text: String) {
        if (hashPool.checkAndRecord(text)) {
            DebugLog.d(TAG, "远端剪贴板重复, 跳过")
            return
        }
        ClipboardHelper.write(this, text)
        DebugLog.d(TAG, "远端剪贴板已写入: ${text.take(50)}...")
    }

    /**
     * 更新通知栏连接状态 (7.7)
     */
    private fun updateConnectionState(state: ConnectionState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GhostClip 同步服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GhostClip 剪贴板同步服务"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun createClipNotificationChannel() {
        val channel = NotificationChannel(
            CLIP_CHANNEL_ID,
            "剪贴板通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到远端剪贴板内容时的临时通知"
            setShowBadge(true)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /**
     * 构建前台服务通知 (7.7)
     * 已配对: 显示设备名 + [暂停同步] [解除配对]
     * 未配对: 显示"等待扫码配对" + [扫码配对]
     */
    private fun buildNotification(state: ConnectionState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GhostClip")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)

        val pairingState = PairingManager.state

        if (pairingState == PairingManager.State.CONNECTED && state == ConnectionState.LAN) {
            // 已配对
            val deviceLabel = PairingManager.macDeviceName.ifEmpty { "Mac" }
            builder.setContentText(getString(R.string.notif_paired, deviceLabel))

            // 解除配对 action
            val unpairIntent = Intent(this, GhostClipService::class.java).apply {
                putExtra(EXTRA_UNPAIR, true)
            }
            val unpairPi = PendingIntent.getService(
                this, 1, unpairIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.notif_action_unpair), unpairPi)
        } else if (pairingState == PairingManager.State.UNPAIRED) {
            // 未配对
            builder.setContentText(getString(R.string.notif_waiting_pair))

            // 扫码配对 action
            val scanIntent = Intent(this, ScanActivity::class.java)
            val scanPi = PendingIntent.getActivity(
                this, 2, scanIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.notif_action_scan), scanPi)
        } else {
            // CONNECTING / RECONNECTING
            builder.setContentText(getString(R.string.status_connecting))
        }

        return builder.build()
    }

    /**
     * 弹出临时剪贴板通知 (7.8)
     */
    private fun showClipNotification(text: String) {
        val preview = if (text.length > 100) text.take(100) + "…" else text

        // 点击复制 action
        val copyIntent = Intent(this, CopyReceiver::class.java).apply {
            putExtra("text", text)
        }
        val copyPi = PendingIntent.getBroadcast(
            this, text.hashCode(), copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CLIP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_clip_title))
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .addAction(0, getString(R.string.notif_clip_copy), copyPi)
            .setTimeoutAfter(30_000) // 30s 后自动消失
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(CLIP_NOTIFICATION_ID, notification)
    }

    enum class ConnectionState {
        LAN, CLOUD, DISCONNECTED
    }

    /**
     * 复制操作广播接收器（通知 [复制] 按钮）
     */
    class CopyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra("text") ?: return
            ClipboardHelper.write(context, text)
            // 关闭通知
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(CLIP_NOTIFICATION_ID)
        }
    }

    companion object {
        private const val TAG = "GhostClipService"
        private const val CHANNEL_ID = "ghostclip_service"
        private const val CLIP_CHANNEL_ID = "ghostclip_clip"
        private const val NOTIFICATION_ID = 1
        private const val CLIP_NOTIFICATION_ID = 2

        const val EXTRA_REMOTE_TEXT = "remote_text"
        const val EXTRA_CONNECTION_STATE = "connection_state"
        const val EXTRA_RELOAD_CLOUD = "reload_cloud"
        const val EXTRA_SCAN_RESULT = "scan_result"
        const val EXTRA_UNPAIR = "unpair"

        /** hashPool -- QuickSyncActivity 和 Service 共享 */
        val hashPool = HashPool()

        /** NetworkCoordinator 实例引用 -- 供静态方法转发 */
        @Volatile
        private var coordinatorInstance: NetworkCoordinator? = null

        fun start(context: Context) {
            val intent = Intent(context, GhostClipService::class.java)
            context.startForegroundService(intent)
        }

        /** 投递远端剪贴板 */
        fun deliverRemoteClip(context: Context, text: String) {
            val intent = Intent(context, GhostClipService::class.java).apply {
                putExtra(EXTRA_REMOTE_TEXT, text)
            }
            context.startForegroundService(intent)
        }

        /** 扫码结果转发给 NetworkCoordinator */
        fun onScanResult(info: PairingInfo) {
            // 优先直接调用（同进程）
            coordinatorInstance?.onScanResult(info)
        }

        /** 解除配对 */
        fun unpair() {
            coordinatorInstance?.unpair()
        }
    }
}
