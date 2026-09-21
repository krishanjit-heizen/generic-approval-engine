package com.approvalengine.adapter.persistence.entity;

import lombok.*;
import javax.persistence.*;
import java.time.Instant;

/**
 * A department an approver spec can name.
 *
 * <p>{@code directory_user.department} carries a foreign key to this table, so a user cannot
 * sit in a department that does not exist.
 */
@Entity @Table(name = "approver_department")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproverDepartmentEntity {

    @Id @Column(name = "dept_code", nullable = false, length = 128)
    private String deptCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
