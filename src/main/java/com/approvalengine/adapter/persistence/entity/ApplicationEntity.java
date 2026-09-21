package com.approvalengine.adapter.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * A registered consuming application — the tenancy root.
 *
 * <p>Every workflow and every request belongs to exactly one of these, and workflow keys are
 * namespaced by {@code appId}. Without that, the first consumer's naming would become the
 * global namespace and "reusable service" would be aspirational.
 */
@Entity
@Table(name = "application")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationEntity {

    @Id
    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Only the hash is stored. The plaintext key is returned once, at registration, and is
     * unrecoverable afterwards — the engine has no legitimate reason to be able to read it
     * back, so it cannot.
     */
    @Column(name = "api_key_hash", nullable = false, length = 128)
    private String apiKeyHash;

    /** Default webhook target; a request may override it per submission. */
    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 128)
    private String webhookSecret;

    /**
     * The previous signing secret, kept live during a rotation. The engine signs with
     * {@code webhookSecret}; a consumer mid-rotation accepts either, so neither side needs
     * a synchronised deploy.
     */
    @Column(name = "webhook_secret_prev", length = 128)
    private String webhookSecretPrev;

    /** Per-application delivery attempt ceiling before an event is dead-lettered. */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
