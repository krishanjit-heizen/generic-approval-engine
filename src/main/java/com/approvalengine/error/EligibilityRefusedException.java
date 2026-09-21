package com.approvalengine.error;

import lombok.Getter;

/**
 * Thrown when an approver holds the task but is no longer entitled to act on it — typically
 * because the role that got them the task has since been revoked.
 *
 * <p>Carries the context needed to record the refusal in the audit trail. That recording cannot
 * happen where the refusal is detected: at that point the transaction holds a row lock on the
 * request and is about to roll back, so an audit row written there would be discarded with it,
 * and one written in a nested transaction would block on the lock its own parent holds. So the
 * refusal is audited by the caller, after the rollback has released the lock.
 *
 * <p>A refused approval is a security-relevant event. Returning 403 without leaving a trace
 * would mean the one category of action most worth reviewing is the only one that leaves no
 * record.
 */
@Getter
public class EligibilityRefusedException extends ForbiddenException {

    private static final long serialVersionUID = 1L;

    private final String requestId;
    private final String taskId;
    private final String actorId;
    private final String assignedVia;

    public EligibilityRefusedException(String type, String message, String requestId,
                                       String taskId, String actorId, String assignedVia) {
        super(type, message);
        this.requestId = requestId;
        this.taskId = taskId;
        this.actorId = actorId;
        this.assignedVia = assignedVia;
    }
}
