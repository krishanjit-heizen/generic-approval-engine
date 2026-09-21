package com.approvalengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Domain-agnostic approval engine.
 *
 * <p>Scheduling is enabled here because two background jobs are core to the engine's
 * guarantees rather than optional extras: the outbox dispatcher (which is what makes a
 * decision's notification survive an unreachable consumer) and the SLA sweeper (which is
 * what stops a request stalling forever on an approver who never responds).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ApprovalEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApprovalEngineApplication.class, args);
    }
}
