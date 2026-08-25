package org.librelab.messaging.util

import android.content.Context
import org.librelab.messaging.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "刚刚" / "N分钟前" / "HH:mm" / "昨天" / "M月d日" / "yyyy/M/d" (locale-aware). */
fun formatRelativeTime(context: Context, dateMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - dateMillis
    if (diff < 60_000L) return context.getString(R.string.time_just_now)
    if (diff < 3_600_000L) {
        return context.getString(R.string.time_minutes_ago, diff / 60_000L)
    }
    val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
    val today = Calendar.getInstance()
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(dateMillis))

        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 ->
            context.getString(R.string.time_yesterday)

        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat(
                context.getString(R.string.date_format_month_day),
                Locale.getDefault()
            ).format(Date(dateMillis))

        else -> SimpleDateFormat(
            context.getString(R.string.date_format_year_month_day),
            Locale.getDefault()
        ).format(Date(dateMillis))
    }
}

/**
 * Absolute bubble time "HH:mm" (conversation bubbles). Extracted from
 * ThreadDetailScreen so the same format is used everywhere; locale-aware.
 */
fun formatBubbleTime(dateMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(dateMillis))
