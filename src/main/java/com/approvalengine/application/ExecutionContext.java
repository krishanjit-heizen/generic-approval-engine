package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import lombok.Getter;

/**
 * Everything the executor needs about one request, gathered once.
 *
 * <p>Exists to keep the executor's methods from taking five correlated parameters, and — more
 * usefully — to guarantee they all describe the same request: the definition here is the
 * <b>pinned</b> version this request started on, not whatever is currently active, and the
 * evaluation context is the immutable snapshot taken at submission.
 *
 * <p>Assembled by {@code RequestService} / {@code ActionService} at the start of a
 * transaction and passed down. Cheap to build; never cached across transactions.
 */
@Getter
public final class ExecutionContext {

    private final ApprovalRequestEntity request;
    private final ApplicationEntity application;

    /** The pinned workflow version, which may well be ARCHIVED by now. */
    private final WorkflowDocument document;

    private final EvaluationContext evaluation;

    public ExecutionContext(ApprovalRequestEntity request,
                            ApplicationEntity application,
                            WorkflowDocument document,
                            EvaluationContext evaluation) {
        this.request = request;
        this.application = application;
        this.document = document;
        this.evaluation = evaluation;
    }

    /** Replaces the evaluation context after a resubmission changed the request's data. */
    public ExecutionContext withEvaluation(EvaluationContext replacement) {
        return new ExecutionContext(request, application, document, replacement);
    }

    public int stageCount() {
        return document.getStages().size();
    }

    public StageDefinition stageAt(int index) {
        return document.getStages().get(index);
    }

    public String requestId() {
        return request.getId();
    }
}
