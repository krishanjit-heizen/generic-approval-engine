package com.approvalengine.adapter.persistence.entity;

import lombok.*;
import javax.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/**
 * Who holds a role.
 *
 * <p>Read in both directions, which is why the migration indexes both columns:
 * role → members drives approver resolution, and user → roles is what lets the engine
 * re-verify at action time that an approver still holds the role that got them the task.
 */
@Entity @Table(name = "approver_role_member")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproverRoleMemberEntity {

    @EmbeddedId
    private Key key;

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        @Column(name = "role_code", nullable = false, length = 128)
        private String roleCode;

        @Column(name = "user_id", nullable = false, length = 128)
        private String userId;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return Objects.equals(roleCode, k.roleCode) && Objects.equals(userId, k.userId);
        }
        @Override public int hashCode() { return Objects.hash(roleCode, userId); }
    }
}
