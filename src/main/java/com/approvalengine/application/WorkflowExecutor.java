package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.adapter.persistence.entity.StageInstanceEntity;
import com.approvalengine.adapter.persistence.repository.ApprovalRequestRepository;
import com.approvalengine.adapter.persistence.repository.ApprovalTaskRepository;
import com.approvalengine.adapter.persistence.repository.StageInstanceRepository;
import com.approvalengine.application.notify.OutboxWriter;
import com.approvalengine.application.resolver.ApproverResolutionService;
import com.approvalengine.application.resolver.ResolvedApprover;
import com.approvalengine.application.resolver.StageResolution;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.notify.EventType;
import com.approvalengine.domain.request.AuditAction;
import com.approvalengine.domain.request.RequestStatus;
import com.approvalengine.domain.request.StageStatus;
import com.approvalengine.domain.request.StateMachine;
import com.approvalengine.domain.request.TaskStatus;
import com.approvalengine.domain.workflow.CompletionPolicy;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.condition.ConditionTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives a request through its workflow.
 *
 * <p><b>The executor holds no state.</b> Every method is a function of rows already in the
 * database, and every step is idempotent and re-entrant. That single property is what makes
 * crash recovery nearly free: there is no in-memory position to lose, so "resume" is just
 * "call {@link #advance} again", and a half-finished activation replayed from the start
 * cannot duplicate anything.
 *
 * <p>All methods must be called inside a transaction owned by the caller
 * ({@code RequestService}, {@code ActionService}, the SLA sweeper, recovery). The executor
 * deliberately does not open its own: state change, audit entry and outbox row have to commit
 * together, and the caller is what defines that boundary.
 */
@Service
@Slf4j
public class WorkflowExecutor {

    private final StageInstanceRepository stages;
    private final ApprovalTaskRepository tasks;
    private final ApprovalRequestRepository requests;
    private final ApproverResolutionService resolution;
    private final AuditWriter audit;
    private final OutboxWriter outbox;
    private final JsonCodec json;

    public WorkflowExecutor(StageInstanceRepository stages,
                            ApprovalTaskRepository tasks,
                            ApprovalRequestRepository requests,
                            ApproverResolutionService resolution,
                            AuditWriter audit,
                            OutboxWriter outbox,
                            JsonCodec json) {
        this.stages = stages;
        this.tasks = tasks;
        this.requests = requests;
        this.resolution = resolution;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    // ========================================================================
    // Starting a request
    // ========================================================================

    /**
     * Plans every stage, then activates the first that applies.
     *
     * <p>All stages are evaluated up front, against the immutable context snapshot, and a
     * row is written for each — including the ones that do not apply. Skipped stages are
     * recorded with the trace that skipped them rather than omitted, because "why was there
     * no Director approval on this ₹2,00,000 expense?" has to be answerable from the record
     * alone. It also means the create response can tell the caller the whole plan up front.
     */
    public void start(ExecutionContext ctx) {
        // Recorded before planning, so the timeline reads in causal order: the request was
        // created, and that is why the stages were then planned.
        audit.record(ctx.requestId(), ctx.getRequest().getRequestedBy(), AuditAction.REQUEST_CREATED,
                ctx.requestId(), null, RequestStatus.CREATED, null,
                AuditWriter.metadata(
                        "workflow", ctx.getRequest().getWorkflowKey(),
                        "workflowVersion", Integer.valueOf(ctx.getRequest().getWorkflowVersion()),
                        "entity", ctx.getRequest().getEntityType() + "/" + ctx.getRequest().getEntityId()));

        planStages(ctx, 0, true);

        outbox.enqueue(ctx.getApplication(), ctx.getRequest(), EventType.REQUEST_CREATED,
                AuditWriter.metadata("plannedStages", plannedStagesPayload(ctx)));

        advance(ctx, ctx.getRequest().getRequestedBy());
    }

    /**
     * Creates or refreshes stage rows from {@code fromIndex} onward.
     *
     * @param initial true when planning a brand-new request; false when re-planning after a
     *                resubmission or a GOTO, which reopens concluded attempts
     */
    private void planStages(ExecutionContext ctx, int fromIndex, boolean initial) {
        for (int index = fromIndex; index < ctx.stageCount(); index++) {
            StageDefinition definition = ctx.stageAt(index);
            ConditionTrace trace = definition.appliesTo(ctx.getEvaluation());
            StageStatus target = trace.isResult() ? StageStatus.PENDING : StageStatus.SKIPPED;

            StageInstanceEntity existing = initial ? null : latestAttempt(ctx.requestId(), index);

            if (existing == null) {
                createStageAttempt(ctx, definition, index, 1, target, trace);
            } else if (existing.getStatus() == StageStatus.PENDING
                    || existing.getStatus() == StageStatus.SKIPPED) {
                // Never reached, so the same row can simply be re-decided in place. This is
                // the edge that lets a resubmission with a reduced amount actually drop the
                // Director stage, instead of leaving it planned from the original context.
                if (existing.getStatus() != target) {
                    StateMachine.requireStageTransition(existing.getId(), existing.getStatus(), target);
                    StageStatus previous = existing.getStatus();
                    existing.setStatus(target);
                    existing.setConditionTraceJson(json.write(trace));
                    stages.save(existing);
                    audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR,
                            target == StageStatus.SKIPPED ? AuditAction.STAGE_SKIPPED : AuditAction.STAGE_PLANNED,
                            existing.getId(), previous, target, "re-planned against the updated context",
                            AuditWriter.metadata("stageId", definition.getId(), "trace", trace));
                } else {
                    existing.setConditionTraceJson(json.write(trace));
                    stages.save(existing);
                }
            } else {
                // A concluded attempt. Leave it untouched as the record of what happened, and
                // open a fresh attempt for this visit.
                createStageAttempt(ctx, definition, index, existing.getAttempt() + 1, target, trace);
                audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.STAGE_REOPENED,
                        existing.getId(), existing.getStatus(), target,
                        "stage revisited — attempt " + (existing.getAttempt() + 1),
                        AuditWriter.metadata("stageId", definition.getId()));
            }
        }
    }

    private StageInstanceEntity createStageAttempt(ExecutionContext ctx, StageDefinition definition,
                                                   int index, int attempt, StageStatus status,
                                                   ConditionTrace trace) {
        StageInstanceEntity entity = StageInstanceEntity.builder()
                .id(Ids.newId(Ids.STAGE))
                .requestId(ctx.requestId())
                .stageId(definition.getId())
                .stageIndex(index)
                .attempt(attempt)
                .name(definition.displayName())
                .mode(definition.getMode())
                // Snapshotted so the stage row explains its own completion rule, rather than
                // requiring a reader to go back to the definition to interpret it.
                .policyJson(json.write(definition.getPolicy()))
                .conditionTraceJson(json.write(trace))
                .onReject(definition.getOnReject())
                .status(status)
                .completedAt(status == StageStatus.SKIPPED ? Instant.now() : null)
                .createdAt(Instant.now())
                .build();
        stages.save(entity);

        audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR,
                status == StageStatus.SKIPPED ? AuditAction.STAGE_SKIPPED : AuditAction.STAGE_PLANNED,
                entity.getId(), null, status,
                status == StageStatus.SKIPPED ? trace.summary() : null,
                AuditWriter.metadata("stageId", definition.getId(),
                        "stageIndex", Integer.valueOf(index),
                        "attempt", Integer.valueOf(attempt),
                        "trace", trace));
        return entity;
    }

    // ========================================================================
    // Advancing
    // ========================================================================

    /**
     * Activates the next stage that still needs approval, or finishes the request.
     *
     * <p>Safe to call repeatedly. It reads the current position from the request row and
     * derives everything else, so recovery after a crash calls exactly this.
     */
    public void advance(ExecutionContext ctx, String actor) {
        ApprovalRequestEntity request = ctx.getRequest();

        for (int index = request.getCurrentStageIndex() + 1; index < ctx.stageCount(); index++) {
            StageInstanceEntity instance = latestAttempt(ctx.requestId(), index);
            if (instance == null) {
                // Should not happen — planning covers every stage — but a definition edited
                // in the database could produce it. Plan the gap rather than skip silently.
                planStages(ctx, index, false);
                instance = latestAttempt(ctx.requestId(), index);
            }
            if (instance.getStatus() == StageStatus.SKIPPED) {
                continue;
            }
            if (instance.getStatus() == StageStatus.PENDING) {
                activate(ctx, instance, index, actor);
                return;
            }
            if (instance.getStatus() == StageStatus.ACTIVE) {
                // Already running: a concurrent action advanced us here first. Nothing to do,
                // which is exactly what makes a duplicated advance harmless.
                log.debug("stage {} of request {} is already active", index, ctx.requestId());
                request.setCurrentStageIndex(index);
                touch(request);
                return;
            }
            // Any other status means this stage already concluded on a previous pass.
        }

        completeRequest(ctx, RequestStatus.APPROVED, actor,
                "every applicable stage approved", EventType.REQUEST_APPROVED);
    }

    /**
     * Resolves approvers for a stage and creates their tasks.
     *
     * <p>Re-entrant: if the stage already has live tasks, this returns without creating more.
     * Combined with the unique constraint on {@code (stage_instance_id, approver_id)}, that is
     * what lets a crash between "create tasks" and "commit" be recovered by simply replaying
     * the activation.
     */
    private void activate(ExecutionContext ctx, StageInstanceEntity instance, int index, String actor) {
        StageDefinition definition = ctx.stageAt(index);

        long live = tasks.countByStageInstanceIdAndStatusIn(instance.getId(),
                Arrays.asList(TaskStatus.PENDING, TaskStatus.WAITING));
        if (live > 0) {
            log.debug("stage {} already has {} live task(s) — activation already done",
                    instance.getId(), Long.valueOf(live));
            return;
        }

        StageResolution resolved = resolution.resolveFor(definition, ctx.getEvaluation());

        if (resolved.getOutcome() == StageResolution.Outcome.SKIP_STAGE) {
            markStage(ctx, instance, StageStatus.SKIPPED, AuditAction.STAGE_SKIPPED,
                    resolved.getReason(), resolved.getNotes());
            ctx.getRequest().setCurrentStageIndex(index);
            advance(ctx, actor);
            return;
        }

        if (resolved.getOutcome() == StageResolution.Outcome.FAIL_REQUEST) {
            // Record WHERE we got stuck before parking. Without this the request keeps
            // currentStageIndex from before this stage, and a later reassign has no idea
            // which stage it is supposed to retry.
            ctx.getRequest().setCurrentStageIndex(index);
            // Nobody can approve this stage. Park it — failing outright would discard
            // approvals already legitimately given, and stalling silently is worse than both.
            parkForAttention(ctx, resolved.getReason(), resolved.getNotes());
            return;
        }

        List<ResolvedApprover> approvers = resolved.getApprovers();
        boolean sequential = definition.getMode() == StageDefinition.Mode.SEQUENTIAL;
        Instant dueAt = definition.getSlaSeconds() == null
                ? null
                : Instant.now().plusSeconds(definition.getSlaSeconds().longValue());

        for (int i = 0; i < approvers.size(); i++) {
            ResolvedApprover approver = approvers.get(i);
            // SEQUENTIAL: everyone is recorded now (snapshot), but only the first is asked.
            // PARALLEL: everyone is asked at once.
            TaskStatus status = (sequential && i > 0) ? TaskStatus.WAITING : TaskStatus.PENDING;

            ApprovalTaskEntity task = ApprovalTaskEntity.builder()
                    .id(Ids.newId(Ids.TASK))
                    .requestId(ctx.requestId())
                    .stageInstanceId(instance.getId())
                    .approverId(approver.getApproverId())
                    .resolvedVia(approver.getResolvedVia())
                    .status(status)
                    // Only a task that is actually someone's turn has a deadline; a WAITING
                    // task's clock starts when it is promoted.
                    .dueAt(status == TaskStatus.PENDING ? dueAt : null)
                    .createdAt(Instant.now())
                    .build();
            tasks.save(task);

            audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.TASK_ASSIGNED,
                    task.getId(), null, status, null,
                    AuditWriter.metadata("stageId", definition.getId(),
                            "approverId", approver.getApproverId(),
                            "resolvedVia", approver.getResolvedVia()));
        }

        StateMachine.requireStageTransition(instance.getId(), instance.getStatus(), StageStatus.ACTIVE);
        instance.setStatus(StageStatus.ACTIVE);
        instance.setActivatedAt(Instant.now());
        stages.save(instance);

        ApprovalRequestEntity request = ctx.getRequest();
        request.setCurrentStageIndex(index);
        if (request.getStatus() != RequestStatus.IN_PROGRESS) {
            StateMachine.requireRequestTransition(request.getId(), request.getStatus(),
                    RequestStatus.IN_PROGRESS);
            request.setStatus(RequestStatus.IN_PROGRESS);
        }
        touch(request);

        audit.record(ctx.requestId(), actor, AuditAction.STAGE_ACTIVATED, instance.getId(),
                StageStatus.PENDING, StageStatus.ACTIVE, null,
                AuditWriter.metadata("stageId", definition.getId(),
                        "mode", definition.getMode(),
                        "policy", definition.getPolicy().toString(),
                        "approverCount", Integer.valueOf(approvers.size()),
                        "resolutionNotes", resolved.getNotes()));

        outbox.enqueue(ctx.getApplication(), request, EventType.STAGE_ACTIVATED,
                AuditWriter.metadata("stage", stagePayload(instance, definition, approvers)));
    }

    /**
     * Re-drives a request from wherever it actually is.
     *
     * <p>This is what startup recovery calls, and it is deliberately different from
     * {@link #advance}: a crash can land mid-stage, not just between stages. The interesting
     * case is a process that died after an approver's task was claimed but before the stage was
     * advanced — the current stage is still ACTIVE with nothing outstanding, which
     * {@code advance} would read as "already running" and leave stuck forever.
     *
     * <p>Because the executor keeps no state in memory, "where was it?" is answerable purely
     * from rows, which is what makes recovery a query rather than a journal replay.
     */
    public void resume(ExecutionContext ctx, String actor) {
        ApprovalRequestEntity request = ctx.getRequest();
        int index = request.getCurrentStageIndex();

        if (index < 0 || index >= ctx.stageCount()) {
            advance(ctx, actor);
            return;
        }

        StageInstanceEntity instance = latestAttempt(ctx.requestId(), index);
        if (instance == null) {
            advance(ctx, actor);
            return;
        }

        if (instance.getStatus() == StageStatus.PENDING) {
            // Planned but never activated. Step back one so advance() picks it up.
            request.setCurrentStageIndex(index - 1);
            advance(ctx, actor);
            return;
        }

        if (instance.getStatus() != StageStatus.ACTIVE) {
            // The stage concluded; only the move to the next one was lost.
            advance(ctx, actor);
            return;
        }

        // ACTIVE: decide it from the task rows as they stand.
        StageOutcome outcome = evaluateStage(ctx, instance, index);
        switch (outcome) {
            case COMPLETE:
                completeStage(ctx, instance, index, actor);
                return;
            case UNSATISFIABLE:
                rejectStage(ctx, instance, index, actor,
                        "no approver remains and the completion policy was not met");
                return;
            case WAITING:
            default:
                long pending = tasks.countByStageInstanceIdAndStatus(instance.getId(), TaskStatus.PENDING);
                if (pending == 0) {
                    // A sequential stage whose promotion was lost mid-flight.
                    promoteNextWaiting(ctx, instance, index);
                }
        }
    }

    /**
     * Re-staffs the stage a parked request is sitting on.
     *
     * <p>Differs from {@link #resume} in one case: an ACTIVE stage with nobody left to ask,
     * which is what a failed SLA escalation leaves behind. {@code resume} reads that as
     * "everyone was heard and the policy was not met" and rejects — correct after a crash,
     * but the opposite of what an operator rescuing the request wants. Here the spent attempt
     * is closed as EXPIRED and a fresh one is opened, so approvers are resolved again against
     * the directory as it stands now.
     */
    public void reassign(ExecutionContext ctx, String actor) {
        int index = ctx.getRequest().getCurrentStageIndex();
        StageInstanceEntity instance = index < 0 || index >= ctx.stageCount()
                ? null
                : latestAttempt(ctx.requestId(), index);

        if (instance == null || instance.getStatus() != StageStatus.ACTIVE
                || evaluateStage(ctx, instance, index) != StageOutcome.UNSATISFIABLE) {
            resume(ctx, actor);
            return;
        }

        markStage(ctx, instance, StageStatus.EXPIRED, AuditAction.STAGE_EXPIRED,
                "no approver remained on this attempt — reopened by reassignment", null);
        goTo(ctx, index, actor, "reassigned after the stage could not be staffed");
    }

    // ========================================================================
    // Stage completion
    // ========================================================================

    /**
     * Re-counts a stage's tasks and decides whether it is complete.
     *
     * <p><b>Counted from the database, inside the caller's transaction — never from a cached
     * tally.</b> That is what makes two simultaneous approvals safe: both re-count, both see
     * a consistent picture, and the optimistic lock on the request lets only one of them act
     * on it.
     */
    public StageOutcome evaluateStage(ExecutionContext ctx, StageInstanceEntity instance, int index) {
        StageDefinition definition = ctx.stageAt(index);
        CompletionPolicy policy = definition.getPolicy();

        // EXPIRED is deliberately not counted. An expired task was handed to someone else by
        // escalation, so counting both it and its replacement would make an ALL stage demand
        // one more approval than there are people left to give it.
        long total = tasks.countByStageInstanceIdAndStatusIn(instance.getId(),
                Arrays.asList(TaskStatus.WAITING, TaskStatus.PENDING, TaskStatus.APPROVED,
                        TaskStatus.REJECTED, TaskStatus.CHANGES_REQUESTED));
        long approved = tasks.countByStageInstanceIdAndStatus(instance.getId(), TaskStatus.APPROVED);
        long required = policy.requiredApprovals((int) total);

        if (approved >= required && required > 0) {
            return StageOutcome.COMPLETE;
        }
        long outstanding = tasks.countByStageInstanceIdAndStatusIn(instance.getId(),
                Arrays.asList(TaskStatus.WAITING, TaskStatus.PENDING));
        if (outstanding == 0) {
            // Nobody left to ask, and the policy was never satisfied — everyone who could
            // have approved declined, expired, or bounced it back.
            return StageOutcome.UNSATISFIABLE;
        }
        return StageOutcome.WAITING;
    }

    /** Marks a stage approved and moves on. */
    public void completeStage(ExecutionContext ctx, StageInstanceEntity instance, int index, String actor) {
        // Any approver still queued or outstanding is withdrawn: the policy is satisfied, and
        // leaving live tasks behind would put items in inboxes that can no longer matter.
        int cancelled = cancelOutstanding(instance, ctx, "stage completed");
        markStage(ctx, instance, StageStatus.APPROVED, AuditAction.STAGE_APPROVED,
                cancelled > 0 ? cancelled + " remaining task(s) withdrawn" : null, null);
        ctx.getRequest().setCurrentStageIndex(index);
        advance(ctx, actor);
    }

    /**
     * Applies the stage's rejection policy.
     *
     * <p>TERMINATE ends the request. CONTINUE records the dissent and carries on — for
     * advisory stages where an objection should be visible without blocking. GOTO sends work
     * back to an earlier stage.
     */
    public void rejectStage(ExecutionContext ctx, StageInstanceEntity instance, int index,
                            String actor, String comment) {
        StageDefinition definition = ctx.stageAt(index);
        cancelOutstanding(instance, ctx, "stage rejected");
        markStage(ctx, instance, StageStatus.REJECTED, AuditAction.STAGE_REJECTED, comment, null);
        ctx.getRequest().setCurrentStageIndex(index);

        switch (definition.getOnReject()) {
            case CONTINUE:
                audit.record(ctx.requestId(), actor, AuditAction.STAGE_REJECTED, instance.getId(),
                        null, null, "onReject=CONTINUE — advancing despite the rejection",
                        AuditWriter.metadata("stageId", definition.getId()));
                advance(ctx, actor);
                return;

            case GOTO:
                int target = ctx.getDocument().indexOfStage(definition.getOnRejectGoto());
                if (target < 0) {
                    // Publish-time validation rejects unknown targets, so this means the
                    // definition changed underneath us. Park rather than guess.
                    parkForAttention(ctx, "onRejectGoto '" + definition.getOnRejectGoto()
                            + "' does not name a stage in the pinned workflow version", null);
                    return;
                }
                goTo(ctx, target, actor, "returned by " + definition.getId());
                return;

            case TERMINATE:
            default:
                abandonRemainingStages(ctx, index);
                completeRequest(ctx, RequestStatus.REJECTED, actor, comment, EventType.REQUEST_REJECTED);
        }
    }

    /**
     * Sends the request back to an earlier stage.
     *
     * <p>Everything from the target onward is reopened, not just the target itself. Going back
     * means redoing the chain: if Finance approved on the strength of a manager decision that
     * has now been revisited, that approval should not silently still count.
     */
    public void goTo(ExecutionContext ctx, int targetIndex, String actor, String reason) {
        planStages(ctx, targetIndex, false);
        ctx.getRequest().setCurrentStageIndex(targetIndex - 1);
        touch(ctx.getRequest());
        audit.record(ctx.requestId(), actor, AuditAction.STAGE_REOPENED, null, null, null, reason,
                AuditWriter.metadata("fromStageIndex", Integer.valueOf(targetIndex)));
        advance(ctx, actor);
    }

    /** An approver bounced the request back to the requester. */
    public void returnStage(ExecutionContext ctx, StageInstanceEntity instance, int index,
                            String actor, String comment) {
        cancelOutstanding(instance, ctx, "changes requested");
        markStage(ctx, instance, StageStatus.RETURNED, AuditAction.STAGE_RETURNED, comment, null);

        ApprovalRequestEntity request = ctx.getRequest();
        request.setCurrentStageIndex(index);
        StateMachine.requireRequestTransition(request.getId(), request.getStatus(),
                RequestStatus.CHANGES_REQUESTED);
        request.setStatus(RequestStatus.CHANGES_REQUESTED);
        touch(request);

        audit.record(ctx.requestId(), actor, AuditAction.REQUEST_CHANGES_REQUESTED, request.getId(),
                RequestStatus.IN_PROGRESS, RequestStatus.CHANGES_REQUESTED, comment, null);

        outbox.enqueue(ctx.getApplication(), request, EventType.REQUEST_CHANGES_REQUESTED,
                AuditWriter.metadata("stageId", ctx.stageAt(index).getId(),
                        "comment", comment, "requestedBy", actor));
    }

    // ========================================================================
    // Terminal outcomes
    // ========================================================================

    /** Ends the request, writing the decision, its audit entry and its notification together. */
    public void completeRequest(ExecutionContext ctx, RequestStatus status, String actor,
                                String comment, EventType eventType) {
        ApprovalRequestEntity request = ctx.getRequest();
        RequestStatus previous = request.getStatus();

        StateMachine.requireRequestTransition(request.getId(), previous, status);
        request.setStatus(status);
        request.setDecidedAt(Instant.now());
        touch(request);

        AuditAction action = status == RequestStatus.APPROVED ? AuditAction.REQUEST_APPROVED
                : status == RequestStatus.REJECTED ? AuditAction.REQUEST_REJECTED
                : AuditAction.REQUEST_CANCELLED;
        audit.record(ctx.requestId(), actor, action, request.getId(), previous, status, comment, null);

        outbox.enqueue(ctx.getApplication(), request, eventType,
                AuditWriter.metadata("finalDecision", status.name(),
                        "decidedBy", decisionTrailPayload(ctx.requestId()),
                        "comment", comment));

        log.info("request {} → {} ({}/{})", request.getId(), status,
                request.getEntityType(), request.getEntityId());
    }

    /**
     * Parks a request that the engine cannot move on its own.
     *
     * <p>Deliberately not terminal. The alternatives are worse: failing the request discards
     * approvals real people already gave, and leaving it silently stuck means nobody finds out
     * until someone asks. NEEDS_ATTENTION is visible, notified, and recoverable via reassign.
     */
    public void parkForAttention(ExecutionContext ctx, String reason, List<String> notes) {
        ApprovalRequestEntity request = ctx.getRequest();
        RequestStatus previous = request.getStatus();

        if (previous != RequestStatus.NEEDS_ATTENTION) {
            StateMachine.requireRequestTransition(request.getId(), previous, RequestStatus.NEEDS_ATTENTION);
            request.setStatus(RequestStatus.NEEDS_ATTENTION);
        }
        request.setFaultReason(reason);
        touch(request);

        audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.REQUEST_NEEDS_ATTENTION,
                request.getId(), previous, RequestStatus.NEEDS_ATTENTION, reason,
                AuditWriter.metadata("resolutionNotes", notes));

        outbox.enqueue(ctx.getApplication(), request, EventType.REQUEST_NEEDS_ATTENTION,
                AuditWriter.metadata("reason", reason, "resolutionNotes", notes));

        log.warn("request {} parked for attention: {}", request.getId(), reason);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    public StageInstanceEntity latestAttempt(String requestId, int stageIndex) {
        return stages.findFirstByRequestIdAndStageIndexOrderByAttemptDesc(requestId, stageIndex)
                .orElse(null);
    }

    /**
     * Promotes the next queued approver in a SEQUENTIAL stage.
     *
     * @return the promoted task, or null if there is nobody queued
     */
    public ApprovalTaskEntity promoteNextWaiting(ExecutionContext ctx, StageInstanceEntity instance,
                                                 int index) {
        List<ApprovalTaskEntity> all = tasks.findByStageInstanceIdOrderByCreatedAtAsc(instance.getId());
        for (ApprovalTaskEntity task : all) {
            if (task.getStatus() == TaskStatus.WAITING) {
                StateMachine.requireTaskTransition(task.getId(), TaskStatus.WAITING, TaskStatus.PENDING);
                task.setStatus(TaskStatus.PENDING);
                Long sla = ctx.stageAt(index).getSlaSeconds();
                // The deadline starts now, when it becomes their turn — not when the stage
                // activated, which could have been days ago behind a slow predecessor.
                task.setDueAt(sla == null ? null : Instant.now().plusSeconds(sla.longValue()));
                tasks.save(task);

                audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.TASK_ASSIGNED,
                        task.getId(), TaskStatus.WAITING, TaskStatus.PENDING,
                        "next approver in sequence",
                        AuditWriter.metadata("approverId", task.getApproverId(),
                                "resolvedVia", task.getResolvedVia()));

                outbox.enqueue(ctx.getApplication(), ctx.getRequest(), EventType.TASK_ASSIGNED,
                        AuditWriter.metadata("taskId", task.getId(),
                                "approverId", task.getApproverId(),
                                "stageId", instance.getStageId()));
                return task;
            }
        }
        return null;
    }

    /**
     * Withdraws every outstanding task across the whole request.
     *
     * <p>Used when a request is cancelled: leaving live tasks behind would keep items in
     * approvers' inboxes for a request that can no longer be decided.
     */
    public int cancelAllOutstanding(ExecutionContext ctx, String reason) {
        int cancelled = 0;
        for (int index = 0; index < ctx.stageCount(); index++) {
            StageInstanceEntity instance = latestAttempt(ctx.requestId(), index);
            if (instance == null) {
                continue;
            }
            cancelled += cancelOutstanding(instance, ctx, reason);
            if (instance.getStatus() == StageStatus.ACTIVE || instance.getStatus() == StageStatus.PENDING) {
                markStage(ctx, instance, StageStatus.CANCELLED, AuditAction.STAGE_CANCELLED, reason, null);
            }
        }
        return cancelled;
    }

    /** Withdraws every WAITING/PENDING task on a stage. Conditional, so it cannot clobber a race. */
    private int cancelOutstanding(StageInstanceEntity instance, ExecutionContext ctx, String reason) {
        Instant now = Instant.now();
        int cancelled = tasks.cancelPendingForStage(instance.getId(), TaskStatus.PENDING,
                TaskStatus.CANCELLED, now);
        cancelled += tasks.cancelPendingForStage(instance.getId(), TaskStatus.WAITING,
                TaskStatus.CANCELLED, now);
        if (cancelled > 0) {
            audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.TASK_CANCELLED,
                    instance.getId(), TaskStatus.PENDING, TaskStatus.CANCELLED, reason,
                    AuditWriter.metadata("cancelledCount", Integer.valueOf(cancelled)));
        }
        return cancelled;
    }

    /** Marks stages after a terminal rejection as abandoned rather than leaving them PENDING. */
    private void abandonRemainingStages(ExecutionContext ctx, int fromIndexExclusive) {
        for (int index = fromIndexExclusive + 1; index < ctx.stageCount(); index++) {
            StageInstanceEntity instance = latestAttempt(ctx.requestId(), index);
            if (instance != null && instance.getStatus() == StageStatus.PENDING) {
                markStage(ctx, instance, StageStatus.REJECTED, AuditAction.STAGE_REJECTED,
                        "abandoned — an earlier stage rejected the request", null);
            }
        }
    }

    private void markStage(ExecutionContext ctx, StageInstanceEntity instance, StageStatus status,
                           AuditAction action, String comment, List<String> notes) {
        StageStatus previous = instance.getStatus();
        StateMachine.requireStageTransition(instance.getId(), previous, status);
        instance.setStatus(status);
        instance.setCompletedAt(Instant.now());
        stages.save(instance);

        audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, action, instance.getId(),
                previous, status, comment,
                AuditWriter.metadata("stageId", instance.getStageId(),
                        "attempt", Integer.valueOf(instance.getAttempt()),
                        "resolutionNotes", notes));
    }

    /**
     * Persists the request and keeps the caller's instance in step with the row.
     *
     * <p>The bulk task updates (claim, cancel) clear the persistence context, which detaches
     * the request the caller is holding. Saving a detached entity is a merge, and a merge
     * bumps {@code @Version} on the managed copy only — so a second save in the same
     * transaction would present the old version and fail as a stale write. A GOTO rejection
     * saves twice (reposition, then activate), which is exactly that case. Flushing and
     * copying the version back makes any number of saves per transaction safe.
     */
    private void touch(ApprovalRequestEntity request) {
        request.setUpdatedAt(Instant.now());
        ApprovalRequestEntity saved = requests.saveAndFlush(request);
        request.setLockVersion(saved.getLockVersion());
    }

    // --- payload builders -------------------------------------------------

    private List<Map<String, Object>> plannedStagesPayload(ExecutionContext ctx) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (StageInstanceEntity instance : stages.findByRequestIdOrderByStageIndexAscAttemptAsc(ctx.requestId())) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("stageId", instance.getStageId());
            entry.put("name", instance.getName());
            entry.put("applies", Boolean.valueOf(instance.getStatus() != StageStatus.SKIPPED));
            out.add(entry);
        }
        return out;
    }

    private Map<String, Object> stagePayload(StageInstanceEntity instance, StageDefinition definition,
                                             List<ResolvedApprover> approvers) {
        List<String> approverIds = new ArrayList<String>();
        for (ResolvedApprover approver : approvers) {
            approverIds.add(approver.getApproverId());
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("stageId", instance.getStageId());
        payload.put("name", instance.getName());
        payload.put("mode", definition.getMode().name());
        payload.put("policy", definition.getPolicy().toString());
        payload.put("approvers", approverIds);
        return payload;
    }

    /** Who decided what, for the terminal webhook — the consumer's record of the approval chain. */
    private List<Map<String, Object>> decisionTrailPayload(String requestId) {
        List<Map<String, Object>> trail = new ArrayList<Map<String, Object>>();
        for (ApprovalTaskEntity task : tasks.findByRequestIdOrderByCreatedAtAsc(requestId)) {
            if (task.getStatus() == TaskStatus.WAITING || task.getStatus() == TaskStatus.PENDING
                    || task.getStatus() == TaskStatus.CANCELLED) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("actor", task.getActedBy() == null ? task.getApproverId() : task.getActedBy());
            entry.put("action", task.getStatus().name());
            entry.put("comment", task.getComment());
            entry.put("at", task.getActedAt());
            trail.add(entry);
        }
        return trail;
    }

    /** What {@link #evaluateStage} concluded. */
    public enum StageOutcome {
        /** The completion policy is satisfied. */
        COMPLETE,
        /** Approvals are still outstanding. */
        WAITING,
        /** Nobody is left to ask and the policy was never met. */
        UNSATISFIABLE
    }
}
