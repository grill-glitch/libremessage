package org.librelab.messaging.antispam

import android.content.Context

/** libre flavor: offline no-op factory — init is a no-op. */
object ServiceFactory {
    fun init(context: Context) {}

    fun create(): AntiSpamService = OfflineAntiSpamService()
}
