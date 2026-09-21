package com.approvalengine.error;

/** 401 — missing or unrecognised API key. */
public class UnauthorizedException extends EngineException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String type, String message) {
        super(type, message);
    }

    @Override
    public int status() {
        return 401;
    }
}
