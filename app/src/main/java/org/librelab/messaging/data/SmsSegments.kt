package org.librelab.messaging.data

/**
 * Rebuild the text of a concatenated ("long") SMS — 长短信.
 *
 * The network delivers such a message as one PDU per segment: each PDU
 * arrives as a separate [android.telephony.SmsMessage] whose body carries
 * only its own part (153 characters for GSM-7, 67 for UCS-2). The system
 * reassembles the parts into a single broadcast, so
 * [android.provider.Telephony.Sms.Intents.getMessagesFromIntent] returns
 * every segment in PDU order — reading only the first one cuts the
 * message at the first segment boundary.
 *
 * A null body (a non-text part) contributes nothing rather than aborting
 * the join; the caller treats an all-null result as "no message".
 */
fun joinSmsSegments(segments: List<String?>): String =
    buildString { segments.forEach { append(it ?: "") } }
