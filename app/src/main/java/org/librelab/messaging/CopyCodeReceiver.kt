package org.librelab.messaging

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** "复制验证码" quick action on the incoming-SMS notification. */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("sms_code", code))
        Toast.makeText(context, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
    }
}
