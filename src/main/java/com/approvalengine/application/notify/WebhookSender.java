package com.approvalengine.application.notify;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.OutboxEventEntity;
import com.approvalengine.application.Hashing;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

/**
 * Delivers one webhook.
 *
 * <p>Signed with HMAC-SHA256 so a consumer can verify the engine sent it, over
 * {@code "{timestamp}.{body}"} rather than the body alone. Including the timestamp in the
 * signed material is what makes the {@code X-Approval-Timestamp} header trustworthy: a captured
 * payload cannot be replayed later with a fresh timestamp, because changing it invalidates the
 * signature.
 *
 * <p>The stored payload is sent verbatim. Re-serializing here could reorder JSON keys and break
 * every signature, which is precisely why the outbox stores the exact bytes it intends to send.
 */
@Component
@Slf4j
public class WebhookSender {

    static final String HEADER_EVENT_ID = "X-Approval-Event-Id";
    static final String HEADER_SIGNATURE = "X-Approval-Signature";
    static final String HEADER_TIMESTAMP = "X-Approval-Timestamp";
    static final String HEADER_EVENT_TYPE = "X-Approval-Event-Type";

    private final RestTemplate restTemplate;

    public WebhookSender(RestTemplate approvalRestTemplate) {
        this.restTemplate = approvalRestTemplate;
    }

    /**
     * POSTs the event.
     *
     * <p>Never throws. Every failure mode — no URL configured, connection refused, timeout,
     * 4xx, 5xx — comes back as a {@link Result} the dispatcher can record and retry. An
     * exception escaping here would abort a whole dispatcher batch because one consumer was
     * unreachable.
     */
    public Result send(OutboxEventEntity event, ApplicationEntity application) {
        if (event.getTargetUrl() == null || event.getTargetUrl().trim().isEmpty()) {
            return Result.failure(null, "no webhook URL is configured for this application");
        }

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String secret = application.getWebhookSecret();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HEADER_EVENT_ID, event.getId());
        headers.set(HEADER_EVENT_TYPE, event.getEventType());
        headers.set(HEADER_TIMESTAMP, timestamp);
        if (secret != null && !secret.trim().isEmpty()) {
            headers.set(HEADER_SIGNATURE,
                    "sha256=" + Hashing.hmacSha256Hex(secret, timestamp + "." + event.getPayloadJson()));
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    event.getTargetUrl(), HttpMethod.POST,
                    new HttpEntity<String>(event.getPayloadJson(), headers), String.class);

            int status = response.getStatusCodeValue();
            if (status >= 200 && status < 300) {
                return Result.success(status);
            }
            // A non-2xx that did not throw. Treated as a failure to be retried: the consumer
            // has not confirmed it recorded the event.
            return Result.failure(Integer.valueOf(status), "consumer returned HTTP " + status);

        } catch (RestClientResponseException e) {
            int status = e.getRawStatusCode();
            // 4xx is retried like anything else, deliberately. A consumer that is misconfigured
            // or mid-deploy commonly answers 404 or 401 for a while, and giving up immediately
            // would drop a decision it genuinely needs. The attempt ceiling is what stops this
            // being unbounded.
            return Result.failure(Integer.valueOf(status),
                    "consumer returned HTTP " + status + ": " + truncate(e.getResponseBodyAsString()));

        } catch (RuntimeException e) {
            // Connection refused, DNS failure, timeout.
            return Result.failure(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    /** Outcome of one delivery attempt. */
    @Getter
    public static final class Result {
        private final boolean delivered;
        private final Integer statusCode;
        private final String error;

        private Result(boolean delivered, Integer statusCode, String error) {
            this.delivered = delivered;
            this.statusCode = statusCode;
            this.error = error;
        }

        static Result success(int statusCode) {
            return new Result(true, Integer.valueOf(statusCode), null);
        }

        static Result failure(Integer statusCode, String error) {
            return new Result(false, statusCode, error);
        }
    }
}
