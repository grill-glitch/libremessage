package org.librelab.messaging.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import org.librelab.messaging.util.OutboxStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads real SMS data from the system Telephony provider and resolves
 * sender names from ContactsContract. Runs on the IO dispatcher.
 */
class SmsRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val contactCache = HashMap<String, ContactInfo>()

    /** content://sms/archived — archived threads; URI stable since API 34. */
    private val archivedUri: Uri = Uri.parse("content://sms/archived")

    /** content://mms root for the MMS pdu table. */
    private val mmsUri: Uri = Uri.parse("content://mms")

    /**
     * Archive (or restore) one thread. The sms table has no `archived`
     * column on this ROM, so we keep the archived thread ids locally and
     * re-classify them as ARCHIVED on load.
     */
    suspend fun archiveThread(threadId: Long, archive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ids = archivedIds().toMutableSet()
        if (archive) ids.add(threadId) else ids.remove(threadId)
        setArchivedIds(ids)
        true
    }

    private fun archivedIds(): Set<Long> =
        context.getSharedPreferences("archive_prefs", Context.MODE_PRIVATE)
            .getStringSet("thread_ids", emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    private fun setArchivedIds(ids: Set<Long>) {
        context.getSharedPreferences("archive_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("thread_ids", ids.map { it.toString() }.toSet())
            .apply()
    }

    /** Permanently delete a conversation (all its sms + mms rows). */
    suspend fun deleteThread(threadId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString())
            )
            resolver.delete(
                mmsUri,
                "thread_id = ?",
                arrayOf(threadId.toString())
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadAll(): List<SmsMessage> = withContext(Dispatchers.IO) {
        // The inbox URI only returns type=1 rows, hiding every message this
        // app sent (type=2/5/6). Query the full sms table instead so sent
        // messages and their conversations show up on the home list.
        val sms = query(
            uri = Telephony.Sms.CONTENT_URI,
            selection = "${Telephony.Sms.TYPE} IN (1,2,4,5,6)",
            selectionArgs = null,
            sortOrder = "${Telephony.Sms.DATE} DESC"
        )
        val mms = queryMms(selection = null, selectionArgs = null, sortOrder = "date DESC")
        val outbox = outboxMessages()
        // Deduplicate by unique key (sms and mms ids may overlap so keys
        // carry a type prefix).
        val byId = LinkedHashMap<String, SmsMessage>()
        sms.forEach { byId[it.key] = it }
        mms.forEach { byId.putIfAbsent(it.key, it) }
        outbox.forEach { byId.putIfAbsent(it.key, it) }
        // Locally-archived threads are re-classified as ARCHIVED so they only
        // show up under the 归档 filter.
        val archived = archivedIds()
        byId.values
            .map { msg ->
                if (msg.threadId in archived && msg.category != MessageCategory.ARCHIVED) {
                    msg.copy(category = MessageCategory.ARCHIVED)
                } else msg
            }
            .sortedByDescending { it.date }
    }

    /** All messages (received + sent, SMS + MMS) of one conversation, oldest first. */
    suspend fun queryThread(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val sms = query(
            uri = Telephony.Sms.CONTENT_URI,
            // inbox + sent + queued + outbox + failed: show our own sends
            // (with their delivery status icons) as well as received ones.
            selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} IN (1,2,4,5,6)",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Sms.DATE} ASC"
        )
        val mms = queryMms(
            selection = "thread_id = ? AND msg_box IN (1,2,4,5)",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "date ASC"
        )
        val outbox = outboxMessages().filter { it.threadId == threadId }
        (sms + mms + outbox).sortedBy { it.date }
    }

    /**
     * MMS messages this app sent, kept in private storage because the
     * telephony stack deletes pending pdu rows on some ROMs.
     */
    private fun outboxMessages(): List<SmsMessage> =
        OutboxStore.all(context).map { r ->
            val contact = lookupContact(r.address)
            val imageFile = File(r.imagePath)
            SmsMessage(
                id = r.id,
                threadId = r.threadId,
                address = r.address,
                body = r.text,
                date = r.date,
                isRead = true,
                isSent = true,
                category = if (contact != null) MessageCategory.PERSON else MessageCategory.OTHER,
                code = null,
                contactName = contact?.name,
                isMms = true,
                imageUris = if (imageFile.exists()) listOf(Uri.fromFile(imageFile)) else emptyList(),
                attachmentName = r.name,
                sendStatus = if (r.failed) SendStatus.FAILED else SendStatus.SENT
            )
        }

    /**
     * Insert our own sent-message record (the system does not write one for
     * third-party sends on some ROMs), so the conversation shows it.
     * @return the new row id.
     */
    fun insertPendingSms(address: String, body: String): Long {
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX) // 6: sending
            put(Telephony.Sms.THREAD_ID, threadId)
        }
        val uri = resolver.insert(Telephony.Sms.CONTENT_URI, values) ?: return -1L
        return ContentUris.parseId(uri)
    }

    /** Mark an outbox row as successfully sent (type 2). */
    fun markSmsSent(id: Long) = updateSmsType(id, Telephony.Sms.MESSAGE_TYPE_SENT)

    /** Mark every message of a thread as read (SMS + MMS). */
    fun markThreadRead(threadId: Long) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            resolver.update(
                Telephony.Sms.CONTENT_URI, values,
                "thread_id = ?", arrayOf(threadId.toString())
            )
            resolver.update(
                Uri.parse("content://mms"), values,
                "thread_id = ?", arrayOf(threadId.toString())
            )
        }
    }

    /** Mark an outbox row as failed (type 5). */
    fun markSmsFailed(id: Long) = updateSmsType(id, Telephony.Sms.MESSAGE_TYPE_FAILED)

    private fun updateSmsType(id: Long, type: Int) {
        val values = ContentValues().apply { put(Telephony.Sms.TYPE, type) }
        resolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString())
        )
    }

    private fun query(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadCol = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addrCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readCol = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeCol = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val threadId = c.getLong(threadCol)
                val address = c.getString(addrCol) ?: ""
                val body = c.getString(bodyCol) ?: ""
                val date = c.getLong(dateCol)
                val isRead = c.getInt(readCol) != 0
                val type = c.getInt(typeCol)
                val isSent = smsIsSent(type)
                val sendStatus = smsSendStatus(type)
                val contact = lookupContact(address)
                val category = SmsParser.classify(body, hasContact = contact != null, archived = uri == archivedUri)
                out += SmsMessage(
                    id = id,
                    threadId = threadId,
                    address = address,
                    body = body,
                    date = date,
                    isRead = isRead,
                    isSent = isSent,
                    category = category,
                    code = if (category == MessageCategory.CODE) SmsParser.extractCode(body) else null,
                    contactName = contact?.name,
                    sendStatus = sendStatus
                )
            }
        }
        return out
    }

    /** MMS rows mapped onto SmsMessage (isMms=true, imageUris populated). */
    private fun queryMms(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        resolver.query(
            mmsUri,
            arrayOf("_id", "thread_id", "date", "msg_box", "read"),
            selection, selectionArgs, sortOrder
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val threadCol = c.getColumnIndexOrThrow("thread_id")
            val dateCol = c.getColumnIndexOrThrow("date")
            val boxCol = c.getColumnIndexOrThrow("msg_box")
            val readCol = c.getColumnIndexOrThrow("read")
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val threadId = c.getLong(threadCol)
                // Skip orphan rows with no valid thread.
                if (threadId <= 0 || threadId == Long.MAX_VALUE) continue
                val rawDate = c.getLong(dateCol)
                // Some ROMs store MMS date in seconds — normalize to millis.
                val date = if (rawDate < 1_000_000_000_000L) rawDate * 1000 else rawDate
                val box = c.getInt(boxCol)
                // box: 1=inbox(received), 2=sent, 4=pending, 5=failed — anything
                // but 1 is our own outgoing MMS and renders on the sent side.
                val isSent = mmsIsSent(box)
                val sendStatus = mmsSendStatus(box)
                val isRead = c.getInt(readCol) != 0
                val parts = readMmsParts(id)
                // Sent MMS: the peer is the TO (type=151) row — the FROM row
                // holds OUR OWN number. Received MMS: peer is FROM (137).
                var address = readMmsAddress(id, sent = isSent) ?: ""
                // Some privacy tooling stores the MMS sender redacted (e.g.
                // "+861****3748"); fall back to the thread's SMS address.
                if (!isUsableAddress(address)) {
                    address = smsAddressForThread(threadId) ?: ""
                }
                val contact = lookupContact(address)
                out += SmsMessage(
                    id = id,
                    threadId = c.getLong(threadCol),
                    address = address,
                    body = parts.text,
                    date = date,
                    isRead = isRead,
                    isSent = isSent,
                    category = if (contact != null) MessageCategory.PERSON else MessageCategory.OTHER,
                    code = null,
                    contactName = contact?.name,
                    isMms = true,
                    imageUris = parts.images,
                    sendStatus = sendStatus
                )
            }
        }
        return out
    }

    private data class MmsParts(val text: String, val images: List<Uri>)

    /** Text part + image part URIs of one MMS (content://mms/{id}/part). */
    private fun readMmsParts(mmsId: Long): MmsParts {
        var text = ""
        val images = ArrayList<Uri>()
        resolver.query(Uri.parse("content://mms/$mmsId/part"), null, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val ctCol = c.getColumnIndexOrThrow("ct")
            val textCol = c.getColumnIndexOrThrow("text")
            while (c.moveToNext()) {
                val ct = c.getString(ctCol) ?: ""
                when {
                    ct.startsWith("image/") ->
                        images += Uri.parse("content://mms/part/${c.getLong(idCol)}")
                    ct == "text/plain" -> text = c.getString(textCol) ?: ""
                }
            }
        }
        return MmsParts(text, images)
    }

    /**
     * Peer address of one MMS: FROM (type=137) for received messages, TO
     * (type=151) for sent ones. Using FROM on a sent MMS would return our
     * own number and make the conversation look like a chat with ourselves.
     */
    private fun readMmsAddress(mmsId: Long, sent: Boolean): String? {
        val wantType = if (sent) 151 else 137
        var address: String? = null
        resolver.query(Uri.parse("content://mms/$mmsId/addr"), null, null, null, null)?.use { c ->
            val addrCol = c.getColumnIndexOrThrow("address")
            val typeCol = c.getColumnIndexOrThrow("type")
            while (c.moveToNext()) {
                if (c.getInt(typeCol) == wantType) {
                    address = c.getString(addrCol)
                    break
                }
            }
        }
        return address
    }

    /** Redacted addresses (e.g. "+861****3748") are unusable for contact lookup. */
    private fun isUsableAddress(address: String): Boolean =
        address.isNotBlank() && !address.contains('*') && normalizeNumber(address).length >= 7

    /** Latest SMS address of a thread, used to repair redacted MMS senders. */
    private fun smsAddressForThread(threadId: Long): String? {
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Resolve a phone number to a contact name + photo via PhoneLookup. */
    fun lookupContact(number: String): ContactInfo? {
        if (number.isBlank()) return null
        contactCache[number]?.let { return it }
        var info = phoneLookup(number)
        val key = normalizeNumber(number)
        // PhoneLookup can miss on country-code/format differences
        // (+861****3748 vs 18857133748 stored bare). Fall back to a
        // normalized index of all contact numbers.
        if (info == null) {
            contactNumberIndex()[key]?.let { info = it }
        }
        // Privacy tooling stores the SMS address redacted ("+861****3748"),
        // so the normalized key only carries its visible digits ("8613748").
        // Match those against the index by first/last visible digits — but
        // only when the match is unambiguous (every mainland mobile shares
        // the first digit 1, so several contacts can share the visible
        // ends; guessing wrong would mislabel the sender).
        if (info == null && key.length < 11) {
            val candidates = contactNumberIndex().entries
                .filter { (cand, _) -> redactedMatches(key, cand) }
            if (candidates.size == 1) info = candidates[0].value
        }
        if (info != null) contactCache[number] = info
        return info
    }

    private fun phoneLookup(number: String): ContactInfo? {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(Uri.encode(number))
            .build()
        return resolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                ContactInfo(
                    name = c.getString(0) ?: number,
                    photoUri = c.getString(1)
                )
            } else null
        }
    }

    /**
     * Digits-only, country code stripped for mainland numbers:
     * "8618857133748" / "188 5713 3748" -> "18857133748".
     * A redacted address ("+861****3748") only yields its visible digits
     * ("8613748") — matched separately via [redactedMatches].
     */
    private fun normalizeNumber(n: String): String {
        var d = n.filter { it.isDigit() }
        if (d.length > 11 && d.startsWith("86")) d = d.drop(2)
        return d
    }

    private var contactNumberIndexMap: Map<String, ContactInfo>? = null

    /** All contact numbers keyed by normalized digits (built lazily, cached). */
    private fun contactNumberIndex(): Map<String, ContactInfo> {
        contactNumberIndexMap?.let { return it }
        val map = HashMap<String, ContactInfo>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            ),
            null, null, null
        )?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val num = c.getString(numCol) ?: continue
                val key = normalizeNumber(num)
                if (key.isNotEmpty() && !map.containsKey(key)) {
                    map[key] = ContactInfo(name = name, photoUri = c.getString(photoCol), number = num)
                }
            }
        }
        contactNumberIndexMap = map
        return map
    }

    /** Contacts with a phone number, for the 联系人 tab. */
    suspend fun loadContacts(): List<ContactInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<ContactInfo>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val number = c.getString(numCol) ?: continue
                if (seen.add(name)) out += ContactInfo(name = name, number = number)
            }
        }
        out
    }
}

/**
 * Delivery state from the sms table `type` column.
 * 2=SENT, 4=QUEUED/6=OUTBOX → SENDING, 5=FAILED, everything else NONE.
 */
fun smsSendStatus(type: Int): SendStatus = when (type) {
    Telephony.Sms.MESSAGE_TYPE_SENT -> SendStatus.SENT
    Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.MESSAGE_TYPE_OUTBOX -> SendStatus.SENDING
    Telephony.Sms.MESSAGE_TYPE_FAILED -> SendStatus.FAILED
    else -> SendStatus.NONE
}

/**
 * Delivery state from the mms table `msg_box` column.
 * 2=sent, 4=pending, 5=failed, everything else NONE.
 */
fun mmsSendStatus(box: Int): SendStatus = when (box) {
    2 -> SendStatus.SENT
    4 -> SendStatus.SENDING
    5 -> SendStatus.FAILED
    else -> SendStatus.NONE
}

/**
 * Whether an sms row is our own sent message. NOTE: kept as the original
 * `type == SENT` semantics (P0-2 in ARCHAEOLOGY.md) — a row this app just
 * inserted as TYPE=OUTBOX renders as received until the sent callback
 * flips it. Unify with [mmsIsSent] only after real-device confirmation.
 */
fun smsIsSent(type: Int): Boolean = type == Telephony.Sms.MESSAGE_TYPE_SENT

/** Whether an mms row is our own sent message (anything but inbox). */
fun mmsIsSent(box: Int): Boolean = box != 1

/**
 * Whether a redacted phone key — the visible digits of a masked address,
 * e.g. "+861****3748" → "8613748" — plausibly belongs to a full candidate
 * key ("18857133748"). Privacy tooling keeps the country code, the first
 * digit and the last 4 digits visible: "8613748" = 86 + 1 + **** + 3748.
 * Keys shorter than 4 digits carry too little information to match.
 */
fun redactedMatches(redactedKey: String, candidateKey: String): Boolean {
    if (redactedKey.length < 4 || candidateKey.length < redactedKey.length) return false
    return if (redactedKey.length == 7 && redactedKey.startsWith("86")) {
        // 86 + first digit + last-4 pattern: check both visible ends.
        candidateKey.startsWith(redactedKey[2].toString()) &&
            candidateKey.endsWith(redactedKey.substring(3))
    } else {
        // Fallback: the visible tail must be a suffix of the candidate.
        candidateKey.endsWith(redactedKey)
    }
}
