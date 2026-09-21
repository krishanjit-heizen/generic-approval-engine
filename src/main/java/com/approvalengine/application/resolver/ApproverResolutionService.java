package com.approvalengine.application.resolver;

import com.approvalengine.domain.workflow.ApproverSpec;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a stage's approver specs into the concrete set of people to ask.
 *
 * <p>Called at <b>stage activation</b>, not at request creation. Two reasons, and both
 * matter for correctness rather than tidiness:
 * <ul>
 *   <li>The org chart consulted is the one true at the moment approval is actually needed.
 *       A request that sits for three weeks should not go to the manager the requester had
 *       when they filed it.</li>
 *   <li>Resolution results are then snapshotted into task rows, so a directory change after
 *       activation cannot retroactively alter a stage already in flight. Late resolution and
 *       immediate snapshotting are two halves of one decision.</li>
 * </ul>
 */
@Service
@Slf4j
public class ApproverResolutionService {

    private final Map<ApproverSpec.Type, ApproverResolver> resolvers =
            new EnumMap<ApproverSpec.Type, ApproverResolver>(ApproverSpec.Type.class);

    public ApproverResolutionService(List<ApproverResolver> availableResolvers) {
        for (ApproverResolver resolver : availableResolvers) {
            ApproverResolver previous = resolvers.put(resolver.supportedType(), resolver);
            if (previous != null) {
                // Two beans claiming one type means resolution would depend on bean ordering.
                // Fail at startup rather than resolve approvers non-deterministically.
                throw new IllegalStateException("two resolvers registered for approver type "
                        + resolver.supportedType() + ": " + previous.getClass().getName()
                        + " and " + resolver.getClass().getName());
            }
        }
        log.info("registered {} approver resolvers: {}", resolvers.size(), resolvers.keySet());
    }

    /**
     * Resolves every approver spec on the stage, applying each spec's {@code onUnresolved}
     * policy when it comes back empty.
     *
     * <p>Specs are additive: a stage listing {@code ROLE(FINANCE)} and {@code ROLE(COMPLIANCE)}
     * asks everyone in both. The results are de-duplicated while preserving order, because
     * one person holding two of the named roles should get one task, not two — and because
     * the database's uniqueness constraint on (stage, approver) would otherwise reject the
     * second insert and fail the whole activation.
     */
    public StageResolution resolveFor(StageDefinition stage, EvaluationContext context) {
        List<String> notes = new ArrayList<String>();
        List<ResolvedApprover> collected = new ArrayList<ResolvedApprover>();
        Set<String> seen = new LinkedHashSet<String>();

        for (int i = 0; i < stage.getApprovers().size(); i++) {
            ApproverSpec spec = stage.getApprovers().get(i);
            SpecOutcome outcome = resolveSpec(spec, context, notes, 0);

            if (outcome.terminal != null) {
                // A spec's fallback chain ended in SKIP_STAGE or FAIL_REQUEST. That decision
                // is about the stage as a whole, so it wins immediately.
                return outcome.terminal;
            }
            for (ResolvedApprover approver : outcome.approvers) {
                if (seen.add(approver.getApproverId())) {
                    collected.add(approver);
                } else {
                    notes.add("duplicate approver " + approver.getApproverId()
                            + " from " + approver.getResolvedVia() + " — already asked, not duplicated");
                }
            }
        }

        if (collected.isEmpty()) {
            // Every spec resolved empty and none declared an onUnresolved policy. Defaulting
            // to FAIL_REQUEST rather than skipping: a stage the author put there for a reason
            // must not vanish silently, and NEEDS_ATTENTION is recoverable.
            notes.add("no approver spec on this stage resolved to anyone");
            return StageResolution.failRequest(
                    "stage '" + stage.getId() + "' resolved to no approvers", notes);
        }
        return StageResolution.resolved(collected, notes);
    }

    /**
     * Resolves one spec, walking its {@code onUnresolved} chain if it comes back empty.
     *
     * @param depth guards a definition whose fallbacks chain further than validation allowed
     */
    private SpecOutcome resolveSpec(ApproverSpec spec, EvaluationContext context,
                                    List<String> notes, int depth) {
        if (depth > ApproverSpec.MAX_FALLBACK_DEPTH) {
            notes.add("fallback chain exceeded " + ApproverSpec.MAX_FALLBACK_DEPTH + " levels");
            return SpecOutcome.terminal(StageResolution.failRequest(
                    "approver fallback chain too deep", notes));
        }

        ApproverResolver resolver = resolvers.get(spec.getType());
        if (resolver == null) {
            // Publish-time validation rejects unknown types, so reaching here means a
            // definition was written before a resolver was removed, or edited in the
            // database. Park it for a human rather than pretend nobody needed to approve.
            notes.add("no resolver registered for approver type " + spec.getType());
            return SpecOutcome.terminal(StageResolution.failRequest(
                    "unsupported approver type " + spec.getType(), notes));
        }

        List<ResolvedApprover> found;
        try {
            found = resolver.resolve(spec, context);
        } catch (RuntimeException e) {
            // A resolver is not supposed to throw, but a directory adapter talking to a real
            // system might. Treat it as "resolved nobody" so the declared fallback policy
            // still gets its say, and record the cause.
            log.warn("resolver for {} threw: {}", spec.describe(), e.getMessage());
            notes.add(spec.describe() + " failed: " + e.getMessage());
            found = java.util.Collections.emptyList();
        }

        if (found != null && !found.isEmpty()) {
            notes.add(spec.describe() + " → " + found.size() + " approver(s)");
            return SpecOutcome.of(found);
        }

        notes.add(spec.describe() + " → nobody");
        ApproverSpec.UnresolvedPolicy policy = spec.getOnUnresolved();
        if (policy == null) {
            // No policy on this spec. Contribute nothing and let the other specs on the
            // stage stand; if they all come back empty, resolveFor fails the request.
            return SpecOutcome.of(java.util.Collections.<ResolvedApprover>emptyList());
        }

        switch (policy.getAction()) {
            case FALLBACK_TO:
                notes.add("falling back to " + (policy.getSpec() == null ? "nothing" : policy.getSpec().describe()));
                if (policy.getSpec() == null) {
                    return SpecOutcome.terminal(StageResolution.failRequest(
                            "onUnresolved FALLBACK_TO has no spec", notes));
                }
                return resolveSpec(policy.getSpec(), context, notes, depth + 1);

            case SKIP_STAGE:
                return SpecOutcome.terminal(StageResolution.skipStage(
                        spec.describe() + " resolved to nobody; stage declared SKIP_STAGE", notes));

            case FAIL_REQUEST:
            default:
                return SpecOutcome.terminal(StageResolution.failRequest(
                        spec.describe() + " resolved to nobody", notes));
        }
    }

    /** Either some approvers, or a stage-wide decision that short-circuits the rest. */
    private static final class SpecOutcome {
        private final List<ResolvedApprover> approvers;
        private final StageResolution terminal;

        private SpecOutcome(List<ResolvedApprover> approvers, StageResolution terminal) {
            this.approvers = approvers;
            this.terminal = terminal;
        }

        static SpecOutcome of(List<ResolvedApprover> approvers) {
            return new SpecOutcome(approvers, null);
        }

        static SpecOutcome terminal(StageResolution resolution) {
            return new SpecOutcome(java.util.Collections.<ResolvedApprover>emptyList(), resolution);
        }
    }
}
