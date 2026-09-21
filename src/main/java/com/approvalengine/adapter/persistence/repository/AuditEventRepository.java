package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Append-only access to request history.
 *
 * <p>There is intentionally no update or delete method here, and none should be added. The
 * inherited {@code delete*} operations from {@link JpaRepository} are never called by the
 * engine; the guarantee that matters is that no engine code path mutates an audit row, and
 * keeping this interface free of mutators is how that stays visible to the next reader.
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, String> {

    List<AuditEventEntity> findByRequestIdOrderBySeqAsc(String requestId);

    /**
     * The next sequence number for a request.
     *
     * <p>Read inside the same transaction as the insert. Under concurrency two writers can
     * read the same value — and the unique constraint on {@code (request_id, seq)} then
     * forces one of them to fail and retry. That is the intended behaviour: it converts a
     * silent interleaving into a visible conflict, which is what keeps the timeline totally
     * ordered rather than merely timestamped.
     */
    @Query("SELECT COALESCE(MAX(a.seq), 0) FROM AuditEventEntity a WHERE a.requestId = :requestId")
    long findMaxSeq(@Param("requestId") String requestId);

    List<AuditEventEntity> findByActorOrderByOccurredAtDesc(String actor);
}
