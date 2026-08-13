package org.librelab.messaging

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.RemoteViews

/**
 * Home-screen widget listing the most recent conversations, mirroring the
 * app's home list (contact name + last message + time).
 *
 * Uses FIXED rows (not a ListView): crDroid's launcher intercepts every
 * touch on ListView items — fill-in extras are dropped and per-row click
 * intents are never delivered — but ordinary views get their own reliable
 * click PendingIntent. Each fixed row therefore carries its own click
 * PendingIntent with the thread id in the extras.
 *
 * Tapping a row opens that thread; the header / "more" link opens the app;
 * the bottom-right FAB starts a new message.
 */
class ListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        val threads = loadThreads(context)

        // Header tap -> open the app.
        views.setOnClickPendingIntent(
            R.id.widget_list_header,
            openApp(context, 0, "", -1L)
        )
        // "More" link -> open the app.
        views.setOnClickPendingIntent(
            R.id.widget_list_more,
            openApp(context, 2, "", -1L)
        )

        // FAB -> new-message draft (same action as the launcher shortcut).
        views.setOnClickPendingIntent(
            R.id.widget_new_msg,
            openApp(context, 1, "org.librelab.messaging.action.NEW_MESSAGE", -1L)
        )

        // Fill the fixed rows; hide the unused ones.
        val rows = arrayOf(
            R.id.widget_row0_root to arrayOf(
                R.id.widget_row0_avatar, R.id.widget_row0_name,
                R.id.widget_row0_preview, R.id.widget_row0_time
            ),
            R.id.widget_row1_root to arrayOf(
                R.id.widget_row1_avatar, R.id.widget_row1_name,
                R.id.widget_row1_preview, R.id.widget_row1_time
            ),
            R.id.widget_row2_root to arrayOf(
                R.id.widget_row2_avatar, R.id.widget_row2_name,
                R.id.widget_row2_preview, R.id.widget_row2_time
            ),
            R.id.widget_row3_root to arrayOf(
                R.id.widget_row3_avatar, R.id.widget_row3_name,
                R.id.widget_row3_preview, R.id.widget_row3_time
            ),
            R.id.widget_row4_root to arrayOf(
                R.id.widget_row4_avatar, R.id.widget_row4_name,
                R.id.widget_row4_preview, R.id.widget_row4_time
            ),
            R.id.widget_row5_root to arrayOf(
                R.id.widget_row5_avatar, R.id.widget_row5_name,
                R.id.widget_row5_preview, R.id.widget_row5_time
            )
        )

        for (i in rows.indices) {
            val (root, ids) = rows[i]
            val (avatar, name, preview, time) = ids
            if (i < threads.size) {
                val t = threads[i]
                views.setViewVisibility(root, android.view.View.VISIBLE)
                views.setTextViewText(name, t.sender)
                views.setTextViewText(preview, t.preview)
                views.setTextViewText(time, t.time)
                views.setTextViewText(avatar, t.avatarInitial)
                views.setColorStateList(
                    avatar,
                    "setBackgroundTintList",
                    android.content.res.ColorStateList.valueOf(t.avatarColor)
                )
                views.setTextColor(avatar, t.avatarContent)
                views.setOnClickPendingIntent(
                    root,
                    openApp(context, 10 + i, "org.librelab.messaging.action.OPEN_THREAD", t.threadId)
                )
            } else {
                views.setViewVisibility(root, android.view.View.GONE)
            }
        }

        if (threads.isEmpty()) {
            views.setViewVisibility(R.id.widget_list_empty, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_list_empty, android.view.View.GONE)
        }
        return views
    }

    private fun openApp(context: Context, requestCode: Int, action: String, threadId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(action)
        if (threadId > 0) intent.putExtra("threadId", threadId)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** Called from [SmsReceiver] after persisting a message. */
        fun requestRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ListWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                // Rebuild the full RemoteViews (re-attaches the per-row click
                // PendingIntents).
                ListWidgetProvider().onUpdate(context, manager, ids)
            }
        }
    }
}

/** One conversation row for the widget. */
data class SmsThreadRow(
    val threadId: Long,
    val sender: String,
    val preview: String,
    val date: Long,
    val avatarInitial: String,
    val avatarColor: Int,
    val avatarContent: Int
) {
    val time: String
        get() = android.text.format.DateFormat.format("HH:mm", date).toString()
}

/** Loads the latest threads directly from the SMS provider. */
private fun loadThreads(context: Context): List<SmsThreadRow> {
    val resolver = context.contentResolver
    val uri = Telephony.Sms.CONTENT_URI
    val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.THREAD_ID
    )
    val byThread = LinkedHashMap<Long, SmsThreadRow>()
    val cursor: Cursor? = try {
        resolver.query(
            uri, projection,
            "${Telephony.Sms.TYPE} IN (1,2,4,5,6)",
            null,
            "${Telephony.Sms.DATE} DESC"
        )
    } catch (e: Exception) {
        null
    }
    cursor?.use { c ->
        while (c.moveToNext()) {
            val threadId = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
            val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
            val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
            val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
            val existing = byThread[threadId]
            if (existing == null || date > existing.date) {
                val name = nameFor(resolver, address)
                val initial = (name ?: address).firstOrNull()?.uppercase() ?: "?"
                byThread[threadId] = SmsThreadRow(
                    threadId = threadId,
                    sender = name ?: address,
                    preview = body,
                    date = date,
                    avatarInitial = initial,
                    avatarColor = avatarColorForKey(name ?: address),
                    avatarContent = avatarContentForKey(name ?: address)
                )
            }
        }
    }
    return byThread.values.sortedByDescending { it.date }.take(6)
}

/** Contact display name for a number, or null when not in contacts. */
private val contactCache = HashMap<String, String?>()

private fun nameFor(resolver: android.content.ContentResolver, address: String): String? {
    contactCache[address]?.let { return it }
    val digits = address.filter { it.isDigit() }
    var name: String? = null
    try {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && name == null) {
                val num = c.getString(numCol) ?: continue
                if (num.filter { it.isDigit() }.endsWith(digits.takeLast(7))) {
                    name = c.getString(nameCol)
                }
            }
        }
    } catch (e: Exception) {
        // no READ_CONTACTS permission or provider error: show the number
    }
    contactCache[address] = name
    return name
}

/** Deterministic palette color — same 8-color MD3 palette as the app. */
private fun avatarColorForKey(key: String): Int {
    val palette = intArrayOf(
        0xFFBBDEFB.toInt(), // blue
        0xFFC8E6C9.toInt(), // green
        0xFFFFE0B2.toInt(), // orange
        0xFFE1BEE7.toInt(), // purple
        0xFFF8BBD0.toInt(), // pink
        0xFFB2EBF2.toInt(), // cyan
        0xFFFFCDD2.toInt(), // red
        0xFFD7CCC8.toInt()  // brown
    )
    return palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]
}

/** Matching dark content color for the avatar letter. */
private fun avatarContentForKey(key: String): Int {
    val palette = intArrayOf(
        0xFF0D47A1.toInt(), // blue
        0xFF1B5E20.toInt(), // green
        0xFFE65100.toInt(), // orange
        0xFF4A148C.toInt(), // purple
        0xFF880E4F.toInt(), // pink
        0xFF006064.toInt(), // cyan
        0xFFB71C1C.toInt(), // red
        0xFF3E2723.toInt()  // brown
    )
    return palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]
}
