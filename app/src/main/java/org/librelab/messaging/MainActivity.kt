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
 * default-SMS-app status) and ACTION_SEND share intents — the app appears
 * in the system share sheet so other apps can send text / images / files
 * into a conversation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val intent = intent
        val number = intent.data?.schemeSpecificPart.orEmpty()
        var body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        var attachmentUri = ""

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

        setContent {
            MessagingTheme {
                MainScreen(
                    initialNumber = number,
                    initialBody = body,
                    initialAttachmentUri = attachmentUri
                )
            }
        }
    }
}
