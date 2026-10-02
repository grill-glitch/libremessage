package org.librelab.messaging.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the multi-segment send accounting. Pure logic, no Android
 * dependencies.
 */
class SmsSendTrackerTest {

    private fun tracker() = SmsSendTracker()

    @Test
    fun singleSegmentResolvesOnItsFirstReport() {
        val t = tracker()
        t.expect(1L, 1)
        assertEquals(SmsSendOutcome.SENT, t.onReport(1L, ok = true))

        t.expect(2L, 1)
        assertEquals(SmsSendOutcome.FAILED, t.onReport(2L, ok = false))
    }

    @Test
    fun multiSegmentIsPendingUntilEverySegmentReports() {
        val t = tracker()
        t.expect(7L, 4)
        assertEquals(SmsSendOutcome.PENDING, t.onReport(7L, true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(7L, true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(7L, true))
        assertEquals(SmsSendOutcome.SENT, t.onReport(7L, true))
    }

    /** A rejected segment fails the whole message right away. */
    @Test
    fun oneFailedSegmentFailsTheMessageImmediately() {
        val t = tracker()
        t.expect(8L, 3)
        assertEquals(SmsSendOutcome.PENDING, t.onReport(8L, true))
        assertEquals(SmsSendOutcome.FAILED, t.onReport(8L, ok = false))
    }

    /** Reports arriving after the decision must not flip the row. */
    @Test
    fun lateReportsAfterAFailureDoNotFlipToSent() {
        val t = tracker()
        t.expect(9L, 4)
        assertEquals(SmsSendOutcome.FAILED, t.onReport(9L, ok = false))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(9L, true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(9L, true))
    }

    @Test
    fun lateReportsAfterSuccessAreIgnored() {
        val t = tracker()
        t.expect(10L, 1)
        assertEquals(SmsSendOutcome.SENT, t.onReport(10L, true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(10L, ok = false))
    }

    /** Process restart: nothing registered, so each report decides on its own. */
    @Test
    fun unknownRecordDecidesPerReport() {
        val t = tracker()
        assertEquals(SmsSendOutcome.SENT, t.onReport(42L, ok = true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(42L, ok = false))

        val t2 = tracker()
        assertEquals(SmsSendOutcome.FAILED, t2.onReport(43L, ok = false))
        assertEquals(SmsSendOutcome.PENDING, t2.onReport(43L, ok = true))
    }

    @Test
    fun forgetDropsTheExpectation() {
        val t = tracker()
        t.expect(11L, 3)
        t.forget(11L)
        // No registration left -> the report decides on its own.
        assertEquals(SmsSendOutcome.SENT, t.onReport(11L, ok = true))
    }

    /** A reused record id starts a fresh count. */
    @Test
    fun expectAfterAdecisionStartsFresh() {
        val t = tracker()
        t.expect(12L, 2)
        assertEquals(SmsSendOutcome.FAILED, t.onReport(12L, ok = false))
        t.expect(12L, 2)
        assertEquals(SmsSendOutcome.PENDING, t.onReport(12L, true))
        assertEquals(SmsSendOutcome.SENT, t.onReport(12L, true))
    }

    @Test
    fun interleavedSendsAreAccountedSeparately() {
        val t = tracker()
        t.expect(20L, 2)
        t.expect(21L, 3)
        assertEquals(SmsSendOutcome.PENDING, t.onReport(20L, true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(21L, true))
        assertEquals(SmsSendOutcome.SENT, t.onReport(20L, true))
        assertEquals(SmsSendOutcome.PENDING, t.onReport(21L, true))
        assertEquals(SmsSendOutcome.SENT, t.onReport(21L, true))
    }
}
