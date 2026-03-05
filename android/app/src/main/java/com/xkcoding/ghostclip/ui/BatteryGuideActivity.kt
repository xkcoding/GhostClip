package com.xkcoding.ghostclip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xkcoding.ghostclip.R

/**
 * 电池优化引导页面
 *
 * 引导用户关闭电池优化, 确保后台同步服务稳定运行.
 * 首次启动或检测到电池优化未关闭时, 由 MainActivity 启动.
 */
class BatteryGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_guide)

        findViewById<ImageView>(R.id.btn_close).setOnClickListener {
            markDismissed()
            finish()
        }

        findViewById<LinearLayout>(R.id.btn_go_settings).setOnClickListener {
            openBatterySettings()
        }

        findViewById<TextView>(R.id.btn_skip).setOnClickListener {
            markDismissed()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            // 显示成功提示，延迟 1.5 秒后自动关闭
            findViewById<TextView>(R.id.text_success).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.btn_go_settings).visibility = View.GONE
            findViewById<TextView>(R.id.btn_skip).visibility = View.GONE
            markDismissed()
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
        }
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.battery_guide_open_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun markDismissed() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISMISSED, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ghostclip_settings"
        private const val KEY_DISMISSED = "battery_guide_dismissed"

        /** 是否需要显示电池优化引导 */
        fun shouldShow(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return false

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getBoolean(KEY_DISMISSED, false)
        }
    }
}
