package com.xkcoding.ghostclip.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.xkcoding.ghostclip.clip.ClipboardHelper
import com.xkcoding.ghostclip.util.DebugLog

/**
 * 透明剪贴板写入 Activity
 *
 * 后台 Service 在 HyperOS/MIUI 等系统上无法连续写入剪贴板，
 * 启动此透明 Activity 获取前台剪贴板权限后立即写入并 finish()。
 */
class ClipWriteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrEmpty()) {
            finish()
            return
        }

        DebugLog.d(TAG, "透明写入剪贴板: ${text.take(80)}")
        ClipboardHelper.write(this, text)
        finish()
    }

    companion object {
        private const val TAG = "ClipWrite"
        const val EXTRA_TEXT = "text"

        /**
         * 从 Service/后台 context 启动透明写入
         */
        fun launch(context: Context, text: String) {
            val intent = Intent(context, ClipWriteActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
