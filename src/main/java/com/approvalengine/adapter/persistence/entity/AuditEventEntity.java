package com.approvalengine.adapter.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One append-only entry in a request's history.
 *
 * <p>Two properties make this an audit trail rather than a log:
 * <ul>
 *   <li><b>Written in the same transaction as the change it describes.</b> History cannot
 *       drift from state, because they commit together or not at all.</li>
 *   <li><b>Totally ordered per request.</b> {@code seq} is a per-request monotonic counter
 *       with a unique constraint, so even when two approvers act at the same instant the
 *       timeline has one unambiguous order — and one of the two writers is forced to retry
 *       rather than interleave.</li>
 * </ul>
 *
 * <p>There is no update or delete path for this table anywhere in the engine, and no
 * repository method that would permit one.
 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** Per-request monotonic sequence. Unique on (request_id, seq). */
    @Column(name = "seq", nullable = false)
    private long seq;

    /** A user id, or {@code SYSTEM} for engine-driven transitions (SLA timeouts, recovery). */
    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** What the transition applied to: {@code REQUEST}, {@code STAGE} or {@code TASK}. */
    @Column(name = "subject_type", nullable = false, length = 32)
    private String subjectType;

    @Column(name = "subject_id", length = 64)
    private String subjectId;

    @Column(name = "from_state", length = 32)
    private String fromState;

    @Column(name = "to_state", length = 32)
    private String toState;

    @Column(name = "comment", length = 2000)
    private String comment;

    /**
     * Condition traces, approver-resolution decisions, error details.
     *
     * <p>This is what lets the history answer *why* and not merely *what* — the difference
     * between "Director stage added" and "Director stage added because
     * amount(250000) &gt; 100000".
     */
    @Lob
    @Column(name = "metadata_json")
    private String metadataJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
