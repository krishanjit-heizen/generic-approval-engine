package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.WorkflowDefinitionEntity;
import com.approvalengine.domain.workflow.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, String> {

    /** The version new requests bind to. At most one per (app, key) by construction. */
    Optional<WorkflowDefinitionEntity> findByAppIdAndWorkflowKeyAndStatus(
            String appId, String workflowKey, WorkflowStatus status);

    /**
     * A specific pinned version — including ARCHIVED ones, which is the point: a request that
     * pinned v1 must keep reading v1 long after v2 became active.
     */
    Optional<WorkflowDefinitionEntity> findByAppIdAndWorkflowKeyAndVersion(
            String appId, String workflowKey, int version);

    /** Determines the next version number when publishing an edit. */
    Optional<WorkflowDefinitionEntity> findFirstByAppIdAndWorkflowKeyOrderByVersionDesc(
            String appId, String workflowKey);

    List<WorkflowDefinitionEntity> findByAppIdOrderByWorkflowKeyAscVersionDesc(String appId);

    List<WorkflowDefinitionEntity> findByAppIdAndWorkflowKeyOrderByVersionDesc(
            String appId, String workflowKey);
}
