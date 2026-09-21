package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.domain.request.RequestStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, String> {

    /**
     * Tenant-scoped read. Every consumer-facing lookup goes through this rather than
     * {@code findById}, so one application can never read another's request by guessing an id.
     */
    Optional<ApprovalRequestEntity> findByIdAndAppId(String id, String appId);

    /**
     * Loads a request for writing, taking a row lock until the transaction ends.
     *
     * <p>Every write path — approver actions, the SLA sweeper, recovery — enters through this,
     * which makes concurrent mutation of one request strictly serial.
     *
     * <p>That is deliberate rather than defeatist. Acting on a request means appending to a
     * totally-ordered audit log and re-counting stage completion; both are serialization points
     * by nature. With optimistic locking alone, N simultaneous approvers on a parallel stage
     * collide on the unique {@code (request_id, seq)} audit constraint on *every* attempt and
     * can exhaust their retry budget — turning a routine race into a 409 the caller cannot fix.
     * A row lock resolves the order once, up front, at no cost to the concurrency that actually
     * matters: different requests never contend.
     *
     * <p>The {@code @Version} column stays as a second line of defence, protecting any path
     * that mutates a request without coming through here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ApprovalRequestEntity r WHERE r.id = :id AND r.appId = :appId")
    Optional<ApprovalRequestEntity> findByIdAndAppIdForUpdate(@Param("id") String id,
                                                              @Param("appId") String appId);

    /** Row-locking load for engine-internal paths, which have no application scope. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ApprovalRequestEntity r WHERE r.id = :id")
    Optional<ApprovalRequestEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * The reconciliation query: "what is actually true for EXP-123?"
     *
     * <p>Ordered newest-first because an entity may have been through approval more than
     * once, and the caller almost always wants the current attempt.
     */
    List<ApprovalRequestEntity> findByAppIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String appId, String entityType, String entityId);

    List<ApprovalRequestEntity> findByAppIdAndStatusOrderByCreatedAtDesc(
            String appId, RequestStatus status, Pageable pageable);

    List<ApprovalRequestEntity> findByAppIdOrderByCreatedAtDesc(String appId, Pageable pageable);

    /**
     * Requests that crashed mid-advancement: still IN_PROGRESS, but their current stage has
     * no outstanding work, so nothing will ever move them again without intervention.
     *
     * <p>This is the whole of startup recovery's detection logic. It works because the engine
     * holds no execution state in memory — a request's true position is always derivable from
     * its rows, so "interrupted" is a query rather than a journal to replay.
     */
    @Query("SELECT r FROM ApprovalRequestEntity r "
            + "WHERE r.status = :inProgress "
            + "AND NOT EXISTS ("
            + "  SELECT 1 FROM ApprovalTaskEntity t "
            + "  WHERE t.requestId = r.id AND t.status = com.approvalengine.domain.request.TaskStatus.PENDING"
            + ") "
            + "ORDER BY r.updatedAt ASC")
    List<ApprovalRequestEntity> findStalled(@Param("inProgress") RequestStatus inProgress,
                                            Pageable pageable);

    List<ApprovalRequestEntity> findByStatusInOrderByUpdatedAtAsc(
            Collection<RequestStatus> statuses, Pageable pageable);

    long countByAppIdAndStatus(String appId, RequestStatus status);
}
