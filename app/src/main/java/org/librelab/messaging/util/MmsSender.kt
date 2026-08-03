package org.librelab.messaging.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import org.librelab.messaging.mms.pdu.EncodedStringValue
import org.librelab.messaging.mms.pdu.PduBody
import org.librelab.messaging.mms.pdu.PduComposer
import org.librelab.messaging.mms.pdu.PduPart
import org.librelab.messaging.mms.pdu.SendReq
import java.io.File

/**
 * Sends an MMS (text + one image). The PDU is encoded with the AOSP
 * com.google.android.mms.pdu library (same one the system Messaging app
 * uses), written to a FileProvider temp file, and handed to the telephony
 * stack via SmsManager.sendMultimediaMessage. The stack reads the PDU
 * bytes from the provider uri — it cannot open content://mms rows.
 *
 * We keep our own copy in [OutboxStore] to render the sent bubble; the
 * sent PendingIntent flips that copy between SENT and FAILED.
 */
object MmsSender {

    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 85

    /**
     * @return the outbox record id on success, null on failure.
     */
    fun send(
        context: Context,
        number: String,
        text: String,
        imageFile: File,
        outboxId: Long,
        sentIntent: android.app.PendingIntent?
    ): Long? = try {
        // 1. Build the M-Send.req PDU.
        val sendReq = SendReq()
        sendReq.setTo(arrayOf(EncodedStringValue(number)))
        if (text.isNotBlank()) {
            sendReq.setSubject(EncodedStringValue(text))
        }
        sendReq.setDate(System.currentTimeMillis() / 1000)

        val body = PduBody()

        // SMIL layout part (required for image presentation).
        val smil = ("<smil><head><layout><root-layout width=\"320px\" height=\"480px\"/>" +
            "<region id=\"Image\" left=\"0\" top=\"0\" width=\"320px\" height=\"480px\" fit=\"meet\"/>" +
            "</layout></head><body><par dur=\"5000ms\"><img src=\"IMG.jpg\" region=\"Image\"/>" +
            "</par></body></smil>")
        val smilPart = PduPart()
        smilPart.setContentType("application/smil".toByteArray())
        smilPart.setName("smil.xml".toByteArray())
        smilPart.setData(smil.toByteArray())
        body.addPart(smilPart)

        if (text.isNotBlank()) {
            val textPart = PduPart()
            textPart.setContentType("text/plain".toByteArray())
            textPart.setName("text.txt".toByteArray())
            textPart.setData(text.toByteArray())
            body.addPart(textPart)
        }

        val imgBytes = imageFile.readBytes()
        val imgPart = PduPart()
        imgPart.setContentType("image/jpeg".toByteArray())
        imgPart.setFilename("IMG.jpg".toByteArray())
        imgPart.setName("IMG.jpg".toByteArray())
        imgPart.setData(imgBytes)
        body.addPart(imgPart)
        sendReq.setBody(body)

        // 2. Encode the PDU (WAP binary).
        val pduBytes = PduComposer(context, sendReq).make() ?: return null

        // 3. Write it to a FileProvider temp file; the stack reads the PDU
        //    bytes from this uri.
        val dir = File(context.filesDir, "mms_outbox").apply { mkdirs() }
        val tmp = File(dir, "rawmms_${System.currentTimeMillis()}.pdu")
        tmp.writeBytes(pduBytes)
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tmp
        )

        // 4. Hand off to the telephony stack.
        SmsManager.getDefault().sendMultimediaMessage(context, contentUri, null, null, sentIntent)

        // 5. Keep our own copy so the thread keeps showing the bubble.
        val persistDir = File(context.filesDir, "mms_sent").apply { mkdirs() }
        val savedImage = File(persistDir, "mms_${System.currentTimeMillis()}.jpg")
        compressImage(imageFile, savedImage)
        val threadId = Telephony.Threads.getOrCreateThreadId(context, number)
        OutboxStore.add(
            context,
            OutboxMms(
                id = outboxId,
                threadId = threadId,
                address = number,
                text = text,
                imagePath = savedImage.absolutePath,
                date = System.currentTimeMillis(),
                failed = false
            )
        )
        outboxId
    } catch (e: Exception) {
        android.util.Log.e("MmsSender", "send failed", e)
        null
    }

    /** Downscale to MAX_DIMENSION and re-encode as JPEG (MMS size limits). */
    private fun compressImage(src: File, dst: File) {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return
        val w = bmp.width
        val h = bmp.height
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(w, h))
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bmp
        }
        try {
            dst.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            if (out !== bmp) out.recycle()
            bmp.recycle()
        }
    }
}
