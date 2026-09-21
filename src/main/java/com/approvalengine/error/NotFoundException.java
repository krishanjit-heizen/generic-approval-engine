package com.approvalengine.error;

/** 404 — the addressed thing does not exist, or does not belong to the calling application. */
public class NotFoundException extends EngineException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String type, String message) {
        super(type, message);
    }

    @Override
    public int status() {
        return 404;
    }
}
