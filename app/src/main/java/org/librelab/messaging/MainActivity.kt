package org.librelab.messaging

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.librelab.messaging.ui.MainScreen
import org.librelab.messaging.ui.theme.MessagingTheme

/**
 * Main entry. Also handles ACTION_SENDTO (smsto:) intents (required for
 * default-SMS-app status), ACTION_SEND share intents, and the launcher
 * long-press shortcuts (new message / codes / pickups).
 */
class MainActivity : ComponentActivity() {

    /** Parsed launch intent: share target + launcher shortcut target. */
    private data class LaunchParams(
        val number: String,
        val body: String,
        val attachmentUri: String,
        val shortcutTarget: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val p = parseIntent(intent)
        setContent {
            MessagingTheme {
                MainScreen(
                    initialNumber = p.number,
                    initialBody = p.body,
                    initialAttachmentUri = p.attachmentUri,
                    shortcutTarget = p.shortcutTarget
                )
            }
        }
    }

    /** singleTask: a second shortcut tap reuses the task and lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val p = parseIntent(intent)
        setContent {
            MessagingTheme {
                MainScreen(
                    initialNumber = p.number,
                    initialBody = p.body,
                    initialAttachmentUri = p.attachmentUri,
                    shortcutTarget = p.shortcutTarget
                )
            }
        }
    }

    private fun parseIntent(intent: Intent): LaunchParams {
        val number = intent.data?.schemeSpecificPart.orEmpty()
        var body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        var attachmentUri = ""
        // Launcher long-press shortcuts: 0 = none, 1 = new message,
        // 2 = verification codes filter, 3 = pickup codes filter.
        val shortcutTarget = when (intent.action) {
            "org.librelab.messaging.action.NEW_MESSAGE" -> 1
            "org.librelab.messaging.action.OPEN_CODES" -> 2
            "org.librelab.messaging.action.OPEN_PICKUPS" -> 3
            else -> 0
        }

        if (intent.action == Intent.ACTION_SEND) {
            // Prefer the stream (image/file) over text when both are present.
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                attachmentUri = uri.toString()
            }
            // Share sheet "copy text" style shares: strip the subject prefix
            // some apps attach ("Look at this: ..."), keep the plain text.
            if (body.isBlank() && attachmentUri.isBlank()) {
                body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            }
        }
        return LaunchParams(number, body, attachmentUri, shortcutTarget)
    }
}
