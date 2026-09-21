package com.approvalengine.error;

/**
 * 403 — authenticated, but not entitled to this specific action.
 *
 * <p>Most often: an actor tried to act on a task they do not hold. Distinct from 404 on
 * purpose — the engine will confirm a request exists to its owning application while still
 * refusing an approval from someone who was never asked.
 */
public class ForbiddenException extends EngineException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String type, String message) {
        super(type, message);
    }

    @Override
    public int status() {
        return 403;
    }
}
