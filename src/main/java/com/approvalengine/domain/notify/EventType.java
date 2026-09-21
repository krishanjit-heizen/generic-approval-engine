package com.approvalengine.domain.notify;

/**
 * The webhook events a consuming application can receive.
 *
 * <p>This is published contract, so the names are stable. A consumer switches on
 * {@code eventType} and is entitled to ignore any value it does not recognise — which is why
 * new members may be added but existing ones must never be renamed or repurposed.
 */
public enum EventType {

    /** An approval process started. Carries the planned stages and the first assignments. */
    REQUEST_CREATED,

    /** A stage became active. Useful for consumers that mirror progress in their own UI. */
    STAGE_ACTIVATED,

    /** Someone was asked to approve. The hook for a consumer that sends its own notifications. */
    TASK_ASSIGNED,

    /** Terminal: every applicable stage approved. */
    REQUEST_APPROVED,

    /** Terminal: an approver rejected under a stage whose onReject is TERMINATE. */
    REQUEST_REJECTED,

    /** Terminal: withdrawn by the requester or the owning application. */
    REQUEST_CANCELLED,

    /** An approver sent it back. The consumer should prompt the requester to revise. */
    REQUEST_CHANGES_REQUESTED,

    /**
     * The engine cannot proceed without intervention — most often because no approver could
     * be resolved. Sent so a consumer is never left waiting on a request that has silently
     * stopped moving.
     */
    REQUEST_NEEDS_ATTENTION;

    /** Whether this event reports a final decision. */
    public boolean isTerminal() {
        return this == REQUEST_APPROVED || this == REQUEST_REJECTED || this == REQUEST_CANCELLED;
    }
}
