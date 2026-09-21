package com.approvalengine.domain.notify;

/**
 * Delivery state of an outbound notification.
 *
 * <pre>
 * PENDING ──(2xx)──▶ SENT
 *    │
 *    └──(attempts exhausted)──▶ DEAD   → visible on /admin/outbox, replayable
 * </pre>
 *
 * <p>{@code DEAD} is not a silent discard. Exhausted events surface on an admin endpoint and
 * can be replayed once the consumer is fixed. The alternative — dropping them, or retrying
 * forever — either loses the notification or lets one broken consumer's backlog crowd out
 * every healthy one.
 */
public enum OutboxStatus {

    /** Awaiting delivery, or awaiting its next backoff window. */
    PENDING,

    /** The consumer returned 2xx, meaning it durably recorded the event. */
    SENT,

    /** Every attempt failed. Held for operator replay rather than dropped. */
    DEAD
}
