package com.approvalengine.domain.workflow.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The only leaf condition: compares one value from the request context against a literal.
 *
 * <pre>{@code { "type": "compare", "path": "amount", "op": "GT", "value": 100000 }}</pre>
 *
 * <p>{@code path} is a dotted read into the submitted context; {@code value} is a JSON
 * literal held as-is. Nothing here is interpreted as an expression.
 */
public final class CompareCondition implements Condition {

    private final String path;
    private final ComparisonOperator op;
    private final Object value;

    @JsonCreator
    public CompareCondition(@JsonProperty("path") String path,
                            @JsonProperty("op") ComparisonOperator op,
                            @JsonProperty("value") Object value) {
        this.path = path;
        this.op = op;
        this.value = value;
    }

    public String getPath() {
        return path;
    }

    public ComparisonOperator getOp() {
        return op;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public ConditionTrace evaluate(EvaluationContext context) {
        if (path == null || op == null) {
            return ConditionTrace.leaf("compare", false, "malformed condition: path and op are required");
        }

        Optional<Object> resolved = context.resolve(path);

        if (!resolved.isPresent() && !op.toleratesMissingValue()) {
            // Fail closed, but say why. A stage silently not applying because the
            // consuming application renamed a context field is exactly the kind of bug
            // that is invisible without this note in the audit trail.
            return ConditionTrace.leaf(describe(null), false,
                    "path '" + path + "' is absent from the request context");
        }

        Object actual = resolved.orElse(null);
        boolean result = op.apply(actual, value);
        return ConditionTrace.leaf(describe(actual), result);
    }

    @Override
    public void validate(String path, List<String> errors) {
        if (this.path == null || this.path.trim().isEmpty()) {
            errors.add(path + ": compare requires a non-empty 'path'");
        }
        if (this.op == null) {
            errors.add(path + ": compare requires an 'op' (one of EQ, NEQ, GT, GTE, LT, LTE, IN, CONTAINS, EXISTS)");
        }
        if (this.op == ComparisonOperator.IN && !(this.value instanceof Collection)) {
            // IN against a scalar would always be false — catch it while authoring.
            errors.add(path + ": op IN requires 'value' to be a list");
        }
        if (this.op != null && this.op != ComparisonOperator.EXISTS && this.value == null) {
            errors.add(path + ": op " + this.op + " requires a 'value'");
        }
    }

    /** Renders {@code amount(25000) > 100000} for the trace. */
    private String describe(Object actual) {
        StringBuilder sb = new StringBuilder(path);
        if (op != ComparisonOperator.EXISTS) {
            sb.append('(').append(actual == null ? "absent" : actual).append(')');
            sb.append(' ').append(op.symbol()).append(' ').append(render(value));
        } else {
            sb.append(' ').append(op.symbol());
        }
        return sb.toString();
    }

    private static String render(Object v) {
        if (v instanceof CharSequence) {
            return "\"" + v + "\"";
        }
        return String.valueOf(v);
    }
}
