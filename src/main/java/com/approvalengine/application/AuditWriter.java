package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.AuditEventEntity;
import com.approvalengine.adapter.persistence.repository.AuditEventRepository;
import com.approvalengine.domain.Ids;
import com.approvalengine.domain.request.AuditAction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes request history.
 *
 * <p><b>Always called inside the transaction of the change it describes.</b> That is the
 * whole reason this is a plain component and not an event listener or an async writer: if
 * history were written outside the transaction, a rolled-back approval could leave an audit
 * row claiming it happened, or a committed one could leave no trace. Coupling them means
 * they are either both true or both absent.
 *
 * <p>There is no update or delete method, and none should be added.
 */
@Component
public class AuditWriter {

    /** Actor recorded for engine-driven transitions: SLA timeouts, recovery, cascades. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final AuditEventRepository repository;
    private final JsonCodec json;

    public AuditWriter(AuditEventRepository repository, JsonCodec json) {
        this.repository = repository;
        this.json = json;
    }

    /** Convenience for entries with no extra detail. */
    public AuditEventEntity record(String requestId, String actor, AuditAction action,
                                   String subjectId, Object fromState, Object toState,
                                   String comment) {
        return record(requestId, actor, action, subjectId, fromState, toState, comment, null);
    }

    /**
     * Appends one entry.
     *
     * <p>The sequence number is read and incremented within the caller's transaction. Under
     * concurrency two writers can read the same maximum — and the unique constraint on
     * {@code (request_id, seq)} then forces one to fail and retry. That is intended: it turns
     * a silent interleaving into a visible conflict, which is what keeps the timeline totally
     * ordered rather than merely timestamped.
     *
     * @param metadata condition traces, resolver notes, error causes — whatever explains
     *                 *why*, as opposed to merely *what*
     */
    public AuditEventEntity record(String requestId, String actor, AuditAction action,
                                   String subjectId, Object fromState, Object toState,
                                   String comment, Map<String, Object> metadata) {
        long nextSeq = repository.findMaxSeq(requestId) + 1;

        AuditEventEntity entry = AuditEventEntity.builder()
                .id(Ids.newId(Ids.AUDIT))
                .requestId(requestId)
                .seq(nextSeq)
                .actor(actor == null ? SYSTEM_ACTOR : actor)
                .action(action.name())
                .subjectType(action.subjectType().name())
                .subjectId(subjectId)
                .fromState(name(fromState))
                .toState(name(toState))
                .comment(truncate(comment, 2000))
                .metadataJson(metadata == null || metadata.isEmpty() ? null : json.write(metadata))
                .occurredAt(Instant.now())
                .build();

        return repository.save(entry);
    }

    /** Builds a metadata map inline, skipping null values. */
    public static Map<String, Object> metadata(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                map.put(String.valueOf(keyValuePairs[i]), value);
            }
        }
        return map;
    }

    private static String name(Object state) {
        if (state == null) {
            return null;
        }
        return state instanceof Enum ? ((Enum<?>) state).name() : String.valueOf(state);
    }

    /**
     * Truncates rather than letting an over-long comment fail the insert.
     *
     * <p>Losing the tail of a comment is a far better outcome than an approval failing to
     * commit because someone pasted an essay into it.
     */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}
