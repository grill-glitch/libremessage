package org.librelab.messaging.data

/**
 * Smart parser: extracts verification codes and classifies SMS by content.
 * Pure logic — no Android dependencies, trivially unit-testable.
 *
 * Multilingual matching (inspired by otphelper's sensitivePhrases and
 * deliveries' per-carrier tracking-number formats): keyword tables cover
 * zh-CN, en, ja, ko, ru, de, es, fr, it, tr, ar, fa… so codes and parcel
 * messages are recognized regardless of the system locale or SMS language.
 * Code candidates are normalized to ASCII digits (Arabic-Indic, Persian,
 * full-width) before matching.
 */
object SmsParser {

    // 4-8 digit sequence not embedded in a longer number
    private val CODE_PATTERN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    // Express pickup code: 【韵达快递】凭15-2-1300到采荷百合路11号驿站取运单尾号1300包裹
    // 【多多代收点】您有2个包裹在采荷百合路11号驿站,取货码1-3-9448、5-4-3216
    // EN: "Pickup code 1-3-9448", "locker code 5-4-3216", "collection code 15-2-1300"
    private val EXPRESS_CODE_PATTERN = Regex(
        "(?:凭|取货码|取件码|pickup\\s*code|locker\\s*code|collection\\s*code|\\bcode)\\s*[:：]?\\s*([\\d]+-[\\d]+-[\\d]+)",
        RegexOption.IGNORE_CASE
    )

    /** 【商户名】 bracket for extracting the merchant name. */
    private val BRACKET_PATTERN = Regex("【([^】]+)】")

    // Verification-code keywords, multilingual. Patterns use \b word
    // boundaries for Latin scripts; CJK/Cyrillic keywords match as-is.
    private val CODE_KEYWORDS = listOf(
        // zh-CN / zh-TW
        "验证码", "校验码", "取件码", "动态密码", "动态码", "短信验证", "安全码",
        "驗證碼", "校驗碼", "識別碼", "認證", "驗證",
        // en
        "\\bcode\\b", "\\botp\\b", "\\b2fa\\b", "one[- ]time[- ]password",
        "\\bpin\\b", "\\bpassword\\b", "\\bverification\\b", "\\bverify\\b",
        "\\bsecurity\\s*code\\b", "\\bauth\\s*code\\b", "\\bactivation\\s*code\\b",
        // ja
        "コード", "パスワード", "認証番号", "ワンタイム", "本人確認",
        // ko
        "인증번호", "비밀번호", "인증", "일회용",
        // ru
        "код", "пароль", "подтверждения",
        // de
        "einmalkennwort", "bestätigungscode", "t?an\\b",
        // es
        "c[oó]digo", "clave", "contraseña", "verificación",
        // fr
        "code", "mot de passe", "vérification",
        // it
        "codice", "password", "verifica",
        // tr
        "kod", "kodunuz", "şifre", "sifre",
        // ar / fa
        "رمز", "کد", "كود", "تایید",
        // pt
        "código", "senha", "verificação"
    )

    // Ad / marketing keywords, multilingual.
    private val AD_KEYWORDS = listOf(
        // zh-CN
        "退订", "回复TD", "回复 T", "回复t", "拒收请回复", "广告", "推广", "优惠", "福利",
        "点击领取", "点击查看", "限时", "秒杀", "红包", "抽奖", "下单立减", "免单",
        "免费", "参保", "补贴", "更划算",
        // en
        "unsubscribe", "opt[ -]out", "\\bad\\b", "ads", "promo", "promotion",
        "discount", "coupon", "sale", "clearance", "limited[ -]time", "flash sale",
        "cashback", "rebate", "bonus", "free gift", "win a", "lucky draw", "lottery",
        "click here", "claim now", "act now", "don't miss", "exclusive offer",
        // ja
        "広告", "キャンペーン", "セール", "クーポン", "限定", "無料",
        // ko
        "광고", "할인", "쿠폰", "이벤트", "무료",
        // ru
        "реклама", "скидка", "акция", "бесплатно", "купон",
        // es/fr/it/pt
        "publicidad", "anuncio", "descuento", "oferta", "gratis",
        "publicité", "promo", "réduction", "offre", "gratuit",
        "pubblicità", "sconto", "offerta", "gratuito",
        "propaganda", "desconto", "oferta", "grátis"
    )

    // Bank / payment keywords, multilingual.
    private val BANK_KEYWORDS = listOf(
        // zh-CN
        "银行", "扣款", "消费", "余额", "还款", "信用卡", "储蓄卡", "转账",
        "入账", "支出", "交易提醒", "pos机", "快捷支付",
        // en
        "bank", "debit", "credit card", "payment", "transaction", "withdrawal",
        "deposit", "balance", "transfer", "charged", "refund", "statement",
        "card ending", "atm",
        // ja
        "銀行", "入金", "出金", "残高", "振込", "請求", "カード",
        // ko
        "은행", "결제", "입금", "출금", "잔액", "송금", "카드",
        // ru
        "банк", "оплата", "списание", "баланс", "перевод", "карта",
        // es/fr/it/pt/de
        "banco", "pago", "tarjeta", "saldo", "transferencia",
        "banque", "paiement", "carte", "solde", "virement",
        "banca", "pagamento", "carta", "saldo", "bonifico",
        "banco", "pagamento", "cartão", "saldo", "transferência",
        "bank", "zahlung", "karte", "konto", "überweisung"
    )

    // E-commerce / courier keywords, multilingual.
    private val ECOMMERCE_KEYWORDS = listOf(
        // zh-CN
        "京东", "顺丰", "淘宝", "天猫", "拼多多", "快递", "包裹", "订单",
        "发货", "物流", "驿站", "取件", "派送", "签收",
        // en
        "order", "package", "parcel", "shipment", "shipping", "delivery",
        "courier", "tracking", "dispatched", "delivered", "arrived", "locker",
        "pickup", "out for delivery", "amazon", "ebay", "aliexpress", "shopee",
        "jd.com", "taobao", "tmall",
        // ja
        "注文", "発送", "配送", "荷物", "お届け", "配達",
        // ko
        "주문", "배송", "택배", "배달", "도착",
        // ru
        "заказ", "доставка", "посылка", "отправление", "курьер",
        // es/fr/it/pt/de
        "pedido", "envío", "paquete", "entrega",
        "commande", "livraison", "colis",
        "ordine", "spedizione", "pacco", "consegna",
        "pedido", "envio", "pacote", "entrega",
        "bestellung", "lieferung", "paket"
    )

    // Service / government / travel keywords, multilingual.
    private val SERVICE_KEYWORDS = listOf(
        // zh-CN
        "12306", "铁路", "航空", "航班", "预约", "挂号", "就诊", "社保",
        "税务", "政务", "app验证", "登录提醒", "安全提醒",
        // en
        "appointment", "reservation", "boarding", "flight", "railway", "ticket",
        "government", "tax", "social security", "login", "sign[ -]in",
        "security alert", "verification code", "two[ -]factor",
        // ja
        "予約", "航空", "搭乗", "切符", "政府", "税",
        // ko
        "예약", "항공", "탑승", "정부", "세금", "로그인",
        // ru
        "бронирование", "рейс", "посадка", "правительство", "налог", "вход",
        // es/fr/it/pt/de
        "cita", "reserva", "vuelo", "gobierno", "impuesto", "acceso",
        "rendez-vous", "réservation", "vol", "gouvernement", "impôt", "connexion",
        "appuntamento", "prenotazione", "volo", "governo", "tasse", "accesso",
        "consulta", "reserva", "voo", "governo", "imposto", "login",
        "termin", "buchung", "flug", "regierung", "steuer", "anmeldung"
    )

    // Carrier keywords, multilingual.
    private val CARRIER_KEYWORDS = listOf(
        // zh-CN
        "中国移动", "中国联通", "中国电信", "运营商", "流量", "话费", "套餐", "5g",
        // en
        "carrier", "network", "data plan", "top[ -]up", "recharge", "prepaid",
        "billing", "sim card", "5g", "4g",
        // ja
        "キャリア", "通信", "データ", "チャージ", "料金",
        // ko
        "통신사", "데이터", "요금", "충전",
        // ru
        "оператор", "тариф", "баланс", "пополнение",
        // es/fr/it/pt/de
        "operador", "tarifa", "recarga", "saldo",
        "opérateur", "forfait", "recharge", "solde",
        "operatore", "tariffa", "ricarica", "saldo",
        "operadora", "tarifa", "recarga", "saldo",
        "anbieter", "tarif", "aufladen", "guthaben"
    )

    // Tracking-number formats used by major couriers (deliveries-style):
    // EMS (2 letters + 9 digits + 2 letters), 4PX, DHL, UPS, InPost, 顺丰…
    private val TRACKING_PATTERNS = listOf(
        Regex("\\b[A-Z]{2}\\d{9}[A-Z]{2}\\b"),      // EMS / international UPU
        Regex("\\b4PX[A-Z0-9]{16}\\b"),             // 4PX
        Regex("\\b(?:JJD|JVGL|3S|JV|JD)\\d+\\b"),   // DHL eCommerce
        Regex("\\b1Z[A-Z0-9]{16}\\b"),              // UPS
        Regex("\\bSF\\d{12,}\\b"),                  // SF Express
        Regex("\\bJD[A-Z0-9]{10,}\\b"),             // JD Logistics
        Regex("\\bYT\\d{12,}\\b"),                  // YTO Express
        Regex("\\bZT\\d{12,}\\b"),                  // ZTO Express
        Regex("\\b\\d{24}\\b"),                     // InPost locker
        Regex("\\b[A-Z]{2}\\d{9}IE\\b", setOf(RegexOption.IGNORE_CASE)) // An Post
    )

    // Keyword tables pre-compiled once. The previous code built a new
    // Regex per keyword per message inside classify()/extractCode() —
    // with ~150 keywords that was the dominant startup cost once the
    // inbox grows (hundreds of Regex constructions per message).
    private val CODE_PATTERNS: List<Regex> by lazy {
        CODE_KEYWORDS.map { Regex(it, RegexOption.IGNORE_CASE) }
    }
    private val AD_PATTERNS: List<Regex> by lazy {
        AD_KEYWORDS.map { Regex(it, RegexOption.IGNORE_CASE) }
    }
    private val BANK_PATTERNS: List<Regex> by lazy {
        BANK_KEYWORDS.map { Regex(it, RegexOption.IGNORE_CASE) }
    }
    private val ECOMMERCE_PATTERNS: List<Regex> by lazy {
        ECOMMERCE_KEYWORDS.map { Regex(it, RegexOption.IGNORE_CASE) }
    }
    private val SERVICE_PATTERNS: List<Regex> by lazy {
        SERVICE_KEYWORDS.map { Regex(it, RegexOption.IGNORE_CASE) }
    }
    private val CARRIER_PATTERNS: List<Regex> by lazy {
        CARRIER_KEYWORDS.map { Regex(it, RegexOption.IGNORE_CASE) }
    }

    /**
     * Extract the verification code. Keyword matching runs on the
     * space-preserving text (English \b word boundaries need the spaces);
     * digit extraction runs on the space-stripped text so "123 456" style
     * codes work. Non-ASCII digits are normalized first.
     */
    fun extractCode(body: String): String? {
        val spaced = normalizeDigits(body)
        // Space-stripped AND dash-stripped text for digit extraction:
        // "123 456" and "123-456" both collapse to "123456" (otphelper-style).
        val text = normalizeDigits(body.replace(" ", "").replace("-", ""))
        // Express pickup messages carry the cabinet number as 凭X-X-XXXX or
        // 取货码X-X-XXXX / pickup code X-X-XXXX and say 驿站/取件 — the
        // literal "取件码" never appears.
        if (spaced.contains("驿站") || spaced.contains("取运单") || spaced.contains("取货码") ||
            spaced.contains("locker") || spaced.contains("pickup code") || spaced.contains("collection code")
        ) {
            EXPRESS_CODE_PATTERN.find(spaced)?.let { return it.groupValues[1] }
        }
        if (CODE_PATTERNS.none { it.containsMatchIn(spaced) }) return null
        val matches = CODE_PATTERN.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return null
        // Prefer the sequence closest to the first keyword occurrence.
        val keywordIndex = CODE_PATTERNS
            .mapNotNull { it.find(spaced)?.range?.first }
            .minOrNull() ?: return matches.first()
        return matches.minByOrNull { kotlin.math.abs(text.indexOf(it) - keywordIndex) }
    }

    /**
     * All codes in one message. Express pickup SMS can carry several
     * cabinet numbers for several parcels:
     * 【多多代收点】…取货码1-3-9448、5-4-3216 → [1-3-9448, 5-4-3216]
     */
    fun extractAllCodes(body: String): List<String> {
        val spaced = normalizeDigits(body)
        if (spaced.contains("取货码") || spaced.contains("pickup code") ||
            spaced.contains("locker code") || spaced.contains("collection code")
        ) {
            val from = spaced.indexOf("取货码").takeIf { it >= 0 }
                ?: spaced.indexOf("pickup code").takeIf { it >= 0 }
                ?: spaced.indexOf("locker code").takeIf { it >= 0 }
                ?: spaced.indexOf("collection code").takeIf { it >= 0 }
                ?: 0
            val all = Regex("[\\d]+-[\\d]+-[\\d]+").findAll(spaced.substring(from))
                .map { it.value }.toList()
            if (all.isNotEmpty()) return all
        }
        return extractCode(body)?.let { listOf(it) } ?: emptyList()
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

    /** Smart card subtitle: true for express pickup messages. */
    fun isPickupCode(body: String): Boolean {
        val spaced = normalizeDigits(body)
        val lower = spaced.lowercase()
        return spaced.contains("取件码") || spaced.contains("取货码") ||
            (spaced.contains("驿站") && EXPRESS_CODE_PATTERN.containsMatchIn(spaced)) ||
            ((lower.contains("pickup code") || lower.contains("locker code") ||
                lower.contains("collection code")) && EXPRESS_CODE_PATTERN.containsMatchIn(spaced))
    }

    /** Any courier tracking number present? (deliveries-style formats) */
    fun hasTrackingNumber(body: String): Boolean =
        TRACKING_PATTERNS.any { it.containsMatchIn(body) }

    fun classify(body: String, hasContact: Boolean, archived: Boolean): MessageCategory {
        if (archived) return MessageCategory.ARCHIVED
        // Express pickup messages (凭/取货码 X-X-X at 驿站) belong to 包裹,
        // NOT 验证码 — keep the two filters clean.
        if (isPickupCode(body)) return MessageCategory.PACKAGE
        if (extractCode(body) != null) return MessageCategory.CODE
        if (hasTrackingNumber(body)) return MessageCategory.ECOMMERCE
        if (AD_PATTERNS.any { it.containsMatchIn(body) }) return MessageCategory.AD
        if (BANK_PATTERNS.any { it.containsMatchIn(body) }) return MessageCategory.BANK
        if (ECOMMERCE_PATTERNS.any { it.containsMatchIn(body) }) return MessageCategory.ECOMMERCE
        if (SERVICE_PATTERNS.any { it.containsMatchIn(body) }) return MessageCategory.SERVICE
        if (CARRIER_PATTERNS.any { it.containsMatchIn(body) }) return MessageCategory.CARRIER
        return if (hasContact) MessageCategory.PERSON else MessageCategory.OTHER
    }

    /**
     * Normalize non-ASCII digits to ASCII 0-9: Arabic-Indic (٠-٩),
     * Persian (۰-۹), full-width (０-９). Keeps Arabic letters intact.
     */
    fun normalizeDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when {
                    ch in '\u0660'..'\u0669' -> ('0' + (ch - '\u0660'))
                    ch in '\u06F0'..'\u06F9' -> ('0' + (ch - '\u06F0'))
                    ch in '\uFF10'..'\uFF19' -> ('0' + (ch - '\uFF10'))
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
}

/**
 * The code/express-entry pipeline shared by the banner and the 验证码/包裹
 * filter lists: keep messages of the wanted categories, split each message
 * into one entry per code (express SMS can carry several cabinet numbers),
 * newest first.
 */
fun codeEntries(messages: List<SmsMessage>, categories: Set<MessageCategory>): List<SmsMessage> =
    messages.filter { it.category in categories }
        .flatMap { msg ->
            SmsParser.extractAllCodes(msg.body).map { code -> msg.copy(code = code) }
        }
        .sortedByDescending { it.date }
