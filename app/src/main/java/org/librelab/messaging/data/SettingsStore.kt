package org.librelab.messaging.data

import android.content.Context

/**
 * App settings persisted in SharedPreferences — the single source of truth
 * for every preference key. Both the receiver (background process) and the
 * UI read through this object, so a setting can never be read with a
 * different default or key in two places.
 */
object SettingsStore {

    private const val PREF_NAME = "settings_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Whether ad messages are shown on the 全部 filter (settings). */
    fun showAdsInAll(context: Context): Boolean =
        prefs(context).getBoolean("show_ads_in_all", false)

    fun setShowAdsInAll(context: Context, show: Boolean) =
        prefs(context).edit().putBoolean("show_ads_in_all", show).apply()

    /** Whether ad-message notifications are muted (default: muted). */
    fun notifyAds(context: Context): Boolean =
        prefs(context).getBoolean("notify_ads", false)

    fun setNotifyAds(context: Context, notify: Boolean) =
        prefs(context).edit().putBoolean("notify_ads", notify).apply()

    /** Whether incoming code messages auto-copy the code to the clipboard. */
    fun autoCopyCode(context: Context): Boolean =
        prefs(context).getBoolean("auto_copy_code", true)

    fun setAutoCopyCode(context: Context, auto: Boolean) =
        prefs(context).edit().putBoolean("auto_copy_code", auto).apply()

    /** Anti verification-code-bombing: mute code messages + hide from 全部. */
    fun antiBomb(context: Context): Boolean =
        prefs(context).getBoolean("anti_bomb", false)

    fun setAntiBomb(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("anti_bomb", on).apply()

    /** End of the temporary "accept codes" window (epoch millis, 0 = none). */
    fun antiBombUntil(context: Context): Long =
        prefs(context).getLong("anti_bomb_until", 0L)

    fun setAntiBombUntil(context: Context, until: Long) =
        prefs(context).edit().putLong("anti_bomb_until", until).apply()

    /** Default SIM subscription id for outgoing messages (0 = auto/system). */
    fun defaultSubId(context: Context): Int =
        prefs(context).getInt("default_sub_id", 0)

    fun setDefaultSubId(context: Context, subId: Int) =
        prefs(context).edit().putInt("default_sub_id", subId).apply()
}
