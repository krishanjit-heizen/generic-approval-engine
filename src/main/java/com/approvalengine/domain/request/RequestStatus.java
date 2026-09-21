package com.approvalengine.domain.request;

/**
 * Lifecycle of an approval request.
 *
 * <pre>
 * CREATED ──▶ IN_PROGRESS ──▶ APPROVED | REJECTED | CANCELLED   (terminal)
 *                  │
 *                  ├──▶ CHANGES_REQUESTED ──▶ IN_PROGRESS       (requester resubmits)
 *                  └──▶ NEEDS_ATTENTION    ──▶ IN_PROGRESS      (operator reassigns)
 * </pre>
 *
 * <p>{@code NEEDS_ATTENTION} is the one state that exists purely for operability: it is
 * where a request lands when the engine cannot proceed on its own — no approver could be
 * resolved, or an execution step faulted. It is deliberately not terminal. The alternative
 * designs are worse: failing the request throws away real human approvals already given,
 * and silently stalling leaves a request that nobody knows is stuck.
 */
public enum RequestStatus {

    /** Persisted, but no stage activated yet. Normally transient within one transaction. */
    CREATED,

    /** A stage is active and tasks are outstanding. */
    IN_PROGRESS,

    /** An approver asked the requester to change something; awaiting resubmission. */
    CHANGES_REQUESTED,

    /** The engine is stuck and needs an operator. Recoverable. */
    NEEDS_ATTENTION,

    /** Every applicable stage approved. */
    APPROVED,

    /** An approver rejected, under a stage whose onReject is TERMINATE. */
    REJECTED,

    /** Withdrawn by the requester or the owning application. */
    CANCELLED;

    /**
     * Whether the request has reached a final decision. Terminal requests are immutable:
     * no further actions, and they drop out of the live-entity uniqueness guard so the same
     * entity can go through approval again later.
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }

    /** Whether approvers may still act. */
    public boolean isActionable() {
        return this == IN_PROGRESS;
    }
}
