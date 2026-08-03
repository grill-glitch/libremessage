package org.librelab.messaging.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "刚刚" / "N分钟前" / "HH:mm" / "昨天" / "M月d日" / "yyyy/M/d" */
fun formatRelativeTime(dateMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - dateMillis
    if (diff < 60_000L) return "刚刚"
    if (diff < 3_600_000L) return "${diff / 60_000L}分钟前"
    val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
    val today = Calendar.getInstance()
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(dateMillis))

        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"

        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(dateMillis))

        else -> SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(dateMillis))
    }
}
