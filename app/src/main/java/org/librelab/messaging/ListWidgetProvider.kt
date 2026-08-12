package org.librelab.messaging

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * Home-screen widget listing the most recent conversations, mirroring the
 * app's home list (sender + last message + time). Rendered as a ListView
 * fed by [ListWidgetViewsFactory] via [ListWidgetService].
 *
 * Tapping a row opens that thread in the app; the header opens the app.
 */
class ListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val views = buildViews(context)
            appWidgetManager.updateAppWidget(id, views)
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
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_list_header, open)

        // FAB -> new-message draft (same action as the launcher shortcut).
        val compose = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .setAction("org.librelab.messaging.action.NEW_MESSAGE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_new_msg, compose)

        // Row tap -> open the thread. The service passes a thread id per row.
        val rowTemplate = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        views.setPendingIntentTemplate(
            R.id.widget_list_view,
            PendingIntent.getActivity(
                context,
                0,
                rowTemplate,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        return views
    }

    companion object {
        /** Called from [SmsReceiver] after persisting a message. */
        fun requestRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ListWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
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
        // Row tap opens the thread.
        val openThread = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("threadId", row.threadId)
        }
        views.setOnClickFillInIntent(R.id.widget_row_root, openThread)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = threads[position].threadId

    override fun hasStableIds(): Boolean = true

    private fun loadThreads() {
        threads.clear()
        val resolver = context.contentResolver
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
                    // Avatar: last digit of the number is more recognizable
                    // than the leading '+' of an international number.
                    val digit = address.filter { it.isDigit() }.lastOrNull()?.toString()
                    byThread[threadId] = SmsThreadRow(
                        threadId = threadId,
                        address = address,
                        sender = address,
                        preview = body,
                        date = date,
                        avatarInitial = digit ?: address.firstOrNull()?.uppercase() ?: "?"
                    )
                }
            }
        }
        threads.addAll(
            byThread.values
                .sortedByDescending { it.date }
                .take(8)
        )
    }
}

/** One conversation row for the widget list. */
data class SmsThreadRow(
    val threadId: Long,
    val address: String,
    val sender: String,
    val preview: String,
    val date: Long,
    val avatarInitial: String
) {
    val time: String
        get() = android.text.format.DateFormat.format("HH:mm", date).toString()
}
