package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.repository.ApplicationRepository;
import com.approvalengine.config.ApprovalProperties;
import com.approvalengine.domain.Ids;
import com.approvalengine.error.ConflictException;
import com.approvalengine.error.NotFoundException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Onboarding for consuming applications.
 *
 * <p>Registration is what makes the engine genuinely multi-consumer rather than
 * single-tenant with aspirations: it mints the credential that scopes every subsequent call,
 * and it is the reason workflow keys can be namespaced per application.
 */
@Service
@Slf4j
public class ApplicationRegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32;

    private final ApplicationRepository repository;
    private final ApprovalProperties properties;

    public ApplicationRegistrationService(ApplicationRepository repository,
                                          ApprovalProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Registers a consumer and issues its credentials.
     *
     * <p>The API key and webhook secret are returned <b>once</b> and never again — only a
     * hash of the key is stored, and there is no endpoint that reads either back. A caller
     * that loses its key rotates rather than recovers, which is the only design under which
     * a database dump is not a set of live credentials.
     */
    @Transactional
    public Registration register(String name, String webhookUrl) {
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("application-exists",
                    "an application named '" + name + "' is already registered");
        }

        String apiKey = randomToken();
        String webhookSecret = randomToken();

        ApplicationEntity entity = ApplicationEntity.builder()
                .appId(Ids.newId(Ids.APPLICATION))
                .name(name)
                .apiKeyHash(Hashing.sha256Hex(apiKey))
                .webhookUrl(webhookUrl)
                .webhookSecret(webhookSecret)
                .maxAttempts(properties.getOutbox().getMaxAttempts())
                .active(true)
                .createdAt(Instant.now())
                .build();

        repository.save(entity);
        log.info("registered application {} ({})", entity.getName(), entity.getAppId());
        return new Registration(entity, apiKey, webhookSecret);
    }

    /**
     * Issues a new webhook signing secret, keeping the previous one live.
     *
     * <p>Both secrets are valid during the overlap, so a consumer can deploy its new secret
     * whenever it likes instead of coordinating a simultaneous cutover with the engine. The
     * engine signs with the new one immediately; the old one stays accepted until the next
     * rotation displaces it.
     */
    @Transactional
    public Registration rotateWebhookSecret(String appId) {
        ApplicationEntity entity = require(appId);
        String next = randomToken();
        entity.setWebhookSecretPrev(entity.getWebhookSecret());
        entity.setWebhookSecret(next);
        repository.save(entity);
        log.info("rotated webhook secret for application {}", appId);
        return new Registration(entity, null, next);
    }

    @Transactional(readOnly = true)
    public ApplicationEntity require(String appId) {
        return repository.findById(appId)
                .orElseThrow(() -> new NotFoundException("application-not-found",
                        "no application registered with id " + appId));
    }

    @Transactional(readOnly = true)
    public List<ApplicationEntity> list() {
        return repository.findAll();
    }

    /** 256 bits, URL-safe, unpadded — safe to paste into a header or an env var. */
    private static String randomToken() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A registration result, carrying the secrets that will never be shown again. */
    @Getter
    public static final class Registration {
        private final ApplicationEntity application;
        private final String apiKey;
        private final String webhookSecret;

        Registration(ApplicationEntity application, String apiKey, String webhookSecret) {
            this.application = application;
            this.apiKey = apiKey;
            this.webhookSecret = webhookSecret;
        }
    }
}
