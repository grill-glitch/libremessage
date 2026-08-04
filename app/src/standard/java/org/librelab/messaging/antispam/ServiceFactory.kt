package org.librelab.messaging.antispam

import android.content.Context

/** standard flavor: real mi-anti-spam network lookup. */
object ServiceFactory {
    lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun create(): AntiSpamService = MiAntiSpamService(appContext)
}
