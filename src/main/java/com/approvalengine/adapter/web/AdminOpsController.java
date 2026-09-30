package com.approvalengine.adapter.web;

import com.approvalengine.adapter.persistence.entity.OutboxEventEntity;
import com.approvalengine.adapter.persistence.repository.OutboxEventRepository;
import com.approvalengine.adapter.web.dto.RequestViews;
import com.approvalengine.application.RequestQueryService;
import com.approvalengine.application.RequestService;
import com.approvalengine.application.notify.OutboxDispatcher;
import com.approvalengine.domain.notify.OutboxStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational endpoints: inspecting notification delivery and rescuing stuck requests.
 *
 * <p>These exist because both failure modes the engine tolerates by design — a consumer that
 * cannot be reached, and a stage that cannot be staffed — are recoverable rather than terminal.
 * A recoverable failure with no way to inspect or retry it is just a silent failure with extra
 * steps.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Operations", description = "Notification delivery and stuck-request recovery")
public class AdminOpsController {

    private final OutboxEventRepository outbox;
    private final OutboxDispatcher dispatcher;
    private final RequestService requests;
    private final RequestQueryService queries;

    public AdminOpsController(OutboxEventRepository outbox,
                              OutboxDispatcher dispatcher,
                              RequestService requests,
                              RequestQueryService queries) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.requests = requests;
        this.queries = queries;
    }

    @GetMapping("/outbox")
    @Operation(summary = "Inspect notification delivery",
            description = "Defaults to DEAD — the events that exhausted their retries and are "
                    + "waiting for someone to fix the consumer and replay them.")
    public List<OutboxView> listOutbox(CallerContext caller,
                                       @RequestParam(defaultValue = "DEAD") OutboxStatus status,
                                       @RequestParam(defaultValue = "50") int limit) {
        List<OutboxView> out = new ArrayList<OutboxView>();
        for (OutboxEventEntity event : outbox.findByAppIdAndStatusOrderByCreatedAtDesc(
                caller.getAppId(), status, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))) {
            out.add(OutboxView.of(event));
        }
        return out;
    }

    @GetMapping("/outbox/summary")
    @Operation(summary = "Counts by delivery status — a one-glance health check")
    public Map<String, Long> outboxSummary(CallerContext caller) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status.name(), Long.valueOf(outbox.countByAppIdAndStatus(caller.getAppId(), status)));
        }
        return counts;
    }

    @PostMapping("/outbox/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Requeue a dead-lettered notification",
            description = "Resets the attempt counter, so a consumer that has been fixed gets the "
                    + "full retry budget rather than one last try.")
    public OutboxView replay(CallerContext caller, @PathVariable String eventId) {
        return OutboxView.of(dispatcher.replay(caller.getAppId(), eventId));
    }

    @PostMapping("/requests/{requestId}/reassign")
    @Operation(summary = "Rescue a NEEDS_ATTENTION request",
            description = "Re-runs approver resolution for the stage that could not be staffed. "
                    + "The fix normally happens in the directory — someone is added to the empty "
                    + "role — and this replays the activation.")
    public RequestViews.Detail reassign(CallerContext caller,
                                        @PathVariable String requestId,
                                        @RequestBody(required = false) ReassignBody body) {
        String reason = body == null ? null : body.getReason();
        String actor = caller.getActorId() == null ? "OPERATOR" : caller.getActorId();
        return queries.detail(requests.reassign(caller.getAppId(), requestId, actor, reason));
    }

    @Getter
    @Setter
    public static class ReassignBody {
        private String reason;
    }

    /** Delivery state of one notification. Never exposes the signing secret. */
    @Getter
    public static final class OutboxView {
        private final String eventId;
        private final String eventType;
        private final String requestId;
        private final OutboxStatus status;
        private final int attempts;
        private final String targetUrl;
        private final Integer lastStatusCode;
        private final String lastError;
        private final Instant nextAttemptAt;
        private final Instant createdAt;
        private final Instant sentAt;

        private OutboxView(OutboxEventEntity e) {
            this.eventId = e.getId();
            this.eventType = e.getEventType();
            this.requestId = e.getRequestId();
            this.status = e.getStatus();
            this.attempts = e.getAttempts();
            this.targetUrl = e.getTargetUrl();
            this.lastStatusCode = e.getLastStatusCode();
            this.lastError = e.getLastError();
            this.nextAttemptAt = e.getNextAttemptAt();
            this.createdAt = e.getCreatedAt();
            this.sentAt = e.getSentAt();
        }

        static OutboxView of(OutboxEventEntity event) {
            return new OutboxView(event);
        }
    }
}
