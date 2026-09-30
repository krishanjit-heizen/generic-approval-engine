package com.approvalengine.adapter.web;

import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.application.ActionService;
import com.approvalengine.application.IdempotencyService;
import com.approvalengine.application.RequestQueryService;
import com.approvalengine.application.RequestService;
import com.approvalengine.adapter.web.dto.RequestViews;
import com.approvalengine.domain.request.RequestStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The consuming application's surface: submit, inspect, reconcile, resubmit, withdraw.
 *
 * <p>Every endpoint here is <b>application</b>-authenticated. Requesters are not a persona the
 * engine hosts — their own product already shows them their expense or purchase order — so the
 * app calls these on their behalf with its own API key. That keeps the engine to exactly one
 * human-facing surface (the approver inbox) and one authentication story.
 */
@RestController
@RequestMapping("/api/v1/requests")
@Tag(name = "Requests", description = "Submit and track approval requests")
@Slf4j
public class RequestController {

    private final RequestService requests;
    private final RequestQueryService queries;
    private final ActionService actions;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public RequestController(RequestService requests,
                             RequestQueryService queries,
                             ActionService actions,
                             IdempotencyService idempotency,
                             ObjectMapper objectMapper) {
        this.requests = requests;
        this.queries = queries;
        this.actions = actions;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @Operation(summary = "Submit an entity for approval",
            description = "Pins the currently ACTIVE workflow version, plans every stage against "
                    + "the submitted context, resolves the first stage's approvers and creates "
                    + "their tasks — all in one transaction. Send an Idempotency-Key so a network "
                    + "retry cannot open a second approval.")
    public ResponseEntity<?> create(
            CallerContext caller,
            @Parameter(description = "Retry-safety key. Same key + same body replays the original "
                    + "response; same key + different body is rejected.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateRequestBody body) {

        String rawBody = serialize(body);
        Optional<IdempotencyService.StoredResponse> replay =
                idempotency.claim(caller.getAppId(), idempotencyKey, "POST /requests", rawBody);
        if (replay.isPresent()) {
            log.debug("replaying stored response for Idempotency-Key {}", idempotencyKey);
            return raw(replay.get());
        }

        RequestViews.Detail detail;
        try {
            ApprovalRequestEntity created = requests.create(caller.getAppId(), body.toCommand());
            detail = queries.detail(created);
        } catch (RuntimeException e) {
            // Release the claim so a legitimate retry is not permanently poisoned by a
            // transient failure.
            idempotency.release(caller.getAppId(), idempotencyKey);
            throw e;
        }

        idempotency.complete(caller.getAppId(), idempotencyKey, HttpStatus.CREATED.value(), detail);
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PostMapping("/{requestId}/actions")
    @Operation(summary = "Approve, reject, or request changes",
            description = "The approver is taken from X-Actor-Id and re-checked against a PENDING "
                    + "task they actually hold. Concurrent actions are safe: the task claim is a "
                    + "conditional update and stage advancement is guarded by an optimistic lock.")
    public ResponseEntity<?> act(
            CallerContext caller,
            @PathVariable String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ActionBody body) {

        String actor = caller.requireActor();

        // The actor is hashed along with the body: who is acting is part of the intent, so the
        // same key presented by a different approver is key reuse, not a replay. Without it,
        // the second approver would be handed the first one's success and never be recorded.
        Map<String, Object> intent = new LinkedHashMap<String, Object>();
        intent.put("actor", actor);
        intent.put("body", body);
        Optional<IdempotencyService.StoredResponse> replay = idempotency.claim(
                caller.getAppId(), idempotencyKey, "POST /requests/" + requestId + "/actions",
                serialize(intent));
        if (replay.isPresent()) {
            return raw(replay.get());
        }

        ActionService.ActionResult result;
        try {
            result = actions.act(caller.getAppId(), requestId, body.toCommand(actor));
        } catch (RuntimeException e) {
            idempotency.release(caller.getAppId(), idempotencyKey);
            throw e;
        }

        idempotency.complete(caller.getAppId(), idempotencyKey, HttpStatus.OK.value(), result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "Full detail: context, stage plan, tasks and complete history")
    public RequestViews.Detail get(CallerContext caller, @PathVariable String requestId) {
        return queries.detail(requests.require(caller.getAppId(), requestId));
    }

    @GetMapping("/{requestId}/audit")
    @Operation(summary = "The audit trail alone, in sequence order")
    public List<RequestViews.AuditEntry> audit(CallerContext caller, @PathVariable String requestId) {
        // Ownership is verified before reading history, so one application cannot read
        // another's audit trail by guessing a request id.
        requests.require(caller.getAppId(), requestId);
        return queries.auditTrail(requestId);
    }

    @GetMapping
    @Operation(summary = "Query requests",
            description = "Pass entityType + entityId to reconcile a specific entity — the "
                    + "authoritative answer to 'what is actually true for EXP-123?' when a "
                    + "webhook was missed.")
    public List<RequestViews.Summary> list(
            CallerContext caller,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "50") int limit) {

        if (entityType != null && entityId != null) {
            return queries.summaries(requests.findByEntity(caller.getAppId(), entityType, entityId));
        }
        return queries.summaries(requests.list(caller.getAppId(), status, limit));
    }

    @PostMapping("/{requestId}/resubmit")
    @Operation(summary = "Resubmit after changes were requested",
            description = "Supplying an updated context re-plans every stage from the returned one "
                    + "onward, so reducing an amount below a threshold genuinely removes the stage "
                    + "that threshold triggered. Already-approved stages stand.")
    public RequestViews.Detail resubmit(CallerContext caller,
                                        @PathVariable String requestId,
                                        @RequestBody ResubmitBody body) {
        String actor = actorOrRequester(caller, requests.require(caller.getAppId(), requestId));
        return queries.detail(requests.resubmit(caller.getAppId(), requestId,
                body.getContext(), actor, body.getComment()));
    }

    @PostMapping("/{requestId}/cancel")
    @Operation(summary = "Withdraw a request",
            description = "Terminal. Outstanding tasks are withdrawn so nothing is left sitting "
                    + "in an approver's inbox.")
    public RequestViews.Detail cancel(CallerContext caller,
                                      @PathVariable String requestId,
                                      @RequestBody(required = false) CancelBody body) {
        String reason = body == null ? null : body.getReason();
        String actor = actorOrRequester(caller, requests.require(caller.getAppId(), requestId));
        return queries.detail(requests.cancel(caller.getAppId(), requestId, actor, reason));
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Requester-initiated operations are attributed to the requester when the application does
     * not name an actor.
     *
     * <p>These are called by the application on the requester's behalf, so an absent
     * {@code X-Actor-Id} is normal here — unlike an approval, where an unattributed action
     * would make the audit trail worthless and is rejected outright.
     */
    private String actorOrRequester(CallerContext caller, ApprovalRequestEntity request) {
        return caller.getActorId() != null ? caller.getActorId() : request.getRequestedBy();
    }

    /** Replays a stored idempotent response byte-for-byte, including its original status. */
    private ResponseEntity<String> raw(IdempotencyService.StoredResponse stored) {
        return ResponseEntity
                .status(stored.getStatus() == null ? HttpStatus.OK.value() : stored.getStatus().intValue())
                .contentType(MediaType.APPLICATION_JSON)
                .body(stored.getBody());
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("could not canonicalise the request body", e);
        }
    }

    // --- request bodies ---------------------------------------------------

    @Getter
    @Setter
    public static class CreateRequestBody {
        private String workflow;
        private String entityType;
        private String entityId;
        private String requestedBy;

        /** Arbitrary business data. The engine only reads the paths the workflow's rules name. */
        private Map<String, Object> context;

        /** Overrides the application's registered webhook URL for this request only. */
        private String callbackUrl;

        RequestService.CreateCommand toCommand() {
            RequestService.CreateCommand command = new RequestService.CreateCommand();
            command.workflow = workflow;
            command.entityType = entityType;
            command.entityId = entityId;
            command.requestedBy = requestedBy;
            command.context = context;
            command.callbackUrl = callbackUrl;
            return command;
        }
    }

    @Getter
    @Setter
    public static class ActionBody {
        /** Optional — omit to act on whichever task on this request is assigned to you. */
        private String taskId;

        private ActionService.Action action;
        private String comment;

        ActionService.ActionCommand toCommand(String actor) {
            ActionService.ActionCommand command = new ActionService.ActionCommand();
            command.taskId = taskId;
            command.action = action;
            command.comment = comment;
            command.actor = actor;
            return command;
        }
    }

    @Getter
    @Setter
    public static class ResubmitBody {
        /** Omit to resubmit unchanged; supply to re-plan the remaining stages. */
        private Map<String, Object> context;
        private String comment;
    }

    @Getter
    @Setter
    public static class CancelBody {
        private String reason;
    }
}
