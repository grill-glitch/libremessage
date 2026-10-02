package org.librelab.messaging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.librelab.messaging.data.SmsSendOutcome
import org.librelab.messaging.data.smsSendTracker
import org.librelab.messaging.util.OutboxStore

/**
 * Updates the delivery state after the telephony stack reports the send
 * result. SMS rows are flipped in the sms table (6 -> 2/5); MMS outbox
 * copies are flipped in our private [OutboxStore]. The UI renders the
 * state icons from these values.
 *
 * A multi-part SMS reports one result per segment: the row is only marked
 * SENT once every segment was accepted, and FAILED as soon as one was
 * rejected (see [org.librelab.messaging.data.SmsSendTracker]).
 */
class SmsSentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "org.librelab.messaging.SMS_SENT"
        const val EXTRA_TYPE = "type"       // "sms" | "mms"
        const val EXTRA_RECORD_ID = "recordId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        if (id == -1L) return
        val ok = resultCode == Activity.RESULT_OK
        if ("mms" == intent.getStringExtra(EXTRA_TYPE)) {
            OutboxStore.mark(context, id, failed = !ok)
            return
        }
        when (smsSendTracker.onReport(id, ok)) {
            SmsSendOutcome.PENDING -> return
            SmsSendOutcome.SENT -> setSmsType(context, id, Telephony.Sms.MESSAGE_TYPE_SENT)
            SmsSendOutcome.FAILED -> setSmsType(context, id, Telephony.Sms.MESSAGE_TYPE_FAILED)
        }
    }

    private fun setSmsType(context: Context, id: Long, type: Int) {
        val values = ContentValues().apply { put(Telephony.Sms.TYPE, type) }
        context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString())
        )
    }
}
