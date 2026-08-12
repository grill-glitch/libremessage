package org.librelab.messaging

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.widget.RemoteViews
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsParser
import org.librelab.messaging.data.MessageCategory

/**
 * Home-screen widget showing the latest verification-code card, mirroring
 * the in-app smart banner (merchant name + code + excerpt). Two layouts:
 * 2×1 (compact card) and 2×3 (card + recent code history).
 *
 * Reads the SMS database directly (widgets live in a separate process and
 * cannot rely on the ViewModel); refreshes on update tick and on a broadcast
 * sent by [SmsReceiver] when a message arrives.
 */
class CodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val isTall = appWidgetManager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= 110
            val views = if (isTall) {
                buildTallViews(context)
            } else {
                buildCompactViews(context)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        // 2×1 vs 2×3 cells share the same provider: re-render with the right
        // layout when the user resizes the widget.
        val isTall = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= 110
        val views = if (isTall) buildTallViews(context) else buildCompactViews(context)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /** 2×1: merchant + code + one-line excerpt. */
    private fun buildCompactViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_code_compact)
        val code = latestCodeMessage(context)
        if (code == null) {
            views.setTextViewText(R.id.widget_code_merchant, context.getString(R.string.widget_no_code))
            views.setTextViewText(R.id.widget_code_value, "")
            views.setTextViewText(R.id.widget_code_body, "")
        } else {
            views.setTextViewText(R.id.widget_code_merchant, code.merchantName)
            views.setTextViewText(R.id.widget_code_value, code.code ?: "")
            views.setTextViewText(R.id.widget_code_body, code.body)
        }
        wireOpenApp(views, context)
        return views
    }

    /** 2×3: the card on top plus the three most recent codes below. */
    private fun buildTallViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_code_tall)
        val codes = recentCodes(context, 4) // first = banner card, rest = history
        if (codes.isEmpty()) {
            views.setTextViewText(R.id.widget_tall_merchant, context.getString(R.string.widget_no_code))
            views.setTextViewText(R.id.widget_tall_code, "")
            views.setTextViewText(R.id.widget_tall_body, "")
            views.setViewVisibility(R.id.widget_tall_history, android.view.View.GONE)
        } else {
            val card = codes[0]
            views.setTextViewText(R.id.widget_tall_merchant, card.merchantName)
            views.setTextViewText(R.id.widget_tall_code, card.code ?: "")
            views.setTextViewText(R.id.widget_tall_body, card.body)
            if (codes.size > 1) {
                views.setViewVisibility(R.id.widget_tall_history, android.view.View.VISIBLE)
                // history rows: index i -> row i-1
                for (i in 0 until 3) {
                    val text = if (i + 1 < codes.size) {
                        val m = codes[i + 1]
                        "${m.merchantName}  ${m.code}"
                    } else {
                        ""
                    }
                    views.setTextViewText(
                        context.resources.getIdentifier("widget_tall_row_${i + 1}", "id", context.packageName),
                        text
                    )
                }
            } else {
                views.setViewVisibility(R.id.widget_tall_history, android.view.View.GONE)
            }
        }
        wireOpenApp(views, context)
        return views
    }

    private fun wireOpenApp(views: RemoteViews, context: Context) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
    }

    /** Latest code/pickup message for the banner card. */
    private fun latestCodeMessage(context: Context): SmsMessage? {
        val codes = recentCodes(context, 1)
        return codes.firstOrNull()
    }

    private fun recentCodes(context: Context, limit: Int): List<SmsMessage> {
        val resolver = context.contentResolver
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.THREAD_ID
        )
        val list = ArrayList<SmsMessage>()
        val cursor: Cursor? = try {
            resolver.query(
                uri, projection,
                "${Telephony.Sms.TYPE} = 1",
                null,
                "${Telephony.Sms.DATE} DESC"
            )
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val category = SmsParser.classify(body, hasContact = false, archived = false)
                if (category == MessageCategory.CODE || category == MessageCategory.PACKAGE) {
                    val code = SmsParser.extractAllCodes(body).firstOrNull()
                    list.add(
                        SmsMessage(
                            id = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID)),
                            threadId = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                            address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                            body = body,
                            date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                            isRead = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.READ)) != 0,
                            isSent = false,
                            category = category,
                            code = code,
                            contactName = null
                        )
                    )
                }
                if (list.size >= limit) break
            }
        }
        return list
    }

    companion object {
        /** Broadcast action: a new SMS arrived, refresh all code widgets. */
        const val ACTION_REFRESH = "org.librelab.messaging.action.WIDGET_REFRESH"

        /** Called from [SmsReceiver] after persisting a message. */
        fun requestRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, CodeWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                CodeWidgetProvider().onUpdate(context, manager, ids)
            }
        }
    }
}
