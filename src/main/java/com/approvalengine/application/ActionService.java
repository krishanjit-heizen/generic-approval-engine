package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.adapter.persistence.entity.StageInstanceEntity;
import com.approvalengine.adapter.persistence.repository.ApprovalTaskRepository;
import com.approvalengine.config.ApprovalProperties;
import com.approvalengine.domain.request.AuditAction;
import com.approvalengine.domain.request.RequestStatus;
import com.approvalengine.domain.request.StageStatus;
import com.approvalengine.domain.request.StateMachine;
import com.approvalengine.domain.request.TaskStatus;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import com.approvalengine.error.ConflictException;
import com.approvalengine.error.EligibilityRefusedException;
import com.approvalengine.error.ForbiddenException;
import com.approvalengine.error.NotFoundException;
import com.approvalengine.error.ValidationException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Recording approver decisions — the concurrency-critical path.
 *
 * <p>Two approvers on a parallel stage will act at the same instant, and the outcome must be
 * indistinguishable from some serial ordering of the same two actions. Three layers get that,
 * with no distributed lock:
 *
 * <ol>
 *   <li><b>Conditional task claim.</b> {@code UPDATE ... WHERE id = ? AND status = 'PENDING'}
 *       makes leaving PENDING single-writer at the row level. Zero rows updated means someone
 *       already acted — double-approval is impossible, decided by the database.</li>
 *   <li><b>Optimistic lock on the request.</b> Stage completion is re-counted inside the same
 *       transaction that advances the stage, and advancing bumps {@code @Version}. Two
 *       concurrent approvals cannot both advance; the loser's commit fails.</li>
 *   <li><b>Bounded retry.</b> The loser re-reads fresh state and re-applies. Its own task
 *       claim already succeeded and is not repeated, so the retry only re-evaluates
 *       completion — and now sees its sibling's approval.</li>
 * </ol>
 *
 * <p>Retries are bounded on purpose. Concurrent approvals on one stage are a handful of
 * writers, not a stampede, so a few attempts resolve essentially every real collision; past
 * that, a caller is better told (409) than left spinning.
 */
@Service
@Slf4j
public class ActionService {

    private static final SecureRandom JITTER = new SecureRandom();

    private final ApprovalTaskRepository tasks;
    private final ApproverEligibilityService eligibility;
    private final RequestService requestService;
    private final ApplicationRegistrationService applications;
    private final WorkflowService workflows;
    private final WorkflowExecutor executor;
    private final AuditWriter audit;
    private final JsonCodec json;
    private final ApprovalProperties properties;

    /**
     * Transactions here are driven programmatically rather than with {@code @Transactional}.
     *
     * <p>The retry loop and the transactional work live in the same class, and Spring's
     * proxy-based transaction advice does not apply to self-invocation — an annotated method
     * called from a sibling method runs with no transaction at all. On a path whose entire
     * correctness rests on transaction boundaries, silently getting none is the worst possible
     * failure mode, so the boundary is made explicit and local instead.
     *
     * <p>{@code PROPAGATION_REQUIRES_NEW} is what makes each retry a genuinely fresh
     * transaction with a fresh persistence context. Reusing one would re-read the stale,
     * version-conflicted entities cached by the failed attempt.
     */
    private final TransactionTemplate transaction;

    public ActionService(ApprovalTaskRepository tasks,
                         ApproverEligibilityService eligibility,
                         RequestService requestService,
                         ApplicationRegistrationService applications,
                         WorkflowService workflows,
                         WorkflowExecutor executor,
                         AuditWriter audit,
                         JsonCodec json,
                         ApprovalProperties properties,
                         PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.eligibility = eligibility;
        this.requestService = requestService;
        this.applications = applications;
        this.workflows = workflows;
        this.executor = executor;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies a decision, retrying if it loses an optimistic-lock race.
     *
     * <p>The retry loop lives outside the transaction because a transaction that has hit an
     * optimistic-lock failure cannot be continued — it must be rolled back and re-run against
     * fresh state.
     */
    public ActionResult act(String appId, String requestId, ActionCommand command) {
        int attempts = Math.max(properties.getConcurrency().getOptimisticRetryAttempts(), 1);
        long baseBackoff = properties.getConcurrency().getOptimisticRetryBaseBackoffMs();

        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> applyOnce(appId, requestId, command));
            } catch (EligibilityRefusedException e) {
                // The transaction has rolled back and the row lock is released, so this insert
                // can proceed without contending with the transaction that produced it.
                recordRefusal(e);
                throw e;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= attempts) {
                    log.info("action on request {} lost {} optimistic-lock races; surfacing 409",
                            requestId, Integer.valueOf(attempt));
                    throw e;
                }
                // Jittered backoff: without it, two racing approvers retry in lockstep and
                // are liable to collide again on exactly the same boundary.
                long sleep = baseBackoff * attempt + JITTER.nextInt((int) Math.max(baseBackoff, 1L));
                log.debug("optimistic-lock conflict on request {} (attempt {}), retrying in {}ms",
                        requestId, Integer.valueOf(attempt), Long.valueOf(sleep));
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Records a refused action, in its own transaction.
     *
     * <p>Best-effort: if writing the audit entry fails, the 403 still stands. Losing the record
     * of a refusal is bad, but converting a correct refusal into a 500 — and thereby telling the
     * caller nothing about why they were blocked — is worse.
     */
    private void recordRefusal(EligibilityRefusedException refusal) {
        try {
            transaction.executeWithoutResult(status ->
                    audit.record(refusal.getRequestId(), refusal.getActorId(),
                            AuditAction.TASK_ACTION_REFUSED, refusal.getTaskId(),
                            TaskStatus.PENDING, TaskStatus.PENDING,
                            "action refused: " + refusal.getMessage(),
                            AuditWriter.metadata("assignedVia", refusal.getAssignedVia(),
                                    "refusal", refusal.getType())));
        } catch (RuntimeException e) {
            log.error("failed to audit a refused action on task {}", refusal.getTaskId(), e);
        }
    }

    /** One attempt. Always invoked inside the {@link #transaction} template above. */
    private ActionResult applyOnce(String appId, String requestId, ActionCommand command) {
        command.validate();

        // Row-locked for the rest of the transaction. Simultaneous approvers on a parallel
        // stage queue here and are then applied strictly one at a time, which is what keeps
        // the audit sequence and the stage tally consistent without a retry storm.
        ApprovalRequestEntity request = requestService.requireForUpdate(appId, requestId);
        ApplicationEntity application = applications.require(appId);

        if (request.getStatus().isTerminal()) {
            throw new ConflictException("request-already-decided",
                    "request is already " + request.getStatus() + "; no further actions are accepted");
        }
        if (!request.getStatus().isActionable()) {
            throw new ConflictException("request-not-actionable",
                    "request is " + request.getStatus() + " and is not currently awaiting approvals");
        }

        ApprovalTaskEntity task = resolveTask(request, command);

        // Authorisation. The actor header names a person; it never grants anything. This
        // re-check against a PENDING task the actor actually holds is what makes trusting the
        // header safe: a wrong or guessed actor id cannot approve anything it was not asked to.
        if (!task.getApproverId().equals(command.actor)) {
            throw new ForbiddenException("not-your-task",
                    "task " + task.getId() + " is assigned to another approver");
        }
        if (task.getStatus() == TaskStatus.WAITING) {
            throw new ConflictException("task-not-yet-active",
                    "this is a sequential stage and it is not yet your turn");
        }
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new ConflictException("task-already-actioned",
                    "task " + task.getId() + " is already " + task.getStatus());
        }

        // Holding the task proves they were ASKED. This proves they are STILL the right person:
        // approvers are snapshotted when a stage activates, and a stage can sit for weeks, so
        // someone assigned via ROLE(FINANCE) may have left Finance since. Roles are looked up
        // here from the user id — never accepted from the caller.
        ApproverEligibilityService.Eligibility verdict = eligibility.check(task, command.actor);
        if (!verdict.isAllowed()) {
            // Audited by act(), after this transaction rolls back and releases its row lock.
            throw new EligibilityRefusedException(verdict.getErrorType(), verdict.getReason(),
                    requestId, task.getId(), command.actor, task.getResolvedVia());
        }

        StageInstanceEntity instance = executor.latestAttempt(requestId, request.getCurrentStageIndex());
        if (instance == null || !instance.getId().equals(task.getStageInstanceId())) {
            // The task belongs to a superseded attempt — the stage was reopened underneath it.
            throw new ConflictException("task-superseded",
                    "this task belongs to an earlier attempt of the stage and is no longer live");
        }

        TaskStatus newStatus = command.action.toTaskStatus();
        StateMachine.requireTaskTransition(task.getId(), TaskStatus.PENDING, newStatus);

        // ---- LAYER 1: the conditional claim. The database picks the winner. ----
        int claimed = tasks.claim(task.getId(), TaskStatus.PENDING, newStatus,
                command.actor, command.comment, Instant.now());
        if (claimed == 0) {
            throw new ConflictException("task-already-actioned",
                    "another action on task " + task.getId() + " committed first");
        }

        audit.record(requestId, command.actor, command.action.toAuditAction(), task.getId(),
                TaskStatus.PENDING, newStatus, command.comment,
                AuditWriter.metadata("stageId", instance.getStageId(),
                        "approverId", task.getApproverId(),
                        "resolvedVia", task.getResolvedVia()));

        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));
        ExecutionContext ctx = new ExecutionContext(request, application, document,
                EvaluationContext.of(json.readMap(request.getContextJson()), request.getRequestedBy()));
        int stageIndex = request.getCurrentStageIndex();

        String outcome;
        switch (command.action) {
            case REJECT:
                executor.rejectStage(ctx, instance, stageIndex, command.actor, command.comment);
                outcome = "STAGE_REJECTED";
                break;

            case REQUEST_CHANGES:
                executor.returnStage(ctx, instance, stageIndex, command.actor, command.comment);
                outcome = "CHANGES_REQUESTED";
                break;

            case APPROVE:
            default:
                outcome = applyApproval(ctx, instance, stageIndex, command.actor);
                break;
        }

        return new ActionResult(request.getId(), request.getStatus(), newStatus, outcome,
                request.getCurrentStageIndex(),
                request.getCurrentStageIndex() < document.getStages().size()
                        && !request.getStatus().isTerminal()
                        ? document.getStages().get(request.getCurrentStageIndex()).getId()
                        : null);
    }

    /**
     * An approval: re-count the stage, then complete it, promote the next approver, or wait.
     *
     * <p>The re-count is the second concurrency layer. It reads task rows in this transaction
     * rather than trusting anything computed earlier, so two simultaneous approvals both see a
     * consistent tally — and whichever advances the stage bumps the request's version, forcing
     * the other to retry rather than advance a second time.
     */
    private String applyApproval(ExecutionContext ctx, StageInstanceEntity instance,
                                 int stageIndex, String actor) {
        WorkflowExecutor.StageOutcome outcome = executor.evaluateStage(ctx, instance, stageIndex);

        switch (outcome) {
            case COMPLETE:
                // ---- LAYER 2: advancing mutates the request and bumps @Version ----
                executor.completeStage(ctx, instance, stageIndex, actor);
                return "STAGE_COMPLETED";

            case UNSATISFIABLE:
                // Everyone who could have approved has been heard from and the policy was
                // never met — a QUORUM of 2 where one approved and one rejected, say. Treated
                // as a stage rejection so the workflow's own onReject policy decides, rather
                // than the engine inventing an outcome.
                executor.rejectStage(ctx, instance, stageIndex, actor,
                        "stage completion policy cannot be satisfied by the remaining approvers");
                return "STAGE_UNSATISFIABLE";

            case WAITING:
            default:
                StageDefinition definition = ctx.stageAt(stageIndex);
                if (definition.getMode() == StageDefinition.Mode.SEQUENTIAL) {
                    ApprovalTaskEntity promoted = executor.promoteNextWaiting(ctx, instance, stageIndex);
                    if (promoted != null) {
                        return "NEXT_APPROVER_ASSIGNED";
                    }
                }
                return "AWAITING_OTHER_APPROVERS";
        }
    }

    /**
     * Finds the task being acted on.
     *
     * <p>{@code taskId} is optional. Omitting it means "act on whatever is assigned to me on
     * this request", which is what an approver actually wants and removes an id the caller
     * would otherwise have to carry around. Supplying it is more precise and is what the
     * inbox does.
     */
    private ApprovalTaskEntity resolveTask(ApprovalRequestEntity request, ActionCommand command) {
        if (command.taskId != null && !command.taskId.trim().isEmpty()) {
            return tasks.findByIdAndRequestId(command.taskId.trim(), request.getId())
                    .orElseThrow(() -> new NotFoundException("task-not-found",
                            "no task " + command.taskId + " on request " + request.getId()));
        }
        return tasks.findFirstByRequestIdAndApproverIdAndStatus(
                        request.getId(), command.actor, TaskStatus.PENDING)
                .orElseThrow(() -> new ForbiddenException("no-pending-task",
                        "you have no pending approval task on this request"));
    }

    /** What an approver can do. */
    public enum Action {
        APPROVE,
        REJECT,
        REQUEST_CHANGES;

        TaskStatus toTaskStatus() {
            switch (this) {
                case REJECT:
                    return TaskStatus.REJECTED;
                case REQUEST_CHANGES:
                    return TaskStatus.CHANGES_REQUESTED;
                case APPROVE:
                default:
                    return TaskStatus.APPROVED;
            }
        }

        AuditAction toAuditAction() {
            switch (this) {
                case REJECT:
                    return AuditAction.TASK_REJECTED;
                case REQUEST_CHANGES:
                    return AuditAction.TASK_CHANGES_REQUESTED;
                case APPROVE:
                default:
                    return AuditAction.TASK_APPROVED;
            }
        }
    }

    /** One approver's decision. */
    public static final class ActionCommand {

        public String taskId;
        public Action action;
        public String comment;
        public String actor;

        void validate() {
            if (action == null) {
                throw new ValidationException("action-required",
                        "'action' must be one of APPROVE, REJECT, REQUEST_CHANGES");
            }
            if (actor == null || actor.trim().isEmpty()) {
                throw new ValidationException("actor-required",
                        "the X-Actor-Id header naming the acting approver is required");
            }
            if (action == Action.REQUEST_CHANGES && (comment == null || comment.trim().isEmpty())) {
                // Asking for changes without saying what to change makes the request
                // unactionable for the requester, so the comment is mandatory here.
                throw new ValidationException("comment-required",
                        "REQUEST_CHANGES requires a comment explaining what needs to change");
            }
        }
    }

    /** What happened, from the caller's point of view. */
    @Getter
    public static final class ActionResult {
        private final String requestId;
        private final RequestStatus requestStatus;
        private final TaskStatus taskStatus;

        /** Coarse outcome: STAGE_COMPLETED, AWAITING_OTHER_APPROVERS, NEXT_APPROVER_ASSIGNED, … */
        private final String stageOutcome;

        private final int currentStageIndex;
        private final String currentStageId;

        ActionResult(String requestId, RequestStatus requestStatus, TaskStatus taskStatus,
                     String stageOutcome, int currentStageIndex, String currentStageId) {
            this.requestId = requestId;
            this.requestStatus = requestStatus;
            this.taskStatus = taskStatus;
            this.stageOutcome = stageOutcome;
            this.currentStageIndex = currentStageIndex;
            this.currentStageId = currentStageId;
        }
    }

    /** Exposed so the inbox can reuse the same status vocabulary. */
    public static StageStatus liveStageStatus() {
        return StageStatus.ACTIVE;
    }
}
