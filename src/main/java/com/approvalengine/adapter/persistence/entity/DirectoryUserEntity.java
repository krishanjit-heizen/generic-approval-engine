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

/**
 * A person the engine can ask for approval.
 *
 * <p>This is the local stand-in for a real HRMS or IAM directory. The engine only ever
 * reaches it through the {@code DirectoryService} interface, so replacing it with a real
 * one is an adapter swap, not a schema change.
 *
 * <p>{@code active} is not cosmetic: an inactive user is never resolved as an approver, and
 * that is precisely what triggers a stage's {@code onUnresolved} fallback chain. Modelling
 * "this person has left" as a directory fact rather than an engine special-case is what
 * keeps "the approver no longer exists" an ordinary, auditable outcome.
 */
@Entity
@Table(name = "directory_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectoryUserEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "email")
    private String email;

    /** Resolves {@code MANAGER_OF} specs, and the target of SLA {@code ESCALATE}. */
    @Column(name = "manager_id", length = 128)
    private String managerId;

    @Column(name = "department", length = 128)
    private String department;

    @Column(name = "active", nullable = false)
    private boolean active;
}
