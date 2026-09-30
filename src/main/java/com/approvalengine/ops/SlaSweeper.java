package com.approvalengine.ops;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.adapter.persistence.entity.StageInstanceEntity;
import com.approvalengine.adapter.persistence.repository.ApplicationRepository;
import com.approvalengine.adapter.persistence.repository.ApprovalRequestRepository;
import com.approvalengine.adapter.persistence.repository.ApprovalTaskRepository;
import com.approvalengine.application.AuditWriter;
import com.approvalengine.application.ExecutionContext;
import com.approvalengine.application.JsonCodec;
import com.approvalengine.application.WorkflowExecutor;
import com.approvalengine.application.WorkflowService;
import com.approvalengine.config.ApprovalProperties;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.request.AuditAction;
import com.approvalengine.domain.request.RequestStatus;
import com.approvalengine.domain.request.StageStatus;
import com.approvalengine.domain.request.StateMachine;
import com.approvalengine.domain.request.TaskStatus;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Handles approvers who never respond.
 *
 * <p>Without this, one unresponsive person stalls a request indefinitely and nobody finds out
 * until someone asks. The stage's declared {@code onTimeout} decides what happens instead, and
 * every outcome goes through the same {@code StateMachine} and audit path as a human decision —
 * attributed to {@code SYSTEM}, so an auto-approval is exactly as traceable as a manual one.
 * That traceability is the reason timeouts are not special-cased anywhere.
 */
@Component
@Slf4j
public class SlaSweeper {

    private final ApprovalTaskRepository tasks;
    private final ApprovalRequestRepository requests;
    private final ApplicationRepository applications;
    private final WorkflowService workflows;
    private final WorkflowExecutor executor;
    private final DirectoryService directory;
    private final AuditWriter audit;
    private final JsonCodec json;
    private final ApprovalProperties properties;
    private final TransactionTemplate transaction;

    public SlaSweeper(ApprovalTaskRepository tasks,
                      ApprovalRequestRepository requests,
                      ApplicationRepository applications,
                      WorkflowService workflows,
                      WorkflowExecutor executor,
                      DirectoryService directory,
                      AuditWriter audit,
                      JsonCodec json,
                      ApprovalProperties properties,
                      PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.requests = requests;
        this.applications = applications;
        this.workflows = workflows;
        this.executor = executor;
        this.directory = directory;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${approval.sla.poll-interval-ms:60000}")
    public void tick() {
        if (!properties.getSla().isEnabled()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            // The scheduler stops calling a job that throws, which would silently disable
            // every SLA in the system.
            log.error("SLA sweep failed", e);
        }
    }

    /**
     * Processes every overdue task.
     *
     * @return how many were acted on
     */
    public int sweep() {
        List<ApprovalTaskEntity> overdue = tasks.findOverdue(
                TaskStatus.PENDING, Instant.now(),
                PageRequest.of(0, properties.getSla().getBatchSize()));

        if (overdue.isEmpty()) {
            return 0;
        }
        log.info("SLA sweep found {} overdue task(s)", Integer.valueOf(overdue.size()));

        int handled = 0;
        for (ApprovalTaskEntity task : overdue) {
            // One transaction per task, so one problem request cannot block the rest. The
            // failure is caught out here, after the template has rolled back: catching it
            // inside would let the transaction commit whatever was written before it threw.
            try {
                Boolean ok = transaction.execute(status -> handleOne(task.getId()));
                if (Boolean.TRUE.equals(ok)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                log.error("failed to process overdue task {}", task.getId(), e);
            }
        }
        return handled;
    }

    private boolean handleOne(String taskId) {
        Optional<ApprovalTaskEntity> found = tasks.findById(taskId);
        if (!found.isPresent()) {
            return false;
        }
        ApprovalTaskEntity task = found.get();

        // Row-locked, so the sweeper and a late-arriving approver cannot both act.
        Optional<ApprovalRequestEntity> maybeRequest = requests.findByIdForUpdate(task.getRequestId());
        if (!maybeRequest.isPresent()) {
            return false;
        }
        ApprovalRequestEntity request = maybeRequest.get();

        // Re-checked under the lock: the approver may have acted in the moment between the
        // scan and now, in which case the deadline is moot.
        if (request.getStatus() != RequestStatus.IN_PROGRESS
                || task.getStatus() != TaskStatus.PENDING) {
            return false;
        }

        Optional<ApplicationEntity> application = applications.findById(request.getAppId());
        if (!application.isPresent()) {
            return false;
        }

        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));
        ExecutionContext ctx = new ExecutionContext(request, application.get(), document,
                EvaluationContext.of(json.readMap(request.getContextJson()), request.getRequestedBy()));

        int index = request.getCurrentStageIndex();
        StageInstanceEntity instance = executor.latestAttempt(request.getId(), index);
        if (instance == null || !instance.getId().equals(task.getStageInstanceId())
                || instance.getStatus() != StageStatus.ACTIVE) {
            // The task belongs to a superseded attempt. Nothing to time out.
            return false;
        }

        StageDefinition definition = ctx.stageAt(index);
        switch (definition.getOnTimeout()) {
            case NOTIFY_ONLY:
                return remind(ctx, task, definition);
            case ESCALATE:
                return escalate(ctx, task, instance, index, definition);
            case AUTO_APPROVE:
                return autoDecide(ctx, task, instance, index, TaskStatus.APPROVED);
            case AUTO_REJECT:
                return autoDecide(ctx, task, instance, index, TaskStatus.REJECTED);
            default:
                return false;
        }
    }

    /**
     * Records a reminder and pushes the deadline out by one SLA period.
     *
     * <p>Extending {@code dueAt} is what stops the sweeper re-firing on the same task every
     * minute forever. It turns NOTIFY_ONLY into a recurring nudge rather than a one-off, which
     * is the more useful behaviour anyway.
     */
    private boolean remind(ExecutionContext ctx, ApprovalTaskEntity task, StageDefinition definition) {
        long period = definition.getSlaSeconds() == null ? 86400L : definition.getSlaSeconds().longValue();
        task.setDueAt(Instant.now().plusSeconds(period));
        tasks.save(task);

        audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.TASK_EXPIRED,
                task.getId(), TaskStatus.PENDING, TaskStatus.PENDING,
                "SLA elapsed — reminder issued, still awaiting the approver",
                AuditWriter.metadata("approverId", task.getApproverId(),
                        "onTimeout", "NOTIFY_ONLY"));
        log.info("reminded {} about overdue task {}", task.getApproverId(), task.getId());
        return true;
    }

    /**
     * Moves the task to the unresponsive approver's manager.
     *
     * <p>If there is no active manager — the escalation path runs out — the request is parked
     * for an operator rather than silently left pending or auto-decided. Inventing a decision
     * nobody made would be the worse failure.
     */
    private boolean escalate(ExecutionContext ctx, ApprovalTaskEntity task,
                             StageInstanceEntity instance, int index, StageDefinition definition) {
        Optional<String> manager = directory.findManagerOf(task.getApproverId());

        if (!manager.isPresent()) {
            expireTask(ctx, task, "SLA elapsed and no manager was available to escalate to");
            executor.parkForAttention(ctx,
                    "SLA escalation failed: " + task.getApproverId() + " has no active manager",
                    java.util.Collections.singletonList("task " + task.getId() + " expired unactioned"));
            return true;
        }

        String managerId = manager.get();

        // The manager may already hold a task on this stage — the unique
        // (stage_instance_id, approver_id) constraint would reject a second one, and asking
        // twice would be wrong anyway.
        boolean alreadyAssigned = false;
        for (ApprovalTaskEntity sibling : tasks.findByStageInstanceIdOrderByCreatedAtAsc(instance.getId())) {
            if (sibling.getApproverId().equals(managerId)) {
                alreadyAssigned = true;
                break;
            }
        }

        expireTask(ctx, task, "SLA elapsed — escalated to " + managerId);

        if (!alreadyAssigned) {
            long period = definition.getSlaSeconds() == null ? 86400L : definition.getSlaSeconds().longValue();
            ApprovalTaskEntity escalated = ApprovalTaskEntity.builder()
                    .id(Ids.newId(Ids.TASK))
                    .requestId(ctx.requestId())
                    .stageInstanceId(instance.getId())
                    .approverId(managerId)
                    .resolvedVia("ESCALATED_FROM(" + task.getApproverId() + ")")
                    .status(TaskStatus.PENDING)
                    .dueAt(Instant.now().plusSeconds(period))
                    .createdAt(Instant.now())
                    .build();
            tasks.save(escalated);

            audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.TASK_ESCALATED,
                    escalated.getId(), null, TaskStatus.PENDING,
                    "escalated from " + task.getApproverId() + " after the SLA elapsed",
                    AuditWriter.metadata("from", task.getApproverId(), "to", managerId));
            log.info("escalated task {} from {} to {}", task.getId(), task.getApproverId(), managerId);
        }

        // The stage may now be decidable — for an ANY policy, the escalation could be the last
        // outstanding voice.
        settleStage(ctx, instance, index);
        return true;
    }

    /** Applies silence as a decision, attributed to SYSTEM. */
    private boolean autoDecide(ExecutionContext ctx, ApprovalTaskEntity task,
                               StageInstanceEntity instance, int index, TaskStatus decision) {
        StateMachine.requireTaskTransition(task.getId(), TaskStatus.PENDING, decision);

        // The same conditional claim an approver's action uses, so a human acting at this exact
        // moment still wins cleanly rather than being overwritten.
        int claimed = tasks.claim(task.getId(), TaskStatus.PENDING, decision,
                AuditWriter.SYSTEM_ACTOR, "auto-" + decision.name().toLowerCase()
                        + " — the SLA elapsed with no response", Instant.now());
        if (claimed == 0) {
            return false;
        }

        audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR,
                decision == TaskStatus.APPROVED ? AuditAction.TASK_APPROVED : AuditAction.TASK_REJECTED,
                task.getId(), TaskStatus.PENDING, decision,
                "SLA elapsed — applied the stage's onTimeout policy",
                AuditWriter.metadata("approverId", task.getApproverId(),
                        "onTimeout", decision == TaskStatus.APPROVED ? "AUTO_APPROVE" : "AUTO_REJECT"));

        if (decision == TaskStatus.REJECTED) {
            executor.rejectStage(ctx, instance, index, AuditWriter.SYSTEM_ACTOR,
                    "auto-rejected: the SLA elapsed with no response");
        } else {
            settleStage(ctx, instance, index);
        }
        log.info("auto-{} task {} for {} after SLA", decision.name().toLowerCase(),
                task.getId(), task.getApproverId());
        return true;
    }

    /** Re-evaluates the stage after a timeout changed its tally. */
    private void settleStage(ExecutionContext ctx, StageInstanceEntity instance, int index) {
        WorkflowExecutor.StageOutcome outcome = executor.evaluateStage(ctx, instance, index);
        switch (outcome) {
            case COMPLETE:
                executor.completeStage(ctx, instance, index, AuditWriter.SYSTEM_ACTOR);
                break;
            case UNSATISFIABLE:
                executor.rejectStage(ctx, instance, index, AuditWriter.SYSTEM_ACTOR,
                        "the stage's completion policy can no longer be satisfied");
                break;
            case WAITING:
            default:
                long pending = tasks.countByStageInstanceIdAndStatus(instance.getId(), TaskStatus.PENDING);
                if (pending == 0) {
                    executor.promoteNextWaiting(ctx, instance, index);
                }
        }
    }

    private void expireTask(ExecutionContext ctx, ApprovalTaskEntity task, String reason) {
        int claimed = tasks.claim(task.getId(), TaskStatus.PENDING, TaskStatus.EXPIRED,
                AuditWriter.SYSTEM_ACTOR, reason, Instant.now());
        if (claimed > 0) {
            audit.record(ctx.requestId(), AuditWriter.SYSTEM_ACTOR, AuditAction.TASK_EXPIRED,
                    task.getId(), TaskStatus.PENDING, TaskStatus.EXPIRED, reason,
                    AuditWriter.metadata("approverId", task.getApproverId()));
        }
    }
}
