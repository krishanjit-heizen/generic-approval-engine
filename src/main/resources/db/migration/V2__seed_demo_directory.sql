-- ============================================================================
-- Demo directory data.
--
-- This stands in for the HRMS/IAM directory a real deployment would read through
-- the DirectoryService adapter. It ships as a migration so that `./run.sh` gives
-- a system you can immediately exercise, rather than an empty database that
-- makes every approver spec resolve to nobody.
--
-- The cast is chosen to exercise the interesting paths, not just the happy one:
--   * a management chain three deep, for MANAGER_OF and SLA escalation
--   * roles with several members, for PARALLEL stages and QUORUM policies
--   * a role with exactly one member, for ANY policies
--   * an INACTIVE user who still holds a role — so "the approver has left" can be
--     demonstrated triggering an onUnresolved fallback rather than being hypothetical
--   * an empty role (LEGAL), for the same reason with no members at all
-- ============================================================================

-- --- people -----------------------------------------------------------------
-- user_id, display_name, email, manager_id, department, active
INSERT INTO directory_user (user_id, display_name, email, manager_id, department, active) VALUES
    ('user-100', 'Asha Nair',        'asha@example.com',    'user-900', 'Engineering', TRUE),
    ('user-101', 'Rohit Kulkarni',   'rohit@example.com',   'user-900', 'Engineering', TRUE),
    ('user-900', 'Ravi Menon',       'ravi@example.com',    'user-950', 'Engineering', TRUE),
    ('user-950', 'Meera Iyer',       'meera@example.com',   NULL,       'Leadership',  TRUE),
    ('user-410', 'Priya Sharma',     'priya@example.com',   'user-950', 'Finance',     TRUE),
    ('user-411', 'Vikram Rao',       'vikram@example.com',  'user-950', 'Finance',     TRUE),
    ('user-500', 'Neha Gupta',       'neha@example.com',    'user-950', 'Compliance',  TRUE),
    -- Left the company. Still holds the SECURITY role below, which is the point:
    -- resolution must return nobody and let the stage's fallback policy decide.
    ('user-700', 'Former Employee',  'gone@example.com',    'user-950', 'Security',    FALSE);

-- --- roles and teams --------------------------------------------------------
-- Note: member_value, not "value" — VALUE is a reserved word in H2 2.x.
INSERT INTO directory_membership (id, user_id, kind, member_value) VALUES
    ('mem_fin_410',  'user-410', 'ROLE', 'FINANCE'),
    ('mem_fin_411',  'user-411', 'ROLE', 'FINANCE'),
    -- Single member, so a stage with policy ANY completes on one approval.
    ('mem_comp_500', 'user-500', 'ROLE', 'COMPLIANCE'),
    ('mem_dir_950',  'user-950', 'ROLE', 'DIRECTOR'),
    -- Fallback target when MANAGER_OF finds nobody.
    ('mem_head_410', 'user-410', 'ROLE', 'FINANCE_HEAD'),
    -- Held only by an INACTIVE user: resolves to nobody.
    ('mem_sec_700',  'user-700', 'ROLE', 'SECURITY'),

    ('mem_team_410', 'user-410', 'TEAM', 'payments'),
    ('mem_team_411', 'user-411', 'TEAM', 'payments'),
    ('mem_team_100', 'user-100', 'TEAM', 'platform'),
    ('mem_team_101', 'user-101', 'TEAM', 'platform');

-- The LEGAL role is deliberately absent rather than empty-but-present: nothing
-- distinguishes them to the engine, and both resolve to nobody.
