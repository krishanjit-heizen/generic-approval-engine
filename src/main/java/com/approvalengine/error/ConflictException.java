package com.approvalengine.error;

/**
 * 409 — the request was well-formed but the engine's current state does not permit it.
 *
 * <p>The common cases are a task another approver already actioned, and a request that has
 * already reached a terminal decision. Both are normal under concurrency, and both must be
 * reported rather than absorbed: silently accepting a late approval would record a decision
 * that never affected the outcome.
 */
public class ConflictException extends EngineException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String type, String message) {
        super(type, message);
    }

    @Override
    public int status() {
        return 409;
    }
}
