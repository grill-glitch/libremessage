package org.librelab.messaging.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Records MMS messages this app sent. The telephony stack consumes and
 * deletes the pending pdu rows on some ROMs, so we keep our own copy
 * (image + text) to render the sent bubble. SMS needs no such store —
 * the system keeps those rows in the sms table.
 */
data class OutboxMms(
    val id: Long,           // negative, to avoid clashing with provider ids
    val threadId: Long,
    val address: String,
    val text: String,
    val imagePath: String,
    val date: Long,
    val failed: Boolean
)

object OutboxStore {

    private const val FILE_NAME = "outbox_mms.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun add(context: Context, record: OutboxMms) {
        val list = all(context).toMutableList()
        list.add(record)
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("threadId", r.threadId)
                    .put("address", r.address)
                    .put("text", r.text)
                    .put("imagePath", r.imagePath)
                    .put("date", r.date)
                    .put("failed", r.failed)
            )
        }
        try {
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            android.util.Log.e("OutboxStore", "write failed", e)
        }
    }

    fun all(context: Context): List<OutboxMms> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OutboxMms(
                    id = o.getLong("id"),
                    threadId = o.getLong("threadId"),
                    address = o.optString("address", ""),
                    text = o.optString("text", ""),
                    imagePath = o.optString("imagePath", ""),
                    date = o.getLong("date"),
                    failed = o.optBoolean("failed", false)
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("OutboxStore", "read failed", e)
            emptyList()
        }
    }

    /** Flip the delivery state of one outbox record (sent callback). */
    fun mark(context: Context, id: Long, failed: Boolean) {
        val list = all(context).map { if (it.id == id) it.copy(failed = failed) else it }
        write(context, list)
    }

    /** Remove one outbox record (user deleted the message). */
    fun remove(context: Context, id: Long) {
        val list = all(context).filter { it.id != id }
        write(context, list)
    }

    private fun write(context: Context, list: List<OutboxMms>) {
        try {
            file(context).writeText(toJson(list).toString())
        } catch (e: Exception) {
            android.util.Log.e("OutboxStore", "write failed", e)
        }
    }

    private fun toJson(list: List<OutboxMms>): JSONArray {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("threadId", r.threadId)
                    .put("address", r.address)
                    .put("text", r.text)
                    .put("imagePath", r.imagePath)
                    .put("date", r.date)
                    .put("failed", r.failed)
            )
        }
        return arr
    }
}
