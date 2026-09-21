package com.approvalengine.application.resolver;

import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.workflow.ApproverSpec;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The six directory-backed resolvers, defined together because they are variations on one
 * idea — ask the directory, wrap the answer — and splitting them across six files would
 * scatter that symmetry without adding clarity.
 *
 * <p>{@code EXTERNAL_HTTP} lives separately in {@link ExternalHttpApproverResolver}, since
 * it has genuinely different failure modes.
 */
@Configuration
public class DirectoryApproverResolvers {

    /** A named individual. Still validated against the directory: a departed user resolves to nobody. */
    @Bean
    public ApproverResolver userResolver(final DirectoryService directory) {
        return new ApproverResolver() {
            @Override
            public ApproverSpec.Type supportedType() {
                return ApproverSpec.Type.USER;
            }

            @Override
            public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
                // Checked against the directory rather than trusted, so that a workflow
                // naming someone who has since left triggers the fallback chain instead of
                // creating a task nobody can action.
                if (!directory.findActivePerson(spec.getUserId()).isPresent()) {
                    return Collections.emptyList();
                }
                return single(spec.getUserId(), spec.describe());
            }
        };
    }

    @Bean
    public ApproverResolver roleResolver(final DirectoryService directory) {
        return new ApproverResolver() {
            @Override
            public ApproverSpec.Type supportedType() {
                return ApproverSpec.Type.ROLE;
            }

            @Override
            public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
                return wrap(directory.findActiveByRole(spec.getRole()), spec.describe());
            }
        };
    }

    @Bean
    public ApproverResolver teamResolver(final DirectoryService directory) {
        return new ApproverResolver() {
            @Override
            public ApproverSpec.Type supportedType() {
                return ApproverSpec.Type.TEAM;
            }

            @Override
            public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
                return wrap(directory.findActiveByTeam(spec.getTeam()), spec.describe());
            }
        };
    }

    @Bean
    public ApproverResolver departmentResolver(final DirectoryService directory) {
        return new ApproverResolver() {
            @Override
            public ApproverSpec.Type supportedType() {
                return ApproverSpec.Type.DEPARTMENT;
            }

            @Override
            public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
                return wrap(directory.findActiveByDepartment(spec.getDepartment()), spec.describe());
            }
        };
    }

    /**
     * The requester's manager, or the manager of a user named in the context.
     *
     * <p>{@code of} accepts {@code $requestedBy}, a literal user id, or a
     * {@code context.path} reference — so "the manager of whoever owns this project" is
     * expressible without the engine knowing what a project is.
     */
    @Bean
    public ApproverResolver managerOfResolver(final DirectoryService directory) {
        return new ApproverResolver() {
            @Override
            public ApproverSpec.Type supportedType() {
                return ApproverSpec.Type.MANAGER_OF;
            }

            @Override
            public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
                String subject = resolveUserReference(spec.getOf(), context);
                if (subject == null) {
                    return Collections.emptyList();
                }
                Optional<String> manager = directory.findManagerOf(subject);
                if (!manager.isPresent()) {
                    return Collections.emptyList();
                }
                return single(manager.get(), "MANAGER_OF(" + subject + ")");
            }
        };
    }

    /**
     * An approver id carried in the request context — a project owner, a cost-centre head,
     * a named reviewer the consuming application already knows about.
     *
     * <p>This is the escape hatch that keeps the engine from needing to model every
     * organisational relationship a consumer might have: the consumer computes who, the
     * engine handles the approval.
     */
    @Bean
    public ApproverResolver contextPathResolver(final DirectoryService directory) {
        return new ApproverResolver() {
            @Override
            public ApproverSpec.Type supportedType() {
                return ApproverSpec.Type.CONTEXT_PATH;
            }

            @Override
            public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
                Optional<Object> value = context.resolve(spec.getPath());
                if (!value.isPresent()) {
                    return Collections.emptyList();
                }
                String provenance = "CONTEXT_PATH(" + spec.getPath() + ")";

                // The path may hold one id or a list of them; consuming applications
                // legitimately do both, and rejecting either would be an arbitrary
                // restriction.
                if (value.get() instanceof Iterable) {
                    List<ResolvedApprover> out = new ArrayList<ResolvedApprover>();
                    for (Object element : (Iterable<?>) value.get()) {
                        String userId = String.valueOf(element);
                        if (directory.findActivePerson(userId).isPresent()) {
                            out.add(ResolvedApprover.of(userId, provenance));
                        }
                    }
                    return out;
                }

                String userId = String.valueOf(value.get());
                if (!directory.findActivePerson(userId).isPresent()) {
                    return Collections.emptyList();
                }
                return single(userId, provenance);
            }
        };
    }

    /**
     * Resolves {@code $requestedBy}, {@code context.some.path} or a literal user id.
     *
     * @return the user id, or null if the reference cannot be resolved
     */
    static String resolveUserReference(String reference, EvaluationContext context) {
        if (reference == null || reference.trim().isEmpty()) {
            return null;
        }
        if (ApproverSpec.REQUESTER_TOKEN.equals(reference)) {
            return context.requestedBy();
        }
        if (reference.startsWith("context.")) {
            Optional<Object> value = context.resolve(reference.substring("context.".length()));
            return value.isPresent() ? String.valueOf(value.get()) : null;
        }
        return reference;
    }

    private static List<ResolvedApprover> single(String userId, String provenance) {
        return Collections.singletonList(ResolvedApprover.of(userId, provenance));
    }

    private static List<ResolvedApprover> wrap(List<String> userIds, String provenance) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<ResolvedApprover> out = new ArrayList<ResolvedApprover>(userIds.size());
        for (String userId : userIds) {
            out.add(ResolvedApprover.of(userId, provenance));
        }
        return out;
    }
}
