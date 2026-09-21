-- ============================================================================
-- Approval engine — initial schema.
--
-- Portability note: this is written for H2 2.1.x but deliberately avoids
-- anything H2-specific that Postgres lacks — no JSONB (JSON lives in CLOB and
-- is converted in the JPA layer), no SKIP LOCKED (the outbox is claimed with a
-- lease update instead). The one exception is the generated column on
-- approval_request, flagged inline.
--
-- All timestamps are UTC (spring.jpa.properties.hibernate.jdbc.time_zone=UTC).
-- All ids are application-generated, prefixed, sortable strings (app_, wf_,
-- req_, stg_, tsk_, aud_, evt_) so they are self-describing in logs and demos.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- application: the tenancy root. Every workflow and request belongs to exactly
-- one registered consuming application.
-- ---------------------------------------------------------------------------
CREATE TABLE application (
    app_id              VARCHAR(64)   NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    -- Only the hash is stored; the plaintext key is returned once at registration.
    api_key_hash        VARCHAR(128)  NOT NULL,
    webhook_url         VARCHAR(1024),
    -- Two secrets may be live at once so a consumer can rotate with zero downtime:
    -- we sign with the current one, the consumer accepts either during the overlap.
    webhook_secret      VARCHAR(128),
    webhook_secret_prev VARCHAR(128),
    max_attempts        INT           NOT NULL DEFAULT 8,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP     NOT NULL,
    CONSTRAINT pk_application PRIMARY KEY (app_id)
);
CREATE UNIQUE INDEX ux_application_api_key ON application (api_key_hash);
CREATE UNIQUE INDEX ux_application_name    ON application (name);


-- ---------------------------------------------------------------------------
-- workflow_definition: immutable once published. Editing publishes version+1;
-- an existing version is never mutated. This is what lets an in-flight request
-- keep executing the exact definition it started on.
--
-- Keyed by (app_id, workflow_key, version) so two consumers can each own an
-- "expense_approval" without collision.
-- ---------------------------------------------------------------------------
CREATE TABLE workflow_definition (
    id              VARCHAR(64)   NOT NULL,
    app_id          VARCHAR(64)   NOT NULL,
    workflow_key    VARCHAR(128)  NOT NULL,
    version         INT           NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    -- DRAFT | ACTIVE | ARCHIVED
    status          VARCHAR(32)   NOT NULL,
    -- The whole definition: stages, conditions, approver specs, policies, display block.
    definition_json CLOB          NOT NULL,
    created_by      VARCHAR(128),
    created_at      TIMESTAMP     NOT NULL,
    published_at    TIMESTAMP,
    CONSTRAINT pk_workflow_definition PRIMARY KEY (id),
    CONSTRAINT fk_workflow_app FOREIGN KEY (app_id) REFERENCES application (app_id)
);
CREATE UNIQUE INDEX ux_workflow_key_version ON workflow_definition (app_id, workflow_key, version);
CREATE INDEX ix_workflow_status ON workflow_definition (app_id, workflow_key, status);


-- ---------------------------------------------------------------------------
-- approval_request: one approval process over one business entity.
--
-- entity_type / entity_id are opaque strings, never foreign keys — the engine
-- has no knowledge of, and no access to, the consuming app's data model.
--
-- workflow_key + workflow_version are PINNED at creation. That pin is the whole
-- answer to "a workflow changed while a request was already running".
-- ---------------------------------------------------------------------------
CREATE TABLE approval_request (
    id                  VARCHAR(64)   NOT NULL,
    app_id              VARCHAR(64)   NOT NULL,
    workflow_key        VARCHAR(128)  NOT NULL,
    workflow_version    INT           NOT NULL,
    workflow_def_id     VARCHAR(64)   NOT NULL,
    entity_type         VARCHAR(128)  NOT NULL,
    entity_id           VARCHAR(255)  NOT NULL,
    requested_by        VARCHAR(128)  NOT NULL,
    -- Immutable snapshot of the business context the rules were evaluated against.
    context_json        CLOB          NOT NULL,
    -- CREATED | IN_PROGRESS | APPROVED | REJECTED | CANCELLED
    --         | CHANGES_REQUESTED | NEEDS_ATTENTION
    status              VARCHAR(32)   NOT NULL,
    current_stage_index INT           NOT NULL DEFAULT -1,
    -- Per-request override of the application's registered webhook_url.
    callback_url        VARCHAR(1024),
    -- Set when status = NEEDS_ATTENTION, so an operator knows what to fix.
    fault_reason        VARCHAR(1024),
    -- JPA @Version. Guards stage advancement against two approvers acting at once.
    lock_version        BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    decided_at          TIMESTAMP,

    -- Belt-and-braces duplicate guard, independent of Idempotency-Key: a retry
    -- that forgets the header still cannot open two LIVE approvals on one entity.
    -- NULL for terminal requests, and H2/Postgres both treat NULLs in a unique
    -- index as distinct — so history is unconstrained while live state is unique.
    -- (Postgres needs `STORED` appended here; a partial unique index would be the
    -- more idiomatic Postgres form.)
    live_entity_key     VARCHAR(700) GENERATED ALWAYS AS (
        CASE WHEN status IN ('CREATED', 'IN_PROGRESS', 'CHANGES_REQUESTED', 'NEEDS_ATTENTION')
             THEN app_id || '|' || entity_type || '|' || entity_id || '|' || workflow_key
        END
    ),

    CONSTRAINT pk_approval_request PRIMARY KEY (id),
    CONSTRAINT fk_request_app FOREIGN KEY (app_id) REFERENCES application (app_id),
    CONSTRAINT fk_request_workflow FOREIGN KEY (workflow_def_id) REFERENCES workflow_definition (id)
);
CREATE UNIQUE INDEX ux_request_live_entity ON approval_request (live_entity_key);
CREATE INDEX ix_request_entity   ON approval_request (app_id, entity_type, entity_id);
CREATE INDEX ix_request_status   ON approval_request (app_id, status);
CREATE INDEX ix_request_requester ON approval_request (app_id, requested_by);
-- Drives startup recovery: find IN_PROGRESS requests to re-drive.
CREATE INDEX ix_request_recovery ON approval_request (status, updated_at);


-- ---------------------------------------------------------------------------
-- stage_instance: one row per stage the engine EVALUATED for a request —
-- including stages that were skipped. A skipped stage is recorded with the
-- condition trace that skipped it, so the history shows what was considered,
-- not just what ran.
-- ---------------------------------------------------------------------------
CREATE TABLE stage_instance (
    id                   VARCHAR(64)  NOT NULL,
    request_id           VARCHAR(64)  NOT NULL,
    -- Stage id as written in the workflow definition.
    stage_id             VARCHAR(128) NOT NULL,
    stage_index          INT          NOT NULL,
    name                 VARCHAR(255),
    -- SEQUENTIAL | PARALLEL
    mode                 VARCHAR(32)  NOT NULL,
    -- PENDING | ACTIVE | APPROVED | REJECTED | SKIPPED | EXPIRED
    status               VARCHAR(32)  NOT NULL,
    -- Snapshot of the stage's completion policy: {"type":"QUORUM","count":2}
    policy_json          CLOB,
    -- Why this stage applied, or didn't — the per-node ConditionEvaluator output.
    condition_trace_json CLOB,
    on_reject            VARCHAR(64),
    activated_at         TIMESTAMP,
    completed_at         TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT pk_stage_instance PRIMARY KEY (id),
    CONSTRAINT fk_stage_request FOREIGN KEY (request_id)
        REFERENCES approval_request (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_stage_request_index ON stage_instance (request_id, stage_index);
CREATE INDEX ix_stage_status ON stage_instance (request_id, status);


-- ---------------------------------------------------------------------------
-- approval_task: one row per resolved approver. Approvers are resolved at stage
-- ACTIVATION and snapshotted here, so a later change in the directory cannot
-- retroactively alter an already-active stage.
--
-- The unique (stage_instance_id, approver_id) is load-bearing: it makes
-- re-driving a half-finished stage activation idempotent, which is what lets
-- crash recovery simply replay the advancement.
-- ---------------------------------------------------------------------------
CREATE TABLE approval_task (
    id                VARCHAR(64)  NOT NULL,
    request_id        VARCHAR(64)  NOT NULL,
    stage_instance_id VARCHAR(64)  NOT NULL,
    approver_id       VARCHAR(128) NOT NULL,
    -- Human-readable provenance, e.g. "MANAGER_OF(user-123)" or "ROLE(FINANCE)".
    -- Recorded so an audit reader can tell WHY this person was asked.
    resolved_via      VARCHAR(255) NOT NULL,
    -- PENDING | APPROVED | REJECTED | CHANGES_REQUESTED | EXPIRED | CANCELLED
    status            VARCHAR(32)  NOT NULL,
    acted_by          VARCHAR(128),
    comment           VARCHAR(2000),
    due_at            TIMESTAMP,
    acted_at          TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_approval_task PRIMARY KEY (id),
    CONSTRAINT fk_task_request FOREIGN KEY (request_id)
        REFERENCES approval_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_stage FOREIGN KEY (stage_instance_id)
        REFERENCES stage_instance (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_task_stage_approver ON approval_task (stage_instance_id, approver_id);
-- The approver inbox: pending tasks for one person, across every application.
CREATE INDEX ix_task_inbox   ON approval_task (approver_id, status);
CREATE INDEX ix_task_stage   ON approval_task (stage_instance_id, status);
-- Drives the SLA sweeper.
CREATE INDEX ix_task_due     ON approval_task (status, due_at);


-- ---------------------------------------------------------------------------
-- audit_event: append-only. Written in the SAME transaction as the state change
-- it describes, so history can never drift from state.
--
-- seq is a per-request monotonic counter with a unique constraint, which gives
-- the timeline a total order even when approvers act concurrently.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    id            VARCHAR(64)  NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    seq           BIGINT       NOT NULL,
    -- User id, or 'SYSTEM' for engine-driven transitions (SLA timeouts, recovery).
    actor         VARCHAR(128) NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    -- What the transition applied to: REQUEST | STAGE | TASK
    subject_type  VARCHAR(32)  NOT NULL,
    subject_id    VARCHAR(64),
    from_state    VARCHAR(32),
    to_state      VARCHAR(32),
    comment       VARCHAR(2000),
    -- Condition traces, resolver decisions, error details.
    metadata_json CLOB,
    occurred_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_request FOREIGN KEY (request_id)
        REFERENCES approval_request (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_audit_request_seq ON audit_event (request_id, seq);
CREATE INDEX ix_audit_actor ON audit_event (actor, occurred_at);


-- ---------------------------------------------------------------------------
-- outbox_event: transactional outbox. A row is written in the same transaction
-- as the state change that produced it, so a committed decision ALWAYS has a
-- pending notification — the two cannot diverge.
--
-- Claimed by a lease update rather than SELECT ... FOR UPDATE SKIP LOCKED:
-- portable, multi-instance safe, and self-healing (an expired lease is simply
-- reclaimable after a crash).
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id              VARCHAR(64)   NOT NULL,
    app_id          VARCHAR(64)   NOT NULL,
    request_id      VARCHAR(64),
    event_type      VARCHAR(64)   NOT NULL,
    payload_json    CLOB          NOT NULL,
    target_url      VARCHAR(1024),
    -- PENDING | SENT | DEAD
    status          VARCHAR(32)   NOT NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP     NOT NULL,
    lease_owner     VARCHAR(128),
    lease_until     TIMESTAMP,
    last_error      VARCHAR(2000),
    last_status_code INT,
    created_at      TIMESTAMP     NOT NULL,
    sent_at         TIMESTAMP,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT fk_outbox_app FOREIGN KEY (app_id) REFERENCES application (app_id)
);
-- The dispatcher's claim query hits exactly this index.
CREATE INDEX ix_outbox_claim  ON outbox_event (status, next_attempt_at);
CREATE INDEX ix_outbox_lease  ON outbox_event (status, lease_until);
CREATE INDEX ix_outbox_request ON outbox_event (request_id);


-- ---------------------------------------------------------------------------
-- idempotency_record: makes retried POSTs safe.
--
-- Same key + same body  -> replay the stored response, no re-execution.
-- Same key + other body -> 422; the client reused a key for a different intent.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    idem_key        VARCHAR(255) NOT NULL,
    app_id          VARCHAR(64)  NOT NULL,
    endpoint        VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(128) NOT NULL,
    response_status INT,
    response_body   CLOB,
    -- Set once the originating transaction commits; a NULL means "in flight".
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_idempotency PRIMARY KEY (app_id, idem_key)
);
CREATE INDEX ix_idempotency_created ON idempotency_record (created_at);


-- ---------------------------------------------------------------------------
-- directory_user / directory_membership: the local stand-in for a real
-- HRMS/IAM. The engine reaches this only through the DirectoryService SPI, so
-- swapping in a real directory means one adapter, not a schema change.
-- ---------------------------------------------------------------------------
CREATE TABLE directory_user (
    user_id     VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    email       VARCHAR(255),
    manager_id  VARCHAR(128),
    department  VARCHAR(128),
    -- An inactive user is never resolved as an approver; that is what triggers
    -- a stage's onUnresolved fallback chain.
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_directory_user PRIMARY KEY (user_id)
);
CREATE INDEX ix_directory_manager    ON directory_user (manager_id);
CREATE INDEX ix_directory_department ON directory_user (department, active);

CREATE TABLE directory_membership (
    id           VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(128) NOT NULL,
    -- ROLE | TEAM
    kind         VARCHAR(32)  NOT NULL,
    -- Named member_value, not "value": VALUE is a reserved word in H2 2.x.
    member_value VARCHAR(128) NOT NULL,
    CONSTRAINT pk_directory_membership PRIMARY KEY (id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id)
        REFERENCES directory_user (user_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_membership ON directory_membership (user_id, kind, member_value);
CREATE INDEX ix_membership_lookup ON directory_membership (kind, member_value);
