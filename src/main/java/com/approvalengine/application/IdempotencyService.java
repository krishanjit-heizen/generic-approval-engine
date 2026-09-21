package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.IdempotencyRecordEntity;
import com.approvalengine.adapter.persistence.repository.IdempotencyRecordRepository;
import com.approvalengine.error.ConflictException;
import com.approvalengine.error.ValidationException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Makes a retried POST safe to send twice.
 *
 * <p>A consuming application retries on network failure — that is correct client behaviour,
 * and the engine's job is to make it harmless rather than to ask them not to. Three outcomes:
 *
 * <ul>
 *   <li><b>New key</b> → claim it and do the work.</li>
 *   <li><b>Same key, same body</b> → replay the stored response. No second request, no second
 *       approval.</li>
 *   <li><b>Same key, different body</b> → 422. The client reused a key for a different intent,
 *       which is a bug worth surfacing rather than guessing about.</li>
 * </ul>
 *
 * <p>The subtle case is a retry that arrives while the first attempt is <i>still running</i> —
 * common, because that is exactly when a client's timeout fires. The claim row is written in
 * its own transaction before the work begins, so the second caller sees a claim with
 * {@code completedAt == null} and is told to retry, rather than being handed an empty response
 * or being allowed to run the work concurrently.
 */
@Service
@Slf4j
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final JsonCodec json;

    public IdempotencyService(IdempotencyRecordRepository repository, JsonCodec json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * Attempts to claim a key for this request.
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}) so the claim is durable before the
     * caller's work starts. If it shared the caller's transaction, a concurrent duplicate would
     * not see the claim until commit — which is the entire window this is meant to close.
     *
     * @return a replayable response when this is a duplicate, or empty when the caller should
     *         proceed with the work
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StoredResponse> claim(String appId, String idemKey, String endpoint, String rawBody) {
        if (idemKey == null || idemKey.trim().isEmpty()) {
            // No key supplied. The caller proceeds unprotected — the database's live-entity
            // uniqueness guard is the backstop for request creation.
            return Optional.empty();
        }

        String hash = json.canonicalHash(rawBody);
        IdempotencyRecordEntity.Key key = IdempotencyRecordEntity.Key.builder()
                .appId(appId).idemKey(idemKey.trim()).build();

        Optional<IdempotencyRecordEntity> existing = repository.findById(key);
        if (existing.isPresent()) {
            return Optional.of(interpret(existing.get(), hash, endpoint, idemKey));
        }

        IdempotencyRecordEntity claim = IdempotencyRecordEntity.builder()
                .key(key)
                .endpoint(endpoint)
                .requestHash(hash)
                .createdAt(Instant.now())
                .build();
        try {
            repository.saveAndFlush(claim);
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            // Two duplicates raced to claim the same key and this one lost. The winner is
            // running the work; re-read and treat this as the duplicate it is.
            log.debug("lost the race to claim idempotency key {}", idemKey);
            return Optional.of(repository.findById(key)
                    .map(record -> interpret(record, hash, endpoint, idemKey))
                    .orElseThrow(() -> new ConflictException("idempotency-conflict",
                            "a concurrent request is using this Idempotency-Key; retry shortly")));
        }
    }

    private StoredResponse interpret(IdempotencyRecordEntity record, String hash,
                                     String endpoint, String idemKey) {
        if (!record.getRequestHash().equals(hash) || !record.getEndpoint().equals(endpoint)) {
            throw new ValidationException("idempotency-key-reuse",
                    "Idempotency-Key '" + idemKey + "' was already used for a different request; "
                            + "use a fresh key for a different intent");
        }
        if (record.getCompletedAt() == null) {
            // Claimed but unfinished: the original attempt is still in flight.
            throw new ConflictException("idempotency-in-progress",
                    "a request with this Idempotency-Key is still being processed; retry shortly");
        }
        return new StoredResponse(record.getResponseStatus(), record.getResponseBody());
    }

    /**
     * Records the response so a later retry can replay it.
     *
     * <p>Also {@code REQUIRES_NEW}: the response must be stored even if the caller's
     * transaction subsequently fails for an unrelated reason, and storing it separately keeps
     * the claim's lifecycle independent of the work's.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String appId, String idemKey, int status, Object responseBody) {
        if (idemKey == null || idemKey.trim().isEmpty()) {
            return;
        }
        IdempotencyRecordEntity.Key key = IdempotencyRecordEntity.Key.builder()
                .appId(appId).idemKey(idemKey.trim()).build();

        repository.findById(key).ifPresent(record -> {
            record.setResponseStatus(Integer.valueOf(status));
            record.setResponseBody(json.write(responseBody));
            record.setCompletedAt(Instant.now());
            repository.save(record);
        });
    }

    /**
     * Releases a claim whose work failed, so the client can legitimately retry.
     *
     * <p>Without this, a request that failed for a transient reason would have its key
     * permanently poisoned — every retry replaying a claim that never completed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String appId, String idemKey) {
        if (idemKey == null || idemKey.trim().isEmpty()) {
            return;
        }
        IdempotencyRecordEntity.Key key = IdempotencyRecordEntity.Key.builder()
                .appId(appId).idemKey(idemKey.trim()).build();
        repository.findById(key).ifPresent(record -> {
            if (record.getCompletedAt() == null) {
                repository.delete(record);
            }
        });
    }

    /** A previously stored response, ready to be replayed verbatim. */
    @Getter
    public static final class StoredResponse {
        private final Integer status;
        private final String body;

        StoredResponse(Integer status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
