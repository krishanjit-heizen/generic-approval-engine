package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApproverRoleMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApproverRoleMemberRepository
        extends JpaRepository<ApproverRoleMemberEntity, ApproverRoleMemberEntity.Key> {

    /**
     * Role → its active members, for approver resolution.
     *
     * <p>The join filters on both the role being active and the user being active, in SQL. That
     * matters: "the role exists but everyone in it has left" must resolve to an empty set,
     * because an empty set is exactly what triggers a stage's onUnresolved fallback. Filtering
     * in the caller instead would let a departed employee be handed a task.
     *
     * <p>Ordered for stability — a SEQUENTIAL stage asks in the order returned.
     */
    @Query("SELECT m.key.userId FROM ApproverRoleMemberEntity m, DirectoryUserEntity u, ApproverRoleEntity r "
            + "WHERE m.key.userId = u.userId AND m.key.roleCode = r.roleCode "
            + "AND u.active = TRUE AND r.active = TRUE AND m.key.roleCode = :roleCode "
            + "ORDER BY m.key.userId ASC")
    List<String> findActiveMembers(@Param("roleCode") String roleCode);

    /**
     * User → the roles they currently hold.
     *
     * <p>This is the authorisation direction: the client tells the engine a user id, and the
     * engine looks the roles up itself rather than trusting anything the client claims. It also
     * backs the re-check that an approver still holds the role that got them their task.
     */
    @Query("SELECT m.key.roleCode FROM ApproverRoleMemberEntity m, ApproverRoleEntity r "
            + "WHERE m.key.roleCode = r.roleCode AND r.active = TRUE AND m.key.userId = :userId "
            + "ORDER BY m.key.roleCode ASC")
    List<String> findRolesOf(@Param("userId") String userId);
}
