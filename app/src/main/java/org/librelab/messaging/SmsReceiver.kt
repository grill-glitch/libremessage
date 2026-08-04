package org.librelab.messaging

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.librelab.messaging.data.MessageCategory
import org.librelab.messaging.data.SmsParser

/**
 * Receives incoming SMS. SMS_RECEIVED is broadcast to every receiver (used
 * for the notification); SMS_DELIVER is routed exclusively to the default
 * SMS app and IS the persistence channel — the system does NOT write the
 * row for the default app, we must insert it into the provider ourselves,
 * otherwise the message only shows up as a notification and never appears
 * in the conversation list.
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
        // Default SMS app: persist the SMS_DELIVER message, then notify.
        if (action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            insertInbox(context, sms)
        }

        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val category = SmsParser.classify(body, hasContact = false, archived = false)

        // Setting: 广告短信静音 (ad messages never pop a notification).
        val adMuted = category == MessageCategory.AD && !prefs.getBoolean("notify_ads", false)
        if (!adMuted) {
            Notifications.notifyIncoming(context, address, body)
        }

        // Setting: 验证码自动复制 (copy the code to the clipboard silently).
        if (category == MessageCategory.CODE && prefs.getBoolean("auto_copy_code", true)) {
            SmsParser.extractCode(body)?.let { code ->
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "clipboard copy failed", e)
                }
            }
        }
    }

    private fun insertInbox(context: Context, sms: android.telephony.SmsMessage) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sms.originatingAddress)
                put(Telephony.Sms.BODY, sms.displayMessageBody)
                put(Telephony.Sms.DATE, sms.timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "insert inbox failed", e)
        }
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
