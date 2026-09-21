package com.approvalengine.adapter.persistence.entity;

import com.approvalengine.domain.notify.OutboxStatus;
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
 * A notification the engine owes a consuming application.
 *
 * <p>The row is written in the <b>same transaction</b> as the state change that produced it.
 * That single fact is what makes "the approval completed but the notification failed"
 * impossible: a committed decision always has a pending notification, so the two cannot
 * diverge. Delivery then becomes a retry problem rather than a consistency problem.
 *
 * <p>Claimed by a <b>lease update</b> rather than {@code SELECT ... FOR UPDATE SKIP LOCKED}:
 * portable across H2 and Postgres, safe with several engine instances running, and
 * self-healing after a crash — an expired lease is simply reclaimable, with no operator
 * involvement and no stuck rows.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** The exact bytes to POST. Signed as-is, so what was audited is what was sent. */
    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    /**
     * Resolved at write time from the request's callback URL or the application's default.
     * Captured here rather than looked up at send time so that re-registering an
     * application cannot silently redirect notifications already in flight.
     */
    @Column(name = "target_url", length = 1024)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Next time this event is eligible for delivery — the backoff schedule lives here. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** Identifies the dispatcher instance holding this row; null when unclaimed. */
    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    /** When the claim expires. A lease past this point is reclaimable by any dispatcher. */
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;
}
