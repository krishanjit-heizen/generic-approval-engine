package com.approvalengine.domain.workflow.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/** Logical negation of a single child condition. */
public final class NotCondition implements Condition {

    private final Condition child;

    @JsonCreator
    public NotCondition(@JsonProperty("child") Condition child) {
        this.child = child;
    }

    public Condition getChild() {
        return child;
    }

    @Override
    public ConditionTrace evaluate(EvaluationContext context) {
        if (child == null) {
            // Cannot happen for a published workflow (validate rejects it), but a
            // definition could be hand-edited in the database. Fail closed and say so
            // rather than throwing inside the executor.
            return ConditionTrace.leaf("not", false, "malformed condition: missing child");
        }
        ConditionTrace inner = child.evaluate(context);
        return ConditionTrace.node("not", !inner.isResult(), Collections.singletonList(inner));
    }

    @Override
    public void validate(String path, List<String> errors) {
        if (child == null) {
            errors.add(path + ": not requires a child condition");
            return;
        }
        child.validate(path + ".child", errors);
    }
}
