package org.librelab.messaging.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import org.librelab.messaging.R

/** Shared clipboard helpers: receiver (silent auto-copy), notification
 * quick action and UI buttons all go through these. */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Copy a verification code. UI callers show the toast; the receiver's
 * silent auto-copy passes [showToast] = false. */
fun copyCodeToClipboard(context: Context, code: String, showToast: Boolean = true) {
    copyToClipboard(context, "sms_code", code)
    if (showToast) {
        Toast.makeText(context, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
    }
}
