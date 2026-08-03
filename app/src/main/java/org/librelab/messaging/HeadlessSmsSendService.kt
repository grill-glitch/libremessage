package org.librelab.messaging

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Headless SMS send service. The system (AOSP SmsApplication) checks for a
 * service with exactly this class name + SEND_RESPOND_VIA_MESSAGE permission
 * to confirm the app is a capable default SMS app. The stub body is enough —
 * the actual sending goes through SmsManager in ComposeMessageScreen.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY
}
