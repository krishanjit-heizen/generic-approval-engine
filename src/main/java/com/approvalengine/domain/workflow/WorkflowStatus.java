package com.approvalengine.domain.workflow;

/**
 * Publication state of one workflow version.
 *
 * <pre>
 * DRAFT ──publish──▶ ACTIVE ──(a newer version is published)──▶ ARCHIVED
 * </pre>
 *
 * <p>At most one ACTIVE version exists per {@code (appId, workflowKey)}: that is the one new
 * requests bind to. ARCHIVED versions are never deleted, because requests that pinned them
 * are still executing against them — and once those finish, the audit trail still needs to
 * explain what rules applied.
 */
public enum WorkflowStatus {

    /** Editable and simulatable, but not yet usable by real requests. */
    DRAFT,

    /** The version new requests bind to. */
    ACTIVE,

    /** Superseded. Still readable, still executing for requests that pinned it. */
    ARCHIVED;

    /** Whether new approval requests may bind to this version. */
    public boolean acceptsNewRequests() {
        return this == ACTIVE;
    }
}
