package com.approvalengine.application.notify;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import com.approvalengine.adapter.persistence.entity.OutboxEventEntity;
import com.approvalengine.adapter.persistence.repository.ApplicationRepository;
import com.approvalengine.adapter.persistence.repository.OutboxEventRepository;
import com.approvalengine.config.ApprovalProperties;
import com.approvalengine.domain.notify.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Delivers queued notifications to consuming applications.
 *
 * <p>This is the half of the transactional outbox that makes "the approval completed but the
 * notification failed" a non-event. The decision and its outbox row committed together, so the
 * only remaining question is when delivery succeeds — never whether the intent survived.
 *
 * <p>Three properties worth being explicit about:
 * <ul>
 *   <li><b>Lease-based claiming.</b> Portable to H2, safe across several engine instances, and
 *       self-healing: a dispatcher that dies mid-delivery leaves a lease that expires on its
 *       own, and the row becomes claimable again with no operator involvement.</li>
 *   <li><b>One transaction per event.</b> A single unreachable consumer must not roll back the
 *       progress made on every other event in the batch.</li>
 *   <li><b>Dead-lettering, not dropping.</b> Exhausted events are visible on
 *       {@code /admin/outbox} and replayable once the consumer is fixed. Retrying forever would
 *       let one broken consumer's backlog crowd out every healthy one.</li>
 * </ul>
 */
@Component
@Slf4j
public class OutboxDispatcher {

    private static final SecureRandom JITTER = new SecureRandom();

    private final OutboxEventRepository outbox;
    private final ApplicationRepository applications;
    private final WebhookSender sender;
    private final ApprovalProperties properties;
    private final TransactionTemplate transaction;

    /** Identifies this process in the lease column, so a restart can spot its own stale claims. */
    private final String instanceId;

    public OutboxDispatcher(OutboxEventRepository outbox,
                            ApplicationRepository applications,
                            WebhookSender sender,
                            ApprovalProperties properties,
                            PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.applications = applications;
        this.sender = sender;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.instanceId = resolveInstanceId();
    }

    @Scheduled(fixedDelayString = "${approval.outbox.poll-interval-ms:2000}")
    public void tick() {
        if (!properties.getOutbox().isEnabled()) {
            return;
        }
        try {
            dispatchBatch();
        } catch (RuntimeException e) {
            // The scheduler stops calling a job that throws, which would silently halt all
            // notification delivery. Swallow, log, and try again on the next tick.
            log.error("outbox dispatch tick failed", e);
        }
    }

    /**
     * Claims a batch and delivers it.
     *
     * @return how many events were delivered successfully
     */
    public int dispatchBatch() {
        ApprovalProperties.Outbox config = properties.getOutbox();
        Instant now = Instant.now();

        List<OutboxEventEntity> claimed = claim(config, now);
        if (claimed.isEmpty()) {
            return 0;
        }

        // One lookup per application per batch, rather than per event.
        Map<String, ApplicationEntity> appCache = new HashMap<String, ApplicationEntity>();
        int delivered = 0;

        for (OutboxEventEntity event : claimed) {
            ApplicationEntity application = appCache.get(event.getAppId());
            if (application == null) {
                Optional<ApplicationEntity> found = applications.findById(event.getAppId());
                if (!found.isPresent()) {
                    log.error("outbox event {} references unknown application {}",
                            event.getId(), event.getAppId());
                    transaction.executeWithoutResult(status -> markDead(event.getId(),
                            "the owning application no longer exists"));
                    continue;
                }
                application = found.get();
                appCache.put(event.getAppId(), application);
            }

            WebhookSender.Result result = sender.send(event, application);
            final ApplicationEntity app = application;
            transaction.executeWithoutResult(status -> record(event.getId(), result, app));
            if (result.isDelivered()) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Takes a lease on up to {@code batchSize} deliverable events.
     *
     * <p>Reading candidates without a lock and then claiming conditionally means two
     * dispatchers may read the same row — but only one update can win, so the cost of a
     * collision is a wasted UPDATE rather than a duplicate delivery.
     */
    private List<OutboxEventEntity> claim(ApprovalProperties.Outbox config, Instant now) {
        return transaction.execute(status -> {
            List<OutboxEventEntity> candidates = outbox.findDeliverable(
                    OutboxStatus.PENDING, now, PageRequest.of(0, config.getBatchSize()));
            if (candidates.isEmpty()) {
                return new ArrayList<OutboxEventEntity>();
            }
            List<String> ids = new ArrayList<String>(candidates.size());
            for (OutboxEventEntity candidate : candidates) {
                ids.add(candidate.getId());
            }
            Instant leaseUntil = now.plusSeconds(config.getLeaseDurationSeconds());
            int won = outbox.claimBatch(ids, instanceId, leaseUntil, OutboxStatus.PENDING, now);
            if (won == 0) {
                return new ArrayList<OutboxEventEntity>();
            }
            return outbox.findByLeaseOwnerAndStatus(instanceId, OutboxStatus.PENDING);
        });
    }

    /** Records the outcome of one attempt: sent, backed off, or dead-lettered. */
    private void record(String eventId, WebhookSender.Result result, ApplicationEntity application) {
        Optional<OutboxEventEntity> found = outbox.findById(eventId);
        if (!found.isPresent()) {
            return;
        }
        OutboxEventEntity event = found.get();
        event.setAttempts(event.getAttempts() + 1);
        event.setLastStatusCode(result.getStatusCode());
        event.setLeaseOwner(null);
        event.setLeaseUntil(null);

        if (result.isDelivered()) {
            event.setStatus(OutboxStatus.SENT);
            event.setSentAt(Instant.now());
            event.setLastError(null);
            outbox.save(event);
            log.debug("delivered {} ({}) to {}", event.getEventType(), event.getId(), event.getTargetUrl());
            return;
        }

        event.setLastError(result.getError());
        int ceiling = application.getMaxAttempts() > 0
                ? application.getMaxAttempts()
                : properties.getOutbox().getMaxAttempts();

        if (event.getAttempts() >= ceiling) {
            event.setStatus(OutboxStatus.DEAD);
            outbox.save(event);
            log.error("outbox event {} ({}) dead-lettered after {} attempts: {} — "
                            + "replay it from /api/v1/admin/outbox once the consumer is fixed",
                    event.getId(), event.getEventType(),
                    Integer.valueOf(event.getAttempts()), result.getError());
            return;
        }

        int backoff = properties.getOutbox().backoffFor(event.getAttempts());
        // Jitter so a fleet of events failing together does not retry in lockstep and hammer a
        // consumer that is just coming back up.
        int jitter = JITTER.nextInt(Math.max(backoff / 4, 1) + 1);
        event.setNextAttemptAt(Instant.now().plusSeconds(backoff + jitter));
        outbox.save(event);

        log.warn("delivery of {} failed (attempt {}/{}): {} — retrying in ~{}s",
                event.getId(), Integer.valueOf(event.getAttempts()), Integer.valueOf(ceiling),
                result.getError(), Integer.valueOf(backoff + jitter));
    }

    private void markDead(String eventId, String reason) {
        outbox.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.DEAD);
            event.setLastError(reason);
            event.setLeaseOwner(null);
            event.setLeaseUntil(null);
            outbox.save(event);
        });
    }

    /**
     * Returns a dead-lettered event to the queue.
     *
     * <p>Resets the attempt counter, so an operator who has fixed the consumer gets the full
     * retry budget again rather than one last try.
     */
    public OutboxEventEntity replay(String eventId) {
        return transaction.execute(status -> {
            OutboxEventEntity event = outbox.findById(eventId).orElseThrow(
                    () -> new com.approvalengine.error.NotFoundException("outbox-event-not-found",
                            "no outbox event " + eventId));
            event.setStatus(OutboxStatus.PENDING);
            event.setAttempts(0);
            event.setNextAttemptAt(Instant.now());
            event.setLeaseOwner(null);
            event.setLeaseUntil(null);
            event.setLastError(null);
            log.info("outbox event {} queued for replay", eventId);
            return outbox.save(event);
        });
    }

    /**
     * Releases leases held by processes that are gone.
     *
     * <p>Not strictly required — an expired lease is already claimable — but it means a restart
     * resumes delivery immediately instead of waiting out the lease window of a process that no
     * longer exists.
     */
    public int releaseStaleLeases() {
        Integer released = transaction.execute(status ->
                Integer.valueOf(outbox.releaseExpiredLeases(OutboxStatus.PENDING, Instant.now())));
        return released == null ? 0 : released.intValue();
    }

    private static String resolveInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        // Host plus a per-process suffix: two instances on one machine must not share a lease
        // identity, or each would treat the other's claims as its own.
        return host + "-" + Long.toString(Math.abs(new SecureRandom().nextLong()), 36);
    }
}
