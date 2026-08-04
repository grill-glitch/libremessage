package org.librelab.messaging.antispam

/**
 * Per-flavor service holder. Each build flavor supplies its own
 * [ServiceFactory] (same class name, different implementation), so the
 * shared UI code never references flavor-specific classes directly and the
 * offline build contains no network code at all.
 */
object AntiSpam {
    val service: AntiSpamService by lazy { ServiceFactory.create() }
}
