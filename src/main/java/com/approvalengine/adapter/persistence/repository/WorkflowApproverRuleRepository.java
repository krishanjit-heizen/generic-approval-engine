package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.WorkflowApproverRuleEntity;
import com.approvalengine.domain.workflow.ApproverSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowApproverRuleRepository
        extends JpaRepository<WorkflowApproverRuleEntity, String> {

    List<WorkflowApproverRuleEntity> findByWorkflowDefIdOrderByStageIndexAscOrdinalAscFallbackDepthAsc(
            String workflowDefId);

    /**
     * Impact analysis: every published workflow that routes to a given role, team or user.
     *
     * <p>The question you need answered before retiring a role or reorganising a team, and the
     * reason these rules are projected into rows rather than left inside a JSON blob.
     */
    List<WorkflowApproverRuleEntity> findByApproverTypeAndTarget(ApproverSpec.Type approverType,
                                                                String target);

    void deleteByWorkflowDefId(String workflowDefId);
}
