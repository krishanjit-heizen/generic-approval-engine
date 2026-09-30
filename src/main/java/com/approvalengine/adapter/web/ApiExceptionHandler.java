package com.approvalengine.adapter.web;

import com.approvalengine.application.JsonCodec;
import com.approvalengine.domain.request.IllegalStateTransitionException;
import com.approvalengine.error.EngineException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single translation point from exceptions to RFC 7807 {@code application/problem+json}.
 *
 * <p>Every error a consuming application can see is shaped here, which is what makes the
 * error contract as stable as the success contract. Two decisions worth stating:
 *
 * <ul>
 *   <li><b>Stable {@code type} slugs.</b> Consumers branch on {@code type}, never on the
 *       prose in {@code detail}, so messages can be improved without breaking anyone.</li>
 *   <li><b>Nothing internal leaks.</b> Unexpected exceptions become a generic 500 with a
 *       correlation id; the stack trace goes to the log, not the response.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    /** Errors the engine raises on purpose, each already carrying its status and slug. */
    @ExceptionHandler(EngineException.class)
    public ResponseEntity<Problem> handleEngine(EngineException e) {
        // Deliberate errors are expected traffic (a retried approval, an invalid definition),
        // so they are logged at debug — a 409 under concurrency is the system working.
        log.debug("engine error [{}]: {}", e.getType(), e.getMessage());
        return problem(e.status(), e.getType(), e.getMessage(), e.getDetails());
    }

    /**
     * Two approvers advanced the same request and this one lost the race after exhausting
     * its retries. A genuine conflict, and the caller should re-read and decide.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Problem> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.info("optimistic lock conflict surfaced to caller: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT.value(), "concurrent-modification",
                "another action on this request committed first; re-read the request and retry",
                Collections.<String>emptyList());
    }

    /**
     * Almost always the live-entity uniqueness guard: a second approval request for an
     * entity that already has one in flight. Reported as a conflict rather than a 500,
     * because it is a meaningful answer to the caller.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Problem> handleIntegrity(DataIntegrityViolationException e) {
        log.info("integrity violation: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT.value(), "duplicate-request",
                "this conflicts with existing state — most likely an approval request is already "
                        + "in flight for this entity and workflow",
                Collections.<String>emptyList());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Problem> handleTransition(IllegalStateTransitionException e) {
        // Reaching here means a precondition check was missed or a race slipped past the
        // optimistic lock. Worth a warning: it is either a real race or an engine bug.
        log.warn("illegal state transition: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT.value(), "illegal-transition", e.getMessage(),
                Collections.<String>emptyList());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleBeanValidation(MethodArgumentNotValidException e) {
        List<String> details = new ArrayList<String>();
        e.getBindingResult().getFieldErrors().forEach(
                error -> details.add(error.getField() + ": " + error.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(
                error -> details.add(error.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST.value(), "request-invalid",
                "request body failed validation", details);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Problem> handleMissingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST.value(), "header-required",
                "required header '" + e.getHeaderName() + "' is missing",
                Collections.<String>emptyList());
    }

    /**
     * A query or path value that does not convert — {@code ?status=BOGUS}, a non-numeric
     * {@code limit}. The caller's mistake, so it must not fall through to the 500 below.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST.value(), "parameter-invalid",
                "parameter '" + e.getName() + "' has an invalid value: " + e.getValue(),
                Collections.<String>emptyList());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Problem> handleMissingParameter(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST.value(), "parameter-required",
                "required parameter '" + e.getParameterName() + "' is missing",
                Collections.<String>emptyList());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Problem> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED.value(), "method-not-allowed",
                "method " + e.getMethod() + " is not supported on this resource",
                Collections.<String>emptyList());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Problem> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "unsupported-media-type",
                "request bodies must be sent as application/json",
                Collections.<String>emptyList());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException e) {
        // Includes unknown condition/approver "type" discriminators, which is the most
        // common authoring mistake — so the cause is surfaced rather than swallowed.
        String cause = e.getMostSpecificCause().getMessage();
        return problem(HttpStatus.BAD_REQUEST.value(), "malformed-json",
                "request body could not be parsed",
                cause == null ? Collections.<String>emptyList() : Collections.singletonList(cause));
    }

    @ExceptionHandler(JsonCodec.JsonCodecException.class)
    public ResponseEntity<Problem> handleStoredJson(JsonCodec.JsonCodecException e) {
        // JSON already in the database failed to parse — a definition edited by hand, or a
        // schema change we failed to migrate. Not the caller's fault, so it is a 500.
        log.error("stored JSON could not be parsed", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR.value(), "stored-data-corrupt",
                "stored data for this resource could not be read",
                Collections.<String>emptyList());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnexpected(Exception e) {
        String correlationId = "err_" + Long.toString(System.nanoTime(), 36);
        log.error("unhandled exception [{}]", correlationId, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal-error",
                "an unexpected error occurred; quote reference " + correlationId + " when reporting it",
                Collections.<String>emptyList());
    }

    private ResponseEntity<Problem> problem(int status, String type, String detail, List<String> details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf("application/problem+json"))
                .body(new Problem(type, status, detail, details, Instant.now()));
    }

    /** RFC 7807 problem document. */
    @Getter
    public static final class Problem {

        /** Stable machine-readable slug. Branch on this, not on {@code detail}. */
        private final String type;

        private final int status;

        /** Human-readable explanation. Wording is not part of the contract. */
        private final String detail;

        /** Field-level or item-level problems, when there are several. */
        private final List<String> errors;

        private final Instant timestamp;

        Problem(String type, int status, String detail, List<String> errors, Instant timestamp) {
            this.type = type;
            this.status = status;
            this.detail = detail;
            this.errors = errors == null || errors.isEmpty() ? null : errors;
            this.timestamp = timestamp;
        }
    }
}
