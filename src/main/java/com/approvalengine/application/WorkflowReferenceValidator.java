package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.WorkflowApproverRuleEntity;
import com.approvalengine.adapter.persistence.repository.WorkflowApproverRuleRepository;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.workflow.ApproverSpec;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.WorkflowDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that every role, team, department and user a workflow names actually exists — and
 * projects its approver rules into queryable rows.
 *
 * <p>Two jobs, deliberately together, because both walk the same tree and both only make sense
 * once roles are real entities rather than opaque strings.
 *
 * <p><b>Why validate at publish.</b> Without this, a workflow routing to {@code ROLE('FINACE')}
 * publishes happily, and the typo surfaces weeks later when a live request activates that stage
 * and parks in NEEDS_ATTENTION. An operator then has to work out whether the role was deleted,
 * misspelled, or never existed. Catching it while the author is still looking at the definition
 * turns a production incident into a 422.
 *
 * <p><b>Why this cannot live in {@code WorkflowDocument.validate()}.</b> That is a domain object
 * with no database access, and keeping it that way is what makes the condition tree and the
 * definition model testable and reusable in isolation. Structural validation ("this stage has no
 * approvers") belongs there; referential validation ("this role does not exist") needs the
 * directory, so it belongs here.
 */
@Service
@Slf4j
public class WorkflowReferenceValidator {

    private final DirectoryService directory;
    private final WorkflowApproverRuleRepository rules;

    public WorkflowReferenceValidator(DirectoryService directory,
                                      WorkflowApproverRuleRepository rules) {
        this.directory = directory;
        this.rules = rules;
    }

    /**
     * Validates every approver reference in the document.
     *
     * @return one message per problem, in document order; empty means publishable
     */
    @Transactional(readOnly = true)
    public List<String> validateReferences(WorkflowDocument document) {
        List<String> errors = new ArrayList<String>();
        for (int i = 0; i < document.getStages().size(); i++) {
            StageDefinition stage = document.getStages().get(i);
            for (int j = 0; j < stage.getApprovers().size(); j++) {
                validateSpec(stage.getApprovers().get(j),
                        "stages[" + i + "].approvers[" + j + "]", errors, 0);
            }
        }
        return errors;
    }

    /** Walks a spec and its {@code onUnresolved} fallback chain. */
    private void validateSpec(ApproverSpec spec, String path, List<String> errors, int depth) {
        if (spec == null || spec.getType() == null || depth > ApproverSpec.MAX_FALLBACK_DEPTH) {
            return;
        }

        switch (spec.getType()) {
            case ROLE:
                if (!directory.roleExists(spec.getRole())) {
                    errors.add(path + ": role '" + spec.getRole()
                            + "' does not exist or is inactive");
                }
                break;
            case TEAM:
                if (!directory.teamExists(spec.getTeam())) {
                    errors.add(path + ": team '" + spec.getTeam()
                            + "' does not exist or is inactive");
                }
                break;
            case DEPARTMENT:
                if (!directory.departmentExists(spec.getDepartment())) {
                    errors.add(path + ": department '" + spec.getDepartment()
                            + "' does not exist or is inactive");
                }
                break;
            case USER:
                if (!directory.userExists(spec.getUserId())) {
                    errors.add(path + ": user '" + spec.getUserId()
                            + "' does not exist or is inactive");
                }
                break;
            case MANAGER_OF:
                // A literal user id can be checked now. $requestedBy and context.* paths are
                // per-request values, so they cannot be — a MANAGER_OF that resolves to nobody
                // at runtime is what the onUnresolved chain is for.
                String of = spec.getOf();
                if (of != null && !ApproverSpec.REQUESTER_TOKEN.equals(of)
                        && !of.startsWith("context.") && !directory.userExists(of)) {
                    errors.add(path + ": MANAGER_OF references user '" + of
                            + "', which does not exist or is inactive");
                }
                break;
            case CONTEXT_PATH:
            case EXTERNAL_HTTP:
            default:
                // Resolved per request from data the engine does not have at publish time.
                break;
        }

        ApproverSpec.UnresolvedPolicy policy = spec.getOnUnresolved();
        if (policy != null && policy.getSpec() != null) {
            validateSpec(policy.getSpec(), path + ".onUnresolved.spec", errors, depth + 1);
        }
    }

    /**
     * Projects the document's approver rules into rows for the published version.
     *
     * <p>Idempotent: existing rows for this definition are cleared first, so re-publishing (or
     * a retried deploy step) cannot produce duplicates.
     */
    @Transactional
    public int projectRules(String workflowDefId, WorkflowDocument document) {
        rules.deleteByWorkflowDefId(workflowDefId);

        List<WorkflowApproverRuleEntity> projected = new ArrayList<WorkflowApproverRuleEntity>();
        for (int stageIndex = 0; stageIndex < document.getStages().size(); stageIndex++) {
            StageDefinition stage = document.getStages().get(stageIndex);
            for (int ordinal = 0; ordinal < stage.getApprovers().size(); ordinal++) {
                collect(projected, workflowDefId, stage.getId(), stageIndex, ordinal,
                        stage.getApprovers().get(ordinal), 0);
            }
        }
        rules.saveAll(projected);
        log.debug("projected {} approver rule(s) for workflow definition {}",
                Integer.valueOf(projected.size()), workflowDefId);
        return projected.size();
    }

    /** Flattens a spec and its fallback chain into rows, recording the chain depth. */
    private void collect(List<WorkflowApproverRuleEntity> out, String workflowDefId, String stageId,
                         int stageIndex, int ordinal, ApproverSpec spec, int depth) {
        if (spec == null || spec.getType() == null || depth > ApproverSpec.MAX_FALLBACK_DEPTH) {
            return;
        }
        out.add(WorkflowApproverRuleEntity.builder()
                .id(Ids.newId("rule"))
                .workflowDefId(workflowDefId)
                .stageId(stageId)
                .stageIndex(stageIndex)
                .ordinal(ordinal)
                .fallbackDepth(depth)
                .approverType(spec.getType())
                .target(targetOf(spec))
                .build());

        ApproverSpec.UnresolvedPolicy policy = spec.getOnUnresolved();
        if (policy != null && policy.getSpec() != null) {
            collect(out, workflowDefId, stageId, stageIndex, ordinal, policy.getSpec(), depth + 1);
        }
    }

    /** The single meaningful target for a spec, whatever its type calls it. */
    private static String targetOf(ApproverSpec spec) {
        switch (spec.getType()) {
            case USER:          return spec.getUserId();
            case ROLE:          return spec.getRole();
            case TEAM:          return spec.getTeam();
            case DEPARTMENT:    return spec.getDepartment();
            case MANAGER_OF:    return spec.getOf();
            case CONTEXT_PATH:  return spec.getPath();
            case EXTERNAL_HTTP: return spec.getUrl();
            default:            return null;
        }
    }
}
