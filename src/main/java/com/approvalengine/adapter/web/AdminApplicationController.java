package com.approvalengine.adapter.web;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.application.ApplicationRegistrationService;
import com.approvalengine.error.ForbiddenException;
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

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding endpoints for consuming applications.
 *
 * <p>Registering and listing are exempt from API-key authentication, because this is where a
 * key comes from. In a real deployment these belong behind operator authentication or a
 * provisioning pipeline — that gap is intentional for local development and is documented
 * rather than glossed over. Rotation is not exempt: it returns a live secret, so an
 * application may only rotate its own.
 */
@RestController
@RequestMapping("/api/v1/admin/applications")
@Tag(name = "Onboarding", description = "Register consuming applications and manage their credentials")
public class AdminApplicationController {

    private final ApplicationRegistrationService service;

    public AdminApplicationController(ApplicationRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a consuming application",
            description = "Returns an API key and webhook signing secret. Both are shown ONCE — "
                    + "only a hash of the key is stored, and neither can be read back.")
    public RegistrationResponse register(@Valid @RequestBody RegisterRequest request) {
        ApplicationRegistrationService.Registration result =
                service.register(request.getName().trim(), request.getWebhookUrl());
        return RegistrationResponse.of(result);
    }

    @PostMapping("/{appId}/rotate-secret")
    @Operation(summary = "Rotate the webhook signing secret",
            description = "The previous secret stays valid until the next rotation, so a consumer "
                    + "can deploy the new one without a synchronised cutover.")
    public RegistrationResponse rotate(CallerContext caller, @PathVariable String appId) {
        if (!caller.getAppId().equals(appId)) {
            throw new ForbiddenException("not-your-application",
                    "an application can only rotate its own webhook secret");
        }
        return RegistrationResponse.of(service.rotateWebhookSecret(appId));
    }

    @GetMapping
    @Operation(summary = "List registered applications (never includes secrets)")
    public List<ApplicationSummary> list() {
        List<ApplicationSummary> out = new ArrayList<ApplicationSummary>();
        for (ApplicationEntity entity : service.list()) {
            out.add(ApplicationSummary.of(entity));
        }
        return out;
    }

    @Getter
    @Setter
    public static class RegisterRequest {

        @NotBlank(message = "is required")
        private String name;

        /** Where decision webhooks are delivered. A request may override it per submission. */
        private String webhookUrl;
    }

    @Getter
    public static final class RegistrationResponse {
        private final String appId;
        private final String name;
        private final String webhookUrl;

        /** Shown once. Send it as {@code X-Api-Key} on every subsequent call. */
        private final String apiKey;

        /** Shown once. Use it to verify the {@code X-Approval-Signature} on webhooks. */
        private final String webhookSecret;

        private final String note;

        private RegistrationResponse(String appId, String name, String webhookUrl,
                                     String apiKey, String webhookSecret) {
            this.appId = appId;
            this.name = name;
            this.webhookUrl = webhookUrl;
            this.apiKey = apiKey;
            this.webhookSecret = webhookSecret;
            this.note = "Store these now — they cannot be retrieved again.";
        }

        static RegistrationResponse of(ApplicationRegistrationService.Registration result) {
            ApplicationEntity app = result.getApplication();
            return new RegistrationResponse(app.getAppId(), app.getName(), app.getWebhookUrl(),
                    result.getApiKey(), result.getWebhookSecret());
        }
    }

    @Getter
    public static final class ApplicationSummary {
        private final String appId;
        private final String name;
        private final String webhookUrl;
        private final boolean active;
        private final Instant createdAt;

        private ApplicationSummary(String appId, String name, String webhookUrl,
                                   boolean active, Instant createdAt) {
            this.appId = appId;
            this.name = name;
            this.webhookUrl = webhookUrl;
            this.active = active;
            this.createdAt = createdAt;
        }

        static ApplicationSummary of(ApplicationEntity entity) {
            return new ApplicationSummary(entity.getAppId(), entity.getName(),
                    entity.getWebhookUrl(), entity.isActive(), entity.getCreatedAt());
        }
    }
}
