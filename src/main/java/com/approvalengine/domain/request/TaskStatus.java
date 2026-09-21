package com.approvalengine.domain.request;

/**
 * Lifecycle of a single approver's task.
 *
 * <p>A task leaves {@code PENDING} exactly once, via a conditional update
 * ({@code ... WHERE id = ? AND status = 'PENDING'}). That single-writer property at the row
 * level is the innermost of the three guards against two approvers acting simultaneously.
 */
public enum TaskStatus {

    /**
     * Queued behind an earlier approver in a SEQUENTIAL stage — resolved and recorded, but
     * not yet this person's turn.
     *
     * <p>This state exists so a SEQUENTIAL stage can snapshot its whole approver list at
     * activation (so a later directory change cannot alter an in-flight stage) while still
     * asking one person at a time. Resolving lazily instead would mean re-resolving mid-stage,
     * which defeats the point of snapshotting; creating every task as PENDING would let the
     * whole chain act at once, which defeats the point of SEQUENTIAL.
     *
     * <p>WAITING tasks never appear in an approver's inbox.
     */
    WAITING,

    /** Awaiting the approver — it is their turn. */
    PENDING,

    APPROVED,

    REJECTED,

    /** Approver bounced it back to the requester for rework. */
    CHANGES_REQUESTED,

    /** SLA elapsed before the approver acted. */
    EXPIRED,

    /**
     * Withdrawn without the approver acting — a sibling rejected and terminated the stage,
     * the request was cancelled, or the task was escalated away by the SLA sweeper.
     */
    CANCELLED;

    public boolean isPending() {
        return this == PENDING;
    }

    /** Whether this outcome counts toward a stage's completion policy. */
    public boolean countsAsApproval() {
        return this == APPROVED;
    }
}
