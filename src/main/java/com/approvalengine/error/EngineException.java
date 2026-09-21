package com.approvalengine.error;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Base for every error the engine reports to a caller deliberately.
 *
 * <p>Each carries a stable machine-readable {@code type} slug — {@code task-already-actioned},
 * {@code idempotency-key-reuse} — rather than relying on the HTTP status alone. A consuming
 * application needs to branch on *why* a 409 happened, and it should be able to do that
 * without string-matching a human message that we reserve the right to reword.
 */
@Getter
public abstract class EngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String type;
    private final List<String> details;

    protected EngineException(String type, String message) {
        this(type, message, Collections.<String>emptyList());
    }

    protected EngineException(String type, String message, List<String> details) {
        super(message);
        this.type = type;
        this.details = details == null ? Collections.<String>emptyList() : details;
    }

    /** HTTP status this maps to. */
    public abstract int status();
}
