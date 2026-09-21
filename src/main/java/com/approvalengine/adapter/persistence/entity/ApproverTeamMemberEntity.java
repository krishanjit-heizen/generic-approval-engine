package com.approvalengine.adapter.persistence.entity;

import lombok.*;
import javax.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/** Who belongs to a team. */
@Entity @Table(name = "approver_team_member")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproverTeamMemberEntity {

    @EmbeddedId
    private Key key;

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        @Column(name = "team_code", nullable = false, length = 128)
        private String teamCode;

        @Column(name = "user_id", nullable = false, length = 128)
        private String userId;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return Objects.equals(teamCode, k.teamCode) && Objects.equals(userId, k.userId);
        }
        @Override public int hashCode() { return Objects.hash(teamCode, userId); }
    }
}
