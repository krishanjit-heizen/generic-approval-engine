package com.approvalengine.error;

import java.util.List;

/**
 * 422 — semantically invalid input.
 *
 * <p>Carries every problem found, not just the first. An author fixing a workflow definition
 * should not have to submit five times to discover five mistakes.
 */
public class ValidationException extends EngineException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String type, String message, List<String> details) {
        super(type, message, details);
    }

    public ValidationException(String type, String message) {
        super(type, message);
    }

    @Override
    public int status() {
        return 422;
    }
}
