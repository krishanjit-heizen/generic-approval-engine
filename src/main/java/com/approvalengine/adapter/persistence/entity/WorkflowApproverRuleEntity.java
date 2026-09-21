package com.approvalengine.adapter.persistence.entity;

import com.approvalengine.domain.workflow.ApproverSpec;
import lombok.*;
import javax.persistence.*;

/**
 * One approver rule from a published workflow, projected out of the definition JSON.
 *
 * <p>The JSON remains the archival artifact — the exact bytes an author published. These rows
 * exist so the model is queryable and checkable: publish-time validation confirms every role,
 * team, department and user a workflow names actually exists, and "which workflows route to
 * FINANCE?" becomes one query rather than a scan of JSON blobs. That question has to be
 * answerable before anyone retires a role or reorganises a team.
 *
 * <p>Written once at publish and never updated; a new workflow version writes new rows.
 */
@Entity @Table(name = "workflow_approver_rule")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowApproverRuleEntity {

    @Id @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "workflow_def_id", nullable = false, length = 64)
    private String workflowDefId;

    @Column(name = "stage_id", nullable = false, length = 128)
    private String stageId;

    @Column(name = "stage_index", nullable = false)
    private int stageIndex;

    /** Position within the stage — load-bearing for SEQUENTIAL, which asks in order. */
    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    /** Depth in an onUnresolved fallback chain; 0 is the primary spec. */
    @Column(name = "fallback_depth", nullable = false)
    private int fallbackDepth;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_type", nullable = false, length = 32)
    private ApproverSpec.Type approverType;

    /** Role code, team code, department code, user id, context path, or URL. */
    @Column(name = "target", length = 1024)
    private String target;
}
