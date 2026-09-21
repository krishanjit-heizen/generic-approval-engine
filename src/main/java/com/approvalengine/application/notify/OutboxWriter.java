package com.approvalengine.application.notify;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.OutboxEventEntity;
import com.approvalengine.adapter.persistence.repository.OutboxEventRepository;
import com.approvalengine.application.JsonCodec;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.notify.EventType;
import com.approvalengine.domain.notify.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enqueues an outbound notification, in the same transaction as the state change.
 *
 * <p>This is the transactional-outbox pattern, and the atomicity is the entire point: a
 * committed decision <b>always</b> has a pending notification, because they commit together
 * or not at all. Delivery then becomes a retry problem rather than a consistency problem, and
 * "the approval completed but the notification failed" stops being a state the system can
 * reach.
 *
 * <p>The alternative — calling the consumer's webhook inline — would put an external HTTP
 * call inside the approval transaction. A slow consumer would hold database locks, and an
 * unreachable one would fail an approval that had already been legitimately given.
 */
@Component
@Slf4j
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final JsonCodec json;

    public OutboxWriter(OutboxEventRepository repository, JsonCodec json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * Writes one event, ready for the dispatcher to pick up.
     *
     * <p>{@code targetUrl} is resolved and stored <b>now</b>, from the request's callback URL
     * or the application's default. Captured rather than looked up at send time, so that
     * re-registering an application cannot silently redirect notifications already in flight.
     *
     * <p>An application with no webhook URL still gets a row. Recording the event and finding
     * nowhere to send it is more useful than pretending it never happened — the history is
     * intact, and configuring a URL later makes it replayable.
     */
    public OutboxEventEntity enqueue(ApplicationEntity application,
                                     ApprovalRequestEntity request,
                                     EventType eventType,
                                     Map<String, Object> payloadDetail) {
        String targetUrl = request.getCallbackUrl() != null && !request.getCallbackUrl().trim().isEmpty()
                ? request.getCallbackUrl()
                : application.getWebhookUrl();

        String eventId = Ids.newId(Ids.OUTBOX);
        Map<String, Object> payload = envelope(eventId, eventType, request, payloadDetail);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .appId(application.getAppId())
                .requestId(request.getId())
                .eventType(eventType.name())
                // Serialized once, here, and sent verbatim. The HMAC signs these exact bytes,
                // so re-serializing at send time could reorder keys and break every signature.
                .payloadJson(json.write(payload))
                .targetUrl(targetUrl)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                // Eligible immediately; the dispatcher picks it up on its next tick.
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            log.debug("queued {} for request {} with no webhook URL configured — it will "
                    + "dead-letter and remain replayable", eventType, request.getId());
        }
        return repository.save(event);
    }

    /**
     * The webhook body.
     *
     * <p>{@code eventId} is the consumer's de-duplication key. Delivery is at-least-once, so
     * a consumer that has already seen an id must be able to discard the repeat — that
     * obligation is part of the published contract, and this field is what makes it possible.
     */
    private Map<String, Object> envelope(String eventId, EventType eventType,
                                         ApprovalRequestEntity request,
                                         Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId);
        payload.put("eventType", eventType.name());
        payload.put("occurredAt", Instant.now().toString());
        payload.put("requestId", request.getId());
        payload.put("entityType", request.getEntityType());
        payload.put("entityId", request.getEntityId());
        payload.put("requestStatus", request.getStatus().name());
        payload.put("workflow", workflowRef(request));
        if (detail != null && !detail.isEmpty()) {
            payload.putAll(detail);
        }
        return payload;
    }

    private Map<String, Object> workflowRef(ApprovalRequestEntity request) {
        Map<String, Object> ref = new LinkedHashMap<String, Object>();
        ref.put("key", request.getWorkflowKey());
        ref.put("version", Integer.valueOf(request.getWorkflowVersion()));
        return ref;
    }
}
