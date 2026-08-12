package org.librelab.messaging.data

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_balance
import com.composables.icons.materialsymbols.outlined.Box
import com.composables.icons.materialsymbols.outlined.Campaign
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Shield
import org.librelab.messaging.R
import java.io.File

/** Category assigned by [SmsParser] to every real message. */
enum class MessageCategory {
    CODE,       // 验证码 / 校验码
    PACKAGE,    // 取件码 / 取货码 (快递驿站)
    AD,         // 广告营销
    BANK,       // 银行扣款 / 消费
    ECOMMERCE,  // 京东 / 顺丰 / 快递
    SERVICE,    // 12306 等服务号
    CARRIER,    // 运营商
    PERSON,     // 已存联系人
    OTHER,      // 普通未知号码
    ARCHIVED;   // 归档线程

    val icon: ImageVector
        get() = when (this) {
            CODE -> MaterialSymbols.Outlined.Shield
            PACKAGE -> MaterialSymbols.Outlined.Box
            AD -> MaterialSymbols.Outlined.Campaign
            BANK -> MaterialSymbols.Outlined.Account_balance
            ECOMMERCE -> MaterialSymbols.Outlined.Box
            SERVICE -> MaterialSymbols.Outlined.Notifications
            CARRIER -> MaterialSymbols.Outlined.Favorite
            PERSON, OTHER -> MaterialSymbols.Outlined.Person
            ARCHIVED -> MaterialSymbols.Outlined.Notifications
        }

    val isNotification: Boolean
        get() = this in setOf(BANK, ECOMMERCE, SERVICE, CARRIER)
}

/** Filter chips on the main screen. */
enum class SmsFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    CODE(R.string.filter_code),
    PACKAGE(R.string.filter_package),
    AD(R.string.filter_ad),
    ARCHIVED(R.string.filter_archived);

    fun matches(category: MessageCategory, body: String = ""): Boolean = when (this) {
        ALL -> category != MessageCategory.ARCHIVED
        CODE -> category == MessageCategory.CODE
        // Express pickup SMS are PACKAGE — never mixed into the 验证码 filter.
        PACKAGE -> category == MessageCategory.PACKAGE
        AD -> category == MessageCategory.AD
        ARCHIVED -> category == MessageCategory.ARCHIVED
    }
}

/** Delivery state of an outgoing message, driven by the sms `type` / mms `msg_box`. */
enum class SendStatus { NONE, SENDING, SENT, FAILED }

/** One active SIM card: subscription id + display name (for the picker). */
data class SimCard(
    val subId: Int,
    val name: String
)

/**
 * Pending MMS attachment chosen by the user: an image (compressed to JPEG
 * on send) or any other file (sent with its original MIME type + name).
 */
data class PendingAttachment(
    val file: File,
    val name: String,
    val mime: String,
    val isImage: Boolean
)

/** One real SMS row read from the Telephony provider. */
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,          // epoch millis
    val isRead: Boolean,
    val isSent: Boolean,     // true = sent by us (type SENT), false = received
    val category: MessageCategory,
    val code: String?,       // extracted verification code, if any
    val contactName: String?, // matched contact display name, if any
    val isMms: Boolean = false,          // true = MMS message
    val imageUris: List<Uri> = emptyList(), // MMS image parts (content://mms/part/...)
    val attachmentName: String? = null,    // MMS file attachment display name
    val sendStatus: SendStatus = SendStatus.NONE // outgoing delivery state
) {
    val sender: String get() = contactName?.takeIf { it.isNotBlank() } ?: address
    val hasCode: Boolean get() = !code.isNullOrBlank()

    /** Unique key across the sms and mms tables (ids may overlap). */
    val key: String get() = "${if (isMms) "m" else "s"}$id"

    /** Smart card title: merchant name from the 【...】 bracket, else sender. */
    val merchantName: String get() = SmsParser.merchantName(body, sender)

    /** Smart card subtitle: pickup code for express messages, else verification code. */
    val isPickupCode: Boolean get() = SmsParser.isPickupCode(body)
}

/** Contact lookup result from ContactsContract. */
data class ContactInfo(
    val name: String,
    val photoUri: String? = null,
    val number: String? = null
)

/**
 * One conversation row: the latest message of a thread plus aggregate
 * counts, so a sender's messages occupy a single list row.
 */
data class SmsThreadItem(
    val message: SmsMessage,   // latest message in the thread
    val unreadCount: Int,
    val totalCount: Int
)
