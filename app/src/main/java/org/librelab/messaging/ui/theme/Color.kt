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

/**
 * Same 8 container/content pairs as ARGB ints — the single source of truth.
 * The Compose layer converts to [AvatarColor]; the widget layer (RemoteViews,
 * separate process) consumes the ints directly.
 */
data class AvatarArgb(val container: Int, val content: Int)

val AvatarPaletteArgb = listOf(
    AvatarArgb(0xFFBBDEFB.toInt(), 0xFF0D47A1.toInt()), // blue
    AvatarArgb(0xFFC8E6C9.toInt(), 0xFF1B5E20.toInt()), // green
    AvatarArgb(0xFFFFE0B2.toInt(), 0xFFE65100.toInt()), // orange
    AvatarArgb(0xFFE1BEE7.toInt(), 0xFF4A148C.toInt()), // purple
    AvatarArgb(0xFFF8BBD0.toInt(), 0xFF880E4F.toInt()), // pink
    AvatarArgb(0xFFB2EBF2.toInt(), 0xFF006064.toInt()), // cyan
    AvatarArgb(0xFFFFCDD2.toInt(), 0xFFB71C1C.toInt()), // red
    AvatarArgb(0xFFD7CCC8.toInt(), 0xFF3E2723.toInt()), // brown
)

/** Deterministic avatar ARGB colors for a contact key (name or address). */
fun avatarArgbFor(key: String): AvatarArgb =
    AvatarPaletteArgb[(key.hashCode() and Int.MAX_VALUE) % AvatarPaletteArgb.size]

/** Deterministic avatar colors for a contact key (name or address). */
fun avatarColorFor(key: String): AvatarColor =
    avatarArgbFor(key).let { AvatarColor(Color(it.container), Color(it.content)) }
