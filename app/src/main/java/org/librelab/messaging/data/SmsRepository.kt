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

    /** Whether ad messages are shown on the 全部 filter (settings). */
    fun showAdsInAll(): Boolean =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getBoolean("show_ads_in_all", false)

    fun setShowAdsInAll(show: Boolean) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("show_ads_in_all", show).apply()
    }

    /** Whether ad-message notifications are muted (default: muted). */
    fun notifyAds(): Boolean =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getBoolean("notify_ads", false)

    fun setNotifyAds(notify: Boolean) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("notify_ads", notify).apply()
    }

    /** Whether incoming code messages auto-copy the code to the clipboard. */
    fun autoCopyCode(): Boolean =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getBoolean("auto_copy_code", true)

    fun setAutoCopyCode(auto: Boolean) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_copy_code", auto).apply()
    }

    /** Anti verification-code-bombing: mute code messages + hide from 全部. */
    fun antiBomb(): Boolean =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getBoolean("anti_bomb", false)

    fun setAntiBomb(on: Boolean) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("anti_bomb", on).apply()
    }

    /** End of the temporary "accept codes" window (epoch millis, 0 = none). */
    fun antiBombUntil(): Long =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getLong("anti_bomb_until", 0L)

    fun setAntiBombUntil(until: Long) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit().putLong("anti_bomb_until", until).apply()
    }

    /** Default SIM subscription id for outgoing messages (0 = auto/system). */
    fun defaultSubId(): Int =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getInt("default_sub_id", 0)

    fun setDefaultSubId(subId: Int) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit().putInt("default_sub_id", subId).apply()
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
                val isSent = type == Telephony.Sms.MESSAGE_TYPE_SENT
                val sendStatus = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_SENT -> SendStatus.SENT
                    Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.MESSAGE_TYPE_OUTBOX -> SendStatus.SENDING
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> SendStatus.FAILED
                    else -> SendStatus.NONE
                }
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
                val isSent = box != 1
                val sendStatus = when (box) {
                    2 -> SendStatus.SENT
                    4 -> SendStatus.SENDING
                    5 -> SendStatus.FAILED
                    else -> SendStatus.NONE
                }
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
        // PhoneLookup can miss on country-code/format differences
        // (+8618857133748 vs 18857133748 stored bare). Fall back to a
        // normalized index of all contact numbers.
        if (info == null) {
            contactNumberIndex()[normalizeNumber(number)]?.let { info = it }
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
     * "+8618857133748" / "8618857133748" / "188 5713 3748" -> "18857133748".
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
