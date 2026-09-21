package com.approvalengine.domain.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * When is a stage's set of approval tasks considered complete?
 *
 * <pre>{@code
 * { "type": "ALL" }                 every approver must approve
 * { "type": "ANY" }                 the first approval carries the stage
 * { "type": "QUORUM", "count": 2 }  any 2 approvals carry the stage
 * }</pre>
 *
 * <p>This is evaluated by re-counting sibling task rows inside the transaction that
 * advances the stage — never from a cached tally — which is what makes two simultaneous
 * approvals impossible to double-count.
 */
public final class CompletionPolicy {

    public enum Type {
        /** Unanimous: every resolved approver must approve. */
        ALL,
        /** First approval wins. */
        ANY,
        /** A fixed number of approvals, regardless of how many approvers were resolved. */
        QUORUM
    }

    private static final CompletionPolicy ALL_POLICY = new CompletionPolicy(Type.ALL, null);

    private final Type type;
    private final Integer count;

    @JsonCreator
    public CompletionPolicy(@JsonProperty("type") Type type,
                            @JsonProperty("count") Integer count) {
        this.type = type == null ? Type.ALL : type;
        this.count = count;
    }

    /** The default when a stage omits its policy: unanimous approval. */
    public static CompletionPolicy allOf() {
        return ALL_POLICY;
    }

    public Type getType() {
        return type;
    }

    public Integer getCount() {
        return count;
    }

    /**
     * How many approvals this stage needs, given how many approvers were actually resolved.
     *
     * <p>A QUORUM larger than the resolved approver count is clamped down, so a stage
     * cannot become unsatisfiable just because the directory returned fewer people than
     * the workflow author expected. That situation is reported in the audit trail by the
     * resolver, not silently absorbed here.
     */
    public int requiredApprovals(int resolvedApproverCount) {
        switch (type) {
            case ANY:
                return resolvedApproverCount == 0 ? 0 : 1;
            case QUORUM:
                int wanted = count == null ? 1 : count.intValue();
                return Math.min(Math.max(wanted, 1), resolvedApproverCount);
            case ALL:
            default:
                return resolvedApproverCount;
        }
    }

    public void validate(String path, List<String> errors) {
        if (type == Type.QUORUM) {
            if (count == null) {
                errors.add(path + ": policy QUORUM requires a 'count'");
            } else if (count.intValue() < 1) {
                errors.add(path + ": policy QUORUM 'count' must be at least 1");
            }
        }
    }

    @Override
    public String toString() {
        return type == Type.QUORUM ? "QUORUM(" + count + ")" : type.name();
    }
}
