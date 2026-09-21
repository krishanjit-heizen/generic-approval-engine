package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.workflow.ApproverSpec;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * Re-checks, at the moment of acting, that an approver is still entitled to.
 *
 * <p>The client tells the engine <i>who</i> is acting and nothing more. It never sends roles —
 * a caller that could assert its own entitlements could assert its way into any approval. So
 * the engine takes the user id, looks the entitlements up in the directory itself, and checks.
 *
 * <p>What this catches that the ownership check alone does not: approvers are resolved and
 * snapshotted when a stage activates, and a stage can sit for weeks. Someone assigned via
 * {@code ROLE(FINANCE)} may have left Finance in the meantime. Holding a task is evidence they
 * were <i>asked</i>; it is not evidence they are <i>still</i> the right person. Without this,
 * the engine would happily accept a Finance sign-off from someone who moved to Marketing a
 * month ago — and the audit trail would record it as a valid Finance approval.
 *
 * <p>Only membership-derived assignments are re-checked. A task assigned to a named individual,
 * to the requester's manager, or from request context was never contingent on a role, so
 * re-checking one would be inventing a rule the workflow never stated.
 */
@Service
@Slf4j
public class ApproverEligibilityService {

    private final DirectoryService directory;

    public ApproverEligibilityService(DirectoryService directory) {
        this.directory = directory;
    }

    /**
     * @return whether the actor may still act, and why not if they may not
     */
    @Transactional(readOnly = true)
    public Eligibility check(ApprovalTaskEntity task, String actorId) {
        // The user must still exist and be active at all. A deactivated account holding a
        // pending task must not be able to sign anything off.
        if (!directory.userExists(actorId)) {
            return Eligibility.denied("approver-inactive",
                    "user " + actorId + " is unknown or no longer active in the directory");
        }

        Optional<Derived> derived = parseAssignment(task.getResolvedVia());
        if (!derived.isPresent()) {
            // USER / MANAGER_OF / CONTEXT_PATH / EXTERNAL_HTTP / escalation — not
            // membership-derived, so there is nothing to re-verify.
            return Eligibility.allowed();
        }

        Derived assignment = derived.get();
        switch (assignment.type) {
            case ROLE: {
                Set<String> roles = directory.rolesOf(actorId);
                if (!roles.contains(assignment.target)) {
                    return Eligibility.denied("approver-no-longer-eligible",
                            "this task was assigned via role '" + assignment.target
                                    + "', which " + actorId + " no longer holds");
                }
                return Eligibility.allowed();
            }
            case TEAM: {
                Set<String> teams = directory.teamsOf(actorId);
                if (!teams.contains(assignment.target)) {
                    return Eligibility.denied("approver-no-longer-eligible",
                            "this task was assigned via team '" + assignment.target
                                    + "', which " + actorId + " is no longer a member of");
                }
                return Eligibility.allowed();
            }
            case DEPARTMENT: {
                Optional<String> department = directory.departmentOf(actorId);
                if (!department.isPresent() || !department.get().equals(assignment.target)) {
                    return Eligibility.denied("approver-no-longer-eligible",
                            "this task was assigned via department '" + assignment.target
                                    + "', which " + actorId + " is no longer in");
                }
                return Eligibility.allowed();
            }
            default:
                return Eligibility.allowed();
        }
    }

    /**
     * Recovers the grouping from the provenance string recorded on the task, e.g.
     * {@code ROLE(FINANCE)} → (ROLE, FINANCE).
     *
     * <p>Parsing the stored provenance rather than re-reading the workflow definition is
     * deliberate: the definition can describe several approver specs per stage, and what matters
     * is the one that actually produced <i>this</i> task. The task row is the only place that is
     * recorded — which is why {@code resolved_via} was persisted in the first place.
     */
    private Optional<Derived> parseAssignment(String resolvedVia) {
        if (resolvedVia == null) {
            return Optional.empty();
        }
        int open = resolvedVia.indexOf('(');
        if (open < 1 || !resolvedVia.endsWith(")")) {
            return Optional.empty();
        }
        String typeName = resolvedVia.substring(0, open);
        String target = resolvedVia.substring(open + 1, resolvedVia.length() - 1);

        ApproverSpec.Type type;
        try {
            type = ApproverSpec.Type.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            // ESCALATED_FROM(...) and anything else not a spec type.
            return Optional.empty();
        }
        if (type != ApproverSpec.Type.ROLE && type != ApproverSpec.Type.TEAM
                && type != ApproverSpec.Type.DEPARTMENT) {
            return Optional.empty();
        }
        return Optional.of(new Derived(type, target));
    }

    private static final class Derived {
        private final ApproverSpec.Type type;
        private final String target;

        Derived(ApproverSpec.Type type, String target) {
            this.type = type;
            this.target = target;
        }
    }

    /** The verdict, with a machine-readable reason when refused. */
    @Getter
    public static final class Eligibility {
        private final boolean allowed;
        private final String errorType;
        private final String reason;

        private Eligibility(boolean allowed, String errorType, String reason) {
            this.allowed = allowed;
            this.errorType = errorType;
            this.reason = reason;
        }

        static Eligibility allowed() {
            return new Eligibility(true, null, null);
        }

        static Eligibility denied(String errorType, String reason) {
            return new Eligibility(false, errorType, reason);
        }
    }
}
