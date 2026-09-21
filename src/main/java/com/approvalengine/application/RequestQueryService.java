package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.AuditEventEntity;
import com.approvalengine.adapter.persistence.entity.StageInstanceEntity;
import com.approvalengine.adapter.persistence.repository.ApprovalTaskRepository;
import com.approvalengine.adapter.persistence.repository.AuditEventRepository;
import com.approvalengine.adapter.persistence.repository.StageInstanceRepository;
import com.approvalengine.adapter.web.dto.RequestViews;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles read models for the request API.
 *
 * <p>Kept separate from {@link RequestService} because the concerns genuinely differ:
 * {@code RequestService} owns write transactions and invariants, this only reads and shapes.
 * Splitting them keeps the write path free of presentation code and makes it obvious that
 * nothing here can mutate state.
 */
@Service
@Transactional(readOnly = true)
public class RequestQueryService {

    private final StageInstanceRepository stages;
    private final ApprovalTaskRepository tasks;
    private final AuditEventRepository auditEvents;
    private final WorkflowService workflows;
    private final JsonCodec json;

    public RequestQueryService(StageInstanceRepository stages,
                               ApprovalTaskRepository tasks,
                               AuditEventRepository auditEvents,
                               WorkflowService workflows,
                               JsonCodec json) {
        this.stages = stages;
        this.tasks = tasks;
        this.auditEvents = auditEvents;
        this.workflows = workflows;
        this.json = json;
    }

    public RequestViews.Detail detail(ApprovalRequestEntity request) {
        List<RequestViews.Stage> stageViews = new ArrayList<RequestViews.Stage>();
        for (StageInstanceEntity instance : stages.findByRequestIdOrderByStageIndexAscAttemptAsc(request.getId())) {
            stageViews.add(new RequestViews.Stage(instance, readJson(instance.getConditionTraceJson())));
        }

        return new RequestViews.Detail(
                RequestViews.Summary.of(request, currentStageId(request)),
                json.readMap(request.getContextJson()),
                stageViews,
                RequestViews.tasks(tasks.findByRequestIdOrderByCreatedAtAsc(request.getId())),
                auditTrail(request.getId()));
    }

    public List<RequestViews.AuditEntry> auditTrail(String requestId) {
        List<RequestViews.AuditEntry> out = new ArrayList<RequestViews.AuditEntry>();
        for (AuditEventEntity event : auditEvents.findByRequestIdOrderBySeqAsc(requestId)) {
            out.add(new RequestViews.AuditEntry(event, readJson(event.getMetadataJson())));
        }
        return out;
    }

    public RequestViews.Summary summary(ApprovalRequestEntity request) {
        return RequestViews.Summary.of(request, currentStageId(request));
    }

    public List<RequestViews.Summary> summaries(List<ApprovalRequestEntity> requests) {
        List<RequestViews.Summary> out = new ArrayList<RequestViews.Summary>(requests.size());
        for (ApprovalRequestEntity request : requests) {
            out.add(summary(request));
        }
        return out;
    }

    /**
     * The stage a request is currently sitting on, or null if it has finished.
     *
     * <p>Read from the pinned definition rather than the live one — a request that started on
     * v1 must report v1's stage ids even after v2 renamed them.
     */
    private String currentStageId(ApprovalRequestEntity request) {
        if (request.getStatus().isTerminal() || request.getCurrentStageIndex() < 0) {
            return null;
        }
        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));
        if (request.getCurrentStageIndex() >= document.getStages().size()) {
            return null;
        }
        return document.getStages().get(request.getCurrentStageIndex()).getId();
    }

    /**
     * Re-inflates stored JSON so it nests as an object in the response rather than appearing
     * as an escaped string.
     *
     * <p>Returns null rather than throwing on unparseable content: a corrupt trace should not
     * make an otherwise-fine request unreadable, and the request's actual state is what the
     * caller came for.
     */
    private JsonNode readJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return json.mapper().readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
