package org.librelab.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Receives incoming SMS. SMS_RECEIVED is broadcast to every receiver;
 * SMS_DELIVER is routed exclusively to the default SMS app. The system
 * persists the message to the provider automatically — our ContentObserver
 * refreshes the UI, here we just notify the user.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
        ) {
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sms = messages.firstOrNull() ?: return
        val body = sms.displayMessageBody ?: return
        val address = sms.originatingAddress ?: "未知号码"
        if (!dedup(address, body)) return
        Notifications.notifyIncoming(context, address, body)
    }

    private companion object {
        // SMS_DELIVER + SMS_RECEIVED can both fire for the same message
        // when this app is the default SMS app; dedupe by content.
        private val seen = ArrayDeque<String>()
        private val lock = Any()

        private fun dedup(address: String, body: String): Boolean = synchronized(lock) {
            val key = "$address\u0000$body"
            if (seen.contains(key)) {
                false
            } else {
                seen.addLast(key)
                if (seen.size > 64) seen.removeFirst()
                true
            }
        }
    }
}
