package org.librelab.messaging

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.librelab.messaging.ui.theme.avatarArgbFor

/**
 * Home-screen widget listing the most recent conversations, mirroring the
 * app's home list (contact name + last message + time). Rendered as a
 * scrollable ListView fed by [ListWidgetViewsFactory] via [ListWidgetService].
 *
 * Row taps use the standard template + fill-in intent mechanism. IMPORTANT:
 * the template PendingIntent MUST be FLAG_MUTABLE — a FLAG_IMMUTABLE
 * template silently drops the fill-in extras (the per-row thread id), which
 * looks like "the launcher eats the click" but is framework behaviour.
 *
 * Tapping a row opens that thread; the header / "more" link opens the app;
 * the FAB starts a new message.
 */
class ListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        // ListView backed by a RemoteViewsService (separate process).
        val intent = Intent(context, ListWidgetService::class.java).apply {
            data = Uri.parse("widget://org.librelab.messaging/list")
        }
        views.setRemoteAdapter(R.id.widget_list_view, intent)
        views.setEmptyView(R.id.widget_list_view, R.id.widget_list_empty)

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
            openApp(context, 1, MainActivity.ACTION_NEW_MESSAGE, -1L)
        )

        // Row tap -> open the thread. The template must be MUTABLE so the
        // per-row fill-in extras (thread id) actually merge into the final
        // intent. (FLAG_IMMUTABLE silently drops them.)
        val rowTemplate = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_THREAD
        }
        views.setPendingIntentTemplate(
            R.id.widget_list_view,
            PendingIntent.getActivity(
                context,
                0,
                rowTemplate,
                PendingIntent.FLAG_MUTABLE
            )
        )
        return views
    }

    private fun openApp(context: Context, requestCode: Int, action: String, threadId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(action)
        if (threadId > 0) intent.putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
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
                ListWidgetProvider().onUpdate(context, manager, ids)
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_view)
            }
        }
    }
}

/** Bound service feeding conversation rows to the widget's ListView. */
class ListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ListWidgetViewsFactory(applicationContext)
}

/** Loads the latest threads directly from the SMS provider. */
class ListWidgetViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private val threads = ArrayList<SmsThreadRow>()
    private val resolver = context.contentResolver

    override fun onCreate() = loadThreads()

    override fun onDataSetChanged() {
        loadThreads()
    }

    override fun onDestroy() = threads.clear()

    override fun getCount(): Int = threads.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = threads[position]
        val views = RemoteViews(context.packageName, R.layout.widget_list_row)
        views.setTextViewText(R.id.widget_row_name, row.sender)
        views.setTextViewText(R.id.widget_row_preview, row.preview)
        views.setTextViewText(R.id.widget_row_time, row.time)
        views.setTextViewText(R.id.widget_row_avatar, row.avatarInitial)
        // Colored avatar, same palette as the app (deterministic per sender).
        views.setColorStateList(
            R.id.widget_row_avatar,
            "setBackgroundTintList",
            android.content.res.ColorStateList.valueOf(row.avatarColor)
        )
        views.setTextColor(R.id.widget_row_avatar, row.avatarContent)
        // Per-row fill-in: only the thread id (no component/action — those
        // come from the template).
        val fillIn = Intent().apply {
            putExtra(MainActivity.EXTRA_THREAD_ID, row.threadId)
        }
        views.setOnClickFillInIntent(R.id.widget_row_root, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = threads[position].threadId

    override fun hasStableIds(): Boolean = true

    private fun loadThreads() {
        threads.clear()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID
        )
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
        // Group by thread, keep the latest message per thread.
        val byThread = LinkedHashMap<Long, SmsThreadRow>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val existing = byThread[threadId]
                if (existing == null || date > existing.date) {
                    val name = nameFor(address)
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
        threads.addAll(byThread.values.sortedByDescending { it.date })
    }

    /** Contact display name for a number, or null when not in contacts. */
    private val contactCache = HashMap<String, String?>()

    private fun nameFor(address: String): String? {
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
    private fun avatarColorForKey(key: String): Int = avatarArgbFor(key).container

    /** Matching dark content color for the avatar letter. */
    private fun avatarContentForKey(key: String): Int = avatarArgbFor(key).content
}

/** One conversation row for the widget list. */
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
