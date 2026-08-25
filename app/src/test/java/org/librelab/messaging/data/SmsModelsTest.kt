package org.librelab.messaging.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the shared pure functions in SmsModels.kt. */
class SmsModelsTest {

    // ---- anti-bomb window logic (one rule for receiver + UI) ----
    @Test
    fun antiBombOffNeverActive() {
        assertFalse(isAntiBombActive(antiBomb = false, until = 0L, now = 1_000L))
        assertFalse(isAntiBombActive(antiBomb = false, until = 999L, now = 1_000L))
    }

    @Test
    fun antiBombOnActiveAfterWindow() {
        // until = 0 → no window ever set → active as soon as the switch is on.
        assertTrue(isAntiBombActive(antiBomb = true, until = 0L, now = 1_000L))
        // Window still open → not active.
        assertFalse(isAntiBombActive(antiBomb = true, until = 2_000L, now = 1_000L))
        // Exactly at the window boundary → not active (strict >).
        assertFalse(isAntiBombActive(antiBomb = true, until = 1_000L, now = 1_000L))
        // Window expired → active again.
        assertTrue(isAntiBombActive(antiBomb = true, until = 999L, now = 1_000L))
    }

    @Test
    fun antiBombDefaultNow() {
        // Default `now` is real wall-clock time; a past window must be active.
        assertTrue(isAntiBombActive(antiBomb = true, until = 0L))
    }

    // ---- groupThreads: one row per sender, latest first, counts aggregate ----
    private fun msg(
        id: Long, threadId: Long, address: String, body: String,
        date: Long, read: Boolean, category: MessageCategory = MessageCategory.OTHER
    ) = SmsMessage(id, threadId, address, body, date, read, isSent = false, category = category, code = null, contactName = null)

    @Test
    fun groupThreadsAggregatesPerThread() {
        val rows = groupThreads(
            listOf(
                msg(1, 10, "10086", "旧消息", 1000, read = true),
                msg(2, 10, "10086", "新消息", 3000, read = false),
                msg(3, 10, "10086", "中间", 2000, read = false)
            )
        )
        assertEquals(1, rows.size)
        assertEquals("新消息", rows[0].message.body)
        assertEquals(2, rows[0].unreadCount)
        assertEquals(3, rows[0].totalCount)
    }

    @Test
    fun groupThreadsFallsBackToAddressWhenNoThreadId() {
        // Two senders with threadId=0 must produce two rows (not crash on a
        // shared key) — the list uses these rows' keys.
        val rows = groupThreads(
            listOf(
                msg(1, 0, "13800000001", "a", 2000, read = true),
                msg(2, 0, "13800000002", "b", 1000, read = true)
            )
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun groupThreadsSortsNewestFirst() {
        val rows = groupThreads(
            listOf(
                msg(1, 10, "10086", "旧", 1000, read = true),
                msg(2, 20, "10010", "新", 9000, read = true),
                msg(3, 30, "95555", "中", 5000, read = true)
            )
        )
        assertEquals(listOf(20L, 30L, 10L), rows.map { it.message.threadId })
    }

    // ---- codeEntries: split multi-parcel messages, filter by category ----
    @Test
    fun codeEntriesSplitsMultiplePickupCodes() {
        val body = "【多多代收点】您有2个包裹在采荷百合路11号驿站,取货码1-3-9448、5-4-3216"
        val entries = codeEntries(
            listOf(msg(1, 10, "106575", body, 1000, read = true, category = MessageCategory.PACKAGE)),
            setOf(MessageCategory.PACKAGE)
        )
        assertEquals(2, entries.size)
        assertEquals(setOf("1-3-9448", "5-4-3216"), entries.map { it.code }.toSet())
    }

    @Test
    fun codeEntriesFiltersByCategory() {
        val messages = listOf(
            msg(1, 10, "106575", "验证码123456", 1000, read = true, category = MessageCategory.CODE),
            msg(2, 20, "106576", "取货码1-2-3456", 2000, read = true, category = MessageCategory.PACKAGE),
            msg(3, 30, "10086", "普通短信", 3000, read = true, category = MessageCategory.OTHER)
        )
        assertEquals(1, codeEntries(messages, setOf(MessageCategory.CODE)).size)
        assertEquals(1, codeEntries(messages, setOf(MessageCategory.PACKAGE)).size)
        assertEquals(2, codeEntries(messages, setOf(MessageCategory.CODE, MessageCategory.PACKAGE)).size)
    }

    @Test
    fun codeEntriesNewestFirst() {
        val entries = codeEntries(
            listOf(
                msg(1, 10, "a", "验证码111111", 1000, read = true, category = MessageCategory.CODE),
                msg(2, 20, "b", "验证码222222", 5000, read = true, category = MessageCategory.CODE)
            ),
            setOf(MessageCategory.CODE)
        )
        assertEquals(listOf("222222", "111111"), entries.map { it.code })
    }

    // ---- delivery-state mapping (sms type / mms msg_box) ----
    @Test
    fun smsSendStatusMapping() {
        assertEquals(SendStatus.SENT, smsSendStatus(2))      // MESSAGE_TYPE_SENT
        assertEquals(SendStatus.SENDING, smsSendStatus(4))   // MESSAGE_TYPE_QUEUED
        assertEquals(SendStatus.SENDING, smsSendStatus(6))   // MESSAGE_TYPE_OUTBOX
        assertEquals(SendStatus.FAILED, smsSendStatus(5))    // MESSAGE_TYPE_FAILED
        assertEquals(SendStatus.NONE, smsSendStatus(1))      // inbox
        assertEquals(SendStatus.NONE, smsSendStatus(0))
    }

    @Test
    fun mmsSendStatusMapping() {
        assertEquals(SendStatus.SENT, mmsSendStatus(2))
        assertEquals(SendStatus.SENDING, mmsSendStatus(4))
        assertEquals(SendStatus.FAILED, mmsSendStatus(5))
        assertEquals(SendStatus.NONE, mmsSendStatus(1))
    }

    @Test
    fun sentFlagSemantics() {
        // P0-2 guard: sms OUTBOX rows are NOT flagged sent (original
        // behaviour); mms anything-but-inbox IS sent.
        assertTrue(smsIsSent(2))
        assertFalse(smsIsSent(6))
        assertFalse(mmsIsSent(1))
        assertTrue(mmsIsSent(2))
        assertTrue(mmsIsSent(4))
    }
}
