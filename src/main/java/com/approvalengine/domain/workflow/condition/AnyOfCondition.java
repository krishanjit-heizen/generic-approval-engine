package com.approvalengine.domain.workflow.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logical OR over child conditions.
 *
 * <p>Like {@link AllOfCondition}, evaluates every child rather than short-circuiting, so
 * the trace shows which clauses matched and which did not.
 */
public final class AnyOfCondition implements Condition {

    private final List<Condition> children;

    @JsonCreator
    public AnyOfCondition(@JsonProperty("children") List<Condition> children) {
        this.children = children == null
                ? Collections.<Condition>emptyList()
                : Collections.unmodifiableList(new ArrayList<Condition>(children));
    }

    public List<Condition> getChildren() {
        return children;
    }

    @Override
    public ConditionTrace evaluate(EvaluationContext context) {
        List<ConditionTrace> traces = new ArrayList<ConditionTrace>(children.size());
        boolean result = false;
        for (Condition child : children) {
            ConditionTrace trace = child.evaluate(context);
            traces.add(trace);
            if (trace.isResult()) {
                result = true;
            }
        }
        return ConditionTrace.node("anyOf", result, traces);
    }

    @Override
    public void validate(String path, List<String> errors) {
        if (children.isEmpty()) {
            // An empty OR is vacuously false, which would silently disable a stage.
            errors.add(path + ": anyOf requires at least one child condition");
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).validate(path + ".children[" + i + "]", errors);
        }
    }
}
