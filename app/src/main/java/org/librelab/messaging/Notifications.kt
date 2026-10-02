package org.librelab.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.librelab.messaging.R
import org.librelab.messaging.data.MessageLinks
import org.librelab.messaging.data.SmsParser

/** Notification helpers for the incoming-SMS receiver. */
object Notifications {

    const val CHANNEL_SMS = "incoming_sms"
    private const val EXTRA_CODE = "code"

    // PendingIntent identity is (requestCode, Intent filterEquals), so each
    // action keeps a stable code: re-posting a notification for the same
    // sender updates its actions instead of stacking duplicates.
    private const val REQ_CONTENT = 0
    private const val REQ_COPY_CODE = 1
    private const val REQ_OPEN_LINK = 2
    private const val REQ_DIAL = 3
    private const val REQ_SMS = 4

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_SMS,
            context.getString(R.string.notification_channel_sms),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    fun notifyIncoming(context: Context, address: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context, REQ_CONTENT,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_THREAD)
                .putExtra(MainActivity.EXTRA_ADDRESS, address)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(address)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

        // Action row, only for what the message actually carries: a web link
        // to open in the browser, and — when the sender is a dialable number —
        // call / reply straight from the notification.
        MessageLinks.firstUrl(body)?.let { url ->
            val openIntent = PendingIntent.getActivity(
                context, REQ_OPEN_LINK,
                Intent(Intent.ACTION_VIEW, Uri.parse(MessageLinks.toUrl(url)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, context.getString(R.string.action_open_link), openIntent)
        }

        if (MessageLinks.isPhoneNumber(address)) {
            val number = MessageLinks.normalizePhone(address)
            val dialIntent = PendingIntent.getActivity(
                context, REQ_DIAL,
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, context.getString(R.string.action_call), dialIntent)

            // Explicit target so the reply draft opens in this app (which is
            // also the default SMS handler); MainActivity's smsto: handling
            // pre-fills the recipient.
            val smsIntent = PendingIntent.getActivity(
                context, REQ_SMS,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_SENDTO)
                    .setData(Uri.fromParts("smsto", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, context.getString(R.string.action_send_sms), smsIntent)
        }

        val code = SmsParser.extractCode(body)
        if (code != null) {
            // The code rides in the Intent *data*: PendingIntent identity
            // ignores extras, so without it a second code notification would
            // reuse this one and the button would copy the other code.
            val copyIntent = PendingIntent.getBroadcast(
                context, REQ_COPY_CODE,
                Intent(context, CopyCodeReceiver::class.java)
                    .setData(Uri.parse("librelab:copycode/$code"))
                    .putExtra(EXTRA_CODE, code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, context.getString(R.string.copy_code), copyIntent)
        }

        NotificationManagerCompat.from(context).notify(
            address.hashCode(), builder.build()
        )
    }

    /** MMS is not rendered by this SMS-only app — just surface its arrival. */
    fun notifyMms(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        // requestCode 1: distinct from the incoming-SMS content intent
        // (requestCode 0 + FLAG_UPDATE_CURRENT would otherwise overwrite
        // its extras, breaking the tap-to-open-conversation flow).
        val contentIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.mms_title))
            .setContentText(context.getString(R.string.mms_body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(0x4D4D53, notification)
    }
}
