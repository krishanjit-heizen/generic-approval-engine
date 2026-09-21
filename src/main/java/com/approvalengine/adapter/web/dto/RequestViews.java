package com.approvalengine.adapter.web.dto;

import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.adapter.persistence.entity.AuditEventEntity;
import com.approvalengine.adapter.persistence.entity.StageInstanceEntity;
import com.approvalengine.domain.request.RequestStatus;
import com.approvalengine.domain.request.StageStatus;
import com.approvalengine.domain.request.TaskStatus;
import com.approvalengine.domain.workflow.StageDefinition;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read models for the request API.
 *
 * <p>Separate from the entities on purpose. Entities carry storage concerns — the JSON
 * columns, the lock version, the denormalised {@code requestId} on tasks — none of which is a
 * contract. Mapping through these types means a schema change does not silently become a
 * breaking API change.
 */
public final class RequestViews {

    private RequestViews() {
    }

    /** Summary, for lists and reconciliation queries. */
    @Getter
    public static final class Summary {
        private final String requestId;
        private final String entityType;
        private final String entityId;
        private final String requestedBy;
        private final RequestStatus status;
        private final WorkflowRef workflow;
        private final String currentStageId;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final Instant decidedAt;
        private final String faultReason;

        private Summary(ApprovalRequestEntity r, String currentStageId) {
            this.requestId = r.getId();
            this.entityType = r.getEntityType();
            this.entityId = r.getEntityId();
            this.requestedBy = r.getRequestedBy();
            this.status = r.getStatus();
            this.workflow = new WorkflowRef(r.getWorkflowKey(), r.getWorkflowVersion());
            this.currentStageId = currentStageId;
            this.createdAt = r.getCreatedAt();
            this.updatedAt = r.getUpdatedAt();
            this.decidedAt = r.getDecidedAt();
            this.faultReason = r.getFaultReason();
        }

        public static Summary of(ApprovalRequestEntity request, String currentStageId) {
            return new Summary(request, currentStageId);
        }
    }

    /** Full detail: the request, its stage plan, its tasks, and its history. */
    @Getter
    public static final class Detail {
        private final Summary request;
        private final Map<String, Object> context;
        private final List<Stage> stages;
        private final List<Task> tasks;
        private final List<AuditEntry> audit;

        public Detail(Summary request, Map<String, Object> context, List<Stage> stages,
                      List<Task> tasks, List<AuditEntry> audit) {
            this.request = request;
            this.context = context;
            this.stages = stages;
            this.tasks = tasks;
            this.audit = audit;
        }
    }

    @Getter
    public static final class WorkflowRef {
        private final String key;
        private final int version;

        public WorkflowRef(String key, int version) {
            this.key = key;
            this.version = version;
        }
    }

    /**
     * One stage attempt.
     *
     * <p>Skipped stages are included, carrying the reason. A caller asking "why was there no
     * Director approval?" gets the answer from this list rather than from an absence.
     */
    @Getter
    public static final class Stage {
        private final String stageInstanceId;
        private final String stageId;
        private final String name;
        private final int index;
        private final int attempt;
        private final StageDefinition.Mode mode;
        private final StageStatus status;
        private final Object conditionTrace;
        private final Instant activatedAt;
        private final Instant completedAt;

        public Stage(StageInstanceEntity s, Object conditionTrace) {
            this.stageInstanceId = s.getId();
            this.stageId = s.getStageId();
            this.name = s.getName();
            this.index = s.getStageIndex();
            this.attempt = s.getAttempt();
            this.mode = s.getMode();
            this.status = s.getStatus();
            this.conditionTrace = conditionTrace;
            this.activatedAt = s.getActivatedAt();
            this.completedAt = s.getCompletedAt();
        }
    }

    @Getter
    public static final class Task {
        private final String taskId;
        private final String stageInstanceId;
        private final String approverId;

        /** Why this person was asked, e.g. {@code MANAGER_OF(user-100)}. */
        private final String resolvedVia;

        private final TaskStatus status;
        private final String actedBy;
        private final String comment;
        private final Instant dueAt;
        private final Instant actedAt;

        public Task(ApprovalTaskEntity t) {
            this.taskId = t.getId();
            this.stageInstanceId = t.getStageInstanceId();
            this.approverId = t.getApproverId();
            this.resolvedVia = t.getResolvedVia();
            this.status = t.getStatus();
            this.actedBy = t.getActedBy();
            this.comment = t.getComment();
            this.dueAt = t.getDueAt();
            this.actedAt = t.getActedAt();
        }
    }

    /** One history entry. Append-only, totally ordered by {@code seq}. */
    @Getter
    public static final class AuditEntry {
        private final long seq;
        private final String actor;
        private final String action;
        private final String subjectType;
        private final String subjectId;
        private final String fromState;
        private final String toState;
        private final String comment;
        private final Object metadata;
        private final Instant occurredAt;

        public AuditEntry(AuditEventEntity e, Object metadata) {
            this.seq = e.getSeq();
            this.actor = e.getActor();
            this.action = e.getAction();
            this.subjectType = e.getSubjectType();
            this.subjectId = e.getSubjectId();
            this.fromState = e.getFromState();
            this.toState = e.getToState();
            this.comment = e.getComment();
            this.metadata = metadata;
            this.occurredAt = e.getOccurredAt();
        }
    }

    public static List<Task> tasks(List<ApprovalTaskEntity> entities) {
        List<Task> out = new ArrayList<Task>(entities.size());
        for (ApprovalTaskEntity entity : entities) {
            out.add(new Task(entity));
        }
        return out;
    }
}
