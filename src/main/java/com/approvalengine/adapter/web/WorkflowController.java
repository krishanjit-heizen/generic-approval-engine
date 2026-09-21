package com.approvalengine.adapter.web;

import com.approvalengine.adapter.persistence.entity.WorkflowDefinitionEntity;
import com.approvalengine.application.WorkflowService;
import com.approvalengine.application.resolver.ResolvedApprover;
import com.approvalengine.domain.workflow.StageDefinition;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.WorkflowStatus;
import com.approvalengine.domain.workflow.condition.ConditionTrace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workflow authoring: define, publish, inspect, and dry-run.
 *
 * <p>Every endpoint is scoped to the calling application, so workflow keys only need to be
 * unique per consumer.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Define, version and simulate approval workflows")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a DRAFT workflow version",
            description = "Validates the whole definition and reports every problem at once. "
                    + "Creating never overwrites: if the key already exists, this becomes the next "
                    + "version, and published versions are immutable.")
    public VersionResponse create(CallerContext caller, @RequestBody WorkflowDocument document) {
        return VersionResponse.of(service.createDraft(caller.getAppId(), document, caller.getActorId()));
    }

    @PostMapping("/{workflowKey}/versions/{version}/publish")
    @Operation(summary = "Publish a version",
            description = "Makes this the version new requests bind to and archives the previous "
                    + "ACTIVE one, in a single transaction. Requests already in flight are "
                    + "unaffected — they pinned their version at creation.")
    public VersionResponse publish(CallerContext caller,
                                   @PathVariable String workflowKey,
                                   @PathVariable int version) {
        return VersionResponse.of(service.publish(caller.getAppId(), workflowKey, version));
    }

    @GetMapping
    @Operation(summary = "List all workflow versions for this application")
    public List<VersionResponse> list(CallerContext caller) {
        List<VersionResponse> out = new ArrayList<VersionResponse>();
        for (WorkflowDefinitionEntity entity : service.list(caller.getAppId())) {
            out.add(VersionResponse.of(entity));
        }
        return out;
    }

    @GetMapping("/{workflowKey}/versions")
    @Operation(summary = "List every version of one workflow, newest first")
    public List<VersionResponse> listVersions(CallerContext caller, @PathVariable String workflowKey) {
        List<VersionResponse> out = new ArrayList<VersionResponse>();
        for (WorkflowDefinitionEntity entity : service.listVersions(caller.getAppId(), workflowKey)) {
            out.add(VersionResponse.of(entity));
        }
        return out;
    }

    @GetMapping("/{workflowKey}/versions/{version}")
    @Operation(summary = "Fetch one version's full definition",
            description = "Works for ARCHIVED versions too — a finished request must stay "
                    + "explainable in terms of the rules that actually applied to it.")
    public DefinitionResponse get(CallerContext caller,
                                  @PathVariable String workflowKey,
                                  @PathVariable int version) {
        WorkflowDefinitionEntity entity = service.require(caller.getAppId(), workflowKey, version);
        return new DefinitionResponse(VersionResponse.of(entity), service.parse(entity));
    }

    @PostMapping("/{workflowKey}/simulate")
    @Operation(summary = "Dry-run a workflow against a hypothetical context",
            description = "Answers 'which stages fire, why, and who gets asked' without creating "
                    + "anything. Approver resolution runs for real, so an empty role surfaces here "
                    + "rather than mid-approval.")
    public SimulationResponse simulate(CallerContext caller,
                                       @PathVariable String workflowKey,
                                       @RequestBody SimulateRequest request) {
        WorkflowService.Simulation simulation = service.simulate(
                caller.getAppId(), workflowKey, request.getVersion(),
                request.getContext(), request.getRequestedBy());
        return SimulationResponse.of(simulation);
    }

    // --- request/response shapes -------------------------------------------------

    @Getter
    @Setter
    public static class SimulateRequest {
        /** Omit to simulate against the currently ACTIVE version. */
        private Integer version;

        /** Required by workflows using {@code MANAGER_OF($requestedBy)}. */
        private String requestedBy;

        private Map<String, Object> context;
    }

    @Getter
    public static final class VersionResponse {
        private final String workflowKey;
        private final int version;
        private final String name;
        private final WorkflowStatus status;
        private final Instant createdAt;
        private final Instant publishedAt;

        private VersionResponse(String workflowKey, int version, String name, WorkflowStatus status,
                                Instant createdAt, Instant publishedAt) {
            this.workflowKey = workflowKey;
            this.version = version;
            this.name = name;
            this.status = status;
            this.createdAt = createdAt;
            this.publishedAt = publishedAt;
        }

        static VersionResponse of(WorkflowDefinitionEntity entity) {
            return new VersionResponse(entity.getWorkflowKey(), entity.getVersion(), entity.getName(),
                    entity.getStatus(), entity.getCreatedAt(), entity.getPublishedAt());
        }
    }

    @Getter
    public static final class DefinitionResponse {
        private final VersionResponse version;
        private final WorkflowDocument definition;

        DefinitionResponse(VersionResponse version, WorkflowDocument definition) {
            this.version = version;
            this.definition = definition;
        }
    }

    @Getter
    public static final class SimulationResponse {
        private final String workflowKey;
        private final int version;
        private final List<StagePreviewResponse> stages;

        private SimulationResponse(String workflowKey, int version, List<StagePreviewResponse> stages) {
            this.workflowKey = workflowKey;
            this.version = version;
            this.stages = stages;
        }

        static SimulationResponse of(WorkflowService.Simulation simulation) {
            List<StagePreviewResponse> stages = new ArrayList<StagePreviewResponse>();
            for (WorkflowService.StagePreview preview : simulation.getStages()) {
                stages.add(StagePreviewResponse.of(preview));
            }
            return new SimulationResponse(simulation.getWorkflowKey(), simulation.getVersion(), stages);
        }
    }

    @Getter
    public static final class StagePreviewResponse {
        private final String stageId;
        private final String name;
        private final boolean applies;

        /** One-line summary of the rule decision, e.g. {@code amount(25000) >= 10000 → true}. */
        private final String reason;

        /** The full per-node evaluation record, for anyone auditing a complex rule. */
        private final ConditionTrace trace;

        private final StageDefinition.Mode mode;
        private final List<ApproverResponse> approvers;
        private final String resolutionProblem;

        private StagePreviewResponse(String stageId, String name, boolean applies, String reason,
                                     ConditionTrace trace, StageDefinition.Mode mode,
                                     List<ApproverResponse> approvers, String resolutionProblem) {
            this.stageId = stageId;
            this.name = name;
            this.applies = applies;
            this.reason = reason;
            this.trace = trace;
            this.mode = mode;
            this.approvers = approvers;
            this.resolutionProblem = resolutionProblem;
        }

        static StagePreviewResponse of(WorkflowService.StagePreview preview) {
            List<ApproverResponse> approvers = new ArrayList<ApproverResponse>();
            for (ResolvedApprover approver : preview.getApprovers()) {
                approvers.add(new ApproverResponse(approver.getApproverId(), approver.getResolvedVia()));
            }
            return new StagePreviewResponse(
                    preview.getStageId(),
                    preview.getName(),
                    preview.isApplies(),
                    preview.getTrace() == null ? null : preview.getTrace().summary(),
                    preview.getTrace(),
                    preview.getMode(),
                    approvers,
                    preview.getResolutionProblem());
        }
    }

    @Getter
    public static final class ApproverResponse {
        private final String approverId;
        private final String resolvedVia;

        ApproverResponse(String approverId, String resolvedVia) {
            this.approverId = approverId;
            this.resolvedVia = resolvedVia;
        }
    }
}
