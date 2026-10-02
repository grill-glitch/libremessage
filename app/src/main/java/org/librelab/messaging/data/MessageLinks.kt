package org.librelab.messaging.data

/**
 * Finds the tappable entities inside a message body: web links, phone
 * numbers, and verification / pickup codes. Pure logic, no Android
 * dependencies — trivially unit-testable.
 *
 * URLs are scanned first and the characters they occupy are excluded from
 * every later rule, so a number inside a link path never becomes a dialer
 * target. Phone detection is deliberately conservative (11-digit mainland
 * mobile, 0-prefixed landline with a separator, explicit `+` international)
 * so verification codes — the app's own core feature — never turn into
 * dialable text.
 *
 * Codes are the opposite case: [SmsParser] already decides which digits a
 * message is about, for the code list, the notification and the category
 * filter. That verdict is reused verbatim here (never re-derived) and the
 * digits become copy targets — tap the number in the bubble and it is in the
 * clipboard. A tap must copy, not dial: a code and a hotline are the same
 * shape, so the two rules are separated by *which* runs the parser claimed.
 */
object MessageLinks {

    enum class Kind { URL, PHONE, CODE }

    /**
     * A tappable range `[start, end)` in the body, the text it covers, and
     * the payload a tap acts on. [value] differs from [text] only for
     * [Kind.CODE]: the parser reads codes off a space/dash-stripped copy, so
     * `"123 456"` is a span whose value is `123456`.
     */
    data class Link(
        val start: Int,
        val end: Int,
        val text: String,
        val kind: Kind,
        val value: String = text
    )

    private val SCHEME_HOST = """[^\s<>"“”‘’'（）()【】\[\]{}，。、；：！？]+"""

    private val URL_PATTERNS = listOf(
        // Explicit scheme: http://, https://, ftp:// …
        Regex("""(?i)\b[a-z][a-z0-9+.\-]*://$SCHEME_HOST"""),
        // www. host, with optional port / path / query / fragment
        Regex("""(?i)\bwww\.[a-z0-9][a-z0-9\-.]*\.[a-z]{2,}(?::\d+)?(?:[/?#]$SCHEME_HOST)?"""),
        // Bare domain with a known TLD (abc.com/path, taobao.cn …)
        Regex(
            "(?i)\\b(?:[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?\\.)+" +
                "(?:com|cn|net|org|io|me|top|xyz|info|biz|tv|cc|co|app|dev|shop|club|vip|" +
                "site|online|link|ltd|gov|edu|ai|us|uk|de|jp|fr|ru|hk|tw|mo|pro|live|" +
                "store|tech|work|wang|xin|ren)" +
                "(?::\\d+)?(?:[/?#]$SCHEME_HOST)?"
        )
    )

    // ---- number shapes -------------------------------------------------
    //
    // One source of truth for "what a phone number looks like", consumed by
    // two callers with two deliberately different thresholds:
    //
    //   * [find] scans free message text. There, a bare 4-8 digit run is
    //     usually a verification code — the app's own core feature — so the
    //     unambiguous shapes below qualify, and 5-6 digit service codes only
    //     qualify when [SmsParser] does not read them as this message's code.
    //   * [isPhoneNumber] validates a whole sender address. An address is
    //     either a number or an alphanumeric sender id (`Baidu`), so the
    //     code ambiguity does not exist and plain 5-15 digit service codes
    //     (10086, 95588) are accepted too.
    //
    // The shared [normalizePhone] keeps both readings of one number in sync;
    // `everyBodyNumberIsAlsoAValidSenderAddress` in the tests pins the
    // containment so the two cannot drift apart.

    private val PHONE_PATTERNS = listOf(
        // Mainland mobile: 1[3-9]xxxxxxxxx, optional +86/0086 prefix and
        // space/dash grouping (138 0013 8000).
        Regex("""(?<!\d)(?:(?:\+|00)86[\s\-]?)?1[3-9]\d[\s\-]?\d{4}[\s\-]?\d{4}(?!\d)"""),
        // Landline: 0 + 2-3 digit area code + separator + 7-8 digits.
        Regex("""(?<!\d)0\d{2,3}[\s\-]\d{7,8}(?!\d)"""),
        // International with an explicit + prefix, 8-15 digits total.
        Regex("""(?<!\d)\+\d(?:[\s\-()]?\d){7,14}(?!\d)""")
    )

    /**
     * Service short codes: 12345 (市民热线), 10086, 95588 — a plain digit run
     * of [SHORT_CODE_MIN]..[SHORT_CODE_MAX] digits. Shape alone cannot
     * separate these from a 6-digit verification code (this inbox holds
     * 982623 and 190836, both codes shaped like hotlines), so [find] keeps a
     * short run only when [SmsParser] does not read it as this message's
     * code. That is a deliberate reuse of the code parser rather than a
     * second, competing opinion about digits.
     *
     * The window must stay inside
     * [SmsParser.CODE_RUN_MIN]..[SmsParser.CODE_RUN_MAX]: outside it the
     * parser could not report the run, the exemption could never fire, and a
     * code-shaped hotline would become dialable inside a code message.
     * `shortCodeRangeStaysInsideTheCodeRunRange` guards that.
     */
    const val SHORT_CODE_MIN = 5
    const val SHORT_CODE_MAX = 6

    private val SHORT_CODE_PATTERN =
        Regex("""(?<!\d)\d{$SHORT_CODE_MIN,$SHORT_CODE_MAX}(?!\d)""")

    /** Punctuation that ends a sentence rather than the URL itself. */
    private val URL_TRAILING =
        ".!,;:?。，、；：！？）】”’\"'…".toSet()

    private val MIN_URL_LEN = 4

    private val SCHEME_PREFIX = Regex("""(?i)^[a-z][a-z0-9+.\-]*://""")

    /**
     * The one relaxation the address check adds on top of [PHONE_PATTERNS]:
     * a whole-token digit run, optional `+`, 5-15 digits. It exists only
     * because an address cannot be a verification code.
     */
    private val ADDRESS_NUMBER = Regex("""^\+?\d{5,15}$""")

    private val PHONE_SEPARATORS =
        " \t\u00A0-‐‑‒–—()（）".toSet()

    /** All links and phone numbers in [body], ordered by position. */
    fun find(body: String): List<Link> {
        if (body.isEmpty()) return emptyList()
        val found = ArrayList<Link>()
        val taken = BooleanArray(body.length)
        for (pattern in URL_PATTERNS) {
            for (m in pattern.findAll(body)) {
                var start = m.range.first
                var end = m.range.last + 1
                // Trim sentence punctuation that the host/path pattern ate.
                while (end > start && body[end - 1] in URL_TRAILING) end--
                if (end - start < MIN_URL_LEN) continue
                if (overlaps(taken, start, end)) continue
                found.add(Link(start, end, body.substring(start, end), Kind.URL))
                mark(taken, start, end)
            }
        }
        for (pattern in PHONE_PATTERNS) {
            for (m in pattern.findAll(body)) {
                val start = m.range.first
                val end = m.range.last + 1
                if (overlaps(taken, start, end)) continue
                found.add(Link(start, end, body.substring(start, end), Kind.PHONE))
                mark(taken, start, end)
            }
        }
        // Codes before short service codes: both live in the same shape
        // space, so the parser's verdict is what separates "tap to copy"
        // from "tap to call". Claiming the ranges here is also what keeps
        // the rule below from offering a dialer for a verification code.
        val codeValues = SmsParser.extractAllCodes(body)
        for (code in codeValues) {
            for (span in codeSpans(body, code)) {
                if (overlaps(taken, span.first, span.last + 1)) continue
                val text = body.substring(span.first, span.last + 1)
                found.add(Link(span.first, span.last + 1, text, Kind.CODE, code))
                mark(taken, span.first, span.last + 1)
            }
        }
        // Short service codes last, because they only exist in the same shape
        // space as verification codes. Every run the parser did not claim as
        // this message's code is a hotline the user can tap; the ones it did
        // claim are already copy targets above.
        val shortRuns = SHORT_CODE_PATTERN.findAll(body).toList()
        for (m in shortRuns) {
            val start = m.range.first
            val end = m.range.last + 1
            if (m.value in codeValues) continue
            if (overlaps(taken, start, end)) continue
            found.add(Link(start, end, m.value, Kind.PHONE))
            mark(taken, start, end)
        }
        return found.sortedBy { it.start }
    }

    /**
     * Every occurrence of [code] in [body]. The parser extracts codes from a
     * space/dash-stripped copy — `"123 456"` comes back as `123456`, a
     * pickup code keeps its dashes — so the search allows one separator
     * between digits instead of requiring a verbatim hit. The digit
     * lookarounds stop a code from being marked inside a longer number
     * (an order id that happens to contain the same digits).
     */
    private fun codeSpans(body: String, code: String): List<IntRange> {
        if (code.isEmpty()) return emptyList()
        val pattern = Regex(
            "(?<!\\d)" +
                code.map { Regex.escape(it.toString()) }.joinToString("[\\s\\-]?") +
                "(?!\\d)"
        )
        return pattern.findAll(body).map { it.range }.toList()
    }

    /** Clickable URLs only. */
    fun urls(body: String): List<Link> = find(body).filter { it.kind == Kind.URL }

    /** Clickable phone numbers only. */
    fun phones(body: String): List<Link> = find(body).filter { it.kind == Kind.PHONE }

    /** Copyable verification / pickup codes only. */
    fun codes(body: String): List<Link> = find(body).filter { it.kind == Kind.CODE }

    /** First web link in [body], or null. */
    fun firstUrl(body: String): String? =
        find(body).firstOrNull { it.kind == Kind.URL }?.text

    /** First phone number in [body], or null. */
    fun firstPhone(body: String): String? =
        find(body).firstOrNull { it.kind == Kind.PHONE }?.text

    /**
     * Make a detected link openable: `www.abc.com` and bare domains get an
     * `http://` prefix, explicit schemes are kept untouched.
     */
    fun toUrl(text: String): String =
        if (SCHEME_PREFIX.containsMatchIn(text)) text else "http://$text"

    /**
     * Strip formatting so a detected number can be handed to the dialer or
     * the SMS stack: keeps digits and a leading `+`, drops spaces, dashes and
     * brackets.
     */
    fun normalizePhone(text: String): String {
        val trimmed = text.trim()
        val sb = StringBuilder(trimmed.length)
        trimmed.forEachIndexed { i, ch ->
            when {
                ch.isDigit() -> sb.append(ch)
                (ch == '+' || ch == '＋') && i == 0 -> sb.append('+')
                ch in PHONE_SEPARATORS -> Unit
                ch == '＋' -> Unit
            }
        }
        return sb.toString()
    }

    /**
     * Whole-token check for a sender address, used by the notification
     * actions. Accepts the number shapes [find] recognises, plus the plain
     * digit runs a body must leave alone (short service codes such as 10086
     * or 95588, unseparated numbers). Alphanumeric sender ids ("Alipay")
     * return false.
     *
     * Strict shapes are checked first so tightening [ADDRESS_NUMBER] later
     * can never reject a number the body scanner already treats as real.
     */
    fun isPhoneNumber(text: String): Boolean {
        val n = normalizePhone(text)
        if (n.isEmpty()) return false
        if (find(n).any { it.kind == Kind.PHONE && it.start == 0 && it.end == n.length }) {
            return true
        }
        return ADDRESS_NUMBER.matches(n)
    }

    private fun overlaps(taken: BooleanArray, start: Int, end: Int): Boolean {
        for (i in start until end) if (taken[i]) return true
        return false
    }

    private fun mark(taken: BooleanArray, start: Int, end: Int) {
        for (i in start until end) taken[i] = true
    }
}
