package org.librelab.messaging.data

/**
 * Smart parser: extracts verification codes and classifies SMS by content.
 * Pure logic — no Android dependencies, trivially unit-testable.
 */
object SmsParser {

    // 4-8 digit sequence not embedded in a longer number
    private val CODE_PATTERN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    // Express pickup code: 【韵达快递】凭15-2-1300到采荷百合路11号驿站取运单尾号1300包裹
    private val EXPRESS_CODE_PATTERN = Regex("凭\\s*([\\d]+-[\\d]+-[\\d]+)")

    /** 【商户名】 bracket for extracting the merchant name. */
    private val BRACKET_PATTERN = Regex("【([^】]+)】")

    private val CODE_KEYWORDS = listOf(
        "验证码", "取件码", "校验码", "动态密码", "动态码", "短信验证", "安全码"
    )
    private val AD_KEYWORDS = listOf(
        "退订", "回复TD", "回复 T", "回复t", "广告", "推广", "优惠", "福利",
        "点击领取", "限时", "秒杀", "红包", "抽奖", "下单立减", "免单"
    )
    private val BANK_KEYWORDS = listOf(
        "银行", "扣款", "消费", "余额", "还款", "信用卡", "储蓄卡", "转账",
        "入账", "支出", "交易提醒", "pos机", "快捷支付"
    )
    private val ECOMMERCE_KEYWORDS = listOf(
        "京东", "顺丰", "淘宝", "天猫", "拼多多", "快递", "包裹", "订单",
        "发货", "物流", "驿站", "取件", "派送", "签收"
    )
    private val SERVICE_KEYWORDS = listOf(
        "12306", "铁路", "航空", "航班", "预约", "挂号", "就诊", "社保",
        "税务", "政务", "app验证", "登录提醒", "安全提醒"
    )
    private val CARRIER_KEYWORDS = listOf(
        "中国移动", "中国联通", "中国电信", "运营商", "流量", "话费", "套餐", "5g"
    )

    /**
     * Extract the verification code. Space-stripped matching handles
     * "123 456" style codes; only returns a value when a code keyword is
     * present so that phone numbers / years are never misread as codes.
     */
    fun extractCode(body: String): String? {
        val text = body.replace(" ", "")
        // Express pickup messages carry the cabinet number as 凭X-X-XXXX and
        // say 驿站/取件 — the literal "取件码" never appears in them.
        if (text.contains("驿站") || text.contains("取运单")) {
            EXPRESS_CODE_PATTERN.find(body)?.let { return it.groupValues[1] }
        }
        if (CODE_KEYWORDS.none { text.contains(it) }) return null
        val matches = CODE_PATTERN.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return null
        // Prefer the sequence closest to the first keyword occurrence.
        val keywordIndex = CODE_KEYWORDS.mapNotNull { text.indexOf(it).takeIf { i -> i >= 0 } }
            .minOrNull() ?: return matches.first()
        return matches.minByOrNull { kotlin.math.abs(text.indexOf(it) - keywordIndex) }
    }

    /** Format a 6-digit code as "XXX XXX" for the smart card display. */
    fun formatCode(code: String): String =
        if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

    /**
     * Merchant name for the smart card title: extracted from the 【...】
     * bracket of the SMS body (e.g. 【京东】 → 京东). Falls back to the
     * sender/address when no bracket is present.
     */
    fun merchantName(body: String, fallback: String): String =
        BRACKET_PATTERN.find(body)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    /** Smart card subtitle: "取件码" for express messages, else "验证码". */
    fun codeLabel(body: String): String =
        if (body.contains("取件码") ||
            (body.contains("驿站") && EXPRESS_CODE_PATTERN.containsMatchIn(body))
        ) "取件码" else "验证码"

    fun classify(body: String, hasContact: Boolean, archived: Boolean): MessageCategory {
        if (archived) return MessageCategory.ARCHIVED
        if (extractCode(body) != null) return MessageCategory.CODE
        if (AD_KEYWORDS.any { body.contains(it) }) return MessageCategory.AD
        if (BANK_KEYWORDS.any { body.contains(it) }) return MessageCategory.BANK
        if (ECOMMERCE_KEYWORDS.any { body.contains(it) }) return MessageCategory.ECOMMERCE
        if (SERVICE_KEYWORDS.any { body.contains(it) }) return MessageCategory.SERVICE
        if (CARRIER_KEYWORDS.any { body.contains(it) }) return MessageCategory.CARRIER
        return if (hasContact) MessageCategory.PERSON else MessageCategory.OTHER
    }
}
