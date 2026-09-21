# Generic Approval Engine

A domain-agnostic approval service. Applications register approval workflows, submit requests,
and the engine resolves approvers, drives sequential and parallel stages, records an auditable
history, and calls back when a decision is reached.

The engine has no idea what an expense claim is. `entityType` and `entityId` are opaque strings;
its output is a decision *event*, never an action on your data.

---

## Quick start

No Docker, no database to install. Just a JDK 8.

```bash
./run.sh                      # → http://localhost:8080
./scripts/api-examples.sh     # every endpoint, as runnable curl
./demo.sh                     # the nine hard scenarios, end to end
```

`run.sh` resolves a JDK 8 itself and exports `JAVA_HOME` for that process only — it does not
touch your shell config. (Worth knowing: a `JAVA_HOME` pointing at a JDK that no longer exists
breaks `mvn` globally, which is why this is handled per-run rather than assumed.)

| | |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI | http://localhost:8080/v3/api-docs |
| Database console | http://localhost:8080/h2-console — JDBC `jdbc:h2:file:./data/approvals`, user `sa`, no password |

### Documentation

| Document | Contents |
|---|---|
| **[docs/API-CONTRACT.md](docs/API-CONTRACT.md)** | Every endpoint, every scenario, every error type |
| **[docs/LLD.md](docs/LLD.md)** | Class-level design, algorithms, diagrams |
| this file | Architecture, decisions, data model, limitations |

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Consuming applications (any domain)                             │
│  expense-app        procurement-app        access-request-app    │
└───────┬──────────────────────────────────────────────▲───────────┘
        │ register · publish workflow · submit         │ signed webhook
        │ reconcile                                    │ (decision made)
        ▼                                             │
┌──────────────────────────────────────────────────────┴───────────┐
│  Approval Engine                                                 │
│                                                                  │
│  Integration API ── Inbox API ── Operations API                   │
│         │              │              │                          │
│         └──────────────┴──────────────┘                          │
│                        │                                         │
│              WorkflowExecutor  ◀── the heart                     │
│                 │        │        │                              │
│    ConditionEvaluator  Approver  StateMachine                    │
│                        Resolution                                │
│                            │                                     │
│                     DirectoryService (SPI)                       │
│                                                                  │
│  @Scheduled: OutboxDispatcher · SlaSweeper · StartupRecovery      │
└──────────────────────────────┬───────────────────────────────────┘
                               │
                        H2 (file mode)
```

**Three surfaces, deliberately separate.** The *Integration API* is machine-to-machine and
tenant-scoped. The *Inbox API* is human-facing and cross-tenant — one approver, one queue,
spanning every consuming application. *Webhooks* are outbound and asynchronous, so a decision
never blocks on a consumer being reachable.

**Layering.** `domain` depends on nothing but Jackson annotations and the JDK. `application`
depends on `domain`. `adapter` depends on both, and nothing points inward. That is why the
condition tree and state machine are testable without a Spring context, and why swapping H2 for
Postgres or the local directory for a real HRMS touches one package.

**The one rule that shapes everything:** `WorkflowExecutor` is a pure function of persisted
state. No in-memory workflow position, no scheduler holding a request in RAM. Restart recovery
falls out of that almost for free — "where was this request?" is a query, so resuming is a
re-drive.

---

## Setup

**Requires** JDK 8 (Amazon Corretto 8 is what this was built against). Nothing else.

```bash
./run.sh                    # build + run
./mvnw -q package           # jar at target/approval-engine-0.1.0-SNAPSHOT.jar
```

Everything is pinned exactly — no version ranges. Spring Boot 2.7.18 is the last line
supporting Java 8; H2 2.1.214, Flyway 8.5.13 and springdoc 1.6.15 are likewise the newest
releases that still run on it.

State lives in `./data/approvals.mv.db`. **File mode, not in-memory** — deliberately, because
demonstrating crash recovery requires state that survives a restart. Delete `./data` for a clean
slate.

### First five minutes

```bash
# 1. register a consuming application (returns an API key — shown once)
curl -s -X POST localhost:8080/api/v1/admin/applications \
  -H 'Content-Type: application/json' \
  -d '{"name":"expense-app","webhookUrl":"http://localhost:9099/hooks"}'

# 2. define and publish a workflow
curl -s -X POST localhost:8080/api/v1/workflows \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' -d '{
  "key":"expense_approval",
  "stages":[
    {"id":"manager","approvers":[{"type":"MANAGER_OF","of":"$requestedBy"}]},
    {"id":"finance","mode":"PARALLEL",
     "condition":{"type":"compare","path":"amount","op":"GTE","value":10000},
     "approvers":[{"type":"ROLE","role":"FINANCE"},{"type":"ROLE","role":"COMPLIANCE"}],
     "policy":{"type":"ALL"}}]}'
curl -s -X POST localhost:8080/api/v1/workflows/expense_approval/versions/1/publish \
  -H "X-Api-Key: $KEY"

# 3. see what a given context WOULD do, without creating anything
curl -s -X POST localhost:8080/api/v1/workflows/expense_approval/simulate \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"requestedBy":"user-100","context":{"amount":25000}}'

# 4. submit
curl -s -X POST localhost:8080/api/v1/requests \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: first-try' -d '{
  "workflow":"expense_approval","entityType":"expense","entityId":"EXP-1",
  "requestedBy":"user-100","context":{"amount":25000,"department":"Engineering"}}'

# 5. approve, as the manager
curl -s -X POST "localhost:8080/api/v1/requests/$RID/actions" \
  -H "X-Api-Key: $KEY" -H 'X-Actor-Id: user-900' \
  -H 'Content-Type: application/json' -d '{"action":"APPROVE"}'
```

A demo directory ships as a Flyway migration (people, roles, teams, reporting lines) so the
engine is immediately exercisable rather than an empty database where every approver spec
resolves to nobody.

---

## Key design decisions

### 1. Ordered stages with intra-stage parallelism — not an arbitrary DAG

A workflow is an ordered list of stages; each may fan out to several approvers at once. This
expresses every case in the requirements, including fan-out/fan-in — `Finance ∥ Compliance →
Final Approval` is one `PARALLEL` stage followed by an ordinary one.

A general DAG would need join semantics and cycle detection for expressiveness nothing has
needed. The upgrade path is narrow by design: the executor only ever asks "which stage comes
after this one, given this outcome".

### 2. Immutable, versioned definitions; requests pin a version

Editing publishes `version + 1`; a published version is never mutated. A request stores its
version at creation and executes that definition to completion.

This makes "someone changed the workflow mid-flight" a non-event — no migration, because there
is nothing to migrate.

### 3. A JSON condition tree with an execution trace — not an expression language

Four node types (`allOf`, `anyOf`, `not`, `compare`) over nine operators. A **closed** set,
because a definition is data a consumer uploads: with SpEL or similar, evaluating a rule could
execute arbitrary code, and could not be explained node by node.

Evaluation returns a trace, not a boolean. This is the difference between an audit trail that
says *"Director stage added"* and one that says *"Director stage added because
amount(250000) > 100000"*.

It also means a missing field is never silent:

```
amount(absent) >= 10000 → false (path 'amount' is absent from the request context)
```

Without that note, a stage that vanished because a consumer renamed a field looks identical to
one that was legitimately not applicable.

### 4. Approvers resolved late, snapshotted immediately

Resolution happens at **stage activation**, so the org chart consulted is the one true when
approval is actually needed — not when the request was filed three weeks ago. Results are then
snapshotted onto task rows, so a later directory change cannot retroactively alter a stage
already in flight.

Those two halves are one decision. Late resolution without snapshotting would make an active
stage shift under the approvers looking at it.

### 5. The approver model is relational, and validated at publish

Roles, teams and departments are entities with primary keys and foreign keys — not opaque
strings. Consequences:

- A workflow naming `ROLE('FINACE')` is rejected with a `422` **while its author is looking at
  it**, instead of surfacing weeks later as a stuck request nobody can diagnose.
- `GET /admin/directory/roles/FINANCE/routed-workflows` answers "what breaks if we retire this
  role?" — which you need *before* reorganising, and which is why approver rules are projected
  into queryable rows rather than left inside JSON.

### 6. The client sends a user id; the engine resolves entitlements itself

Roles are **never** accepted from the caller. A client that could assert its own roles could
assert its way into any approval. The client names the user; the engine looks up what they hold.

That enables a check the ownership test alone cannot make: when an approver acts, the engine
re-verifies they **still hold the role that got them the task**. Holding a task proves you were
*asked*; it does not prove you are *still* the right person. Someone assigned via
`ROLE(FINANCE)` who moved to Marketing last month is refused — and the refusal is audited,
because a blocked approval is exactly what a reviewer needs to see.

Only membership-derived assignments are re-checked. A task assigned to a named individual was
never contingent on a role, so re-checking one would invent a rule the workflow never stated.

### 7. Concurrency: a row lock, then a conditional claim, then a re-count

Guarantee: **N concurrent actions produce the same outcome as some serial ordering of them.**

1. A `PESSIMISTIC_WRITE` row lock on the request for the action's duration.
2. A conditional task claim — `UPDATE … WHERE id = ? AND status = 'PENDING'` — making the
   transition single-writer at the row level.
3. Stage completion re-counted from the database inside the same transaction that advances it.

I tried optimistic locking first and it was wrong. Acting on a request means appending to a
totally-ordered audit log and re-counting completion — both serialization points by nature. With
optimistic locking, N approvers collide on the unique `(request_id, seq)` audit constraint on
*every* retry and can exhaust their budget, turning a routine race into a `409` the caller
cannot fix. Concurrency across *different* requests is where throughput matters, and that is
untouched.

### 8. A transactional outbox for every notification

The state change and the intent-to-notify commit in the **same transaction**. A committed
decision therefore always has a pending notification: they cannot diverge, and "the approval
completed but the notification failed" stops being a reachable state.

Delivery is claimed by a **lease update** rather than `SELECT … FOR UPDATE SKIP LOCKED` —
portable, multi-instance safe, and self-healing, since a dispatcher that dies mid-delivery
leaves a lease that expires on its own.

Exhausted events are **dead-lettered, not dropped**: visible on `/admin/outbox` and replayable.
Retrying forever would let one broken consumer's backlog crowd out every healthy one.

### 9. Append-only audit, written in-transaction

History is written in the same transaction as the change it describes, so it cannot drift from
state. `seq` is unique per request, giving a total order even under concurrency — two writers
reading the same maximum forces one to retry rather than interleave.

There is no update or delete path for audit rows anywhere in the engine.

### 10. No two-phase commit

Once humans have approved, the request is `APPROVED`. If the consuming app then fails to apply
the action, the engine does **not** roll back. Distributed transactions across an organisational
boundary are not worth their cost, and the audit trail must reflect reality: those people did
approve. Compensation is the consumer's concern — or a reversal request, as a first-class
approval of its own.

---

## Data model

```
application ──┬── workflow_definition ──┬── approval_request ──┬── stage_instance ──┬── approval_task
              │      (immutable,        │   (pins key+version) │  (one per attempt) │
              │       versioned)        │                      ├── audit_event      │
              │        └── workflow_approver_rule               └── (append-only)    │
              └── outbox_event                                                       │
                                                                                     │
approver_role ──── approver_role_member ────────────────────── directory_user ───────┘
approver_team ──── approver_team_member ─────────────────────────────┘
approver_department ─────────────────────────────────────────────────┘
                              idempotency_record
```

| Table | Purpose |
|---|---|
| `application` | tenancy root: hashed API key, webhook URL + signing secrets, attempt ceiling |
| `workflow_definition` | `(app_id, key, version)` unique; immutable once published |
| `workflow_approver_rule` | approver rules projected at publish, for validation and impact analysis |
| `approval_request` | pinned workflow version, context snapshot, status, `@Version`, generated `live_entity_key` |
| `stage_instance` | one row per stage **attempt**, with the condition trace that included or skipped it |
| `approval_task` | one per resolved approver, with `resolved_via` provenance |
| `audit_event` | append-only; unique `(request_id, seq)` |
| `outbox_event` | notification queue with lease + backoff |
| `idempotency_record` | stored responses, keyed per application |
| `approver_role` / `_team` / `_department` + members | the relational approver model |
| `directory_user` | people, reporting lines, active flag |

### Constraints that carry design weight

| Constraint | What it buys |
|---|---|
| `approval_request.live_entity_key` (generated, unique) | A retry that forgets `Idempotency-Key` still cannot open two live approvals for one entity. `NULL` for terminal requests, and NULLs are distinct in a unique index — so live state is unique while history is unconstrained |
| `approval_task (stage_instance_id, approver_id)` unique | Makes re-driving a half-finished activation idempotent — the mechanism crash recovery relies on |
| `audit_event (request_id, seq)` unique | Total ordering under concurrency: a silent interleaving becomes a visible conflict |
| `stage_instance (request_id, stage_index, attempt)` unique | Lets a stage be revisited (`GOTO`, resubmit) without overwriting what happened the first time |
| FKs on role/team membership | A membership cannot name a role that never existed |

**Portability:** no `JSONB` (JSON lives in `CLOB`, converted in one place), no `SKIP LOCKED`.
The one H2-shaped concession is the generated column, flagged inline in the migration with its
Postgres equivalent.

---

## Testing approach

There is **no automated test suite** — a deliberate scope decision for this project, not an
oversight. Verification is by executable demonstration instead:

| Script | What it establishes |
|---|---|
| `scripts/api-examples.sh` | Every endpoint exercised as runnable curl, printing each command so any single call can be copied and re-run |
| `demo.sh` | The nine hard scenarios end to end — including ones a curl script cannot show on its own, because it owns the engine's lifecycle |

`demo.sh` covers: the full lifecycle · mid-flight workflow change · duplicate submission ·
**three approvers acting simultaneously** · an approver who no longer exists · an unreachable
consumer · **killing the engine mid-workflow and restarting it** · an approver who never
responds · rejection and changes-requested-then-resubmit.

Two of those are the reason the script controls the process rather than assuming a running
server: crash recovery needs a kill and a restart, and the notification-failure scenario needs a
consumer that is genuinely down.

**What this trades away, honestly:** no regression safety net. The concurrency and recovery
guarantees are demonstrated on each run rather than asserted continuously, so a refactor could
silently break one and only the next demo run would reveal it. The highest-value tests to add
first would be `ConcurrentActionIT`, `RecoveryIT` and `WorkflowVersioningIT` — precisely the
behaviours hardest to verify by hand.

**Bugs this approach did catch**, all found by running the thing rather than reading it:

- Optimistic locking exhausting its retry budget under three concurrent approvers, surfacing as
  a misleading `409 duplicate-request` — which led to the row-lock redesign (decision 7).
- `@Transactional` on a method called from a sibling in the same class, so Spring's proxying
  meant the concurrency-critical path ran with **no transaction at all**.
- Reassign crashing because `parkForAttention` ran before the stage index was recorded.
- A refusal audit row being rolled back by the very exception it was recording.

---

## Assumptions

- The consuming application owns the entity. The engine never validates or dereferences
  `entityId`.
- A user directory exists externally; the seeded tables stand in for it behind
  `DirectoryService`.
- Notifying *approvers* (email, Slack) is the consumer's job — `TASK_ASSIGNED` is the hook. It is
  modelled as the same outbox event type, so adding it is a plug-in rather than a redesign.
- Single-instance is the local target, but the lease-based outbox and row-locked write paths are
  already multi-instance safe. No leader election or distributed lock needed.
- Clocks are UTC throughout.

---

## Known limitations

**Approver authentication is out of scope.** `X-Actor-Id` is trusted behind a valid application
API key. Ownership and entitlement are re-verified on every action, so the exposure is *reading*
another approver's inbox — not acting as them. A real deployment needs OIDC, or a signed identity
assertion from the calling application, in front of the inbox endpoints.

**Application registration is unauthenticated.** It is where the first API key comes from. In a
real deployment it belongs behind operator auth or a provisioning pipeline.

**Self-approval is not prevented.** If a requester resolves as their own approver, they can
approve their own request. Segregation of duties needs either an `excludeRequester` stage flag or
a global policy; deliberately not half-built.

**Ordered stages, not arbitrary DAGs** (decision 1).

**Conditions read only the context snapshot.** No external data fetch during evaluation, so a
rule cannot depend on live state elsewhere.

**Webhook-only outcome delivery.** A consumer unreachable for an extended window has no
self-service catch-up: recovery is operator-driven replay, or querying by `entityId`. A consumer
with no inbound ingress at all cannot be integrated. A cursor-based `GET /events?since=` feed is
the fix and is a small addition on top of the existing outbox — deferred, not overlooked.

**`EXTERNAL_HTTP` resolution is uncached.** Every stage activation calls out. Fine at this scale,
a latency concern at volume.

**No delegation or out-of-office.** `onUnresolved` fallbacks and SLA `ESCALATE` cover the
adjacent cases.

**Demo directory data ships in a migration**, so it is present anywhere migrations run. A real
deployment would gate it behind a profile.

**H2 file mode** is a local-development choice. Schema and SQL are deliberately
Postgres-portable, but that portability is untested against a real Postgres.

**Audit is append-only but not cryptographically chained**, so it resists accident, not a
determined operator with database access.

---

## What I would do next, in order

1. **Tests for the three guarantees that matter** — concurrency, recovery, version pinning. The
   demo proves them once; tests would prove them continuously.
2. **Signed identity assertions** replacing the trusted actor header, with roles still resolved
   server-side.
3. **Cursor-based events feed** (`GET /events?since=`) so consumers can self-heal without an
   operator.
4. **Postgres profile** with a `SKIP LOCKED` dispatcher, validating the portability claim.
5. **Approver UI** on the existing inbox API — the endpoints already return render-ready
   label/value pairs precisely so a client can stay dumb.
6. **Metrics** on stage latency and approver responsiveness. The data is all in
   `approval_task`; nothing surfaces it yet.
7. **Self-approval policy**, done properly rather than as a flag.
8. **Delegation and out-of-office**, which is the most-requested feature this kind of engine
   always grows.
