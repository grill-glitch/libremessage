package org.librelab.messaging.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the link / phone-number detector behind the clickable message
 * bubbles and the notification actions. Pure logic, no Android deps.
 */
class MessageLinksTest {

    private fun texts(body: String, kind: MessageLinks.Kind) =
        MessageLinks.find(body).filter { it.kind == kind }.map { it.text }

    // ---- URLs ----

    @Test
    fun detectsExplicitScheme() {
        val body = "详情请见 https://example.com/a?b=1 ，谢谢"
        assertEquals(listOf("https://example.com/a?b=1"), texts(body, MessageLinks.Kind.URL))
    }

    @Test
    fun detectsWwwHost() {
        assertEquals(listOf("www.abc.com"), texts("点击 www.abc.com 领取", MessageLinks.Kind.URL))
    }

    @Test
    fun detectsBareDomainWithPath() {
        assertEquals(
            listOf("taobao.cn/item/1"),
            texts("优惠券 taobao.cn/item/1 限时", MessageLinks.Kind.URL)
        )
    }

    @Test
    fun trimsTrailingSentencePunctuation() {
        assertEquals(listOf("https://a.com"), texts("看 https://a.com。", MessageLinks.Kind.URL))
        assertEquals(listOf("https://a.com/x"), texts("看 https://a.com/x, 好吗", MessageLinks.Kind.URL))
    }

    @Test
    fun ignoresVersionLikeTokens() {
        assertTrue(texts("版本 1.3.6 已发布", MessageLinks.Kind.URL).isEmpty())
    }

    @Test
    fun toUrlAddsSchemeOnlyWhenMissing() {
        assertEquals("http://www.abc.com", MessageLinks.toUrl("www.abc.com"))
        assertEquals("http://abc.com/x", MessageLinks.toUrl("abc.com/x"))
        assertEquals("https://abc.com/x", MessageLinks.toUrl("https://abc.com/x"))
    }

    // ---- phone numbers ----

    @Test
    fun detectsMainlandMobile() {
        assertEquals(listOf("13800138000"), texts("回电 13800138000", MessageLinks.Kind.PHONE))
    }

    @Test
    fun detectsGroupedAndPrefixedMobile() {
        assertEquals(
            listOf("138 0013 8000"),
            texts("电话 138 0013 8000", MessageLinks.Kind.PHONE)
        )
        assertEquals(
            listOf("+8613800138000"),
            texts("联系 +8613800138000", MessageLinks.Kind.PHONE)
        )
        assertEquals(
            listOf("+86 138-0013-8000"),
            texts("联系 +86 138-0013-8000", MessageLinks.Kind.PHONE)
        )
    }

    @Test
    fun detectsLandlineWithSeparator() {
        assertEquals(listOf("0571-88888888"), texts("客服 0571-88888888", MessageLinks.Kind.PHONE))
        assertEquals(listOf("010 12345678"), texts("电话 010 12345678", MessageLinks.Kind.PHONE))
    }

    @Test
    fun detectsInternationalWithPlus() {
        assertEquals(
            listOf("+1 555 123 4567"),
            texts("call +1 555 123 4567 now", MessageLinks.Kind.PHONE)
        )
    }

    /** The core feature must not regress: codes stay codes, not dialable text. */
    @Test
    fun verificationCodesAreNotPhoneNumbers() {
        assertTrue(texts("您的验证码是123456，5分钟内有效", MessageLinks.Kind.PHONE).isEmpty())
        assertTrue(texts("验证码 1234", MessageLinks.Kind.PHONE).isEmpty())
        assertTrue(texts("取货码 1-3-9448", MessageLinks.Kind.PHONE).isEmpty())
        assertTrue(texts("订单号 1380013800012345", MessageLinks.Kind.PHONE).isEmpty())
        // Real 6-digit codes shaped exactly like hotlines (this inbox's).
        assertTrue(texts("【支付宝】登录验证码：982623。请勿泄露", MessageLinks.Kind.PHONE).isEmpty())
        assertTrue(texts("【哔哩哔哩】验证码190836，5分钟内有效", MessageLinks.Kind.PHONE).isEmpty())
        // 5-digit code text, the trickiest case: same shape as 12345.
        assertTrue(texts("您的验证码95588，请勿告诉他人", MessageLinks.Kind.PHONE).isEmpty())
    }

    // ---- codes (tap in the bubble = copy) ----

    /**
     * A code in a bubble is a copy target, and the span covers exactly the
     * digits the parser reported — that is what the chat page underlines and
     * what lands in the clipboard.
     */
    @Test
    fun verificationCodeDigitsAreCopyTargets() {
        val body = "【支付宝】登录验证码：982623。支付宝全力保护你的账户安全，验证码请勿泄露给他人。"
        val code = MessageLinks.codes(body).single()
        assertEquals("982623", code.value)
        assertEquals("982623", body.substring(code.start, code.end))
        // …and it must NOT be offered as something to dial.
        assertTrue(texts(body, MessageLinks.Kind.PHONE).isEmpty())
    }

    /** Pickup codes keep their dashes: 取货码1-3-9448 copies as 1-3-9448. */
    @Test
    fun pickupCodeCopiesWithItsDashes() {
        val body = "【多多代收点】您有2个包裹在采荷百合路11号驿站,取货码1-3-9448。"
        val code = MessageLinks.codes(body).single()
        assertEquals("1-3-9448", code.value)
        assertEquals("1-3-9448", body.substring(code.start, code.end))
    }

    /** Several parcels, several gaps: every pickup code is copyable. */
    @Test
    fun everyPickupCodeInOneMessageIsCopyable() {
        val body = "【多多代收点】取货码1-3-9448、5-4-3216"
        assertEquals(listOf("1-3-9448", "5-4-3216"), MessageLinks.codes(body).map { it.value })
    }

    /**
     * The parser reads codes off a space-stripped copy, so a spaced code
     * appears in the body as it was written while copying as one number.
     */
    @Test
    fun spacedCodeSpanCoversTheWrittenDigitsButCopiesStripped() {
        val body = "您的验证码是 123 456 ，5分钟内有效"
        val code = MessageLinks.codes(body).single()
        assertEquals("123 456", body.substring(code.start, code.end))
        assertEquals("123456", code.value)
        assertEquals("123456", SmsParser.extractCode(body))
    }

    /** Same digits as an order id elsewhere: only the real code is marked. */
    @Test
    fun codeIsNotMarkedInsideALongerNumber() {
        val body = "验证码123456，订单号999123456999"
        val code = MessageLinks.codes(body).single()
        assertEquals(3, code.start)
        assertEquals("123456", code.value)
    }

    /** Code wins over hotline when a message carries both shapes. */
    @Test
    fun codeAndHotlineInOneMessageStayDifferentKinds() {
        val body = "【某某银行】验证码982623，客服热线95588。"
        assertEquals(listOf("982623"), MessageLinks.codes(body).map { it.value })
        assertEquals(listOf("95588"), texts(body, MessageLinks.Kind.PHONE))
    }

    /** Bodies with no code keyword yield no copy targets at all. */
    @Test
    fun noCodesWithoutACodeMessage() {
        assertTrue(MessageLinks.codes("请拨打10086办理").isEmpty())
        assertTrue(MessageLinks.codes("这是一条短信。").isEmpty())
    }

    /**
     * Short service codes ARE recognised in a body — 12345 (市民热线), 10086,
     * 95588 — because shape alone cannot separate them from codes, the
     * verification-code parser decides which run a message is about.
     */
    @Test
    fun shortServiceCodesInBodyAreRecognised() {
        assertEquals(listOf("10086"), texts("请拨打10086办理", MessageLinks.Kind.PHONE))
        assertEquals(listOf("12345"), texts("客服电话12345", MessageLinks.Kind.PHONE))
        assertEquals(listOf("95588"), texts("银行热线95588", MessageLinks.Kind.PHONE))
        assertEquals(listOf("12345", "12315"), texts("市民热线12345、12315", MessageLinks.Kind.PHONE))
        // Prefixed with the hotline wording or not — both count.
        assertEquals(listOf("12306"), texts("12306 购票", MessageLinks.Kind.PHONE))
    }

    /**
     * As a *sender address* there is no code ambiguity, so 5-15 digit
     * short codes qualify — the notification offers call / reply for them.
     */
    @Test
    fun shortServiceCodesAsSenderAreActionable() {
        assertTrue(MessageLinks.isPhoneNumber("12345"))
        assertTrue(MessageLinks.isPhoneNumber("10086"))
        assertTrue(MessageLinks.isPhoneNumber("95588"))
        assertFalse(MessageLinks.isPhoneNumber("1069"))
        assertFalse(MessageLinks.isPhoneNumber("Baidu"))
    }

    /**
     * The two thresholds must stay related, not independent: everything the
     * body scanner treats as a number has to be a valid sender address too.
     * If a new shape is added to the body patterns and the address check does
     * not follow, this fails instead of silently dropping notification
     * buttons.
     */
    @Test
    fun everyBodyNumberIsAlsoAValidSenderAddress() {
        val bodies = listOf(
            "回电 13800138000",
            "联系 +8613800138000",
            "电话 138 0013 8000",
            "客服 0571-88888888",
            "call +1 555 123 4567",
            "官网 www.abc.com 热线 0571-88888888"
        )
        val numbers = bodies.flatMap { texts(it, MessageLinks.Kind.PHONE) }
        assertEquals(6, numbers.size)
        numbers.forEach { number ->
            assertTrue("body number '$number' must pass the address check",
                MessageLinks.isPhoneNumber(number))
        }
    }

    /**
     * Containment guard: [SmsParser] owns "what a bare digit run is"
     * (4-8 digits). The short-code window has to sit inside it, otherwise a
     * run could be linkified that the exemption is structurally unable to
     * protect — i.e. a code would become dialable inside a code message.
     */
    @Test
    fun shortCodeRangeStaysInsideTheCodeRunRange() {
        assertTrue(MessageLinks.SHORT_CODE_MIN >= SmsParser.CODE_RUN_MIN)
        assertTrue(MessageLinks.SHORT_CODE_MAX <= SmsParser.CODE_RUN_MAX)
    }

    /** A 7-digit run is outside the hotline window and stays plain text. */
    @Test
    fun sevenDigitRunIsNotAShortCode() {
        assertTrue(texts("单号1234567", MessageLinks.Kind.PHONE).isEmpty())
    }

    /** The address relaxation is exactly: 5-15 digit runs, nothing more. */
    @Test
    fun addressRelaxationIsOnlyTheShortCodeCase() {
        assertTrue(MessageLinks.isPhoneNumber("12345"))
        assertTrue(MessageLinks.isPhoneNumber("10086"))
        assertFalse(MessageLinks.isPhoneNumber("1069"))   // too short
        assertFalse(MessageLinks.isPhoneNumber("1234567890123456")) // too long
        assertFalse(MessageLinks.isPhoneNumber("Baidu"))
    }

    // ---- mixed / precedence ----

    @Test
    fun phoneInsideUrlIsNotDialable() {
        val body = "见 https://example.com/tel/13800138000 谢谢"
        assertEquals(1, texts(body, MessageLinks.Kind.URL).size)
        assertTrue(texts(body, MessageLinks.Kind.PHONE).isEmpty())
    }

    @Test
    fun findsBothKindsOrderedByPosition() {
        val body = "官网 www.abc.com，客服电话 0571-88888888"
        val all = MessageLinks.find(body)
        assertEquals(2, all.size)
        assertEquals(MessageLinks.Kind.URL, all[0].kind)
        assertEquals(MessageLinks.Kind.PHONE, all[1].kind)
        assertTrue(all[0].start < all[1].start)
        // Ranges must address the original body so spans line up.
        assertEquals(all[0].text, body.substring(all[0].start, all[0].end))
        assertEquals(all[1].text, body.substring(all[1].start, all[1].end))
    }

    @Test
    fun firstUrlAndFirstPhone() {
        val body = "链接 https://a.com 电话 13800138000"
        assertEquals("https://a.com", MessageLinks.firstUrl(body))
        assertEquals("13800138000", MessageLinks.firstPhone(body))
        assertNull(MessageLinks.firstUrl("no links here"))
        assertNull(MessageLinks.firstPhone("no numbers"))
    }

    // ---- sender-address validation (notification actions) ----

    @Test
    fun senderIdPhoneValidation() {
        assertTrue(MessageLinks.isPhoneNumber("+8613800138000"))
        assertTrue(MessageLinks.isPhoneNumber("13800138000"))
        assertTrue(MessageLinks.isPhoneNumber("10086"))
        assertTrue(MessageLinks.isPhoneNumber("0571-88888888"))
        assertFalse(MessageLinks.isPhoneNumber("Alipay"))
        assertFalse(MessageLinks.isPhoneNumber("1234"))
        assertFalse(MessageLinks.isPhoneNumber(""))
    }

    @Test
    fun normalizesDialableNumber() {
        assertEquals("+8613800138000", MessageLinks.normalizePhone("+86 138-0013-8000"))
        assertEquals("057188888888", MessageLinks.normalizePhone("0571-8888 8888"))
        assertEquals("13800138000", MessageLinks.normalizePhone("138 0013 8000"))
    }
}
