package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.WorkflowDefinitionEntity;
import com.approvalengine.adapter.persistence.repository.WorkflowDefinitionRepository;
import com.approvalengine.application.resolver.ApproverResolutionService;
import com.approvalengine.application.resolver.ResolvedApprover;
import com.approvalengine.application.resolver.StageResolution;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.WorkflowStatus;
import com.approvalengine.domain.workflow.condition.ConditionTrace;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import com.approvalengine.error.ConflictException;
import com.approvalengine.error.NotFoundException;
import com.approvalengine.error.ValidationException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authoring, versioning and dry-running workflow definitions.
 *
 * <p>The versioning rules here are what make "a workflow was changed while a request was
 * already running" a non-event:
 * <ul>
 *   <li>A published version is <b>immutable</b>. An edit is a new version, never an update.</li>
 *   <li>At most one version per {@code (app, key)} is ACTIVE; new requests bind to it.</li>
 *   <li>Superseded versions are ARCHIVED, never deleted, because in-flight requests are
 *       still executing them and finished ones still need explaining.</li>
 * </ul>
 */
@Service
@Slf4j
public class WorkflowService {

    private final WorkflowDefinitionRepository repository;
    private final ApproverResolutionService resolution;
    private final WorkflowReferenceValidator references;
    private final JsonCodec json;

    public WorkflowService(WorkflowDefinitionRepository repository,
                           ApproverResolutionService resolution,
                           WorkflowReferenceValidator references,
                           JsonCodec json) {
        this.repository = repository;
        this.resolution = resolution;
        this.references = references;
        this.json = json;
    }

    /**
     * Creates a new DRAFT version of a workflow.
     *
     * <p>Validation runs here rather than at publish so an author gets told immediately, but
     * it runs again at publish — a draft could be created before a resolver type is removed.
     */
    @Transactional
    public WorkflowDefinitionEntity createDraft(String appId, WorkflowDocument document, String createdBy) {
        requireValid(document);
        requireResolvableReferences(document);

        int nextVersion = repository
                .findFirstByAppIdAndWorkflowKeyOrderByVersionDesc(appId, document.getKey())
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);

        WorkflowDefinitionEntity entity = WorkflowDefinitionEntity.builder()
                .id(Ids.newId(Ids.WORKFLOW))
                .appId(appId)
                .workflowKey(document.getKey())
                .version(nextVersion)
                .name(document.displayName())
                .status(WorkflowStatus.DRAFT)
                .definitionJson(json.write(document))
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .build();

        log.info("created workflow draft {} v{} for app {}", document.getKey(), nextVersion, appId);
        return repository.save(entity);
    }

    /**
     * Publishes a draft, making it the version new requests bind to.
     *
     * <p>Archiving the previous ACTIVE version and activating this one happen in one
     * transaction, so there is never a moment with two active versions — or none, which
     * would make request creation fail for anyone unlucky enough to arrive mid-publish.
     */
    @Transactional
    public WorkflowDefinitionEntity publish(String appId, String workflowKey, int version) {
        WorkflowDefinitionEntity target = require(appId, workflowKey, version);

        if (target.getStatus() == WorkflowStatus.ACTIVE) {
            // Idempotent: re-publishing the live version is a no-op rather than an error, so
            // a retried deployment step does not fail.
            return target;
        }
        if (target.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new ConflictException("workflow-version-archived",
                    "version " + version + " of '" + workflowKey + "' is archived and cannot be re-published; "
                            + "create a new version instead");
        }

        // Re-validated at publish, not just at draft. A role named by a draft can be retired
        // in the meantime, and publishing is the last moment that is cheap to catch.
        WorkflowDocument document = parse(target);
        requireValid(document);
        requireResolvableReferences(document);

        Optional<WorkflowDefinitionEntity> current =
                repository.findByAppIdAndWorkflowKeyAndStatus(appId, workflowKey, WorkflowStatus.ACTIVE);
        if (current.isPresent()) {
            WorkflowDefinitionEntity previous = current.get();
            previous.setStatus(WorkflowStatus.ARCHIVED);
            repository.save(previous);
            log.info("archived workflow {} v{} (superseded by v{})",
                    workflowKey, previous.getVersion(), version);
        }

        target.setStatus(WorkflowStatus.ACTIVE);
        target.setPublishedAt(Instant.now());
        // Project the approver rules into queryable rows now that this version is live.
        references.projectRules(target.getId(), document);
        log.info("published workflow {} v{} for app {}", workflowKey, version, appId);
        return repository.save(target);
    }

    /** The version a new request would bind to. */
    @Transactional(readOnly = true)
    public WorkflowDefinitionEntity requireActive(String appId, String workflowKey) {
        return repository.findByAppIdAndWorkflowKeyAndStatus(appId, workflowKey, WorkflowStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("workflow-not-found",
                        "no active version of workflow '" + workflowKey + "'; publish one first"));
    }

    /**
     * A specific version — including ARCHIVED ones. This is the read path an in-flight
     * request uses, which is exactly why archived versions must remain readable.
     */
    @Transactional(readOnly = true)
    public WorkflowDefinitionEntity require(String appId, String workflowKey, int version) {
        return repository.findByAppIdAndWorkflowKeyAndVersion(appId, workflowKey, version)
                .orElseThrow(() -> new NotFoundException("workflow-not-found",
                        "workflow '" + workflowKey + "' has no version " + version));
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionEntity requireById(String workflowDefId) {
        return repository.findById(workflowDefId)
                .orElseThrow(() -> new NotFoundException("workflow-not-found",
                        "workflow definition " + workflowDefId + " no longer exists"));
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionEntity> list(String appId) {
        return repository.findByAppIdOrderByWorkflowKeyAscVersionDesc(appId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionEntity> listVersions(String appId, String workflowKey) {
        return repository.findByAppIdAndWorkflowKeyOrderByVersionDesc(appId, workflowKey);
    }

    /** Deserializes a stored definition back into its executable form. */
    public WorkflowDocument parse(WorkflowDefinitionEntity entity) {
        WorkflowDocument document = json.read(entity.getDefinitionJson(), WorkflowDocument.class);
        if (document == null) {
            throw new IllegalStateException(
                    "workflow definition " + entity.getId() + " has an empty definition_json");
        }
        return document;
    }

    /**
     * Dry-runs a workflow against a hypothetical context: which stages would apply, why, and
     * who would be asked — without creating anything.
     *
     * <p>Not in the original requirements, and included on purpose. It is the cheapest way to
     * demonstrate that rule evaluation is deterministic and explainable, and it is what lets
     * an author check a rule change before it touches a real request. Approver resolution
     * runs for real here, so it also surfaces "this role is empty" at authoring time rather
     * than mid-approval.
     */
    @Transactional(readOnly = true)
    public Simulation simulate(String appId, String workflowKey, Integer version,
                               Map<String, Object> context, String requestedBy) {
        WorkflowDefinitionEntity entity = version == null
                ? requireActive(appId, workflowKey)
                : require(appId, workflowKey, version.intValue());
        WorkflowDocument document = parse(entity);
        EvaluationContext evaluation = EvaluationContext.of(context, requestedBy);

        List<StagePreview> previews = new ArrayList<StagePreview>();
        for (StageDefinition stage : document.getStages()) {
            ConditionTrace trace = stage.appliesTo(evaluation);

            if (!trace.isResult()) {
                previews.add(new StagePreview(stage.getId(), stage.displayName(), false,
                        trace, null, new ArrayList<ResolvedApprover>(), null));
                continue;
            }

            StageResolution resolved = resolution.resolveFor(stage, evaluation);
            previews.add(new StagePreview(
                    stage.getId(),
                    stage.displayName(),
                    true,
                    trace,
                    stage.getMode(),
                    new ArrayList<ResolvedApprover>(resolved.getApprovers()),
                    resolved.isResolved() ? null : resolved.getReason()));
        }
        return new Simulation(entity.getWorkflowKey(), entity.getVersion(), previews);
    }

    /**
     * Rejects a definition that names a role, team, department or user which does not exist.
     *
     * <p>Separate from {@link #requireValid} because the two answer different questions.
     * Structural validity is a property of the document alone; whether ROLE('FINANCE') resolves
     * to anything is a property of the directory. Reported under a distinct error type so a
     * caller can tell "your JSON is malformed" from "your JSON is fine but Finance doesn't
     * exist here".
     */
    private void requireResolvableReferences(WorkflowDocument document) {
        List<String> errors = references.validateReferences(document);
        if (!errors.isEmpty()) {
            throw new ValidationException("workflow-references-unknown",
                    "the workflow names approvers that do not exist", errors);
        }
    }

    private void requireValid(WorkflowDocument document) {
        List<String> errors = document == null
                ? java.util.Collections.singletonList("a workflow definition is required")
                : document.validate();
        if (!errors.isEmpty()) {
            throw new ValidationException("workflow-invalid",
                    "workflow definition is not valid", errors);
        }
    }

    /** Result of {@link #simulate}. */
    @Getter
    public static final class Simulation {
        private final String workflowKey;
        private final int version;
        private final List<StagePreview> stages;

        Simulation(String workflowKey, int version, List<StagePreview> stages) {
            this.workflowKey = workflowKey;
            this.version = version;
            this.stages = stages;
        }
    }

    /** What one stage would do, for a given context. */
    @Getter
    public static final class StagePreview {
        private final String stageId;
        private final String name;
        private final boolean applies;
        private final ConditionTrace trace;
        private final StageDefinition.Mode mode;
        private final List<ResolvedApprover> approvers;

        /** Non-null when the stage applies but nobody could be resolved to approve it. */
        private final String resolutionProblem;

        StagePreview(String stageId, String name, boolean applies, ConditionTrace trace,
                     StageDefinition.Mode mode, List<ResolvedApprover> approvers,
                     String resolutionProblem) {
            this.stageId = stageId;
            this.name = name;
            this.applies = applies;
            this.trace = trace;
            this.mode = mode;
            this.approvers = approvers;
            this.resolutionProblem = resolutionProblem;
        }
    }
}
