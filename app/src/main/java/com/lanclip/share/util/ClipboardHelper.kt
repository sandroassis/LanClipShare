package com.lanclip.share.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object ClipboardHelper {

    fun readCurrentClipboardText(context: Context): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            if (!cm.hasPrimaryClip()) return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            text?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun copyToSystemClipboard(context: Context, text: String, isSensitive: Boolean = false) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            val clip = ClipData.newPlainText("LanClip Share", text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isSensitive) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            cm.setPrimaryClip(clip)
        }
    }
}
