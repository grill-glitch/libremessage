package org.librelab.messaging

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.librelab.messaging.antispam.ServiceFactory
import org.librelab.messaging.ui.MainScreen
import org.librelab.messaging.ui.theme.MessagingTheme

/**
 * Main entry. Also handles ACTION_SENDTO (smsto:) intents, which the system
 * requires for default-SMS-app status.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceFactory.init(applicationContext)
        val number = intent.data?.schemeSpecificPart.orEmpty()
        val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent {
            MessagingTheme {
                MainScreen(initialNumber = number, initialBody = body)
            }
        }
    }
}
