package com.approvalengine.adapter.persistence.entity;

import lombok.*;
import javax.persistence.*;
import java.time.Instant;

/** A team an approver spec can name. */
@Entity @Table(name = "approver_team")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproverTeamEntity {

    @Id @Column(name = "team_code", nullable = false, length = 128)
    private String teamCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
