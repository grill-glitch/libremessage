package org.librelab.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.librelab.messaging.util.copyCodeToClipboard

/** "复制验证码" quick action on the incoming-SMS notification. */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        copyCodeToClipboard(context, code)
    }
}
