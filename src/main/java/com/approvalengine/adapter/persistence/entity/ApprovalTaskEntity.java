package com.approvalengine.adapter.persistence.entity;

import com.approvalengine.domain.request.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One approver's outstanding decision.
 *
 * <p>Approvers are resolved when the stage <i>activates</i>, and snapshotted here. Two
 * consequences worth being explicit about:
 * <ul>
 *   <li>The org chart consulted is the one true at the moment approval was actually needed,
 *       not at submission time.</li>
 *   <li>A later directory change — someone changes team, someone leaves — cannot
 *       retroactively alter an already-active stage.</li>
 * </ul>
 *
 * <p>The unique constraint on {@code (stage_instance_id, approver_id)} is load-bearing
 * rather than merely tidy: it is what makes re-driving a half-finished stage activation
 * idempotent, and therefore what lets crash recovery simply replay the advancement.
 */
@Entity
@Table(name = "approval_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalTaskEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /** Denormalised from the stage for inbox and audit queries that never need the stage row. */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "stage_instance_id", nullable = false, length = 64)
    private String stageInstanceId;

    @Column(name = "approver_id", nullable = false, length = 128)
    private String approverId;

    /**
     * Human-readable provenance, e.g. {@code MANAGER_OF(user-123)} or {@code ROLE(FINANCE)}.
     * Recorded so an audit reader can tell *why* this person was asked, without re-running
     * resolution against a directory that has since changed.
     */
    @Column(name = "resolved_via", nullable = false)
    private String resolvedVia;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status;

    /**
     * Who actually acted. Normally equal to {@code approverId}; differs when the SLA sweeper
     * resolves the task, in which case it is {@code SYSTEM}.
     */
    @Column(name = "acted_by", length = 128)
    private String actedBy;

    @Column(name = "comment", length = 2000)
    private String comment;

    /** Derived from the stage's slaSeconds at activation; drives the SLA sweeper. */
    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
