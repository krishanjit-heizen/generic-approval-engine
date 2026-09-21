package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.WorkflowDefinitionEntity;
import com.approvalengine.adapter.persistence.repository.ApprovalRequestRepository;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.notify.EventType;
import com.approvalengine.domain.request.AuditAction;
import com.approvalengine.domain.request.RequestStatus;
import com.approvalengine.domain.request.StateMachine;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import com.approvalengine.error.ConflictException;
import com.approvalengine.error.NotFoundException;
import com.approvalengine.error.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Submitting, resubmitting and withdrawing approval requests.
 *
 * <p>Owns the transaction boundary for those operations: the request row, its stage plan, its
 * tasks, its audit entries and its outbox events all commit together, or none of them do.
 */
@Service
@Slf4j
public class RequestService {

    private final ApprovalRequestRepository requests;
    private final WorkflowService workflows;
    private final ApplicationRegistrationService applications;
    private final WorkflowExecutor executor;
    private final AuditWriter audit;
    private final JsonCodec json;

    public RequestService(ApprovalRequestRepository requests,
                          WorkflowService workflows,
                          ApplicationRegistrationService applications,
                          WorkflowExecutor executor,
                          AuditWriter audit,
                          JsonCodec json) {
        this.requests = requests;
        this.workflows = workflows;
        this.applications = applications;
        this.executor = executor;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Creates a request and runs it as far as it will go — normally to the first stage
     * awaiting a human.
     *
     * <p>Synchronous on purpose. Returning {@code 202 Accepted} and processing later would
     * create a "submitted but nothing has happened yet" state the caller has to poll out of,
     * and would buy nothing: restart safety comes from the executor being re-drivable, not
     * from deferring the first step.
     *
     * <p>The workflow version is <b>pinned here</b>. Whatever is published afterwards, this
     * request executes the definition it started on, to completion.
     */
    @Transactional
    public ApprovalRequestEntity create(String appId, CreateCommand command) {
        command.validate();

        ApplicationEntity application = applications.require(appId);
        WorkflowDefinitionEntity definition = workflows.requireActive(appId, command.workflow);
        WorkflowDocument document = workflows.parse(definition);

        ApprovalRequestEntity request = ApprovalRequestEntity.builder()
                .id(Ids.newId(Ids.REQUEST))
                .appId(appId)
                .workflowKey(definition.getWorkflowKey())
                .workflowVersion(definition.getVersion())
                .workflowDefId(definition.getId())
                .entityType(command.entityType)
                .entityId(command.entityId)
                .requestedBy(command.requestedBy)
                // Snapshotted, never re-read. Six months on, the amount in the consuming app
                // may have changed, but the approval must stay explainable in terms of what
                // the approvers actually saw.
                .contextJson(json.write(command.context))
                .status(RequestStatus.CREATED)
                .currentStageIndex(-1)
                .callbackUrl(command.callbackUrl)
                .lockVersion(Long.valueOf(0L))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Flushed before execution so the live-entity uniqueness guard fires here — a second
        // in-flight approval for the same entity is rejected before any stages are planned.
        requests.saveAndFlush(request);

        ExecutionContext ctx = new ExecutionContext(request, application, document,
                EvaluationContext.of(command.context, command.requestedBy));
        executor.start(ctx);

        log.info("created request {} on {} v{} for {}/{}", request.getId(),
                definition.getWorkflowKey(), Integer.valueOf(definition.getVersion()),
                command.entityType, command.entityId);
        return request;
    }

    /**
     * Resumes a request after the requester addressed the requested changes.
     *
     * <p>An updated context is optional but consequential: supplying one re-plans every stage
     * from the returned one onward. That is deliberate — an approver who says "reduce this
     * below the Director threshold" expects compliance to actually remove the Director stage,
     * not to leave it planned from the original figures. Stages already approved stand.
     */
    @Transactional
    public ApprovalRequestEntity resubmit(String appId, String requestId,
                                          Map<String, Object> updatedContext, String actor,
                                          String comment) {
        ApprovalRequestEntity request = requireForUpdate(appId, requestId);

        if (request.getStatus() != RequestStatus.CHANGES_REQUESTED) {
            throw new ConflictException("resubmit-not-applicable",
                    "request is " + request.getStatus() + "; only a CHANGES_REQUESTED request "
                            + "can be resubmitted");
        }

        ApplicationEntity application = applications.require(appId);
        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));

        Map<String, Object> context = updatedContext;
        if (context != null && !context.isEmpty()) {
            request.setContextJson(json.write(context));
        } else {
            context = json.readMap(request.getContextJson());
        }

        StateMachine.requireRequestTransition(request.getId(), request.getStatus(),
                RequestStatus.IN_PROGRESS);
        request.setStatus(RequestStatus.IN_PROGRESS);
        request.setUpdatedAt(Instant.now());
        requests.save(request);

        audit.record(requestId, actor, AuditAction.REQUEST_RESUBMITTED, requestId,
                RequestStatus.CHANGES_REQUESTED, RequestStatus.IN_PROGRESS, comment,
                AuditWriter.metadata("contextUpdated",
                        Boolean.valueOf(updatedContext != null && !updatedContext.isEmpty())));

        ExecutionContext ctx = new ExecutionContext(request, application, document,
                EvaluationContext.of(context, request.getRequestedBy()));

        // Re-run from the stage that bounced it. goTo re-plans and reopens everything from
        // there, which is the same machinery a GOTO rejection uses.
        executor.goTo(ctx, Math.max(request.getCurrentStageIndex(), 0), actor,
                "resubmitted after changes were requested");
        return request;
    }

    /** Withdraws a request. Terminal, and notified like any other decision. */
    @Transactional
    public ApprovalRequestEntity cancel(String appId, String requestId, String actor, String reason) {
        ApprovalRequestEntity request = requireForUpdate(appId, requestId);

        if (request.getStatus().isTerminal()) {
            throw new ConflictException("request-already-decided",
                    "request is already " + request.getStatus() + " and cannot be cancelled");
        }

        ApplicationEntity application = applications.require(appId);
        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));

        ExecutionContext ctx = new ExecutionContext(request, application, document,
                EvaluationContext.of(json.readMap(request.getContextJson()), request.getRequestedBy()));

        // Withdraw any outstanding work first, so nothing is left sitting in an inbox.
        executor.cancelAllOutstanding(ctx, "request cancelled");
        executor.completeRequest(ctx, RequestStatus.CANCELLED, actor, reason,
                EventType.REQUEST_CANCELLED);
        return request;
    }

    /**
     * Rescues a request the engine could not move.
     *
     * <p>Re-runs resolution for the stuck stage, which is the right behaviour: NEEDS_ATTENTION
     * is almost always "the role was empty" or "the approver had left", and the fix happens in
     * the directory. Once it does, this replays the activation.
     */
    @Transactional
    public ApprovalRequestEntity reassign(String appId, String requestId, String actor, String reason) {
        ApprovalRequestEntity request = requireForUpdate(appId, requestId);

        if (request.getStatus() != RequestStatus.NEEDS_ATTENTION) {
            throw new ConflictException("reassign-not-applicable",
                    "request is " + request.getStatus() + "; only a NEEDS_ATTENTION request "
                            + "can be reassigned");
        }

        ApplicationEntity application = applications.require(appId);
        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));

        StateMachine.requireRequestTransition(request.getId(), request.getStatus(),
                RequestStatus.IN_PROGRESS);
        request.setStatus(RequestStatus.IN_PROGRESS);
        request.setFaultReason(null);
        request.setUpdatedAt(Instant.now());
        requests.save(request);

        audit.record(requestId, actor, AuditAction.REQUEST_REASSIGNED, requestId,
                RequestStatus.NEEDS_ATTENTION, RequestStatus.IN_PROGRESS, reason, null);

        ExecutionContext ctx = new ExecutionContext(request, application, document,
                EvaluationContext.of(json.readMap(request.getContextJson()), request.getRequestedBy()));

        // resume() re-attempts the stage the request is actually sitting on, rather than
        // advancing past it. Reusing the recovery path is deliberate: "figure out where this
        // request is and carry on" is the same problem whether the interruption was a crash
        // or an unstaffable stage, and one implementation cannot drift from the other.
        executor.resume(ctx, actor);
        return request;
    }

    // --- reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public ApprovalRequestEntity require(String appId, String requestId) {
        return requests.findByIdAndAppId(requestId, appId)
                .orElseThrow(() -> new NotFoundException("request-not-found",
                        "no request " + requestId + " for this application"));
    }

    /**
     * Loads a request for mutation, taking a row lock for the rest of the caller's transaction.
     *
     * <p>Must be joined to an existing transaction — a lock taken and released immediately would
     * be pointless. Every write path uses this so that concurrent mutation of one request is
     * serialized rather than raced.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ApprovalRequestEntity requireForUpdate(String appId, String requestId) {
        return requests.findByIdAndAppIdForUpdate(requestId, appId)
                .orElseThrow(() -> new NotFoundException("request-not-found",
                        "no request " + requestId + " for this application"));
    }

    /** The reconciliation query: what is actually true for this entity? */
    @Transactional(readOnly = true)
    public List<ApprovalRequestEntity> findByEntity(String appId, String entityType, String entityId) {
        return requests.findByAppIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                appId, entityType, entityId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestEntity> list(String appId, RequestStatus status, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        return status == null
                ? requests.findByAppIdOrderByCreatedAtDesc(appId, page)
                : requests.findByAppIdAndStatusOrderByCreatedAtDesc(appId, status, page);
    }

    /** What a consuming application must supply to open an approval. */
    public static final class CreateCommand {

        public String workflow;
        public String entityType;
        public String entityId;
        public String requestedBy;
        public Map<String, Object> context;
        public String callbackUrl;

        void validate() {
            java.util.List<String> errors = new java.util.ArrayList<String>();
            requireText(workflow, "workflow", errors);
            requireText(entityType, "entityType", errors);
            requireText(entityId, "entityId", errors);
            requireText(requestedBy, "requestedBy", errors);
            if (!errors.isEmpty()) {
                throw new ValidationException("request-invalid",
                        "approval request is missing required fields", errors);
            }
        }

        private static void requireText(String value, String field, java.util.List<String> errors) {
            if (value == null || value.trim().isEmpty()) {
                errors.add(field + " is required");
            }
        }
    }
}
