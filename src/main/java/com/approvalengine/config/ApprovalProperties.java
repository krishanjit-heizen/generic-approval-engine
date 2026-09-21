package com.approvalengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tunables for the engine's background machinery and concurrency handling.
 *
 * <p>These are configuration rather than constants because the right values differ between
 * a local demo and a real deployment — a 2-second dispatcher poll is fine on one instance
 * and wasteful on twenty — and because being able to turn the schedulers off is what makes
 * the engine's behaviour reproducible when debugging.
 */
@ConfigurationProperties(prefix = "approval")
@Getter
@Setter
public class ApprovalProperties {

    private Outbox outbox = new Outbox();
    private Sla sla = new Sla();
    private Recovery recovery = new Recovery();
    private Concurrency concurrency = new Concurrency();

    @Getter
    @Setter
    public static class Outbox {

        private boolean enabled = true;

        private long pollIntervalMs = 2000L;

        /** How many events one dispatcher tick claims. Bounds the blast radius of a slow consumer. */
        private int batchSize = 20;

        /**
         * How long a claim is held. Must comfortably exceed the HTTP timeouts below —
         * otherwise a second dispatcher could reclaim a row while the first is still
         * mid-request, and the consumer would see a duplicate.
         */
        private int leaseDurationSeconds = 30;

        /** Attempts before an event is dead-lettered for operator replay. */
        private int maxAttempts = 8;

        /**
         * Backoff per attempt, in seconds; the final value repeats for any further attempts.
         * Jitter is applied on top so a fleet of events failing together does not retry in
         * lockstep and hammer a recovering consumer.
         */
        private List<Integer> backoffSeconds =
                new ArrayList<Integer>(Arrays.asList(2, 5, 15, 60, 300, 900, 3600));

        private Http http = new Http();

        /** Published to consumers as the window they should accept on X-Approval-Timestamp. */
        private int signatureToleranceSeconds = 300;

        @Getter
        @Setter
        public static class Http {
            private int connectTimeoutMs = 3000;
            private int readTimeoutMs = 5000;
        }

        /** Backoff for a given attempt number (1-based), clamped to the configured schedule. */
        public int backoffFor(int attempt) {
            if (backoffSeconds == null || backoffSeconds.isEmpty()) {
                return 60;
            }
            int index = Math.min(Math.max(attempt, 1), backoffSeconds.size()) - 1;
            return backoffSeconds.get(index).intValue();
        }
    }

    @Getter
    @Setter
    public static class Sla {
        private boolean enabled = true;
        private long pollIntervalMs = 60000L;
        private int batchSize = 100;
    }

    @Getter
    @Setter
    public static class Recovery {
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Concurrency {

        /**
         * How many times an action retries after losing an optimistic-lock race.
         *
         * <p>Bounded deliberately. Concurrent approvals on one stage are a handful of
         * writers, not a stampede, so a few retries resolve essentially every real
         * collision — and anything beyond that is a genuine conflict the caller should be
         * told about (409) rather than something to spin on.
         */
        private int optimisticRetryAttempts = 3;

        private long optimisticRetryBaseBackoffMs = 25L;
    }
}
