package org.librelab.messaging

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.librelab.messaging.ui.MainScreen
import org.librelab.messaging.ui.theme.MessagingTheme

/**
 * Main entry. Also handles ACTION_SENDTO (smsto:) intents (required for
 * default-SMS-app status), ACTION_SEND share intents, the launcher
 * long-press shortcuts (new message / codes / pickups), and widget row
 * taps (open a specific thread).
 *
 * The launch params live in a Compose state updated by [onNewIntent]:
 * setContent runs once, and every intent — including repeats while the
 * activity is already on screen — flows through the same state so
 * MainScreen recomposes and reacts (instead of a stale LaunchedEffect
 * keyed on Unit).
 */
class MainActivity : ComponentActivity() {

    companion object {
        // Launcher shortcut / widget / notification deep-link actions.
        const val ACTION_NEW_MESSAGE = "org.librelab.messaging.action.NEW_MESSAGE"
        const val ACTION_OPEN_CODES = "org.librelab.messaging.action.OPEN_CODES"
        const val ACTION_OPEN_PICKUPS = "org.librelab.messaging.action.OPEN_PICKUPS"
        const val ACTION_OPEN_THREAD = "org.librelab.messaging.action.OPEN_THREAD"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_THREAD_ID = "threadId"
    }

    /** Parsed launch intent: share target + launcher shortcut target. */
    private data class LaunchParams(
        val number: String,
        val body: String,
        val attachmentUri: String,
        val shortcutTarget: Int,
        val threadId: Long = -1L
    )

    private var launchParams by mutableStateOf(LaunchParams("", "", "", 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchParams = parseIntent(intent)
        setContent {
            MessagingTheme {
                MainScreen(
                    initialNumber = launchParams.number,
                    initialBody = launchParams.body,
                    initialAttachmentUri = launchParams.attachmentUri,
                    shortcutTarget = launchParams.shortcutTarget,
                    initialThreadId = launchParams.threadId
                )
            }
        }
    }

    /** singleTask: a second shortcut tap reuses the task and lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchParams = parseIntent(intent)
    }

    private fun parseIntent(intent: Intent): LaunchParams {
        // Widget row tap: a dedicated PendingIntent per row carries the
        // thread id as an extra (template + fill-in merging drops extras).
        val widgetThreadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        // Notification tap: carries the sender address (no thread id).
        val notificationAddress = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val number = intent.data?.schemeSpecificPart.orEmpty()
        var body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        var attachmentUri = ""
        // Launcher long-press shortcuts: 0 = none, 1 = new message,
        // 2 = verification codes filter, 3 = pickup codes filter;
        // 4 = open a thread (widget row with threadId, or notification
        // with an address).
        val shortcutTarget = when {
            intent.action == ACTION_NEW_MESSAGE -> 1
            intent.action == ACTION_OPEN_CODES -> 2
            intent.action == ACTION_OPEN_PICKUPS -> 3
            intent.action == ACTION_OPEN_THREAD && (widgetThreadId > 0 || notificationAddress.isNotBlank()) -> 4
            else -> 0
        }
        if (widgetThreadId > 0 && shortcutTarget == 4) {
            return LaunchParams(number = "", body = "", attachmentUri = "", shortcutTarget = 4, threadId = widgetThreadId)
        }
        if (notificationAddress.isNotBlank() && shortcutTarget == 4) {
            return LaunchParams(number = notificationAddress, body = "", attachmentUri = "", shortcutTarget = 4)
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
