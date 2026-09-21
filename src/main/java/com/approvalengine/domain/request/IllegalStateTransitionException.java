package com.approvalengine.domain.request;

/**
 * Thrown when code attempts a status change the {@link StateMachine} does not permit.
 *
 * <p>This is not an expected control-flow path for a well-behaved caller — the API layer
 * checks preconditions and returns a 409 before reaching here. Hitting this exception means
 * either a genuine race the optimistic lock did not catch, or an engine bug. Either way it
 * must abort the transaction rather than degrade into an unrecorded state change.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String subjectType;
    private final String subjectId;
    private final String fromState;
    private final String toState;

    public IllegalStateTransitionException(String subjectType, String subjectId,
                                          String fromState, String toState) {
        super(subjectType + " " + subjectId + " cannot move from " + fromState + " to " + toState);
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.fromState = fromState;
        this.toState = toState;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }
}
