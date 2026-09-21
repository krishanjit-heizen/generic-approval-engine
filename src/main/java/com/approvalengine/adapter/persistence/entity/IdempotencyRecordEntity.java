package com.approvalengine.adapter.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/**
 * A remembered response, so a retried POST does not execute twice.
 *
 * <p>The consuming application retries on network failure — that is correct client
 * behaviour, and the engine has to make it safe rather than ask them not to.
 *
 * <ul>
 *   <li>Same key, same body → replay the stored response verbatim. No second request, no
 *       second approval.</li>
 *   <li>Same key, different body → 422. The client reused a key for a different intent,
 *       which is a bug worth surfacing rather than guessing about.</li>
 * </ul>
 *
 * <p>Keyed by {@code (appId, idemKey)}: keys are only unique within a consuming application,
 * so two tenants choosing the same UUID cannot collide.
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecordEntity {

    @EmbeddedId
    private Key key;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    /** Hash of the canonical request body, to distinguish replay from key reuse. */
    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Lob
    @Column(name = "response_body")
    private String responseBody;

    /**
     * Set once the originating transaction commits.
     *
     * <p>A null here means "in flight": the row was claimed but its work has not finished.
     * A retry that arrives during that window is told to retry later rather than being
     * handed an empty response, which is what stops a duplicate from slipping through the
     * gap between claiming a key and completing the work.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "app_id", nullable = false, length = 64)
        private String appId;

        @Column(name = "idem_key", nullable = false)
        private String idemKey;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return java.util.Objects.equals(appId, other.appId)
                    && java.util.Objects.equals(idemKey, other.idemKey);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(appId, idemKey);
        }
    }
}
