package com.approvalengine.adapter.persistence.entity;

import com.approvalengine.domain.request.StageStatus;
import com.approvalengine.domain.workflow.StageDefinition;
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
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One stage of a running request — including stages that were evaluated and skipped.
 *
 * <p>Recording skipped stages is a deliberate choice. A stage whose condition was false
 * still gets a row, carrying the {@code conditionTraceJson} that skipped it, so the history
 * shows what the engine considered rather than only what it ran. Without it, "why was there
 * no Director approval on this ₹2,00,000 expense?" is unanswerable after the fact.
 */
@Entity
@Table(name = "stage_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageInstanceEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** The stage's id as written in the workflow definition. */
    @Column(name = "stage_id", nullable = false, length = 128)
    private String stageId;

    @Column(name = "stage_index", nullable = false)
    private int stageIndex;

    /**
     * Which visit to this stage this row represents, starting at 1.
     *
     * <p>A stage is revisited when {@code onReject: GOTO} sends work back, or when a
     * requester resubmits after changes were requested. Each visit gets its own row rather
     * than mutating the previous one — partly so {@code approval_task}'s
     * {@code (stage_instance_id, approver_id)} uniqueness can keep making crash re-drive
     * idempotent, and partly because "Finance rejected on attempt 1, approved on attempt 2"
     * is the audit trail anyone investigating actually needs.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private StageDefinition.Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StageStatus status;

    /**
     * Snapshot of the completion policy in force for this stage, e.g.
     * {@code {"type":"QUORUM","count":2}}. Snapshotted rather than re-read from the
     * definition so the stage's own row explains its own completion rule.
     */
    @Lob
    @Column(name = "policy_json")
    private String policyJson;

    /** Why this stage applied, or did not — the per-node condition evaluation record. */
    @Lob
    @Column(name = "condition_trace_json")
    private String conditionTraceJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_reject", length = 64)
    private StageDefinition.RejectAction onReject;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
