-- ============================================================================
-- Stage attempts.
--
-- A stage can legitimately be visited more than once by one request:
--   * onReject: GOTO sends work back to an earlier stage
--   * an approver requests changes, and the requester resubmits
--
-- Both need the SAME approver asked again on the SAME stage. But
-- approval_task is unique on (stage_instance_id, approver_id) — deliberately,
-- because that is what makes re-driving a half-finished stage activation
-- idempotent after a crash. Re-asking within one stage_instance would collide
-- with the very constraint that keeps recovery safe.
--
-- So a revisit gets its own stage_instance row instead. The old attempt is left
-- exactly as it was, which is also the better audit story: "Finance rejected on
-- attempt 1, approved on attempt 2" stays legible, where mutating the original
-- row in place would erase the first outcome.
--
-- The current attempt of a stage is the one with the highest `attempt`.
-- ============================================================================

ALTER TABLE stage_instance ADD COLUMN attempt INT DEFAULT 1 NOT NULL;

-- Replaces the (request_id, stage_index) uniqueness, which attempts make wrong.
DROP INDEX ux_stage_request_index;
CREATE UNIQUE INDEX ux_stage_request_index_attempt
    ON stage_instance (request_id, stage_index, attempt);

-- Locating the live attempt of a given stage.
CREATE INDEX ix_stage_current ON stage_instance (request_id, stage_index, attempt DESC);
