package com.approvalengine.domain.workflow;

import com.approvalengine.domain.workflow.condition.Condition;
import com.approvalengine.domain.workflow.condition.ConditionTrace;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One step of an approval workflow: a condition deciding whether it applies, the approvers
 * to ask, and the policies governing completion, rejection and timeout.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class StageDefinition {

    /** How the approvers within this one stage are asked. */
    public enum Mode {
        /**
         * Approvers are asked one at a time, in declaration order — the next is only asked
         * once the previous has approved.
         */
        SEQUENTIAL,
        /** Every approver is asked at once. */
        PARALLEL
    }

    /** What happens to the request when an approver rejects at this stage. */
    public enum RejectAction {
        /** End the request as REJECTED. The default, and what almost every workflow wants. */
        TERMINATE,
        /**
         * Record the rejection and carry on to the next stage. For advisory stages where a
         * dissent should be visible in the audit trail without blocking.
         */
        CONTINUE,
        /** Jump back to an earlier stage — e.g. return to the requester's manager for rework. */
        GOTO
    }

    /** What happens when an approver never responds and the stage's SLA elapses. */
    public enum TimeoutAction {
        /** Reassign to the unresponsive approver's manager. */
        ESCALATE,
        /** Treat silence as consent. Applied as a SYSTEM actor, and fully audited. */
        AUTO_APPROVE,
        /** Treat silence as refusal. */
        AUTO_REJECT,
        /** Emit a reminder and keep waiting. */
        NOTIFY_ONLY
    }

    private final String id;
    private final String name;
    private final Condition condition;
    private final Mode mode;
    private final List<ApproverSpec> approvers;
    private final CompletionPolicy policy;
    private final RejectAction onReject;
    private final String onRejectGoto;
    private final Long slaSeconds;
    private final TimeoutAction onTimeout;

    @JsonCreator
    public StageDefinition(@JsonProperty("id") String id,
                           @JsonProperty("name") String name,
                           @JsonProperty("condition") Condition condition,
                           @JsonProperty("mode") Mode mode,
                           @JsonProperty("approvers") List<ApproverSpec> approvers,
                           @JsonProperty("policy") CompletionPolicy policy,
                           @JsonProperty("onReject") RejectAction onReject,
                           @JsonProperty("onRejectGoto") String onRejectGoto,
                           @JsonProperty("slaSeconds") Long slaSeconds,
                           @JsonProperty("onTimeout") TimeoutAction onTimeout) {
        this.id = id;
        this.name = name;
        this.condition = condition;
        this.mode = mode == null ? Mode.SEQUENTIAL : mode;
        this.approvers = approvers == null
                ? Collections.<ApproverSpec>emptyList()
                : Collections.unmodifiableList(new ArrayList<ApproverSpec>(approvers));
        this.policy = policy == null ? CompletionPolicy.allOf() : policy;
        this.onReject = onReject == null ? RejectAction.TERMINATE : onReject;
        this.onRejectGoto = onRejectGoto;
        this.slaSeconds = slaSeconds;
        this.onTimeout = onTimeout == null ? TimeoutAction.NOTIFY_ONLY : onTimeout;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Condition getCondition() {
        return condition;
    }

    public Mode getMode() {
        return mode;
    }

    public List<ApproverSpec> getApprovers() {
        return approvers;
    }

    public CompletionPolicy getPolicy() {
        return policy;
    }

    public RejectAction getOnReject() {
        return onReject;
    }

    public String getOnRejectGoto() {
        return onRejectGoto;
    }

    public Long getSlaSeconds() {
        return slaSeconds;
    }

    public TimeoutAction getOnTimeout() {
        return onTimeout;
    }

    /** Display name for the inbox and audit trail, falling back to the stage id. */
    public String displayName() {
        return name == null || name.trim().isEmpty() ? id : name;
    }

    /**
     * Decides whether this stage applies to a request, and records why.
     *
     * <p>A stage with no condition always applies. A stage whose condition is false is
     * recorded as SKIPPED with this trace attached — it is never dropped silently, because
     * "what did the engine consider and reject" is part of the audit story.
     */
    public ConditionTrace appliesTo(EvaluationContext context) {
        if (condition == null) {
            return ConditionTrace.unconditional();
        }
        return condition.evaluate(context);
    }

    void validate(String errorPath, List<String> errors) {
        if (id == null || id.trim().isEmpty()) {
            errors.add(errorPath + ": stage requires an 'id'");
        }
        if (approvers.isEmpty()) {
            errors.add(errorPath + ": stage requires at least one approver");
        }
        for (int i = 0; i < approvers.size(); i++) {
            approvers.get(i).validate(errorPath + ".approvers[" + i + "]", errors);
        }
        if (condition != null) {
            condition.validate(errorPath + ".condition", errors);
        }
        policy.validate(errorPath + ".policy", errors);

        if (onReject == RejectAction.GOTO && (onRejectGoto == null || onRejectGoto.trim().isEmpty())) {
            errors.add(errorPath + ": onReject GOTO requires 'onRejectGoto' naming a stage");
        }
        if (slaSeconds != null && slaSeconds.longValue() <= 0) {
            errors.add(errorPath + ": 'slaSeconds' must be positive when present");
        }
        if (onTimeout != TimeoutAction.NOTIFY_ONLY && slaSeconds == null) {
            // An onTimeout with no SLA can never fire. Reject it rather than let an author
            // believe an escalation is configured when nothing will ever trigger it.
            errors.add(errorPath + ": onTimeout " + onTimeout + " requires 'slaSeconds'");
        }
    }
}
