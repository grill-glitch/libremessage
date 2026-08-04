package org.librelab.messaging.antispam

/**
 * No-op offline implementation. The libre flavor declares no INTERNET
 * permission, so this service can never perform a lookup — callers simply
 * get null (no mark) for every number.
 */
class OfflineAntiSpamService : AntiSpamService {
    override fun isAvailable(): Boolean = false

    override suspend fun lookup(number: String): NumberMark? = null
}
