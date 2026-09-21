package com.approvalengine.adapter.web;

import com.approvalengine.adapter.persistence.entity.ApproverRoleEntity;
import com.approvalengine.adapter.persistence.entity.ApproverRoleMemberEntity;
import com.approvalengine.adapter.persistence.entity.WorkflowApproverRuleEntity;
import com.approvalengine.adapter.persistence.repository.ApproverRoleMemberRepository;
import com.approvalengine.adapter.persistence.repository.ApproverRoleRepository;
import com.approvalengine.adapter.persistence.repository.WorkflowApproverRuleRepository;
import com.approvalengine.adapter.persistence.repository.WorkflowDefinitionRepository;
import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.workflow.ApproverSpec;
import com.approvalengine.error.NotFoundException;
import com.approvalengine.error.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Managing the approver model: roles, their members, and what routes to them.
 *
 * <p>These endpoints exist because the model is now relational rather than a bag of strings.
 * Two of them earn their place beyond CRUD convenience:
 *
 * <ul>
 *   <li>{@code GET /roles/{code}/routed-workflows} answers "what breaks if we retire this
 *       role?" — the question you have to be able to answer <i>before</i> reorganising, and the
 *       whole reason approver rules are projected into rows instead of left inside JSON.</li>
 *   <li>{@code GET /users/{id}/entitlements} shows what the engine will resolve for a user.
 *       Since the client sends only a user id and the engine looks entitlements up itself,
 *       being able to see exactly what it found is what makes an authorisation decision
 *       debuggable rather than mysterious.</li>
 * </ul>
 *
 * <p>In a real deployment the directory is owned by an HRMS and these would be read-only.
 */
@RestController
@RequestMapping("/api/v1/admin/directory")
@Tag(name = "Directory", description = "Roles, membership, and approver-routing impact analysis")
public class AdminDirectoryController {

    private final ApproverRoleRepository roles;
    private final ApproverRoleMemberRepository roleMembers;
    private final WorkflowApproverRuleRepository rules;
    private final WorkflowDefinitionRepository definitions;
    private final DirectoryService directory;

    public AdminDirectoryController(ApproverRoleRepository roles,
                                    ApproverRoleMemberRepository roleMembers,
                                    WorkflowApproverRuleRepository rules,
                                    WorkflowDefinitionRepository definitions,
                                    DirectoryService directory) {
        this.roles = roles;
        this.roleMembers = roleMembers;
        this.rules = rules;
        this.definitions = definitions;
        this.directory = directory;
    }

    @GetMapping("/roles")
    @Operation(summary = "List roles with their current member count")
    public List<RoleView> listRoles() {
        List<RoleView> out = new ArrayList<RoleView>();
        for (ApproverRoleEntity role : roles.findByActiveTrueOrderByRoleCodeAsc()) {
            out.add(new RoleView(role.getRoleCode(), role.getName(), role.getDescription(),
                    directory.findActiveByRole(role.getRoleCode())));
        }
        return out;
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Define a role",
            description = "A role must exist before a workflow can be published against it — "
                    + "that ordering is what turns a typo into a 422 instead of a stuck request.")
    @Transactional
    public RoleView createRole(@RequestBody RoleBody body) {
        if (body.getRoleCode() == null || body.getRoleCode().trim().isEmpty()) {
            throw new ValidationException("role-code-required", "'roleCode' is required");
        }
        String code = body.getRoleCode().trim();
        ApproverRoleEntity role = roles.findById(code).orElseGet(() ->
                ApproverRoleEntity.builder()
                        .roleCode(code)
                        .name(body.getName() == null ? code : body.getName())
                        .description(body.getDescription())
                        .active(true)
                        .createdAt(Instant.now())
                        .build());
        roles.save(role);
        return new RoleView(role.getRoleCode(), role.getName(), role.getDescription(),
                directory.findActiveByRole(role.getRoleCode()));
    }

    @PostMapping("/roles/{roleCode}/members/{userId}")
    @Operation(summary = "Grant a role to a user",
            description = "The usual fix for a request parked in NEEDS_ATTENTION because a role "
                    + "resolved to nobody. Follow it with a reassign to replay the activation.")
    @Transactional
    public RoleView addMember(@PathVariable String roleCode, @PathVariable String userId) {
        requireRole(roleCode);
        if (!directory.userExists(userId)) {
            throw new NotFoundException("user-not-found",
                    "user '" + userId + "' does not exist or is inactive");
        }
        roleMembers.save(ApproverRoleMemberEntity.builder()
                .key(ApproverRoleMemberEntity.Key.builder().roleCode(roleCode).userId(userId).build())
                .build());
        return new RoleView(roleCode, null, null, directory.findActiveByRole(roleCode));
    }

    @DeleteMapping("/roles/{roleCode}/members/{userId}")
    @Operation(summary = "Revoke a role",
            description = "Takes effect immediately for resolution AND for anyone already holding "
                    + "a task assigned via this role — acting on it is refused, because the task "
                    + "proves they were asked, not that they are still the right person.")
    @Transactional
    public RoleView removeMember(@PathVariable String roleCode, @PathVariable String userId) {
        requireRole(roleCode);
        roleMembers.deleteById(
                ApproverRoleMemberEntity.Key.builder().roleCode(roleCode).userId(userId).build());
        return new RoleView(roleCode, null, null, directory.findActiveByRole(roleCode));
    }

    @GetMapping("/users/{userId}/entitlements")
    @Operation(summary = "What the engine resolves for this user",
            description = "The client sends only a user id; the engine looks entitlements up "
                    + "itself and never accepts them from the caller. This shows what it found.")
    public Map<String, Object> entitlements(@PathVariable String userId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("userId", userId);
        out.put("active", Boolean.valueOf(directory.userExists(userId)));
        out.put("roles", directory.rolesOf(userId));
        out.put("teams", directory.teamsOf(userId));
        out.put("department", directory.departmentOf(userId).orElse(null));
        out.put("manager", directory.findManagerOf(userId).orElse(null));
        return out;
    }

    @GetMapping("/roles/{roleCode}/routed-workflows")
    @Operation(summary = "Which published workflows route approvals to this role",
            description = "Impact analysis before retiring a role or reorganising a team. This is "
                    + "the query that justifies projecting approver rules into rows rather than "
                    + "leaving them inside the definition JSON.")
    public List<RoutedWorkflow> routedWorkflows(@PathVariable String roleCode) {
        List<RoutedWorkflow> out = new ArrayList<RoutedWorkflow>();
        for (WorkflowApproverRuleEntity rule
                : rules.findByApproverTypeAndTarget(ApproverSpec.Type.ROLE, roleCode)) {
            definitions.findById(rule.getWorkflowDefId()).ifPresent(definition ->
                    out.add(new RoutedWorkflow(definition.getWorkflowKey(), definition.getVersion(),
                            definition.getStatus().name(), rule.getStageId(),
                            rule.getFallbackDepth() > 0)));
        }
        return out;
    }

    private void requireRole(String roleCode) {
        if (!roles.findById(roleCode).isPresent()) {
            throw new NotFoundException("role-not-found", "no role '" + roleCode + "'");
        }
    }

    // --- shapes -----------------------------------------------------------

    @Getter
    @Setter
    public static class RoleBody {
        private String roleCode;
        private String name;
        private String description;
    }

    @Getter
    public static final class RoleView {
        private final String roleCode;
        private final String name;
        private final String description;
        private final List<String> members;
        private final int memberCount;

        RoleView(String roleCode, String name, String description, List<String> members) {
            this.roleCode = roleCode;
            this.name = name;
            this.description = description;
            this.members = members;
            this.memberCount = members == null ? 0 : members.size();
        }
    }

    @Getter
    public static final class RoutedWorkflow {
        private final String workflowKey;
        private final int version;
        private final String status;
        private final String stageId;

        /** True when the role is only reached as an onUnresolved fallback, not the primary route. */
        private final boolean asFallback;

        RoutedWorkflow(String workflowKey, int version, String status, String stageId,
                       boolean asFallback) {
            this.workflowKey = workflowKey;
            this.version = version;
            this.status = status;
            this.stageId = stageId;
            this.asFallback = asFallback;
        }
    }
}
