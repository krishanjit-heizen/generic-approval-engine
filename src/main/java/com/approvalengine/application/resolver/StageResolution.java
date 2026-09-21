package com.approvalengine.application.resolver;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of resolving every approver spec on one stage.
 *
 * <p>Resolution has three possible endings, not two, and modelling that explicitly is what
 * keeps "the approver is unavailable" an auditable decision rather than an exception:
 *
 * <ul>
 *   <li>{@link Outcome#RESOLVED} — at least one approver was found; create their tasks.</li>
 *   <li>{@link Outcome#SKIP_STAGE} — nobody was found, and the stage declared that as
 *       acceptable. The stage is recorded SKIPPED, with the reason, and execution continues.</li>
 *   <li>{@link Outcome#FAIL_REQUEST} — nobody was found and the stage requires someone. The
 *       request parks in NEEDS_ATTENTION for an operator to reassign.</li>
 * </ul>
 *
 * <p>{@code notes} accumulates what happened along the way — which specs came back empty,
 * which fallbacks fired — and is written into the audit trail. Without it, a request that
 * silently skipped a Finance approval would be indistinguishable from one where Finance was
 * never configured.
 */
@Getter
public final class StageResolution {

    public enum Outcome {
        RESOLVED,
        SKIP_STAGE,
        FAIL_REQUEST
    }

    private final Outcome outcome;
    private final List<ResolvedApprover> approvers;
    private final String reason;
    private final List<String> notes;

    private StageResolution(Outcome outcome, List<ResolvedApprover> approvers,
                            String reason, List<String> notes) {
        this.outcome = outcome;
        this.approvers = approvers == null
                ? Collections.<ResolvedApprover>emptyList()
                : Collections.unmodifiableList(new ArrayList<ResolvedApprover>(approvers));
        this.reason = reason;
        this.notes = notes == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(notes));
    }

    public static StageResolution resolved(List<ResolvedApprover> approvers, List<String> notes) {
        return new StageResolution(Outcome.RESOLVED, approvers, null, notes);
    }

    public static StageResolution skipStage(String reason, List<String> notes) {
        return new StageResolution(Outcome.SKIP_STAGE, null, reason, notes);
    }

    public static StageResolution failRequest(String reason, List<String> notes) {
        return new StageResolution(Outcome.FAIL_REQUEST, null, reason, notes);
    }

    public boolean isResolved() {
        return outcome == Outcome.RESOLVED;
    }
}
