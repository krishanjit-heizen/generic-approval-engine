package com.approvalengine.domain.request;

/**
 * The vocabulary of the audit trail.
 *
 * <p>A closed enum rather than free text, so history can be queried and rendered reliably
 * rather than grepped. Every entry the engine writes is one of these.
 */
public enum AuditAction {

    // --- request lifecycle ---
    REQUEST_CREATED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    REQUEST_CANCELLED,
    REQUEST_CHANGES_REQUESTED,
    REQUEST_RESUBMITTED,

    /** The engine could not proceed and parked the request for an operator. */
    REQUEST_NEEDS_ATTENTION,

    /** An operator rescued a parked request. */
    REQUEST_REASSIGNED,

    /** Startup recovery re-drove a request interrupted mid-advancement. */
    REQUEST_RECOVERED,

    // --- stage lifecycle ---
    /**
     * Recorded once per stage at creation, carrying the condition trace. This is what makes
     * "what did the engine consider, and why did it decide that" answerable later, as
     * opposed to only "what ran".
     */
    STAGE_PLANNED,
    STAGE_ACTIVATED,
    STAGE_APPROVED,
    STAGE_REJECTED,
    STAGE_SKIPPED,
    STAGE_EXPIRED,
    STAGE_RETURNED,

    /** The request was withdrawn while this stage was still open. */
    STAGE_CANCELLED,

    /** A revisit created a new attempt of a stage, via GOTO or a resubmission. */
    STAGE_REOPENED,

    // --- task lifecycle ---
    TASK_ASSIGNED,
    TASK_APPROVED,
    TASK_REJECTED,
    TASK_CHANGES_REQUESTED,
    TASK_EXPIRED,
    TASK_CANCELLED,

    /** The SLA sweeper moved a task to the unresponsive approver's manager. */
    TASK_ESCALATED,

    /**
     * Someone holding the task was refused — most often because the role that got them the
     * task has since been revoked. Recorded because a refused approval is exactly the kind of
     * event a reviewer needs to see, and it would otherwise be the only action leaving no trace.
     */
    TASK_ACTION_REFUSED;

    /** What the entry is about, stored alongside it for filtering. */
    public SubjectType subjectType() {
        String name = name();
        if (name.startsWith("REQUEST_")) {
            return SubjectType.REQUEST;
        }
        if (name.startsWith("STAGE_")) {
            return SubjectType.STAGE;
        }
        return SubjectType.TASK;
    }

    public enum SubjectType {
        REQUEST,
        STAGE,
        TASK
    }
}
