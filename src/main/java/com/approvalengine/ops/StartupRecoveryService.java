package com.approvalengine.ops;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.repository.ApplicationRepository;
import com.approvalengine.adapter.persistence.repository.ApprovalRequestRepository;
import com.approvalengine.application.AuditWriter;
import com.approvalengine.application.ExecutionContext;
import com.approvalengine.application.JsonCodec;
import com.approvalengine.application.WorkflowExecutor;
import com.approvalengine.application.WorkflowService;
import com.approvalengine.application.notify.OutboxDispatcher;
import com.approvalengine.config.ApprovalProperties;
import com.approvalengine.domain.request.AuditAction;
import com.approvalengine.domain.request.RequestStatus;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Resumes work interrupted by a crash or a restart.
 *
 * <p>There is remarkably little to do here, and that is the point. The engine keeps no execution
 * state in memory: a request's true position is always derivable from its rows, every executor
 * step is idempotent, and task creation is guarded by a unique
 * {@code (stage_instance_id, approver_id)} so replaying a half-finished activation cannot
 * duplicate anything. So recovery is not a journal to replay — it is a query for requests that
 * stopped moving, and a re-drive.
 *
 * <p>Detection is {@code IN_PROGRESS with no outstanding task}. That is exactly the state a
 * process dying between "record the approval" and "advance the stage" leaves behind, and it is
 * unreachable during normal operation because both happen in one transaction.
 */
@Component
@Slf4j
public class StartupRecoveryService {

    /** Bounded so a catastrophic backlog cannot make startup hang indefinitely. */
    private static final int MAX_RECOVERED_PER_RUN = 500;

    private final ApprovalRequestRepository requests;
    private final ApplicationRepository applications;
    private final WorkflowService workflows;
    private final WorkflowExecutor executor;
    private final OutboxDispatcher dispatcher;
    private final AuditWriter audit;
    private final JsonCodec json;
    private final ApprovalProperties properties;
    private final TransactionTemplate transaction;

    public StartupRecoveryService(ApprovalRequestRepository requests,
                                  ApplicationRepository applications,
                                  WorkflowService workflows,
                                  WorkflowExecutor executor,
                                  OutboxDispatcher dispatcher,
                                  AuditWriter audit,
                                  JsonCodec json,
                                  ApprovalProperties properties,
                                  PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.applications = applications;
        this.workflows = workflows;
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs once the application is ready to serve traffic.
     *
     * <p>{@code ApplicationReadyEvent} rather than {@code @PostConstruct}: recovery re-drives
     * workflows and enqueues notifications, so it needs a fully initialised context — and it
     * must not delay the port opening, because a slow recovery should not look like a failed
     * deployment.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.getRecovery().isEnabled()) {
            log.info("startup recovery is disabled");
            return;
        }
        try {
            int released = dispatcher.releaseStaleLeases();
            if (released > 0) {
                log.info("released {} stale outbox lease(s) from a previous process",
                        Integer.valueOf(released));
            }
            int resumed = recoverStalledRequests();
            log.info("startup recovery complete — {} stalled request(s) resumed",
                    Integer.valueOf(resumed));
        } catch (RuntimeException e) {
            // A failure here must not prevent the application from serving. The engine is
            // still correct without recovery — requests merely stay stuck until the next
            // restart or an operator reassign — so degrade rather than refuse to boot.
            log.error("startup recovery failed; the engine is running but some requests may "
                    + "remain stalled", e);
        }
    }

    /**
     * Finds and re-drives requests that stopped moving.
     *
     * @return how many were resumed
     */
    public int recoverStalledRequests() {
        List<ApprovalRequestEntity> stalled = requests.findStalled(
                RequestStatus.IN_PROGRESS, PageRequest.of(0, MAX_RECOVERED_PER_RUN));

        if (stalled.isEmpty()) {
            return 0;
        }
        log.warn("found {} request(s) IN_PROGRESS with no outstanding task — resuming",
                Integer.valueOf(stalled.size()));

        int resumed = 0;
        for (ApprovalRequestEntity candidate : stalled) {
            // One transaction per request: a single unrecoverable request must not roll back
            // the others. The failure is caught out here, after the template has rolled back:
            // catching it inside would commit a half-finished re-drive.
            try {
                Boolean ok = transaction.execute(status -> recoverOne(candidate.getId()));
                if (Boolean.TRUE.equals(ok)) {
                    resumed++;
                }
            } catch (RuntimeException e) {
                log.error("failed to recover request {}", candidate.getId(), e);
            }
        }
        return resumed;
    }

    private boolean recoverOne(String requestId) {
        // Row-locked, in case an approver's action arrives at the same moment.
        Optional<ApprovalRequestEntity> found = requests.findByIdForUpdate(requestId);
        if (!found.isPresent()) {
            return false;
        }
        ApprovalRequestEntity request = found.get();

        // Re-checked under the lock: a concurrent action may already have moved it on
        // between the scan and now.
        if (request.getStatus() != RequestStatus.IN_PROGRESS) {
            return false;
        }

        Optional<ApplicationEntity> application = applications.findById(request.getAppId());
        if (!application.isPresent()) {
            log.error("cannot recover request {} — application {} no longer exists",
                    requestId, request.getAppId());
            return false;
        }

        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));
        ExecutionContext ctx = new ExecutionContext(request, application.get(), document,
                EvaluationContext.of(json.readMap(request.getContextJson()),
                        request.getRequestedBy()));

        audit.record(requestId, AuditWriter.SYSTEM_ACTOR, AuditAction.REQUEST_RECOVERED,
                requestId, RequestStatus.IN_PROGRESS, RequestStatus.IN_PROGRESS,
                "resumed after an engine restart interrupted execution",
                AuditWriter.metadata("currentStageIndex",
                        Integer.valueOf(request.getCurrentStageIndex())));

        executor.resume(ctx, AuditWriter.SYSTEM_ACTOR);
        log.info("resumed request {}", requestId);
        return true;
    }
}
