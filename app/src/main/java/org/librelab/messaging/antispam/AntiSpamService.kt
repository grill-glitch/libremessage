package org.librelab.messaging.antispam

/**
 * Number-marking lookup result — the MIUI yellow-page / anti-spam data
 * surfaced for one phone number. Mirrors the mi-anti-spam API response.
 */
data class NumberMark(
    /** Mark category, e.g. "广告推销" / "快递外卖". Null when unmarked. */
    val category: String? = null,
    /** Mark category id (1,2,3,5,6,13,14,21…). */
    val categoryId: Int? = null,
    /** How many users marked this number. */
    val count: Int? = null,
    /** Yellow-page business name, e.g. "招商银行". */
    val businessName: String? = null,
    /** Yellow-page icon URL (thumbnail). */
    val iconUrl: String? = null,
    /** Risk level string, e.g. "high". */
    val riskType: String? = null,
) {
    /** True when the number carries any mark or yellow-page record. */
    val isMarked: Boolean
        get() = category != null || businessName != null
}

/**
 * Anti-spam / number-marking backend. The [lookup] call is network I/O and
 * must run off the main thread.
 *
 * Each build flavor supplies its own implementation:
 *  - standard: real mi-anti-spam (MIUI yellow-page) HTTP lookup
 *  - libre:    offline no-op that always returns null
 */
interface AntiSpamService {
    /** Whether this build can actually query (standard=true, libre=false). */
    fun isAvailable(): Boolean

    suspend fun lookup(number: String): NumberMark?
}
