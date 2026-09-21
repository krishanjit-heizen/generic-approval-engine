package com.approvalengine.application.resolver;

import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.workflow.ApproverSpec;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the consuming application who should approve.
 *
 * <p>The escape hatch for organisational logic the engine has no business modelling: "the
 * account manager for this vendor", "whoever is on call for this service". The consumer
 * computes it; the engine runs the approval.
 *
 * <p>Request:
 * <pre>{@code POST {spec.url}  { "requestedBy": "user-123", "context": { ... } } }</pre>
 * Response:
 * <pre>{@code { "approverIds": ["user-900", "user-901"] } }</pre>
 *
 * <p><b>Failure is not an exception here.</b> A consumer that is down, slow, or returns
 * nonsense yields an empty list, which hands control to the stage's {@code onUnresolved}
 * policy — normally parking the request in NEEDS_ATTENTION with the reason recorded. The
 * alternative, letting the exception escape, would abort a request that a human could
 * easily rescue, and would couple the engine's availability to every consumer's.
 */
@Component
@Slf4j
public class ExternalHttpApproverResolver implements ApproverResolver {

    private final RestTemplate restTemplate;
    private final DirectoryService directory;

    public ExternalHttpApproverResolver(RestTemplate approvalRestTemplate, DirectoryService directory) {
        this.restTemplate = approvalRestTemplate;
        this.directory = directory;
    }

    @Override
    public ApproverSpec.Type supportedType() {
        return ApproverSpec.Type.EXTERNAL_HTTP;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context) {
        String provenance = spec.describe();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("requestedBy", context.requestedBy());
        body.put("context", context.raw());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> response;
        try {
            response = restTemplate.postForObject(
                    spec.getUrl(), new HttpEntity<Map<String, Object>>(body, headers), Map.class);
        } catch (RuntimeException e) {
            log.warn("external approver resolution failed for {}: {}", spec.getUrl(), e.getMessage());
            return Collections.emptyList();
        }

        if (response == null || !(response.get("approverIds") instanceof Iterable)) {
            log.warn("external approver resolution at {} returned no 'approverIds' array", spec.getUrl());
            return Collections.emptyList();
        }

        List<ResolvedApprover> out = new ArrayList<ResolvedApprover>();
        for (Object element : (Iterable<?>) response.get("approverIds")) {
            String userId = String.valueOf(element);
            // Still validated against the directory. A consumer naming someone who has left
            // must not create an unactionable task, and the consumer is not the authority on
            // who currently works here.
            if (directory.findActivePerson(userId).isPresent()) {
                out.add(ResolvedApprover.of(userId, provenance));
            } else {
                log.warn("external resolver at {} named unknown or inactive user {}", spec.getUrl(), userId);
            }
        }
        return out;
    }
}
