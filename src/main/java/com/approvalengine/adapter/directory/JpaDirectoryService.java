package com.approvalengine.adapter.directory;

import com.approvalengine.adapter.persistence.entity.DirectoryUserEntity;
import com.approvalengine.adapter.persistence.repository.ApproverDepartmentRepository;
import com.approvalengine.adapter.persistence.repository.ApproverRoleMemberRepository;
import com.approvalengine.adapter.persistence.repository.ApproverRoleRepository;
import com.approvalengine.adapter.persistence.repository.ApproverTeamMemberRepository;
import com.approvalengine.adapter.persistence.repository.ApproverTeamRepository;
import com.approvalengine.adapter.persistence.repository.DirectoryUserRepository;
import com.approvalengine.domain.directory.DirectoryPerson;
import com.approvalengine.domain.directory.DirectoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Database-backed directory: roles, teams, departments and reporting lines as real tables.
 *
 * <p>Everything organisation-specific reaches the engine through {@link DirectoryService}, so
 * pointing this at a real HRMS or IAM system is one class, with no change to resolution,
 * execution or the API.
 *
 * <p>Active-filtering happens in SQL, in the repository queries, rather than here. That is not
 * a performance nicety: "the role exists but everyone in it has left" has to resolve to an
 * empty set, because an empty set is what triggers a stage's {@code onUnresolved} fallback. If
 * a departed employee slipped through, the engine would create a task nobody can ever action.
 */
@Service
@Transactional(readOnly = true)
public class JpaDirectoryService implements DirectoryService {

    private final DirectoryUserRepository users;
    private final ApproverRoleRepository roles;
    private final ApproverTeamRepository teams;
    private final ApproverDepartmentRepository departments;
    private final ApproverRoleMemberRepository roleMembers;
    private final ApproverTeamMemberRepository teamMembers;

    public JpaDirectoryService(DirectoryUserRepository users,
                               ApproverRoleRepository roles,
                               ApproverTeamRepository teams,
                               ApproverDepartmentRepository departments,
                               ApproverRoleMemberRepository roleMembers,
                               ApproverTeamMemberRepository teamMembers) {
        this.users = users;
        this.roles = roles;
        this.teams = teams;
        this.departments = departments;
        this.roleMembers = roleMembers;
        this.teamMembers = teamMembers;
    }

    @Override
    public Optional<DirectoryPerson> findActivePerson(String userId) {
        if (blank(userId)) {
            return Optional.empty();
        }
        return users.findByUserIdAndActiveTrue(userId).map(JpaDirectoryService::toPerson);
    }

    @Override
    public Optional<String> findManagerOf(String userId) {
        Optional<DirectoryUserEntity> user = users.findByUserIdAndActiveTrue(userId);
        if (!user.isPresent()) {
            return Optional.empty();
        }
        String managerId = user.get().getManagerId();
        if (blank(managerId)) {
            return Optional.empty();
        }
        // The manager must be active too. Escalating to someone who has left would create a
        // task nobody can action, so this returns empty and lets the stage's onUnresolved
        // policy decide — visibly, in the audit trail.
        return users.findByUserIdAndActiveTrue(managerId).map(DirectoryUserEntity::getUserId);
    }

    @Override
    public List<String> findActiveByRole(String role) {
        return blank(role) ? Collections.<String>emptyList() : roleMembers.findActiveMembers(role);
    }

    @Override
    public List<String> findActiveByTeam(String team) {
        return blank(team) ? Collections.<String>emptyList() : teamMembers.findActiveMembers(team);
    }

    @Override
    public List<String> findActiveByDepartment(String department) {
        if (blank(department)) {
            return Collections.emptyList();
        }
        List<DirectoryUserEntity> found = users.findByDepartmentAndActiveTrue(department);
        List<String> ids = new ArrayList<String>(found.size());
        for (DirectoryUserEntity user : found) {
            ids.add(user.getUserId());
        }
        // Stable order: a SEQUENTIAL stage asks in the order given, so an unstable one would
        // make the same workflow behave differently between runs.
        Collections.sort(ids);
        return ids;
    }

    @Override
    public Set<String> rolesOf(String userId) {
        if (blank(userId) || !users.findByUserIdAndActiveTrue(userId).isPresent()) {
            // An inactive user holds nothing, regardless of what memberships still exist.
            return Collections.emptySet();
        }
        return new LinkedHashSet<String>(roleMembers.findRolesOf(userId));
    }

    @Override
    public Set<String> teamsOf(String userId) {
        if (blank(userId) || !users.findByUserIdAndActiveTrue(userId).isPresent()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<String>(teamMembers.findTeamsOf(userId));
    }

    @Override
    public Optional<String> departmentOf(String userId) {
        return users.findByUserIdAndActiveTrue(userId)
                .map(DirectoryUserEntity::getDepartment);
    }

    @Override
    public boolean roleExists(String role) {
        return !blank(role) && roles.existsByRoleCodeAndActiveTrue(role);
    }

    @Override
    public boolean teamExists(String team) {
        return !blank(team) && teams.existsByTeamCodeAndActiveTrue(team);
    }

    @Override
    public boolean departmentExists(String department) {
        return !blank(department) && departments.existsByDeptCodeAndActiveTrue(department);
    }

    @Override
    public boolean userExists(String userId) {
        return !blank(userId) && users.findByUserIdAndActiveTrue(userId).isPresent();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static DirectoryPerson toPerson(DirectoryUserEntity entity) {
        return DirectoryPerson.builder()
                .userId(entity.getUserId())
                .displayName(entity.getDisplayName())
                .email(entity.getEmail())
                .managerId(entity.getManagerId())
                .department(entity.getDepartment())
                .build();
    }
}
