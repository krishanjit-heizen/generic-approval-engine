package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.OutboxEventEntity;
import com.approvalengine.domain.notify.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, String> {

    /**
     * Finds events eligible for delivery: pending, past their backoff window, and either
     * unclaimed or holding an expired lease.
     *
     * <p>Deliberately a plain SELECT with no locking. Two dispatchers may well read the same
     * candidate — {@link #claimBatch} is what resolves that, and it is conditional, so a
     * double read costs one wasted update rather than a double delivery.
     */
    @Query("SELECT o FROM OutboxEventEntity o "
            + "WHERE o.status = :pending "
            + "AND o.nextAttemptAt <= :now "
            + "AND (o.leaseOwner IS NULL OR o.leaseUntil < :now) "
            + "ORDER BY o.nextAttemptAt ASC")
    List<OutboxEventEntity> findDeliverable(@Param("pending") OutboxStatus pending,
                                            @Param("now") Instant now,
                                            Pageable pageable);

    /**
     * Takes a time-boxed lease on a batch.
     *
     * <p>This is the portable stand-in for {@code SELECT ... FOR UPDATE SKIP LOCKED}, which
     * H2 does not support reliably. The re-checked {@code leaseOwner IS NULL OR leaseUntil <
     * now} predicate is what makes it safe: only one dispatcher's update can win a given
     * row, and the loser simply moves on.
     *
     * <p>It is also self-healing. A dispatcher that dies mid-delivery leaves a lease that
     * expires on its own, and the row becomes claimable again with no operator involvement —
     * which is why a crash cannot strand a notification.
     *
     * @return how many rows this caller actually won
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEventEntity o "
            + "SET o.leaseOwner = :owner, o.leaseUntil = :leaseUntil "
            + "WHERE o.id IN :ids "
            + "AND o.status = :pending "
            + "AND (o.leaseOwner IS NULL OR o.leaseUntil < :now)")
    int claimBatch(@Param("ids") Collection<String> ids,
                   @Param("owner") String owner,
                   @Param("leaseUntil") Instant leaseUntil,
                   @Param("pending") OutboxStatus pending,
                   @Param("now") Instant now);

    /** Reads back exactly the rows this dispatcher now owns. */
    List<OutboxEventEntity> findByLeaseOwnerAndStatus(String leaseOwner, OutboxStatus status);

    /**
     * Releases leases held by dispatchers that are no longer running.
     *
     * <p>Called on startup. Not strictly required — an expired lease is already reclaimable
     * — but it means a restart resumes immediately instead of waiting out the lease window
     * of a process that no longer exists.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEventEntity o "
            + "SET o.leaseOwner = NULL, o.leaseUntil = NULL "
            + "WHERE o.status = :pending AND o.leaseOwner IS NOT NULL AND o.leaseUntil < :now")
    int releaseExpiredLeases(@Param("pending") OutboxStatus pending, @Param("now") Instant now);

    List<OutboxEventEntity> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);

    List<OutboxEventEntity> findByRequestIdOrderByCreatedAtAsc(String requestId);

    long countByStatus(OutboxStatus status);
}
