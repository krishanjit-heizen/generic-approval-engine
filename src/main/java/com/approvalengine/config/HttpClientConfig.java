package com.approvalengine.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Outbound HTTP for the two places the engine calls someone else: delivering decision
 * webhooks, and asking a consuming application to resolve approvers.
 *
 * <p>Timeouts are mandatory, not defensive. Both calls happen on engine-owned threads — the
 * outbox dispatcher and, worse, inside a stage activation — so an unresponsive consumer
 * without a read timeout would stall the dispatcher or hold a database transaction open
 * indefinitely. The defaults are deliberately short: a consumer that cannot answer in
 * seconds is treated as unavailable and retried, which is a better outcome than blocking.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate approvalRestTemplate(RestTemplateBuilder builder,
                                            ApprovalProperties properties) {
        ApprovalProperties.Outbox.Http http = properties.getOutbox().getHttp();
        return builder
                .setConnectTimeout(Duration.ofMillis(http.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(http.getReadTimeoutMs()))
                .build();
    }
}
