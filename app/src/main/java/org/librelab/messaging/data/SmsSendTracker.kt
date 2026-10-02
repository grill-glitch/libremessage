package org.librelab.messaging.data

/** Decision for one outgoing message after a segment report. */
enum class SmsSendOutcome {
    /** More segments are still waiting for their report. */
    PENDING,
    /** Every segment was accepted by the stack. */
    SENT,
    /** At least one segment was rejected. */
    FAILED
}

/**
 * Delivery accounting for an outgoing SMS.
 *
 * A long message does not fit in one PDU, so the stack sends it as one PDU
 * per segment and reports one result per segment. The outbox row may only
 * read SENT once *every* segment was accepted, and FAILED as soon as one was
 * rejected — flipping on the first report would show a half-sent message as
 * delivered. A single-segment send resolves on its first report, so short
 * messages behave exactly as before.
 *
 * Reports for an unregistered record — the process was restarted between the
 * send and its callbacks, so the in-memory state is gone — fall back to a
 * per-report decision, because an outbox row must never stay stuck in
 * SENDING. Already-resolved records ignore further reports, so a late segment
 * report cannot flip a decided row.
 *
 * Pure logic, no Android dependencies: the receiver owns the instance and the
 * repository side is the caller's business.
 */
class SmsSendTracker {

    private val lock = Any()

    /** record id -> segments still to report. */
    private val pending = HashMap<Long, Int>()

    /** Record ids already decided (bounded), so late reports are ignored. */
    private val resolved = ArrayDeque<Long>()

    /** Register a send: [parts] segment reports are expected for [recordId]. */
    fun expect(recordId: Long, parts: Int) = synchronized(lock) {
        resolved.remove(recordId)
        pending[recordId] = if (parts > 1) parts else 1
    }

    /** Drop a send that never left the device (the stack threw). */
    fun forget(recordId: Long) {
        synchronized(lock) { pending.remove(recordId) }
    }

    /** Account for one segment report and decide, if this was the last one. */
    fun onReport(recordId: Long, ok: Boolean): SmsSendOutcome = synchronized(lock) {
        if (resolved.contains(recordId)) return SmsSendOutcome.PENDING
        val remaining = pending[recordId]
        if (remaining == null) {
            // Nothing registered: decide per report (see class KDoc).
            return decide(recordId, ok)
        }
        if (!ok) return decide(recordId, false)
        val left = remaining - 1
        if (left <= 0) return decide(recordId, true)
        pending[recordId] = left
        SmsSendOutcome.PENDING
    }

    private fun decide(recordId: Long, ok: Boolean): SmsSendOutcome {
        pending.remove(recordId)
        resolved.addLast(recordId)
        if (resolved.size > RESOLVED_MEMORY) resolved.removeFirst()
        return if (ok) SmsSendOutcome.SENT else SmsSendOutcome.FAILED
    }

    private companion object {
        /** How many decided record ids are remembered to swallow late reports. */
        const val RESOLVED_MEMORY = 64
    }
}

/**
 * The registry shared by [MessageSender] (registers a send, forgets it when
 * the stack rejects the hand-off) and
 * [org.librelab.messaging.SmsSentReceiver] (reports each segment result).
 */
val smsSendTracker = SmsSendTracker()
