package com.xkcoding.ghostclip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.xkcoding.ghostclip.R
import com.xkcoding.ghostclip.net.PairingManager
import com.xkcoding.ghostclip.service.GhostClipService

/**
 * 设置界面
 *
 * 包含: 云端地址/Token/开关、权限状态(电池优化)、关于信息
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("ghostclip_settings", Context.MODE_PRIVATE)
    }

    // 记录进入时的云端配置, 用于判断是否有变化
    private var initialCloudUrl: String = ""
    private var initialCloudToken: String = ""
    private var initialCloudEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initialCloudUrl = prefs.getString("cloud_url", "") ?: ""
        initialCloudToken = prefs.getString("cloud_token", "") ?: ""
        initialCloudEnabled = prefs.getBoolean("cloud_enabled", false)

        setupBackButton()
        setupPairingSection()
        setupCloudSection()
        setupPermissionsSection()
        setupAboutSection()
    }

    override fun onResume() {
        super.onResume()
        updatePairingInfo()
        updatePermissionBadges()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun setupBackButton() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }

    private fun setupPairingSection() {
        findViewById<LinearLayout>(R.id.row_unpair).setOnClickListener {
            if (PairingManager.state == PairingManager.State.CONNECTED ||
                PairingManager.state == PairingManager.State.RECONNECTING
            ) {
                GhostClipService.unpair()
                Toast.makeText(this, R.string.unpaired_toast, Toast.LENGTH_SHORT).show()
                updatePairingInfo()
            }
        }
    }

    private fun updatePairingInfo() {
        val statusValue = findViewById<TextView>(R.id.pair_status_value)
        val macIdValue = findViewById<TextView>(R.id.mac_id_value)

        val isPaired = PairingManager.state == PairingManager.State.CONNECTED ||
            PairingManager.state == PairingManager.State.RECONNECTING ||
            PairingManager.state == PairingManager.State.CONNECTING

        if (isPaired) {
            statusValue.text = getString(R.string.pair_status_paired)
            statusValue.setTextColor(ContextCompat.getColor(this, R.color.accent_dark))
            macIdValue.text = PairingManager.macHash?.take(8) ?: "--"
        } else {
            statusValue.text = getString(R.string.pair_status_unpaired)
            statusValue.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            macIdValue.text = "--"
        }
    }

    private fun setupCloudSection() {
        val urlEdit = findViewById<EditText>(R.id.edit_cloud_url)
        val tokenEdit = findViewById<EditText>(R.id.edit_cloud_token)
        val cloudSwitch = findViewById<MaterialSwitch>(R.id.switch_cloud)

        urlEdit.setText(prefs.getString("cloud_url", ""))
        tokenEdit.setText(prefs.getString("cloud_token", ""))
        cloudSwitch.isChecked = prefs.getBoolean("cloud_enabled", false)

        cloudSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("cloud_enabled", isChecked).apply()
        }
    }

    private fun setupPermissionsSection() {
        // 电池优化行 - 跳转到电池优化设置
        findViewById<LinearLayout>(R.id.row_battery).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "\u65e0\u6cd5\u6253\u5f00\u8bbe\u7f6e", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updatePermissionBadges() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(packageName)
        val badgeBattery = findViewById<TextView>(R.id.badge_battery)

        if (isIgnoringBattery) {
            badgeBattery.text = getString(R.string.status_enabled)
            badgeBattery.setBackgroundResource(R.drawable.bg_badge_accent)
            badgeBattery.setTextColor(ContextCompat.getColor(this, R.color.accent_dark))
        } else {
            badgeBattery.text = getString(R.string.status_not_disabled)
            badgeBattery.setBackgroundResource(R.drawable.bg_badge_warning)
            badgeBattery.setTextColor(ContextCompat.getColor(this, R.color.warning_dark))
        }
    }

    private fun setupAboutSection() {
        findViewById<LinearLayout>(R.id.row_github).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xkcoding/GhostClip")))
            } catch (_: Exception) {}
        }
    }

    private fun saveSettings() {
        val urlEdit = findViewById<EditText>(R.id.edit_cloud_url)
        val tokenEdit = findViewById<EditText>(R.id.edit_cloud_token)
        val cloudSwitch = findViewById<MaterialSwitch>(R.id.switch_cloud)

        val newUrl = urlEdit.text.toString().trimEnd('/')
        val newToken = tokenEdit.text.toString().trim()
        val newEnabled = cloudSwitch.isChecked

        prefs.edit().apply {
            putString("cloud_url", newUrl)
            putString("cloud_token", newToken)
            putBoolean("cloud_enabled", newEnabled)
            apply()
        }

        // 如果云端配置有变化, 通知 Service 重新加载
        if (newUrl != initialCloudUrl || newToken != initialCloudToken || newEnabled != initialCloudEnabled) {
            val intent = Intent(this, GhostClipService::class.java).apply {
                putExtra(GhostClipService.EXTRA_RELOAD_CLOUD, true)
            }
            startForegroundService(intent)
        }
    }
}
