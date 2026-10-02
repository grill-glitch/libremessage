package org.librelab.messaging.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the multipart-SMS segment join. Pure logic, no Android deps.
 */
class SmsSegmentsTest {

    @Test
    fun joinsSegmentsInOrder() {
        assertEquals(
            "第一段" + "第二段" + "第三段",
            joinSmsSegments(listOf("第一段", "第二段", "第三段"))
        )
    }

    @Test
    fun singleSegmentIsReturnedUnchanged() {
        assertEquals("短消息", joinSmsSegments(listOf("短消息")))
    }

    /** A 3-segment GSM-7 message keeps its trailing part (was cut before). */
    @Test
    fun longGsm7MessageIsNotTruncatedAtTheFirstSegment() {
        val body = "A".repeat(153) + "B".repeat(153) + "TAIL"
        assertEquals(
            body,
            joinSmsSegments(listOf("A".repeat(153), "B".repeat(153), "TAIL"))
        )
    }

    @Test
    fun skipsNullParts() {
        assertEquals("前半后半", joinSmsSegments(listOf("前半", null, "后半")))
    }

    @Test
    fun allNullPartsYieldEmptyBody() {
        assertEquals("", joinSmsSegments(listOf(null, null)))
        assertEquals("", joinSmsSegments(emptyList()))
    }
}
