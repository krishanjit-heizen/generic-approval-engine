package com.approvalengine.adapter.persistence.entity;

import com.approvalengine.domain.request.RequestStatus;
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
import javax.persistence.Version;
import java.time.Instant;

/**
 * One approval process over one business entity.
 *
 * <p>{@code entityType} and {@code entityId} are opaque strings, never foreign keys: the
 * engine has no access to and no opinion about the consuming application's data model. Its
 * output is a decision event, not an action on the entity.
 */
@Entity
@Table(name = "approval_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRequestEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    /**
     * The workflow version this request is PINNED to, captured at creation.
     *
     * <p>Publishing a new version of the workflow does not affect a request already running:
     * it keeps executing {@code workflowDefId} to completion. This is the whole mechanism
     * behind "a workflow was changed mid-flight" — there is no migration, because there is
     * nothing to migrate.
     */
    @Column(name = "workflow_key", nullable = false, length = 128)
    private String workflowKey;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;

    @Column(name = "workflow_def_id", nullable = false, length = 64)
    private String workflowDefId;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    /**
     * Immutable snapshot of the business context the rules were evaluated against.
     *
     * <p>Snapshotting rather than re-fetching matters for auditability: six months later the
     * expense amount in the consuming app may have changed, but the approval must still be
     * explainable in terms of what the approvers actually saw.
     */
    @Lob
    @Column(name = "context_json", nullable = false)
    private String contextJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RequestStatus status;

    /** Index into the workflow's stage list; -1 until the first stage activates. */
    @Column(name = "current_stage_index", nullable = false)
    private int currentStageIndex;

    /** Per-request override of the application's registered webhook URL. */
    @Column(name = "callback_url", length = 1024)
    private String callbackUrl;

    /** Populated when status is NEEDS_ATTENTION, so an operator knows what to fix. */
    @Column(name = "fault_reason", length = 1024)
    private String faultReason;

    /**
     * Optimistic lock. Stage advancement re-counts sibling tasks and bumps this in the same
     * transaction, so two approvers acting simultaneously cannot both advance the stage —
     * the second commit fails and is retried against fresh state.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    // Note: the generated `live_entity_key` column is deliberately NOT mapped. It exists
    // purely to give the database a uniqueness guard over non-terminal requests, and
    // Hibernate must never try to write it.
}
