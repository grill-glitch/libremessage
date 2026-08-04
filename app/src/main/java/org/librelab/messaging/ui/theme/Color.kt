package org.librelab.messaging.ui.theme

import androidx.compose.ui.graphics.Color

// Fallback MD3 scheme (used below Android 12 or when dynamic color is off).
// Material You baseline purple.

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/**
 * Avatar palette: 8 Material container/content pairs. Each contact gets one
 * deterministically from the hash of their display name, so the same person
 * always keeps the same color across the conversation list, the detail
 * header and the message bubbles — making people easy to tell apart.
 */
data class AvatarColor(val container: Color, val content: Color)

val AvatarPalette = listOf(
    AvatarColor(Color(0xFFBBDEFB), Color(0xFF0D47A1)), // blue
    AvatarColor(Color(0xFFC8E6C9), Color(0xFF1B5E20)), // green
    AvatarColor(Color(0xFFFFE0B2), Color(0xFFE65100)), // orange
    AvatarColor(Color(0xFFE1BEE7), Color(0xFF4A148C)), // purple
    AvatarColor(Color(0xFFF8BBD0), Color(0xFF880E4F)), // pink
    AvatarColor(Color(0xFFB2EBF2), Color(0xFF006064)), // cyan
    AvatarColor(Color(0xFFFFCDD2), Color(0xFFB71C1C)), // red
    AvatarColor(Color(0xFFD7CCC8), Color(0xFF3E2723)), // brown
)

/** Deterministic avatar colors for a contact key (name or address). */
fun avatarColorFor(key: String): AvatarColor {
    val idx = (key.hashCode() and Int.MAX_VALUE) % AvatarPalette.size
    return AvatarPalette[idx]
}
