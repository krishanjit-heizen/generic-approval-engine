-- ============================================================================
-- A first-class approver model.
--
-- Before this, roles and teams were opaque strings in a single polymorphic
-- directory_membership table, and departments were a free-text column. Three
-- consequences, all bad:
--
--   * A workflow could route to ROLE('FINACE') and nobody found out until a real
--     request activated that stage and parked in NEEDS_ATTENTION. A typo became a
--     runtime incident instead of a 422 at authoring time.
--   * Nothing could answer "which workflows route to Finance?" or "what would
--     break if we retire this team?"
--   * kind + member_value carried no referential integrity, so a membership could
--     name a role that never existed.
--
-- So roles, teams and departments become entities with primary keys, and
-- membership becomes two join tables with real foreign keys. Resolution then runs
-- against a model the database itself can keep honest.
-- ============================================================================


-- --- the groupings an approver spec can name ---------------------------------
CREATE TABLE approver_role (
    role_code   VARCHAR(128) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(1024),
    -- A retired role stops resolving without deleting the history of who held it.
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_approver_role PRIMARY KEY (role_code)
);

CREATE TABLE approver_team (
    team_code  VARCHAR(128) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_approver_team PRIMARY KEY (team_code)
);

CREATE TABLE approver_department (
    dept_code  VARCHAR(128) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_approver_department PRIMARY KEY (dept_code)
);

-- --- backfill from whatever the seeded data already contained ----------------
-- Derived rather than hard-coded, so this migration is correct against a database
-- that has been added to since V2.
INSERT INTO approver_role (role_code, name, created_at)
SELECT DISTINCT member_value, member_value, CURRENT_TIMESTAMP
FROM directory_membership WHERE kind = 'ROLE';

INSERT INTO approver_team (team_code, name, created_at)
SELECT DISTINCT member_value, member_value, CURRENT_TIMESTAMP
FROM directory_membership WHERE kind = 'TEAM';

INSERT INTO approver_department (dept_code, name, created_at)
SELECT DISTINCT department, department, CURRENT_TIMESTAMP
FROM directory_user WHERE department IS NOT NULL;


-- --- membership, now with referential integrity ------------------------------
CREATE TABLE approver_role_member (
    role_code VARCHAR(128) NOT NULL,
    user_id   VARCHAR(128) NOT NULL,
    CONSTRAINT pk_approver_role_member PRIMARY KEY (role_code, user_id),
    CONSTRAINT fk_role_member_role FOREIGN KEY (role_code)
        REFERENCES approver_role (role_code) ON DELETE CASCADE,
    CONSTRAINT fk_role_member_user FOREIGN KEY (user_id)
        REFERENCES directory_user (user_id) ON DELETE CASCADE
);
-- Resolution direction: role → its members.
CREATE INDEX ix_role_member_role ON approver_role_member (role_code);
-- Authorisation direction: user → the roles they hold. Needed to re-verify at action
-- time that an approver still holds the role that got them the task.
CREATE INDEX ix_role_member_user ON approver_role_member (user_id);

CREATE TABLE approver_team_member (
    team_code VARCHAR(128) NOT NULL,
    user_id   VARCHAR(128) NOT NULL,
    CONSTRAINT pk_approver_team_member PRIMARY KEY (team_code, user_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (team_code)
        REFERENCES approver_team (team_code) ON DELETE CASCADE,
    CONSTRAINT fk_team_member_user FOREIGN KEY (user_id)
        REFERENCES directory_user (user_id) ON DELETE CASCADE
);
CREATE INDEX ix_team_member_team ON approver_team_member (team_code);
CREATE INDEX ix_team_member_user ON approver_team_member (user_id);

INSERT INTO approver_role_member (role_code, user_id)
SELECT member_value, user_id FROM directory_membership WHERE kind = 'ROLE';

INSERT INTO approver_team_member (team_code, user_id)
SELECT member_value, user_id FROM directory_membership WHERE kind = 'TEAM';

-- The polymorphic table has no remaining purpose.
DROP TABLE directory_membership;


-- --- a user's department is now a reference, not free text -------------------
ALTER TABLE directory_user
    ADD CONSTRAINT fk_user_department FOREIGN KEY (department)
    REFERENCES approver_department (dept_code);


-- ============================================================================
-- Projection of each published workflow's approver rules.
--
-- The definition JSON stays the archival artifact — the exact bytes an author
-- published. These rows are the queryable projection of the approver rules inside
-- it, written once at publish and never updated (a new version writes new rows).
--
-- Purpose is integrity and answerability, not execution:
--   * publish-time validation checks every ROLE/TEAM/DEPARTMENT/USER a workflow
--     names actually exists, so a typo is a 422 rather than a stuck request;
--   * "which workflows route to FINANCE?" becomes one query, which is what you
--     need before retiring a role or reorganising a team.
-- ============================================================================
CREATE TABLE workflow_approver_rule (
    id              VARCHAR(64)  NOT NULL,
    workflow_def_id VARCHAR(64)  NOT NULL,
    stage_id        VARCHAR(128) NOT NULL,
    stage_index     INT          NOT NULL,
    -- Position within the stage. Load-bearing for SEQUENTIAL stages, which ask in
    -- declaration order.
    ordinal         INT          NOT NULL,
    -- Nesting depth in an onUnresolved fallback chain: 0 is the primary spec.
    fallback_depth  INT          NOT NULL DEFAULT 0,
    -- USER | ROLE | TEAM | DEPARTMENT | MANAGER_OF | CONTEXT_PATH | EXTERNAL_HTTP
    approver_type   VARCHAR(32)  NOT NULL,
    -- The role code, team code, department code, user id, context path or URL.
    -- Intentionally not a foreign key: the column holds different kinds of target
    -- depending on approver_type, and CONTEXT_PATH/EXTERNAL_HTTP reference nothing
    -- resolvable. Integrity is enforced at publish, where the type is known.
    target          VARCHAR(1024),
    CONSTRAINT pk_workflow_approver_rule PRIMARY KEY (id),
    CONSTRAINT fk_rule_workflow FOREIGN KEY (workflow_def_id)
        REFERENCES workflow_definition (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_rule_position
    ON workflow_approver_rule (workflow_def_id, stage_index, ordinal, fallback_depth);
-- Impact analysis: everything routing to a given role or team.
CREATE INDEX ix_rule_target ON workflow_approver_rule (approver_type, target);
