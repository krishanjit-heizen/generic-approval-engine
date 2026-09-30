# API Contract

Complete contract for the approval engine, with the behaviour of **every** endpoint under
**every** scenario it can encounter — not just the happy path.

- Base URL: `http://localhost:8080`
- All paths are prefixed `/api/v1`
- Live spec: `/v3/api-docs` · Browsable: `/swagger-ui.html`

---

## Contents

1. [Conventions](#1-conventions)
2. [Error catalogue](#2-error-catalogue)
3. [Onboarding](#3-onboarding)
4. [Workflow authoring](#4-workflow-authoring)
5. [Requests](#5-requests)
6. [Approver inbox](#6-approver-inbox)
7. [Directory](#7-directory)
8. [Operations](#8-operations)
9. [Outbound webhooks](#9-outbound-webhooks)
10. [Cross-cutting guarantees](#10-cross-cutting-guarantees)

---

# 1. Conventions

## Who calls what

The engine has three distinct surfaces, and conflating them is the most common integration
mistake:

| Surface | Caller | Auth | Paths |
|---|---|---|---|
| **Integration** | the consuming application, machine-to-machine | `X-Api-Key` | `/workflows/**`, `/requests/**` |
| **Inbox** | a human approver | `X-Api-Key` + `X-Actor-Id` | `/inbox/**`, `/requests/{id}/actions` |
| **Operations** | an operator / provisioning | `X-Api-Key` (registration exempt) | `/admin/**` |

## Headers

| Header | Required on | Meaning |
|---|---|---|
| `X-Api-Key` | everything except `POST` and `GET /admin/applications` | Identifies and **authenticates** the calling application. Scopes every query — one application can never read another's data. Looked up by SHA-256 hash; the plaintext is never stored. |
| `X-Actor-Id` | any endpoint that records an action | Names the acting human. **Not authenticated** in this phase — see [§10.6](#106-the-actor-header-and-why-it-is-safe-enough). Never carries roles: the engine looks entitlements up from the id itself. |
| `Idempotency-Key` | optional on `POST /requests` and `POST /requests/{id}/actions` | Makes a client retry safe. See [§10.1](#101-idempotency). |
| `Content-Type` | any request with a body | `application/json` |

## Error shape

Every error is RFC 7807 `application/problem+json`:

```json
{
  "type": "workflow-references-unknown",
  "status": 422,
  "detail": "the workflow names approvers that do not exist",
  "errors": [
    "stages[0].approvers[0]: role 'FINACE' does not exist or is inactive"
  ],
  "timestamp": "2026-08-23T09:21:06.897Z"
}
```

**Branch on `type`, never on `detail`.** `type` is a stable contract; `detail` is prose we
reserve the right to reword. `errors` is present only when there are several distinct problems
— validation reports them all at once rather than making you resubmit to find the next one.

## Versioning

Path-prefixed `/api/v1`. Within v1, changes are **additive only** — new optional request fields,
new response fields, new endpoints, new enum members. Anything breaking gets `/api/v2` and both
run side by side.

Consequently a client **must** tolerate unknown response fields and unknown enum values.

---

# 2. Error catalogue

Every `type` the engine can return.

### 401 — authentication

| `type` | When |
|---|---|
| `api-key-required` | `X-Api-Key` header absent |
| `api-key-invalid` | key not recognised, **or** belongs to a deactivated application. One message for both, so an attacker holding an invalid key learns nothing about which |

### 403 — authenticated but not entitled

| `type` | When |
|---|---|
| `not-your-task` | the task belongs to a different approver |
| `not-your-application` | rotating the webhook secret of an application other than the caller's own |
| `no-pending-task` | no `taskId` given and the actor holds nothing outstanding on this request |
| `approver-no-longer-eligible` | the actor holds the task but has lost the role/team/department it was assigned through |
| `approver-inactive` | the actor is unknown to the directory, or deactivated |

### 404 — not found

| `type` | When |
|---|---|
| `workflow-not-found` | no such workflow key, or no such version |
| `request-not-found` | no such request **for the calling application** |
| `task-not-found` | no such task, or not on the named request |
| `application-not-found` | no such `appId` |
| `role-not-found` | no such role |
| `user-not-found` | no such directory user, or inactive |
| `outbox-event-not-found` | no such notification event |

### 409 — valid request, wrong state

| `type` | When |
|---|---|
| `task-already-actioned` | someone already decided this task — the normal outcome for the losers of a race |
| `task-not-yet-active` | sequential stage; it is not this approver's turn yet |
| `task-superseded` | the task belongs to an earlier *attempt* of a stage that has since been reopened |
| `request-already-decided` | terminal (`APPROVED`/`REJECTED`/`CANCELLED`) — no further actions accepted |
| `request-not-actionable` | not currently awaiting approvals (e.g. `CHANGES_REQUESTED`, `NEEDS_ATTENTION`) |
| `resubmit-not-applicable` | only a `CHANGES_REQUESTED` request can be resubmitted |
| `reassign-not-applicable` | only a `NEEDS_ATTENTION` request can be reassigned |
| `duplicate-request` | a live approval already exists for this `(entityType, entityId, workflow)` |
| `workflow-version-archived` | an archived version cannot be re-published |
| `application-exists` | that application name is taken |
| `concurrent-modification` | lost an optimistic-lock race after exhausting retries |
| `illegal-transition` | a state transition the state machine forbids — a real race or an engine bug |
| `idempotency-in-progress` | the original request under this key is still running; retry shortly |
| `idempotency-conflict` | two duplicates raced to claim the same key |

### 400 / 422 — bad input

| `type` | Status | When |
|---|---|---|
| `malformed-json` | 400 | body unparseable — includes an unknown `type` discriminator on a condition or approver |
| `header-required` | 400 | a required header is missing |
| `parameter-invalid` | 400 | a query or path value does not convert — `?status=BOGUS`, a non-numeric `limit` |
| `parameter-required` | 400 | a required query parameter is missing |
| `method-not-allowed` | 405 | the HTTP method is not supported on this resource |
| `unsupported-media-type` | 415 | the body was not sent as `application/json` |
| `request-invalid` | 400/422 | body failed validation; `errors` lists the fields |
| `workflow-invalid` | 422 | definition is structurally invalid (duplicate stage ids, GOTO to nowhere, `QUORUM` with no count…) |
| `workflow-references-unknown` | 422 | definition is structurally fine but names a role/team/department/user that does not exist |
| `idempotency-key-reuse` | 422 | same key, different body — or, on an action, a different `X-Actor-Id` |
| `actor-required` | 422 | endpoint records an action but no `X-Actor-Id` |
| `action-required` | 422 | `action` missing or not one of the three verbs |
| `comment-required` | 422 | `REQUEST_CHANGES` without a comment |
| `role-code-required` | 422 | role creation without a `roleCode` |

### 500 — engine fault

| `type` | When |
|---|---|
| `stored-data-corrupt` | JSON already in the database failed to parse (hand-edited definition, bad migration) |
| `internal-error` | anything unhandled. Returns a correlation id; the stack trace goes to the log, never the response |

---

# 3. Onboarding

## `POST /admin/applications`

Registers a consuming application and mints its credentials.

**Auth:** none — this is where a key comes from. In a real deployment it belongs behind
operator authentication or a provisioning pipeline; leaving it open is a local-development
affordance, not a design position.

```json
{ "name": "expense-app", "webhookUrl": "https://expense.internal/hooks/approvals" }
```

`201`:
```json
{
  "appId": "app_mt5hgef2_0wwa8socw",
  "name": "expense-app",
  "webhookUrl": "https://expense.internal/hooks/approvals",
  "apiKey": "20GdXrdelrICEsVIVDH8MfhgGlomgTDc5zbBV-XCNYs",
  "webhookSecret": "LpkosT75i9B-9MCr-se2fZW9GeJIUHMr-kkP-LbZSwg",
  "note": "Store these now — they cannot be retrieved again."
}
```

| Scenario | Result |
|---|---|
| Valid registration | `201` with both secrets |
| **Secrets requested again later** | Impossible. Only a hash of the key is stored and there is no read-back endpoint, so a database dump is not a set of live credentials. A lost key is **rotated**, not recovered. |
| Name already registered | `409 application-exists` |
| `name` blank/missing | `400 request-invalid` |
| No `webhookUrl` | `201`. Permitted — the app may reconcile by polling instead. Its events will still be recorded, will fail delivery, and will dead-letter (replayable once a URL exists). |

## `POST /admin/applications/{appId}/rotate-secret`

`200` with a new `webhookSecret`. The **previous secret stays valid** until the next rotation,
so a consumer can deploy the new one whenever it likes instead of coordinating a simultaneous
cutover. The engine signs with the new one immediately.

| Scenario | Result |
|---|---|
| Valid, called with the application's own `X-Api-Key` | `200`, new secret returned once |
| No `X-Api-Key` | `401 api-key-required` |
| `appId` is not the caller's own | `403 not-your-application` |
| Rotate twice in quick succession | The secret from **two** rotations ago stops being accepted. Only one overlap window is kept. |

## `GET /admin/applications`

`200` — array of `{appId, name, webhookUrl, active, createdAt}`. **Never** includes secrets.

---

# 4. Workflow authoring

## `POST /workflows`

Creates a `DRAFT` version. The body **is** the workflow definition.

```json
{
  "key": "expense_approval",
  "name": "Expense Approval",
  "display": {
    "titleTemplate": "{{context.department}} expense — INR {{context.amount}}",
    "summaryFields": [
      { "path": "amount",      "label": "Amount",  "format": "CURRENCY_INR" },
      { "path": "vendor.name", "label": "Vendor",  "format": "TEXT" }
    ]
  },
  "stages": [
    {
      "id": "manager",
      "name": "Manager Approval",
      "condition": null,
      "mode": "SEQUENTIAL",
      "approvers": [
        { "type": "MANAGER_OF", "of": "$requestedBy",
          "onUnresolved": { "action": "FALLBACK_TO",
                            "spec": { "type": "ROLE", "role": "FINANCE_HEAD" } } }
      ],
      "policy": { "type": "ALL" },
      "onReject": "TERMINATE",
      "slaSeconds": 86400,
      "onTimeout": "ESCALATE"
    },
    {
      "id": "finance",
      "condition": { "type": "compare", "path": "amount", "op": "GTE", "value": 10000 },
      "mode": "PARALLEL",
      "approvers": [ { "type": "ROLE", "role": "FINANCE" },
                     { "type": "ROLE", "role": "COMPLIANCE" } ],
      "policy": { "type": "ALL" }
    }
  ]
}
```

### Field reference

| Field | Default | Notes |
|---|---|---|
| `stages[].id` | — | Required, unique. Appears in audit rows and `GOTO` targets. |
| `stages[].condition` | `null` = always applies | Condition tree; see below |
| `stages[].mode` | `SEQUENTIAL` | `SEQUENTIAL` asks one at a time in declaration order; `PARALLEL` asks everyone at once |
| `stages[].approvers[]` | — | At least one. **Additive** — listing two roles asks everyone in both |
| `stages[].policy` | `{"type":"ALL"}` | `ALL` \| `ANY` \| `QUORUM` + `count` |
| `stages[].onReject` | `TERMINATE` | `TERMINATE` \| `CONTINUE` \| `GOTO` (+ `onRejectGoto`) |
| `stages[].slaSeconds` | none | Deadline per task |
| `stages[].onTimeout` | `NOTIFY_ONLY` | `NOTIFY_ONLY` \| `ESCALATE` \| `AUTO_APPROVE` \| `AUTO_REJECT` |
| `display` | none | Presentation for the engine-hosted inbox. Optional; absent it falls back to a generic key/value table |

### Condition tree

Four node types, dispatched on `type`. A **closed** set on purpose: a definition is data
uploaded by a consumer, so evaluating it must never be able to execute anything.

```json
{ "type": "allOf",  "children": [ … ] }
{ "type": "anyOf",  "children": [ … ] }
{ "type": "not",    "child": { … } }
{ "type": "compare","path": "amount", "op": "GTE", "value": 10000 }
```

`op` ∈ `EQ` `NEQ` `GT` `GTE` `LT` `LTE` `IN` `CONTAINS` `EXISTS`.

Semantics worth knowing:
- Numeric-aware: `25000` and `"25000"` compare equal, because JSON hands over Integer, Long, Double and String inconsistently for the same business value.
- Evaluation is **total** — never throws. A missing path yields `false` **with a note** recorded in the trace.
- `allOf`/`anyOf` do **not** short-circuit, so the trace shows the outcome of every clause rather than only the first failure.

### Approver types

| `type` | Fields | Resolves to |
|---|---|---|
| `USER` | `userId` | that person — still checked against the directory, so a departed named user resolves to nobody |
| `ROLE` | `role` | all **active** holders of the role |
| `TEAM` | `team` | all active members |
| `DEPARTMENT` | `department` | all active members |
| `MANAGER_OF` | `of` | manager of `$requestedBy`, a literal user id, or `context.some.path`. The manager must also be active |
| `CONTEXT_PATH` | `path` | an id, or a list of ids, read from the request context |
| `EXTERNAL_HTTP` | `url` | asks the consuming app: `POST {url} {requestedBy, context}` → `{"approverIds":[…]}` |

Every spec may carry `onUnresolved`, which turns "nobody matched" from an exception into a
declared decision: `FALLBACK_TO` (another spec, chainable to depth 5) → `SKIP_STAGE` →
`FAIL_REQUEST` (the default).

### Scenarios

| Scenario | Result |
|---|---|
| Valid definition, new key | `201`, `version: 1`, `status: DRAFT` |
| Valid definition, **existing** key | `201` with `version: n+1`. Creating never overwrites — an "edit" is always a new version |
| Structurally invalid | `422 workflow-invalid`, `errors` lists **all** problems |
| Names a role/team/department/user that does not exist | `422 workflow-references-unknown`, per-reference errors, **including inside `onUnresolved` chains** |
| Unknown `type` on a condition or approver | `400 malformed-json` with the parser's cause attached |
| Stage with no approvers | `422 workflow-invalid` — a stage nobody approves is meaningless |
| Duplicate stage ids | `422` — ids appear in audit rows and GOTO targets, so duplicates make both ambiguous |
| `onReject: GOTO` naming a non-existent stage | `422` |
| `onTimeout: ESCALATE` with no `slaSeconds` | `422` — the escalation could never fire while looking configured |
| `policy: QUORUM` with no `count` | `422` |
| Empty `allOf`/`anyOf` | `422` — vacuously true / vacuously false is almost certainly an authoring mistake |
| `op: IN` with a scalar `value` | `422` — would always be false |

<details><summary><b>Why references are validated at publish, not at runtime</b></summary>

Without it, `ROLE('FINACE')` publishes happily and the typo surfaces weeks later, when a live
request activates that stage and parks in `NEEDS_ATTENTION`. An operator then has to work out
whether the role was deleted, renamed, misspelled, or never existed. Catching it while the
author is still looking at the definition turns a production incident into a `422`.
</details>

## `POST /workflows/{key}/versions/{version}/publish`

Makes this the version new requests bind to, and archives the previous `ACTIVE` one — **in one
transaction**, so there is never a moment with two active versions or none.

| Scenario | Result |
|---|---|
| Publishing a `DRAFT` | `200`, `status: ACTIVE`; previous active → `ARCHIVED` |
| Publishing the **currently active** version again | `200`, no-op. Idempotent so a retried deploy step does not fail |
| Publishing an `ARCHIVED` version | `409 workflow-version-archived` — roll forward with a new version; published versions are immutable |
| Unknown key or version | `404 workflow-not-found` |
| A role the draft named has been **retired since the draft was created** | `422 workflow-references-unknown`. Re-validated at publish, because that is the last cheap moment |
| **A request is already running on the previous version** | Unaffected. It pinned its version at creation and executes it to completion. See [§10.3](#103-workflow-version-pinning) |

On success the definition's approver rules are projected into `workflow_approver_rule`, which is
what makes [`/routed-workflows`](#get-admindirectoryrolesrolecoderouted-workflows) answerable.

## `GET /workflows` · `GET /workflows/{key}/versions`

`200` — arrays of `{workflowKey, version, name, status, createdAt, publishedAt}`. Versions are
newest-first. Scoped to the caller, so keys only need to be unique per application.

## `GET /workflows/{key}/versions/{version}`

`200` with `{version: {...}, definition: {...}}` — the full definition as published.

Works for `ARCHIVED` versions, deliberately: a finished request must stay explainable in terms
of the rules that actually applied to it.

## `POST /workflows/{key}/simulate`

Dry-run: which stages fire, **why**, and who would be asked. Nothing is persisted.

```json
{ "version": 1, "requestedBy": "user-100",
  "context": { "amount": 25000, "department": "Engineering" } }
```

`200`:
```json
{
  "workflowKey": "expense_approval",
  "version": 1,
  "stages": [
    { "stageId": "manager", "applies": true,
      "reason": "no condition → true",
      "mode": "SEQUENTIAL",
      "approvers": [ { "approverId": "user-900", "resolvedVia": "MANAGER_OF(user-100)" } ],
      "trace": { "expression": "no condition", "result": true } },
    { "stageId": "finance", "applies": true,
      "reason": "amount(25000) >= 10000 → true",
      "approvers": [ { "approverId": "user-410", "resolvedVia": "ROLE(FINANCE)" },
                     { "approverId": "user-411", "resolvedVia": "ROLE(FINANCE)" },
                     { "approverId": "user-500", "resolvedVia": "ROLE(COMPLIANCE)" } ] },
    { "stageId": "director", "applies": false,
      "reason": "amount(25000) > 100000 → false", "approvers": [] }
  ]
}
```

| Scenario | Result |
|---|---|
| `version` omitted | Simulates the `ACTIVE` version |
| `version` given | Simulates that exact version, **including `ARCHIVED`** — so you can compare how two versions treat the same input |
| A context field the rules reference is missing | `applies: false` with an explicit note: `amount(absent) >= 10000 → false (path 'amount' is absent from the request context)` |
| A stage's role is empty | `applies: true` plus `resolutionProblem` explaining nobody could be staffed. Resolution runs **for real**, so an empty role surfaces at authoring time rather than mid-approval |
| `requestedBy` omitted but a workflow uses `MANAGER_OF($requestedBy)` | That spec resolves to nobody; `resolutionProblem` says so |

---

# 5. Requests

## `POST /requests`

Submits an entity for approval. Pins the active workflow version, plans every stage against the
submitted context, resolves the first applicable stage's approvers and creates their tasks —
**all in one transaction**.

```
X-Api-Key: …
Idempotency-Key: 8f3c…        ← strongly recommended
```
```json
{
  "workflow": "expense_approval",
  "entityType": "expense",
  "entityId": "EXP-123",
  "requestedBy": "user-100",
  "context": { "amount": 25000, "department": "Engineering",
               "vendor": { "name": "Acme" } },
  "callbackUrl": "https://expense.internal/hooks/approvals"
}
```

`201` returns the full detail view — request, context, stage plan, tasks, audit trail.

| Field | Required | Notes |
|---|---|---|
| `workflow` | yes | Key; binds to the currently `ACTIVE` version |
| `entityType` / `entityId` | yes | **Opaque strings.** The engine never validates or dereferences them — its output is a decision *event*, not an action on your entity |
| `requestedBy` | yes | Resolves `MANAGER_OF($requestedBy)` |
| `context` | no | Arbitrary business data. The engine only reads the paths the rules name. **Snapshotted, never re-read** |
| `callbackUrl` | no | Overrides the application's registered webhook for this request only |

### Scenarios

| Scenario | Result |
|---|---|
| Valid submission | `201`. Status is `IN_PROGRESS` with the first stage `ACTIVE` |
| Every stage's condition is false | `201`, status **`APPROVED`** immediately. Nothing applied, so there is nothing to ask |
| First stage's approvers cannot be resolved | `201`, status **`NEEDS_ATTENTION`** with `faultReason`. Recoverable via reassign — never auto-approved, never silently stalled |
| Retry with **same** `Idempotency-Key` + same body | `201` replaying the stored response. No second request |
| Retry with same key + **different** body | `422 idempotency-key-reuse` |
| Retry with **no** key, while one is live for that entity | `409 duplicate-request` — a database uniqueness guard on non-terminal requests catches it anyway |
| Same entity again after the previous request is **terminal** | `201`. Terminal requests drop out of the guard, so an entity can go through approval more than once |
| Original request still in flight, retry arrives | `409 idempotency-in-progress` — retry shortly. Better than handing back an empty response |
| Unknown workflow, or no published version | `404 workflow-not-found` |
| Missing `workflow`/`entityType`/`entityId`/`requestedBy` | `422 request-invalid`, all missing fields listed |
| Request creation fails for a transient reason | The idempotency claim is **released**, so a legitimate retry is not permanently poisoned |

## `POST /requests/{requestId}/actions`

Records an approver's decision. **The concurrency-critical endpoint.**

```
X-Actor-Id: user-900          ← required
Idempotency-Key: …            ← recommended
```
```json
{ "taskId": "tsk_01HX…", "action": "APPROVE", "comment": "Within Q3 budget" }
```

`action` ∈ `APPROVE` · `REJECT` · `REQUEST_CHANGES`.
`taskId` is **optional** — omit it to act on whichever task on this request is assigned to you.

`200`:
```json
{ "requestId": "req_01HX…", "requestStatus": "IN_PROGRESS",
  "taskStatus": "APPROVED", "stageOutcome": "STAGE_COMPLETED",
  "currentStageIndex": 1, "currentStageId": "finance" }
```

`stageOutcome` ∈ `STAGE_COMPLETED` · `AWAITING_OTHER_APPROVERS` · `NEXT_APPROVER_ASSIGNED` ·
`STAGE_REJECTED` · `STAGE_UNSATISFIABLE` · `CHANGES_REQUESTED`.

### Authorisation, in order

1. Request must exist **and belong to the calling application** → else `404`.
2. Request must be `IN_PROGRESS` → else `409`.
3. The actor must **hold** a `PENDING` task on it → else `403`.
4. The actor must **still be entitled** — if the task came via a role/team/department, they must still hold it → else `403`.

Steps 3 and 4 are different questions. Holding the task proves you were *asked*; entitlement
proves you are *still the right person*. Approvers are snapshotted when a stage activates and a
stage can sit for weeks.

### Scenarios

| Scenario | Result |
|---|---|
| Valid approval, policy not yet satisfied | `200 AWAITING_OTHER_APPROVERS` |
| Valid approval, policy satisfied | `200 STAGE_COMPLETED`; engine advances to the next applicable stage |
| Valid approval completing the **last** stage | `200`, `requestStatus: APPROVED`, terminal webhook queued |
| Sequential stage, approval by the current approver | `200 NEXT_APPROVER_ASSIGNED`; the next queued approver is promoted and their SLA clock starts **now**, not when the stage activated |
| **N approvers act simultaneously** | All recorded exactly once; exactly one advances the stage. See [§10.2](#102-concurrent-actions) |
| Task already decided | `409 task-already-actioned` |
| Sequential stage, not your turn | `409 task-not-yet-active` |
| Task belongs to a superseded stage attempt | `409 task-superseded` |
| `taskId` belongs to another approver | `403 not-your-task` |
| No `taskId` and actor holds nothing | `403 no-pending-task` |
| **Role revoked since assignment** | `403 approver-no-longer-eligible` — *and the refusal is written to the audit trail*, because a refused approval is exactly what a reviewer needs to see |
| Actor deactivated in the directory | `403 approver-inactive` |
| Task assigned via `USER`/`MANAGER_OF`/`CONTEXT_PATH` | No entitlement re-check — those were never contingent on a role, so re-checking one would invent a rule the workflow never stated |
| `REJECT`, stage `onReject: TERMINATE` | `200`; request `REJECTED`, remaining stages abandoned, sibling tasks withdrawn |
| `REJECT`, stage `onReject: CONTINUE` | `200`; dissent recorded, execution continues |
| `REJECT`, stage `onReject: GOTO` | `200`; target stage **and everything after it** reopened as a new attempt |
| `REQUEST_CHANGES` | `200`; request `CHANGES_REQUESTED`, outstanding tasks withdrawn |
| `REQUEST_CHANGES` with no comment | `422 comment-required` — asking for changes without saying what would leave the requester stuck |
| `QUORUM(2)` where one approves and one rejects, nobody left | `200 STAGE_UNSATISFIABLE`; treated as a stage rejection so the workflow's own `onReject` decides, rather than the engine inventing an outcome |
| Missing `X-Actor-Id` | `422 actor-required` — never defaults to a placeholder, which would make the audit row look authoritative while being meaningless |
| Missing/invalid `action` | `422 action-required` |
| Request already terminal | `409 request-already-decided` |
| Request `CHANGES_REQUESTED` or `NEEDS_ATTENTION` | `409 request-not-actionable` |
| Retry with same `Idempotency-Key` | Stored response replayed; no second decision recorded |
| Same `Idempotency-Key` from a different `X-Actor-Id` | `422 idempotency-key-reuse` |

## `GET /requests/{requestId}`

Full detail: request summary, the submitted context, **every** stage attempt, every task, and
the complete audit trail.

```json
{
  "request": { "requestId": "…", "entityType": "expense", "entityId": "EXP-123",
               "status": "IN_PROGRESS", "workflow": { "key": "expense_approval", "version": 1 },
               "currentStageId": "finance", "faultReason": null },
  "context": { "amount": 25000 },
  "stages": [
    { "stageId": "manager", "index": 0, "attempt": 1, "status": "APPROVED",
      "conditionTrace": { "expression": "no condition", "result": true } },
    { "stageId": "director", "index": 2, "attempt": 1, "status": "SKIPPED",
      "conditionTrace": { "expression": "amount(25000) > 100000", "result": false } }
  ],
  "tasks": [ { "taskId": "…", "approverId": "user-900",
               "resolvedVia": "MANAGER_OF(user-100)", "status": "APPROVED" } ],
  "audit": [ { "seq": 1, "actor": "user-100", "action": "REQUEST_CREATED" } ]
}
```

| Scenario | Notes |
|---|---|
| Skipped stages | **Included**, carrying the trace that skipped them. "Why was there no Director approval on this ₹2,00,000 expense?" must be answerable from the record alone |
| Revisited stages | One entry per `attempt`. `[0.1] RETURNED` and `[0.2] ACTIVE` both appear — the first pass is never overwritten |
| `WAITING` tasks | Included, so you can see the whole sequential queue |
| Request owned by another application | `404 request-not-found`. Not `403` — existence is not disclosed across tenants |
| `currentStageId` after completion | `null` |
| Stage ids after the workflow was re-versioned | Read from the **pinned** version, so a request that started on v1 reports v1's ids even if v2 renamed them |

## `GET /requests/{requestId}/audit`

`200` — the audit trail alone, ordered by `seq`.

```json
[ { "seq": 7, "actor": "user-900", "action": "TASK_APPROVED",
    "subjectType": "TASK", "subjectId": "tsk_…",
    "fromState": "PENDING", "toState": "APPROVED",
    "comment": "Within Q3 budget",
    "metadata": { "stageId": "manager", "resolvedVia": "MANAGER_OF(user-100)" },
    "occurredAt": "2026-08-23T09:11:28Z" } ]
```

Properties: **append-only** (no update or delete path exists anywhere in the engine); written in
the **same transaction** as the change it describes, so history cannot drift from state; and
**totally ordered** per request via a unique `(request_id, seq)`.

`metadata` is where the *why* lives — condition traces, resolver notes, refusal reasons.

Actions: `REQUEST_CREATED` `REQUEST_APPROVED` `REQUEST_REJECTED` `REQUEST_CANCELLED`
`REQUEST_CHANGES_REQUESTED` `REQUEST_RESUBMITTED` `REQUEST_NEEDS_ATTENTION` `REQUEST_REASSIGNED`
`REQUEST_RECOVERED` `STAGE_PLANNED` `STAGE_ACTIVATED` `STAGE_APPROVED` `STAGE_REJECTED`
`STAGE_SKIPPED` `STAGE_EXPIRED` `STAGE_RETURNED` `STAGE_CANCELLED` `STAGE_REOPENED` `TASK_ASSIGNED`
`TASK_APPROVED` `TASK_REJECTED` `TASK_CHANGES_REQUESTED` `TASK_EXPIRED` `TASK_CANCELLED`
`TASK_ESCALATED` `TASK_ACTION_REFUSED`.

## `GET /requests`

Query and reconcile.

| Query | Behaviour |
|---|---|
| `?entityType=expense&entityId=EXP-123` | **The reconciliation path.** Newest first — an entity may have been through approval more than once |
| `?status=IN_PROGRESS` | Filter by status |
| `?limit=50` | Capped at 200 |
| no params | Most recent requests for the caller |

Use this when a webhook was missed. The engine is authoritative; a consumer's local copy is not.

## `POST /requests/{requestId}/resubmit`

Resumes a `CHANGES_REQUESTED` request.

```json
{ "context": { "amount": 80000 }, "comment": "reduced as requested" }
```

| Scenario | Result |
|---|---|
| With an updated `context` | `200`. Every stage from the returned one onward is **re-planned** against the new context |
| Reducing an amount below a threshold | The stage that threshold triggered is re-planned to `SKIPPED`. An approver who says "bring this under 1 lakh" gets compliance honoured, rather than the Director stage lingering from the original figures |
| Without a `context` | `200`, re-runs the same stage unchanged |
| Already-approved earlier stages | Stand. Only the returned stage onward is reopened |
| The returned stage | Gets a **new attempt**; attempt 1 remains as the record of the first pass |
| Request not `CHANGES_REQUESTED` | `409 resubmit-not-applicable` |
| `X-Actor-Id` omitted | Attributed to `requestedBy`. Permitted here — the app calls this on the requester's behalf, unlike an approval where an unattributed action would make the audit worthless |

## `POST /requests/{requestId}/cancel`

```json
{ "reason": "duplicate submission" }
```

`200`. Terminal. Outstanding tasks are withdrawn so nothing is left sitting in an inbox, and a
`REQUEST_CANCELLED` webhook is queued.

| Scenario | Result |
|---|---|
| `IN_PROGRESS` / `CHANGES_REQUESTED` / `NEEDS_ATTENTION` | `200`, `CANCELLED` |
| Already terminal | `409 request-already-decided` |
| Body omitted entirely | `200` — the reason is optional |

---

# 6. Approver inbox

Engine-hosted, and **cross-application**: one approver, one queue, regardless of which consumer
raised the request. That shared queue is the main reason to host it here rather than leave it to
each app — and it is only coherent because tenancy is explicit, so items can be labelled with
the application they came from.

Identity comes from `X-Actor-Id`; every endpoint filters to tasks that actor actually holds.

## `GET /inbox`

`200` — pending approvals, oldest first.

```json
[ { "taskId": "tsk_…", "requestId": "req_…", "appId": "app_…",
    "title": "Engineering expense — INR 25000",
    "entityType": "expense", "entityId": "EXP-123",
    "requestedBy": "user-100", "requestedByName": "Asha Nair",
    "assignedVia": "ROLE(FINANCE)",
    "status": "PENDING", "dueAt": "2026-08-24T09:00:00Z", "overdue": false,
    "fields": [ { "label": "Amount", "value": 25000, "format": "CURRENCY_INR" } ] } ]
```

| Scenario | Notes |
|---|---|
| `WAITING` tasks (sequential, not your turn) | **Excluded.** Showing them would invite acting out of order |
| Items from several applications | All present. That is the point |
| Workflow has a `display` block | `title` rendered from its template; `fields` are its declared label/value pairs, already resolved |
| Workflow has **no** `display` block | Falls back to a generic key/value table over the context. Display metadata improves the experience; it is never required for correctness |
| A `display` field the context does not contain | Listed with `value: null`. Dropping it would make an incomplete submission look complete |
| Missing `X-Actor-Id` | `422 actor-required` |
| `overdue` | Computed for you, so a UI does not re-implement deadline maths |
| `context` | **Not** included — a fifty-item list does not need fifty full business payloads |

## `GET /inbox/history?limit=50`

`200` — what this approver has already decided, most recent first. Statuses `APPROVED`,
`REJECTED`, `CHANGES_REQUESTED`, `EXPIRED`.

## `GET /inbox/{taskId}`

`200` — the full approval screen for one task. Same shape as a list item **plus** the complete
`context`.

| Scenario | Result |
|---|---|
| Your own task | `200` |
| Someone else's task | `403 not-your-task` |
| Unknown task | `404 task-not-found` |
| Task whose request has been deleted | `404 request-not-found` |

Rendering is resolved server-side, so a web inbox, a Slack message and a CLI all show the same
thing without re-implementing path lookup or knowing anything about the consuming domain.

---

# 7. Directory

Roles, teams and departments are first-class entities with foreign keys — not opaque strings.
That is what lets a workflow naming a non-existent role be rejected at publish.

In a real deployment the directory is owned by an HRMS and these would be read-only.

## `GET /admin/directory/roles`

`200` — `[{roleCode, name, description, members, memberCount}]`, active roles only.

## `POST /admin/directory/roles`

```json
{ "roleCode": "LEGAL", "name": "Legal Review", "description": "Contract sign-off" }
```
`201`. Idempotent on `roleCode`. `422 role-code-required` if blank.

**A role must exist before a workflow can be published against it.** That ordering is the whole
mechanism behind turning a typo into a `422`.

## `POST /admin/directory/roles/{roleCode}/members/{userId}`

`200`. Grants the role.

| Scenario | Result |
|---|---|
| Valid grant | `200` with the updated member list |
| Already a member | `200`, no-op |
| Unknown role | `404 role-not-found` |
| Unknown or inactive user | `404 user-not-found` |
| **Request parked in `NEEDS_ATTENTION` because this role was empty** | Grant, then [reassign](#post-adminrequestsrequestidreassign) to replay the activation. This is the standard repair |

## `DELETE /admin/directory/roles/{roleCode}/members/{userId}`

`200`. Revokes the role, with effect **both** on future resolution and on anyone already holding
a task assigned via it — their next action is refused `403 approver-no-longer-eligible` and the
refusal is audited.

## `GET /admin/directory/users/{userId}/entitlements`

What the engine resolves for a user. The client sends only a user id and the engine looks
entitlements up itself — never accepting them from the caller, because a client that could
assert its own roles could assert its way into any approval. This endpoint makes that lookup
inspectable.

```json
{ "userId": "user-410", "active": true,
  "roles": ["FINANCE", "FINANCE_HEAD"], "teams": ["payments"],
  "department": "Finance", "manager": "user-950" }
```

An unknown user returns `active: false` with empty collections rather than a 404 — "this user
has no entitlements" is a useful answer.

## `GET /admin/directory/roles/{roleCode}/routed-workflows`

Impact analysis: every published workflow that routes approvals to this role.

```json
[ { "workflowKey": "expense_approval", "version": 1, "status": "ACTIVE",
    "stageId": "finance", "asFallback": false },
  { "workflowKey": "expense_approval", "version": 1, "status": "ACTIVE",
    "stageId": "manager", "asFallback": true } ]
```

`asFallback: true` means the role is only reached through an `onUnresolved` chain, not as the
primary route — a materially smaller blast radius.

This is the question you must be able to answer *before* retiring a role or reorganising a team,
and the reason approver rules are projected into rows rather than left inside JSON blobs.

---

# 8. Operations

## `GET /admin/outbox?status=DEAD&limit=50`

`200` — notification delivery state. `status` ∈ `PENDING` `SENT` `DEAD`, defaulting to `DEAD`.

```json
[ { "eventId": "evt_…", "eventType": "REQUEST_APPROVED", "requestId": "req_…",
    "status": "DEAD", "attempts": 8, "targetUrl": "https://…/hooks",
    "lastStatusCode": null,
    "lastError": "ResourceAccessException: I/O error on POST",
    "nextAttemptAt": "…", "sentAt": null } ]
```

Scoped to the calling application: only its own events are listed. Never exposes the signing
secret.

## `GET /admin/outbox/summary`

`200` — `{"PENDING": 0, "SENT": 26, "DEAD": 3}`. A one-glance health check; a rising `DEAD`
count means a consumer needs attention.

## `POST /admin/outbox/{eventId}/replay`

`202`. Requeues a dead-lettered notification and **resets the attempt counter**, so an operator
who has fixed the consumer gets the full retry budget rather than one last try.

`404 outbox-event-not-found` for an unknown id, or one that belongs to another application. Replaying an already-`SENT` event is permitted —
delivery is at-least-once and consumers must dedupe on `eventId` regardless.

## `POST /admin/requests/{requestId}/reassign`

```json
{ "reason": "added user-500 to the SECURITY role" }
```

`200` with the full detail view. Re-runs resolution for the stage that could not be staffed —
the right behaviour, because `NEEDS_ATTENTION` is almost always "the role was empty" or "the
approver had left", and the fix happens in the directory.

| Scenario | Result |
|---|---|
| `NEEDS_ATTENTION` and the directory has been fixed | `200`, `IN_PROGRESS`, the stage activates with real approvers |
| `NEEDS_ATTENTION` and the directory has **not** been fixed | `200`, but resolution fails again and it re-parks. The `faultReason` is refreshed |
| Any other status | `409 reassign-not-applicable` |
| `X-Actor-Id` omitted | Attributed to `OPERATOR` |

---

# 9. Outbound webhooks

The engine's only outbound channel. Delivered by a background dispatcher, never inline with an
approval.

```
POST {callbackUrl or the application's webhookUrl}
Content-Type: application/json
X-Approval-Event-Id: evt_01HX…
X-Approval-Event-Type: REQUEST_APPROVED
X-Approval-Timestamp: 1787476289
X-Approval-Signature: sha256=<hmac>
```
```json
{
  "eventId": "evt_01HX…",
  "eventType": "REQUEST_APPROVED",
  "occurredAt": "2026-08-23T09:11:28.989Z",
  "requestId": "req_01HX…",
  "entityType": "expense",
  "entityId": "EXP-123",
  "requestStatus": "APPROVED",
  "workflow": { "key": "expense_approval", "version": 1 },
  "finalDecision": "APPROVED",
  "decidedBy": [ { "actor": "user-900", "action": "APPROVED",
                   "comment": "Within Q3 budget", "at": "…" } ]
}
```

## Verifying the signature

```
expected = "sha256=" + HMAC_SHA256(webhookSecret, timestamp + "." + rawBody)
```

Sign the **raw body bytes**, and compare in constant time. The timestamp is inside the signed
material on purpose: it makes `X-Approval-Timestamp` trustworthy, so a captured payload cannot
be replayed later with a fresh timestamp. Reject anything older than ~5 minutes.

During a secret rotation, accept **either** the current or the previous secret.

## Event types

| `eventType` | When |
|---|---|
| `REQUEST_CREATED` | approval started; carries the planned stages |
| `STAGE_ACTIVATED` | a stage became active |
| `TASK_ASSIGNED` | someone was asked — the hook for sending your own notifications |
| `REQUEST_APPROVED` | **terminal** |
| `REQUEST_REJECTED` | **terminal** |
| `REQUEST_CANCELLED` | **terminal** |
| `REQUEST_CHANGES_REQUESTED` | prompt the requester to revise |
| `REQUEST_NEEDS_ATTENTION` | the engine is stuck — sent so you are never left waiting on a request that has silently stopped moving |

New members may be added. **Ignore event types you do not recognise.**

## The delivery contract

**Promised:** at-least-once · ordered **per request** · never lost. The decision and its outbox
row commit in the same transaction, so they cannot diverge.

**Not promised:** exactly-once · ordering **across** requests.

Therefore a consumer **must** dedupe on `eventId` and tolerate cross-request reordering.

`2xx` means **"I durably recorded this"** — not "I finished the business action". Persist the
event and process asynchronously, so a slow downstream does not become retry pressure on the
engine.

| Consumer response | Engine behaviour |
|---|---|
| `2xx` | `SENT` |
| `4xx` | Retried. A consumer mid-deploy commonly answers 404 or 401 for a while, and giving up immediately would drop a decision it genuinely needs |
| `5xx`, timeout, connection refused | Retried with exponential backoff + jitter |
| Still failing after `maxAttempts` (default 8) | `DEAD`, visible on `/admin/outbox`, replayable |

Backoff defaults to `2, 5, 15, 60, 300, 900, 3600` seconds, last value repeating, with jitter so
a fleet of events failing together does not retry in lockstep against a recovering consumer.

## No two-phase commit

Once humans have approved, the request is `APPROVED`, full stop. If your application then fails
to apply the action, the engine does **not** roll back. Distributed transactions across an
organisational boundary are not worth their cost, and the audit trail must reflect reality:
those people did approve.

Recovery is the consumer's concern — retry from your own store, or submit a reversal request
through the engine as a first-class approval of its own.

## What a consumer must implement

1. Store `requestId` against your entity when you submit.
2. Expose a webhook endpoint that verifies the HMAC, dedupes on `eventId`, and returns `2xx` fast.
3. Make applying a decision idempotent.
4. Reconcile periodically via `GET /requests?entityType=&entityId=` so a missed webhook self-heals.
5. Never treat your local copy of approval state as authoritative — the engine is.

---

# 10. Cross-cutting guarantees

## 10.1 Idempotency

`Idempotency-Key` on `POST /requests` and `POST /requests/{id}/actions`.

| Situation | Behaviour |
|---|---|
| New key | Claimed, work proceeds |
| Same key, same body | Stored response replayed verbatim, including its original status code. No re-execution |
| Same key, different body | `422 idempotency-key-reuse` |
| Same key, original still running | `409 idempotency-in-progress` |
| Two duplicates race for one key | One proceeds; the other gets the replay or `409 idempotency-conflict` |
| Work fails transiently | Claim released, so a legitimate retry is not permanently poisoned |
| **No key supplied** | Unprotected — but a database uniqueness guard on non-terminal requests still prevents two live approvals for one entity |

Bodies are compared by a **canonical** hash (parsed and re-serialised), so key ordering,
whitespace and a client's pretty-printer do not turn a genuine retry into a `422`.

Keys are scoped per application; two tenants choosing the same UUID cannot collide.

## 10.2 Concurrent actions

Guarantee: **the outcome of N concurrent actions equals some serial ordering of those same
actions.** No lost updates, no double advancement, no ambiguous audit.

Three mechanisms:

1. **A row lock on the request** for the duration of the action. Simultaneous approvers queue and are applied strictly one at a time. Different requests never contend.
2. **A conditional task claim** — `UPDATE … WHERE id = ? AND status = 'PENDING'`. Leaving `PENDING` is single-writer at the row level; the database picks the winner.
3. **Completion re-counted from the database inside the same transaction** that advances the stage — never from a cached tally.

Verified: three approvers firing simultaneously at one `PARALLEL`/`ALL` stage produced three
task approvals, exactly one stage advancement, and a contiguous audit sequence.

<details><summary><b>Why a row lock rather than optimistic locking alone</b></summary>

Optimistic locking was tried first and was wrong. Acting on a request means appending to a
totally-ordered audit log and re-counting completion — both serialization points by nature. With
optimistic locking, N approvers collide on the unique `(request_id, seq)` audit constraint on
*every* retry and can exhaust their budget, turning a routine race into a `409` the caller
cannot fix. A row lock resolves the order once, up front, at no cost to the concurrency that
matters. The `@Version` column remains as a second line of defence.
</details>

## 10.3 Workflow version pinning

A request stores `workflow_key` **and** `workflow_version` at creation and executes that exact
definition to completion.

Publishing a new version affects **new requests only**. In-flight requests are untouched — there
is no migration, because there is nothing to migrate. Archived versions are never deleted, since
requests are still executing them and finished ones still need explaining.

Verified: a request created on v1 finished on v1, with the Finance stage still skipped, after v2
had lowered the threshold that would have included it.

## 10.4 Approver unavailability

Resolution happens at **stage activation**, not request creation — so the org chart consulted is
the one true when approval is actually needed. Results are then **snapshotted** onto task rows,
so a later directory change cannot retroactively alter an in-flight stage.

"Nobody matched" is a declared decision, not an exception:
`FALLBACK_TO` → `SKIP_STAGE` → `FAIL_REQUEST` (default).

`FAIL_REQUEST` parks the request in `NEEDS_ATTENTION` — visible, notified via webhook, and
recoverable via reassign. Never auto-approved; never silently stalled.

## 10.5 Restart recovery

All execution state is rows. The executor is a pure function of those rows, every step is
idempotent, and task creation is guarded by a unique `(stage_instance_id, approver_id)` so
replaying a half-finished activation cannot duplicate anything.

On boot the engine releases stale outbox leases, finds requests that are `IN_PROGRESS` with no
outstanding task — precisely the state a process dying between "record the approval" and
"advance the stage" leaves behind — and re-drives them, recording `REQUEST_RECOVERED`.

No API surface. It just happens.

## 10.6 The actor header, and why it is safe enough

`X-Actor-Id` is **not authenticated** in this phase, and that is a stated limitation rather than
an oversight.

What makes it tolerable: **identity is never the authorisation.** Every action re-verifies that
the actor holds a `PENDING` task on that request, and that they still hold the role it was
assigned through. Roles are never accepted from the caller — the engine takes the user id and
looks entitlements up itself. So a wrong or guessed actor id cannot approve anything it was not
asked to.

The residual exposure is **reading** another approver's inbox, not acting as them. Closing it
requires real authentication (OIDC, or a signed identity assertion from the calling application)
in front of the inbox endpoints.

## 10.7 SLA and non-response

A task with `slaSeconds` gets a `dueAt`. A background sweeper applies the stage's declared
`onTimeout`:

| `onTimeout` | Behaviour |
|---|---|
| `NOTIFY_ONLY` | Reminder recorded; the deadline is pushed out one period, so it recurs rather than firing every minute forever |
| `ESCALATE` | Task `EXPIRED`; a new task is created for the approver's manager with a fresh deadline. No active manager → the request parks in `NEEDS_ATTENTION` rather than inventing a decision |
| `AUTO_APPROVE` | Silence treated as consent |
| `AUTO_REJECT` | Silence treated as refusal |

Timeouts go through the same state machine and audit path as human decisions, attributed to
`SYSTEM` — so an auto-approval is exactly as traceable as a manual one.

Sequential stages start each approver's clock when it becomes **their** turn, not when the stage
activated behind a slow predecessor.
