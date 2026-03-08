package com.xkcoding.ghostclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.xkcoding.ghostclip.R
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.clip.HashPool
import com.xkcoding.ghostclip.net.NetworkCoordinator
import com.xkcoding.ghostclip.ui.MainActivity
import com.xkcoding.ghostclip.util.DebugLog

/**
 * 前台 Service：剪贴板同步 + 通知栏 + 网络协调
 */
class GhostClipService : LifecycleService() {

    private var networkCoordinator: NetworkCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.d(TAG, "GhostClipService 启动")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.DISCONNECTED))

        // 启动网络
        networkCoordinator = NetworkCoordinator(this, hashPool).also { it.start() }
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

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        DebugLog.d(TAG, "GhostClipService 销毁")
        networkCoordinator?.stop()
        networkCoordinator = null
        super.onDestroy()
    }

    /**
     * 处理远端剪贴板（去重后写入本地）
     */
    private fun onReceiveRemoteClip(text: String) {
        if (hashPool.checkAndRecord(text)) {
            DebugLog.d(TAG, "远端剪贴板重复, 跳过")
            return
        }
        ClipboardHelper.write(this, text)
        DebugLog.d(TAG, "远端剪贴板已写入: ${text.take(50)}...")
    }

    /**
     * 更新通知栏连接状态
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

    private fun buildNotification(state: ConnectionState): Notification {
        val contentText = when (state) {
            ConnectionState.LAN -> "GhostClip \u8fd0\u884c\u4e2d \u00b7 \u5df2\u8fde\u63a5 Mac (\u5c40\u57df\u7f51)"
            ConnectionState.CLOUD -> "GhostClip \u8fd0\u884c\u4e2d \u00b7 \u4e91\u7aef\u540c\u6b65\u4e2d"
            ConnectionState.DISCONNECTED -> "GhostClip \u8fd0\u884c\u4e2d \u00b7 \u672a\u8fde\u63a5\u5230 Mac"
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GhostClip")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    enum class ConnectionState {
        LAN, CLOUD, DISCONNECTED
    }

    companion object {
        private const val TAG = "GhostClipService"
        private const val CHANNEL_ID = "ghostclip_service"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_REMOTE_TEXT = "remote_text"
        const val EXTRA_CONNECTION_STATE = "connection_state"
        const val EXTRA_RELOAD_CLOUD = "reload_cloud"

        /** hashPool -- QuickSyncActivity 和 Service 共享 */
        val hashPool = HashPool()

        fun start(context: Context) {
            val intent = Intent(context, GhostClipService::class.java)
            context.startForegroundService(intent)
        }

        /** 投递远端剪贴板 -- Service 负责去重 + 写入 */
        fun deliverRemoteClip(context: Context, text: String) {
            val intent = Intent(context, GhostClipService::class.java).apply {
                putExtra(EXTRA_REMOTE_TEXT, text)
            }
            context.startForegroundService(intent)
        }
    }
}
