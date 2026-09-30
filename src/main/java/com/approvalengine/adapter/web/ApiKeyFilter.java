package com.approvalengine.adapter.web;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.repository.ApplicationRepository;
import com.approvalengine.application.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates the calling application and establishes the {@link CallerContext}.
 *
 * <p>Lookup is by SHA-256 of the presented key, so the database never holds a usable
 * credential: a leaked dump cannot be replayed against the engine.
 *
 * <p><b>Exempt paths.</b> Application registration is exempt, because a brand-new consumer
 * has no key yet — it is the endpoint that issues one. In a real deployment that endpoint
 * belongs behind operator authentication or a provisioning pipeline; leaving it open is a
 * local-development affordance and is recorded as such in the README, not treated as a
 * design position.
 *
 * <p>The exemption covers the collection itself and nothing beneath it. Operations on an
 * existing application — rotating its webhook secret — hand out a credential, so they are
 * authenticated like everything else.
 */
@Component
@Order(10)
@Slf4j
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";
    static final String ACTOR_HEADER = "X-Actor-Id";

    /**
     * Paths that carry no tenant scope: docs and the H2 console.
     */
    private static final List<String> EXEMPT_PREFIXES = Arrays.asList(
            "/v3/api-docs",
            "/swagger-ui",
            "/swagger-resources",
            "/webjars",
            "/h2-console");

    /** The bootstrap endpoint that mints the very first API key. Matched exactly. */
    private static final String REGISTRATION_PATH = "/api/v1/admin/applications";

    private final ApplicationRepository applications;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(ApplicationRepository applications, ObjectMapper objectMapper) {
        this.applications = applications;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals(REGISTRATION_PATH) || path.equals(REGISTRATION_PATH + "/")) {
            return true;
        }
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        // Only /api/** is tenant-scoped; anything else (error pages, favicon) passes through.
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(API_KEY_HEADER);
        if (presented == null || presented.trim().isEmpty()) {
            reject(response, 401, "api-key-required",
                    "the " + API_KEY_HEADER + " header is required");
            return;
        }

        Optional<ApplicationEntity> application =
                applications.findByApiKeyHashAndActiveTrue(Hashing.sha256Hex(presented.trim()));
        if (!application.isPresent()) {
            // One message for both "no such key" and "key belongs to a deactivated app": a
            // caller holding an invalid key learns nothing about which of the two it is.
            log.debug("rejected request to {} — unrecognised API key", request.getRequestURI());
            reject(response, 401, "api-key-invalid", "the presented API key is not recognised");
            return;
        }

        ApplicationEntity app = application.get();
        String actorId = trimToNull(request.getHeader(ACTOR_HEADER));
        request.setAttribute(CallerContext.ATTRIBUTE,
                new CallerContext(app.getAppId(), app.getName(), actorId));

        chain.doFilter(request, response);
    }

    /**
     * Writes a problem+json body directly.
     *
     * <p>The filter runs before Spring MVC, so {@code @RestControllerAdvice} cannot see these
     * rejections. Duplicating the shape here — rather than letting the container emit its own
     * HTML error page — keeps the error contract uniform for every response a consumer can
     * receive.
     */
    private void reject(HttpServletResponse response, int status, String type, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("status", status);
        body.put("detail", detail);
        body.put("timestamp", Instant.now().toString());

        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Media type constant kept here so the filter and the advice cannot drift apart. */
    static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");
}
