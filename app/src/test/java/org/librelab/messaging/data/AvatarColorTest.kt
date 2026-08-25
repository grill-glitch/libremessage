package org.librelab.messaging.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the Phase 2.2 avatar-palette change: AvatarColor was built from
 * Long color literals (Color(0xFFBBDEFB)) and now from ARGB ints
 * (Color(0xFFBBDEFB.toInt())). Both constructors must produce the same
 * color, otherwise every avatar silently changes color.
 */
class AvatarColorTest {

    private val pairs = listOf(
        0xFFBBDEFB to 0xFF0D47A1, // blue
        0xFFC8E6C9 to 0xFF1B5E20, // green
        0xFFFFE0B2 to 0xFFE65100, // orange
        0xFFE1BEE7 to 0xFF4A148C, // purple
        0xFFF8BBD0 to 0xFF880E4F, // pink
        0xFFB2EBF2 to 0xFF006064, // cyan
        0xFFFFCDD2 to 0xFFB71C1C, // red
        0xFFD7CCC8 to 0xFF3E2723  // brown
    )

    @Test
    fun intConstructorMatchesLongConstructor() {
        for ((container, content) in pairs) {
            val fromLong = Color(container)          // original: Long literal
            val fromInt = Color(container.toInt())   // Phase 2.2: ARGB int
            assertEquals(
                "container 0x${container.toString(16)}: Long vs Int constructor differ",
                fromLong, fromInt
            )
            val contentLong = Color(content)
            val contentInt = Color(content.toInt())
            assertEquals(
                "content 0x${content.toString(16)}: Long vs Int constructor differ",
                contentLong, contentInt
            )
        }
    }

    @Test
    fun argbValuesSurviveConversion() {
        // The ARGB int round-trip must keep the exact channel values.
        val c = Color(0xFFBBDEFB.toInt())
        assertEquals(0xFF, (c.alpha * 255f).toInt())
        assertEquals(0xBB, (c.red * 255f).toInt())
        assertEquals(0xDE, (c.green * 255f).toInt())
        assertEquals(0xFB, (c.blue * 255f).toInt())
    }
}
