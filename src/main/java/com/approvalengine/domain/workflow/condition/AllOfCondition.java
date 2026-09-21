package com.approvalengine.domain.workflow.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logical AND over child conditions.
 *
 * <p>Evaluates <i>every</i> child even once one is false, rather than short-circuiting.
 * That is a deliberate trade of a negligible amount of work for a complete trace: an
 * auditor reading why a stage was skipped should see the outcome of each clause, not just
 * the first one that failed.
 */
public final class AllOfCondition implements Condition {

    private final List<Condition> children;

    @JsonCreator
    public AllOfCondition(@JsonProperty("children") List<Condition> children) {
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
        boolean result = true;
        for (Condition child : children) {
            ConditionTrace trace = child.evaluate(context);
            traces.add(trace);
            if (!trace.isResult()) {
                result = false;
            }
        }
        return ConditionTrace.node("allOf", result, traces);
    }

    @Override
    public void validate(String path, List<String> errors) {
        if (children.isEmpty()) {
            // An empty AND is vacuously true, which is almost certainly an authoring
            // mistake rather than an intent — reject it instead of silently applying.
            errors.add(path + ": allOf requires at least one child condition");
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).validate(path + ".children[" + i + "]", errors);
        }
    }
}
