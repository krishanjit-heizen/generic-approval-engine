package com.approvalengine.domain.workflow.condition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;

/**
 * The record of *why* a condition produced the result it did.
 *
 * <p>This is the difference between an approval trail that says "the Director stage was
 * added" and one that says "the Director stage was added because amount(250000) GT 100000
 * was true". Traces are persisted onto the stage instance and into the audit log, and
 * surfaced on the API as the {@code reason} on each planned stage.
 *
 * <p>Immutable: a trace describes a past evaluation and must never be edited.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ConditionTrace {

    private final String expression;
    private final boolean result;
    private final String note;
    private final List<ConditionTrace> children;

    private ConditionTrace(String expression, boolean result, String note, List<ConditionTrace> children) {
        this.expression = expression;
        this.result = result;
        this.note = note;
        this.children = children == null || children.isEmpty()
                ? null
                : Collections.unmodifiableList(children);
    }

    /** A terminal evaluation, e.g. a single comparison. */
    public static ConditionTrace leaf(String expression, boolean result) {
        return new ConditionTrace(expression, result, null, null);
    }

    /**
     * A terminal evaluation that needs explaining — most often a path the consuming
     * application did not supply. Recording the note is what stops a silently-false rule
     * from looking like a deliberate one.
     */
    public static ConditionTrace leaf(String expression, boolean result, String note) {
        return new ConditionTrace(expression, result, note, null);
    }

    /** A composite evaluation (allOf / anyOf / not) over child traces. */
    public static ConditionTrace node(String expression, boolean result, List<ConditionTrace> children) {
        return new ConditionTrace(expression, result, null, children);
    }

    /** Used when a stage declares no condition at all, so it always applies. */
    public static ConditionTrace unconditional() {
        return new ConditionTrace("no condition", true, null, null);
    }

    public String getExpression() {
        return expression;
    }

    public boolean isResult() {
        return result;
    }

    public String getNote() {
        return note;
    }

    public List<ConditionTrace> getChildren() {
        return children;
    }

    /**
     * One-line human summary, used as the {@code reason} field on API responses.
     * Deliberately shallow — the full tree is available in the persisted trace for anyone
     * who needs to audit a complex rule.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder(expression).append(" → ").append(result);
        if (note != null) {
            sb.append(" (").append(note).append(')');
        }
        return sb.toString();
    }
}
