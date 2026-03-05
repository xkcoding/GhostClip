package com.xkcoding.ghostclip.clip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * ClipboardManager
 */
object ClipboardHelper {

    /**
     * null
     */
    fun read(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!cm.hasPrimaryClip()) return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * ()
     */
    fun write(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("GhostClip", text)
        cm.setPrimaryClip(clip)
    }
}
