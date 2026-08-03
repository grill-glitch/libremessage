package org.librelab.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * MMS (WAP Push) receiver. Its presence is REQUIRED by the system's
 * default-SMS-app role qualification (RoleManagerService):
 *   WAP_PUSH_DELIVER + application/vnd.wap.mms-message + BROADCAST_WAP_PUSH.
 *
 * This app is SMS-only, so it does not render MMS content — it just tells
 * the user one arrived. The system persists MMS to the provider itself.
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION
        ) {
            return
        }
        Notifications.notifyMms(context)
    }
}
