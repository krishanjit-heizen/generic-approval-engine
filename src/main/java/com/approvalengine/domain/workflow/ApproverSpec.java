package com.approvalengine.domain.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A declaration of *who* should approve, resolved against the directory at the moment the
 * stage activates.
 *
 * <p>This is the seam that makes the engine domain-agnostic: a workflow says "the
 * requester's manager" or "whoever holds the FINANCE role", never a hard-coded user list.
 *
 * <pre>{@code
 * { "type": "USER",         "userId": "user-900" }
 * { "type": "ROLE",         "role": "FINANCE" }
 * { "type": "TEAM",         "team": "payments" }
 * { "type": "DEPARTMENT",   "department": "Engineering" }
 * { "type": "MANAGER_OF",   "of": "$requestedBy" }
 * { "type": "CONTEXT_PATH", "path": "projectOwnerId" }
 * { "type": "EXTERNAL_HTTP","url": "https://app.internal/resolve-approvers" }
 * }</pre>
 *
 * <p>Every spec may carry an {@code onUnresolved} policy. That is what turns "the approver
 * left the company" from an exception into a declared, auditable decision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApproverSpec {

    /** Token usable in {@code of} / {@code path} to mean the submitting user. */
    public static final String REQUESTER_TOKEN = "$requestedBy";

    public enum Type {
        /** A named individual. */
        USER,
        /** Everyone holding a role. */
        ROLE,
        /** Everyone in a team. */
        TEAM,
        /** Everyone in a department. */
        DEPARTMENT,
        /** The manager of a given user (or of the requester). */
        MANAGER_OF,
        /** An approver id carried in the request context, e.g. a project owner. */
        CONTEXT_PATH,
        /** Ask the consuming application over HTTP. */
        EXTERNAL_HTTP
    }

    /** What to do when a spec resolves to nobody usable. */
    public enum UnresolvedAction {
        /** Try another spec instead. Chains, with a depth limit. */
        FALLBACK_TO,
        /** Treat the stage as not applicable and move on. Recorded in the audit trail. */
        SKIP_STAGE,
        /**
         * Park the request in NEEDS_ATTENTION for an operator to reassign. The default —
         * failing loudly beats quietly approving something nobody looked at.
         */
        FAIL_REQUEST
    }

    /** {@link UnresolvedAction} plus, for FALLBACK_TO, the spec to try next. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class UnresolvedPolicy {
        private final UnresolvedAction action;
        private final ApproverSpec spec;

        @JsonCreator
        public UnresolvedPolicy(@JsonProperty("action") UnresolvedAction action,
                               @JsonProperty("spec") ApproverSpec spec) {
            this.action = action == null ? UnresolvedAction.FAIL_REQUEST : action;
            this.spec = spec;
        }

        public UnresolvedAction getAction() {
            return action;
        }

        public ApproverSpec getSpec() {
            return spec;
        }

        void validate(String path, List<String> errors, int depth) {
            if (action == UnresolvedAction.FALLBACK_TO) {
                if (spec == null) {
                    errors.add(path + ": onUnresolved FALLBACK_TO requires a 'spec' to fall back to");
                } else {
                    spec.validate(path + ".spec", errors, depth + 1);
                }
            }
        }
    }

    /**
     * Guards against a definition whose fallback chain loops or nests unreasonably deep.
     * Enforced both at publish (validation) and at resolution time, because a definition
     * could be edited in the database after publication.
     */
    public static final int MAX_FALLBACK_DEPTH = 5;

    private final Type type;
    private final String userId;
    private final String role;
    private final String team;
    private final String department;
    private final String of;
    private final String path;
    private final String url;
    private final UnresolvedPolicy onUnresolved;

    @JsonCreator
    public ApproverSpec(@JsonProperty("type") Type type,
                        @JsonProperty("userId") String userId,
                        @JsonProperty("role") String role,
                        @JsonProperty("team") String team,
                        @JsonProperty("department") String department,
                        @JsonProperty("of") String of,
                        @JsonProperty("path") String path,
                        @JsonProperty("url") String url,
                        @JsonProperty("onUnresolved") UnresolvedPolicy onUnresolved) {
        this.type = type;
        this.userId = userId;
        this.role = role;
        this.team = team;
        this.department = department;
        this.of = of;
        this.path = path;
        this.url = url;
        this.onUnresolved = onUnresolved;
    }

    public Type getType() {
        return type;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getTeam() {
        return team;
    }

    public String getDepartment() {
        return department;
    }

    public String getOf() {
        return of;
    }

    public String getPath() {
        return path;
    }

    public String getUrl() {
        return url;
    }

    public UnresolvedPolicy getOnUnresolved() {
        return onUnresolved;
    }

    /**
     * Human-readable provenance recorded on every task this spec produces, e.g.
     * {@code MANAGER_OF(user-123)}. Persisted so an audit reader can tell why a particular
     * person was asked, months later, without re-running resolution.
     */
    public String describe() {
        if (type == null) {
            return "UNKNOWN";
        }
        switch (type) {
            case USER:
                return "USER(" + userId + ")";
            case ROLE:
                return "ROLE(" + role + ")";
            case TEAM:
                return "TEAM(" + team + ")";
            case DEPARTMENT:
                return "DEPARTMENT(" + department + ")";
            case MANAGER_OF:
                return "MANAGER_OF(" + of + ")";
            case CONTEXT_PATH:
                return "CONTEXT_PATH(" + path + ")";
            case EXTERNAL_HTTP:
                return "EXTERNAL_HTTP(" + url + ")";
            default:
                return type.name();
        }
    }

    public void validate(String errorPath, List<String> errors) {
        validate(errorPath, errors, 0);
    }

    void validate(String errorPath, List<String> errors, int depth) {
        if (depth > MAX_FALLBACK_DEPTH) {
            errors.add(errorPath + ": onUnresolved fallback chain is deeper than "
                    + MAX_FALLBACK_DEPTH + " levels");
            return;
        }
        if (type == null) {
            errors.add(errorPath + ": approver requires a 'type'");
            return;
        }
        switch (type) {
            case USER:
                requireField(userId, "userId", errorPath, errors);
                break;
            case ROLE:
                requireField(role, "role", errorPath, errors);
                break;
            case TEAM:
                requireField(team, "team", errorPath, errors);
                break;
            case DEPARTMENT:
                requireField(department, "department", errorPath, errors);
                break;
            case MANAGER_OF:
                requireField(of, "of", errorPath, errors);
                break;
            case CONTEXT_PATH:
                requireField(path, "path", errorPath, errors);
                break;
            case EXTERNAL_HTTP:
                requireField(url, "url", errorPath, errors);
                break;
            default:
                break;
        }
        if (onUnresolved != null) {
            onUnresolved.validate(errorPath + ".onUnresolved", errors, depth);
        }
    }

    private static void requireField(String value, String field, String errorPath, List<String> errors) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(errorPath + ": approver type requires '" + field + "'");
        }
    }
}
