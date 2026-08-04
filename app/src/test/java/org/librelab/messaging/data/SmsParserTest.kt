package org.librelab.messaging.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the multilingual SmsParser. Pure logic, no Android deps.
 */
class SmsParserTest {

    // ---- verification code extraction, zh-CN ----
    @Test
    fun extractZhCode() {
        assertEquals("123456", SmsParser.extractCode("【某某银行】您的验证码是123456，5分钟内有效。"))
        assertEquals("654321", SmsParser.extractCode("验证码 654321，请勿泄露"))
        assertEquals("123456", SmsParser.extractCode("您的校验码为 123456。"))
    }

    // ---- verification code extraction, en ----
    @Test
    fun extractEnCode() {
        assertEquals("482913", SmsParser.extractCode("Your code is 482913. Do not share it."))
        assertEquals("482913", SmsParser.extractCode("Your verification code is 482913."))
        assertEquals("482913", SmsParser.extractCode("OTP: 482913"))
        assertEquals("482913", SmsParser.extractCode("One Time Password 482913"))
        assertEquals("482913", SmsParser.extractCode("Your PIN is 482913"))
    }

    // ---- ja / ko / ru ----
    @Test
    fun extractJaKoRuCode() {
        assertEquals("482913", SmsParser.extractCode("認証番号は482913です"))
        assertEquals("482913", SmsParser.extractCode("인증번호 482913"))
        assertEquals("482913", SmsParser.extractCode("Ваш код подтверждения: 482913"))
    }

    // ---- Arabic-Indic / Persian / full-width digits ----
    @Test
    fun normalizeNonAsciiDigits() {
        assertEquals("123456", SmsParser.extractCode("رمز التحقق ١٢٣٤٥٦"))
        assertEquals("123456", SmsParser.extractCode("کد تایید ۱۲۳۴۵۶"))
        assertEquals("123456", SmsParser.extractCode("验证码 １２３４５６"))
    }

    // ---- codes with spaces / dashes ----
    @Test
    fun extractSpacedCode() {
        assertEquals("123456", SmsParser.extractCode("验证码 123 456"))
        assertEquals("123456", SmsParser.extractCode("Your code is 123-456"))
    }

    // ---- no keyword → no code (phone numbers must not match) ----
    @Test
    fun noKeywordNoCode() {
        assertNull(SmsParser.extractCode("13800138000 给你打电话"))
        assertNull(SmsParser.extractCode("快递员 13800138000 联系您"))
        assertNull(SmsParser.extractCode("2026年8月4日 天气晴"))
    }

    // ---- express pickup codes ----
    @Test
    fun extractExpressCodeZh() {
        val body = "【韵达快递】凭15-2-1300到采荷百合路11号驿站取运单尾号1300包裹"
        assertEquals("15-2-1300", SmsParser.extractCode(body))
        assertTrue(SmsParser.isPickupCode(body))
        assertEquals(MessageCategory.PACKAGE, SmsParser.classify(body, hasContact = false, archived = false))
    }

    @Test
    fun extractExpressCodeEn() {
        val body = "Your parcel arrived at the locker. Pickup code: 5-4-3216"
        assertEquals("5-4-3216", SmsParser.extractCode(body))
        assertTrue(SmsParser.isPickupCode(body))
        assertEquals(MessageCategory.PACKAGE, SmsParser.classify(body, hasContact = false, archived = false))
    }

    @Test
    fun extractMultipleExpressCodes() {
        val body = "【多多代收点】您有2个包裹在采荷百合路11号驿站,取货码1-3-9448、5-4-3216"
        assertEquals(
            listOf("1-3-9448", "5-4-3216"),
            SmsParser.extractAllCodes(body)
        )
    }

    // ---- classification ----
    @Test
    fun classifyCategories() {
        assertEquals(MessageCategory.CODE, SmsParser.classify("【淘宝】验证码123456", false, false))
        assertEquals(MessageCategory.AD, SmsParser.classify("退订回复TD，限时优惠大促", false, false))
        assertEquals(
            MessageCategory.AD,
            SmsParser.classify("Exclusive offer! Unsubscribe anytime. Limited time sale.", false, false)
        )
        assertEquals(MessageCategory.BANK, SmsParser.classify("您的信用卡消费128元", false, false))
        assertEquals(
            MessageCategory.BANK,
            SmsParser.classify("Your card ending 1234 was charged $99.99", false, false)
        )
        assertEquals(MessageCategory.ECOMMERCE, SmsParser.classify("您的京东订单已发货", false, false))
        assertEquals(MessageCategory.SERVICE, SmsParser.classify("12306 预约挂号提醒", false, false))
        assertEquals(MessageCategory.CARRIER, SmsParser.classify("中国移动流量套餐提醒", false, false))
        assertEquals(MessageCategory.OTHER, SmsParser.classify("今天天气不错", false, false))
        assertEquals(MessageCategory.PERSON, SmsParser.classify("晚上一起吃饭吗", true, false))
        assertEquals(MessageCategory.ARCHIVED, SmsParser.classify("任何内容", false, true))
    }

    // ---- tracking numbers (deliveries-style formats) ----
    @Test
    fun trackingNumberDetection() {
        assertEquals(
            MessageCategory.ECOMMERCE,
            SmsParser.classify("您的包裹已发出，单号 RA123456789CN", false, false)
        )
        assertEquals(
            MessageCategory.ECOMMERCE,
            SmsParser.classify("Your package is on the way. Tracking: 1Z999AA10123456784", false, false)
        )
        assertTrue(SmsParser.hasTrackingNumber("单号 RA123456789CN"))
    }

    // ---- merchant name ----
    @Test
    fun merchantNameExtraction() {
        assertEquals("京东", SmsParser.merchantName("【京东】您的订单已发货", "10086"))
        assertEquals("10086", SmsParser.merchantName("您的订单已发货", "10086"))
    }

    // ---- code formatting ----
    @Test
    fun formatSixDigitCode() {
        assertEquals("123 456", SmsParser.formatCode("123456"))
        assertEquals("12345", SmsParser.formatCode("12345"))
    }
}
