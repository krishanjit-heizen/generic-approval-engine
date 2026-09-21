package com.approvalengine.adapter.persistence.entity;

import lombok.*;
import javax.persistence.*;
import java.time.Instant;

/**
 * A role an approver spec can name — now an entity rather than an opaque string.
 *
 * <p>Being a real row is what lets the engine reject a workflow that routes to a role nobody
 * defined, at publish time, instead of discovering the typo when a live request parks in
 * NEEDS_ATTENTION.
 */
@Entity @Table(name = "approver_role")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproverRoleEntity {

    @Id @Column(name = "role_code", nullable = false, length = 128)
    private String roleCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    /** A retired role stops resolving without erasing who used to hold it. */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
