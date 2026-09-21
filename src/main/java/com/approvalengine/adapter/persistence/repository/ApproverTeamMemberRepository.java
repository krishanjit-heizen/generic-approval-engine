package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApproverTeamMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApproverTeamMemberRepository
        extends JpaRepository<ApproverTeamMemberEntity, ApproverTeamMemberEntity.Key> {

    /** Team → active members. Same active-filtering rationale as roles. */
    @Query("SELECT m.key.userId FROM ApproverTeamMemberEntity m, DirectoryUserEntity u, ApproverTeamEntity t "
            + "WHERE m.key.userId = u.userId AND m.key.teamCode = t.teamCode "
            + "AND u.active = TRUE AND t.active = TRUE AND m.key.teamCode = :teamCode "
            + "ORDER BY m.key.userId ASC")
    List<String> findActiveMembers(@Param("teamCode") String teamCode);

    @Query("SELECT m.key.teamCode FROM ApproverTeamMemberEntity m, ApproverTeamEntity t "
            + "WHERE m.key.teamCode = t.teamCode AND t.active = TRUE AND m.key.userId = :userId "
            + "ORDER BY m.key.teamCode ASC")
    List<String> findTeamsOf(@Param("userId") String userId);
}
