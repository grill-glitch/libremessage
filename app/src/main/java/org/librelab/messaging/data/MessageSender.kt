package org.librelab.messaging.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import org.librelab.messaging.SmsSentReceiver
import org.librelab.messaging.util.MmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends SMS/MMS on behalf of the conversation screen. Owns the provider
 * bookkeeping (outbox row for SMS, the private outbox record for MMS) and
 * the send-result PendingIntent, so the UI only validates input and reacts
 * to the outcome. The delivery callback ([SmsSentReceiver]) flips the
 * provider row / outbox record to SENT or FAILED.
 */
class MessageSender(
    private val context: Context,
    private val vm: SmsViewModel
) {

    /**
     * Send one SMS. Inserts our own outbox row first (some ROMs do not
     * write one for third-party sends), then hands the message to
     * SmsManager; the sent callback flips the row to SENT/FAILED.
     *
     * A message longer than one PDU is divided into segments and handed over
     * with [SmsManager.sendMultipartTextMessage]: [SmsManager.sendTextMessage]
     * encodes a single PDU, so everything past the first segment would never
     * leave the device. Each segment gets its own sent PendingIntent and the
     * row only reads SENT once every segment was accepted.
     * @return the provider row id.
     * @throws Exception when the stack rejects the send (the row is marked
     *         FAILED before rethrowing, so the UI only needs to toast).
     */
    fun sendSms(address: String, body: String, subId: Int): Long {
        val recordId = vm.insertPendingSms(address, body)
        try {
            val smsManager = if (subId > 0) {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsSendTracker.expect(recordId, parts.size)
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) sentIntents.add(sendResultIntent("sms", recordId, i))
                smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(address, null, body, sendResultIntent("sms", recordId, 0), null)
            }
            return recordId
        } catch (e: Exception) {
            smsSendTracker.forget(recordId)
            vm.markSmsFailed(recordId)
            throw e
        }
    }

    /**
     * Send one MMS (text + attachment). The PDU is encoded and handed to
     * the telephony stack by [MmsSender], which also keeps the private
     * outbox copy; the sent callback flips that copy to FAILED. Runs on
     * the IO dispatcher (MMS data may need enabling first).
     * @return the outbox record id, or null on failure.
     */
    suspend fun sendMms(
        address: String,
        text: String,
        attachment: PendingAttachment,
        subId: Int
    ): Long? = withContext(Dispatchers.IO) {
        val outboxId = -System.currentTimeMillis()
        val sentIntent = sendResultIntent("mms", outboxId)
        MmsSender.send(context, address, text, attachment, outboxId, sentIntent, subId)?.let { outboxId }
    }

    /**
     * Send-result callback PendingIntent, shared by the SMS and MMS paths.
     * [partIndex] distinguishes the segments of one multi-part SMS — they
     * must not share a PendingIntent, or the stack would report them as one.
     */
    private fun sendResultIntent(type: String, recordId: Long, partIndex: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(recordId, partIndex),
            Intent(SmsSentReceiver.ACTION_SMS_SENT)
                .setPackage(context.packageName)
                .putExtra(SmsSentReceiver.EXTRA_TYPE, type)
                .putExtra(SmsSentReceiver.EXTRA_RECORD_ID, recordId),
            PendingIntent.FLAG_IMMUTABLE
        )

    /** Record id shifted to leave room for the segment index (max 16 segments). */
    private fun requestCode(recordId: Long, partIndex: Int): Int =
        (recordId.toInt() shl PART_CODE_SHIFT) + (partIndex and PART_CODE_MASK)

    private companion object {
        const val PART_CODE_SHIFT = 5
        const val PART_CODE_MASK = 0x1F
    }
}
