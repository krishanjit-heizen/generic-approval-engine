package com.approvalengine.domain.workflow.condition;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A serializable boolean expression over an approval request's context.
 *
 * <p>Conditions decide which stages of a workflow apply to a given request — the
 * "amount &gt; 100000 also needs a Director" logic — and they are stored as part of the
 * workflow definition JSON rather than as code, so a consuming application can author
 * them without a deployment.
 *
 * <p>Design constraints this type deliberately enforces:
 * <ul>
 *   <li><b>Closed node set.</b> Four node types, dispatched by Jackson on {@code "type"}.
 *       No expression language, so evaluating a definition cannot execute anything.</li>
 *   <li><b>Self-explaining.</b> Evaluation returns a {@link ConditionTrace}, not a bare
 *       boolean, so the audit log can record why a stage applied.</li>
 *   <li><b>Total.</b> Evaluation never throws. A malformed or absent context value yields
 *       false with a note.</li>
 *   <li><b>Validatable ahead of time.</b> {@link #validate} runs at publish, so a broken
 *       rule is rejected while someone is authoring it rather than discovered mid-approval.</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AllOfCondition.class, name = "allOf"),
        @JsonSubTypes.Type(value = AnyOfCondition.class, name = "anyOf"),
        @JsonSubTypes.Type(value = NotCondition.class, name = "not"),
        @JsonSubTypes.Type(value = CompareCondition.class, name = "compare")
})
public interface Condition {

    /**
     * Evaluates this condition and records how it got there.
     *
     * @return a trace whose {@code result} is the boolean outcome; never null, never throws
     */
    ConditionTrace evaluate(EvaluationContext context);

    /**
     * Structural validation, run when a workflow version is published.
     *
     * @param path  dotted location of this node for error messages, e.g. {@code "stages[1].condition.children[0]"}
     * @param errors accumulator; a node appends one entry per problem and does not throw
     */
    void validate(String path, List<String> errors);
}
