package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.domain.request.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTaskEntity, String> {

    /**
     * Claims a task for exactly one actor.
     *
     * <p>This is the innermost guard against two approvers acting at the same instant. The
     * {@code AND status = PENDING} predicate makes the transition single-writer at the row
     * level: the database decides the winner, and the loser gets zero rows updated rather
     * than silently overwriting a decision that was already recorded.
     *
     * <p>Callers MUST branch on the return value — a 0 means "already actioned", which the
     * API surfaces as 409 (or as an idempotent replay, if the same Idempotency-Key is
     * presenting the same body).
     *
     * @return 1 if this caller won the claim, 0 if the task had already left PENDING
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApprovalTaskEntity t "
            + "SET t.status = :newStatus, t.actedBy = :actedBy, t.comment = :comment, t.actedAt = :actedAt "
            + "WHERE t.id = :taskId AND t.status = :expectedStatus")
    int claim(@Param("taskId") String taskId,
              @Param("expectedStatus") TaskStatus expectedStatus,
              @Param("newStatus") TaskStatus newStatus,
              @Param("actedBy") String actedBy,
              @Param("comment") String comment,
              @Param("actedAt") Instant actedAt);

    /**
     * Withdraws the remaining tasks on a stage — used when a rejection terminates the stage,
     * or the request is cancelled. Conditional on PENDING so it can never overwrite a
     * decision an approver made concurrently.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApprovalTaskEntity t "
            + "SET t.status = :cancelled, t.actedAt = :at "
            + "WHERE t.stageInstanceId = :stageInstanceId AND t.status = :pending")
    int cancelPendingForStage(@Param("stageInstanceId") String stageInstanceId,
                              @Param("pending") TaskStatus pending,
                              @Param("cancelled") TaskStatus cancelled,
                              @Param("at") Instant at);

    List<ApprovalTaskEntity> findByStageInstanceIdOrderByCreatedAtAsc(String stageInstanceId);

    List<ApprovalTaskEntity> findByRequestIdOrderByCreatedAtAsc(String requestId);

    Optional<ApprovalTaskEntity> findByIdAndRequestId(String id, String requestId);

    /** Whether this actor holds an outstanding task on the request — the authorisation check. */
    Optional<ApprovalTaskEntity> findFirstByRequestIdAndApproverIdAndStatus(
            String requestId, String approverId, TaskStatus status);

    long countByStageInstanceIdAndStatus(String stageInstanceId, TaskStatus status);

    long countByStageInstanceIdAndStatusIn(String stageInstanceId, Collection<TaskStatus> statuses);

    /** The engine-hosted inbox: one approver's queue, spanning every consuming application. */
    List<ApprovalTaskEntity> findByApproverIdAndStatusOrderByCreatedAtAsc(
            String approverId, TaskStatus status);

    List<ApprovalTaskEntity> findByApproverIdAndStatusInOrderByActedAtDesc(
            String approverId, Collection<TaskStatus> statuses, Pageable pageable);

    /** Drives the SLA sweeper: tasks whose deadline has passed and nobody has acted. */
    @Query("SELECT t FROM ApprovalTaskEntity t "
            + "WHERE t.status = :pending AND t.dueAt IS NOT NULL AND t.dueAt <= :now "
            + "ORDER BY t.dueAt ASC")
    List<ApprovalTaskEntity> findOverdue(@Param("pending") TaskStatus pending,
                                         @Param("now") Instant now,
                                         Pageable pageable);
}
