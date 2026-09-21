package com.approvalengine.domain.request;

/**
 * Lifecycle of one stage of a running request.
 *
 * <p>Note that {@code SKIPPED} is a recorded outcome, not an absence. A stage whose
 * condition evaluated false still gets a row, carrying the condition trace that skipped
 * it — so the history shows what the engine considered, not only what it ran.
 */
public enum StageStatus {

    /** Applicable, but not reached yet. */
    PENDING,

    /** Approvers resolved and tasks outstanding. */
    ACTIVE,

    /** Completion policy satisfied. */
    APPROVED,

    /** An approver rejected at this stage. */
    REJECTED,

    /** Condition evaluated false, so the stage did not apply to this request. */
    SKIPPED,

    /** SLA elapsed and the stage's onTimeout resolved it without a human decision. */
    EXPIRED,

    /**
     * An approver sent the request back to the requester for changes.
     *
     * <p>A distinct status rather than reusing REJECTED, because the two mean opposite
     * things to a reader: REJECTED ended the request, RETURNED is waiting on the requester
     * and will resume. When they resubmit, a new attempt of this stage is created and this
     * row stays as the record of the first pass.
     */
    RETURNED;

    public boolean isTerminal() {
        return this != PENDING && this != ACTIVE;
    }

    /** Whether this attempt of the stage counts as satisfied for advancement purposes. */
    public boolean isPassed() {
        return this == APPROVED || this == SKIPPED;
    }
}
