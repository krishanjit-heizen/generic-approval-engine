#!/usr/bin/env bash
#
# Every API call the engine currently supports, as a runnable walkthrough.
#
#   Terminal 1:  ./run.sh
#   Terminal 2:  ./scripts/api-examples.sh
#
# Each section prints the curl it is about to run, so you can copy any single call
# out of the output and re-run it by hand.
#
# Scope: this covers what is BUILT today — application onboarding, workflow
# authoring/versioning, and simulation. Request submission, approval actions, the
# inbox and webhooks are not implemented yet and are not exercised here.
#
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
JQ="python3 -m json.tool"

# --- helpers ----------------------------------------------------------------

step()  { printf '\n\033[1;36m━━━ %s\033[0m\n' "$1"; }
note()  { printf '\033[0;90m%s\033[0m\n' "$1"; }
show()  { printf '\033[0;33m$ %s\033[0m\n' "$1"; }

# Runs a curl, echoing it first, and pretty-prints the JSON response.
run() {
  show "$1"
  eval "$1" | $JQ 2>/dev/null || true
}

require_engine() {
  if ! curl -fsS "$BASE/v3/api-docs" >/dev/null 2>&1; then
    echo "error: engine is not responding at $BASE — start it with ./run.sh" >&2
    exit 1
  fi
}

require_engine

# ============================================================================
# 1. Onboarding — register a consuming application
# ============================================================================
step "1. Register a consuming application"
note "This endpoint is intentionally exempt from API-key auth: it is where a key comes"
note "from. In a real deployment it belongs behind operator auth or a provisioning pipeline."

REGISTER_CURL="curl -s -X POST $BASE/api/v1/admin/applications \
  -H 'Content-Type: application/json' \
  -d '{\"name\":\"expense-app-$RANDOM\",\"webhookUrl\":\"http://localhost:9999/hooks/approvals\"}'"

show "$REGISTER_CURL"
REGISTRATION=$(eval "$REGISTER_CURL")
echo "$REGISTRATION" | $JQ

API_KEY=$(echo "$REGISTRATION" | python3 -c 'import sys,json; print(json.load(sys.stdin)["apiKey"])')
APP_ID=$(echo "$REGISTRATION" | python3 -c 'import sys,json; print(json.load(sys.stdin)["appId"])')

note ""
note "The apiKey and webhookSecret above are shown ONCE — only a hash of the key is stored."
note "Everything below sends it as:  -H 'X-Api-Key: \$API_KEY'"

AUTH="-H 'X-Api-Key: $API_KEY'"

step "1b. List registered applications (never returns secrets)"
run "curl -s $BASE/api/v1/admin/applications"

# ============================================================================
# 2. Authentication
# ============================================================================
step "2. Calling without a key → 401 problem+json"
note "Errors are RFC 7807. Branch on the stable 'type' slug, never on the prose in 'detail'."
run "curl -s $BASE/api/v1/workflows"

step "2b. Calling with a bad key → 401, same message as an unknown key"
note "A caller holding an invalid key learns nothing about WHY it is invalid."
run "curl -s $BASE/api/v1/workflows -H 'X-Api-Key: not-a-real-key'"

# ============================================================================
# 3. Workflow validation
# ============================================================================
step "3. An invalid definition → 422 listing EVERY problem at once"
note "Seven distinct mistakes, one response. An author should not have to submit seven"
note "times to discover seven errors."

run "curl -s -X POST $BASE/api/v1/workflows $AUTH \
  -H 'Content-Type: application/json' \
  -d '{
  \"key\": \"broken\",
  \"stages\": [
    { \"id\": \"a\", \"approvers\": [] },
    { \"id\": \"a\", \"approvers\": [{\"type\":\"ROLE\"}],
      \"policy\": {\"type\":\"QUORUM\"},
      \"onReject\": \"GOTO\", \"onRejectGoto\": \"nowhere\",
      \"onTimeout\": \"ESCALATE\",
      \"condition\": {\"type\":\"compare\",\"path\":\"amount\",\"op\":\"IN\",\"value\":5} }
  ]
}'"

# ============================================================================
# 4. Create the workflow from the problem statement
# ============================================================================
step "4. Create the expense workflow (amount tiers from the brief)"
note "  amount < 10,000            → Manager"
note "  10,000 – 1,00,000          → Manager → Finance ∥ Compliance"
note "  > 1,00,000                 → Manager → Finance ∥ Compliance → Director"
note ""
note "The 'display' block is what lets the engine-hosted inbox render an expense claim"
note "without containing any code about expenses."

read -r -d '' EXPENSE_WORKFLOW <<'JSON' || true
{
  "key": "expense_approval",
  "name": "Expense Approval",
  "display": {
    "titleTemplate": "{{context.department}} expense — INR {{context.amount}}",
    "summaryFields": [
      { "path": "amount",      "label": "Amount",     "format": "CURRENCY_INR" },
      { "path": "department",  "label": "Department",  "format": "TEXT" },
      { "path": "vendor.name", "label": "Vendor",      "format": "TEXT" },
      { "path": "invoiceUrl",  "label": "Invoice",     "format": "LINK" }
    ]
  },
  "stages": [
    { "id": "manager", "name": "Manager Approval",
      "approvers": [
        { "type": "MANAGER_OF", "of": "$requestedBy",
          "onUnresolved": { "action": "FALLBACK_TO",
                            "spec": { "type": "ROLE", "role": "FINANCE_HEAD" } } }
      ],
      "policy": { "type": "ALL" },
      "onReject": "TERMINATE",
      "slaSeconds": 86400, "onTimeout": "ESCALATE" },

    { "id": "finance", "name": "Finance + Compliance",
      "condition": { "type": "compare", "path": "amount", "op": "GTE", "value": 10000 },
      "mode": "PARALLEL",
      "approvers": [ { "type": "ROLE", "role": "FINANCE" },
                     { "type": "ROLE", "role": "COMPLIANCE" } ],
      "policy": { "type": "ALL" },
      "onReject": "TERMINATE" },

    { "id": "director", "name": "Director",
      "condition": { "type": "compare", "path": "amount", "op": "GT", "value": 100000 },
      "approvers": [ { "type": "ROLE", "role": "DIRECTOR" } ],
      "policy": { "type": "ANY" } }
  ]
}
JSON

show "curl -s -X POST $BASE/api/v1/workflows -H 'X-Api-Key: \$API_KEY' \\
  -H 'X-Actor-Id: user-100' -H 'Content-Type: application/json' -d '<the JSON above>'"
curl -s -X POST "$BASE/api/v1/workflows" \
  -H "X-Api-Key: $API_KEY" -H 'X-Actor-Id: user-100' \
  -H 'Content-Type: application/json' -d "$EXPENSE_WORKFLOW" | $JQ

step "4b. Publish v1 — it becomes the version new requests bind to"
run "curl -s -X POST $BASE/api/v1/workflows/expense_approval/versions/1/publish $AUTH"

step "4c. Re-publishing the live version is idempotent, not an error"
note "So a retried deployment step does not fail."
run "curl -s -X POST $BASE/api/v1/workflows/expense_approval/versions/1/publish $AUTH"

# ============================================================================
# 5. Reading definitions
# ============================================================================
step "5. List all workflow versions for this application"
run "curl -s $BASE/api/v1/workflows $AUTH"

step "5b. Fetch one version's full definition"
run "curl -s $BASE/api/v1/workflows/expense_approval/versions/1 $AUTH"

# ============================================================================
# 6. Simulation — the rule engine, made inspectable
# ============================================================================
step "6. Simulate the three amount tiers"
note "'simulate' answers: given this context, which stages fire, WHY, and who gets asked —"
note "without creating anything. Approver resolution runs for real."

for AMOUNT in 5000 25000 250000; do
  printf '\n\033[1;37m  amount = %s\033[0m\n' "$AMOUNT"
  show "curl -s -X POST $BASE/api/v1/workflows/expense_approval/simulate -H 'X-Api-Key: \$API_KEY' \\
  -H 'Content-Type: application/json' \\
  -d '{\"requestedBy\":\"user-100\",\"context\":{\"amount\":$AMOUNT,\"department\":\"Engineering\"}}'"

  curl -s -X POST "$BASE/api/v1/workflows/expense_approval/simulate" \
    -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
    -d "{\"requestedBy\":\"user-100\",\"context\":{\"amount\":$AMOUNT,\"department\":\"Engineering\"}}" \
  | python3 -c '
import sys, json
for s in json.load(sys.stdin)["stages"]:
    mark = "APPLIES" if s["applies"] else "SKIPPED"
    who  = ", ".join("%s (%s)" % (a["approverId"], a["resolvedVia"]) for a in s["approvers"]) or "-"
    print("    %-7s  %-9s  %-38s  %s" % (mark, s["stageId"], s["reason"], who))
'
done

step "6b. Full simulation payload, including the per-node condition trace"
note "The 'trace' tree is what gets persisted onto the stage and into the audit log."
run "curl -s -X POST $BASE/api/v1/workflows/expense_approval/simulate $AUTH \
  -H 'Content-Type: application/json' \
  -d '{\"requestedBy\":\"user-100\",\"context\":{\"amount\":250000,\"department\":\"Engineering\"}}'"

# ============================================================================
# 7. The interesting failure paths
# ============================================================================
step "7. Unresolvable approver → the declared fallback fires"
note "user-950 has no manager in the directory, so MANAGER_OF resolves to nobody and the"
note "spec's onUnresolved FALLBACK_TO ROLE(FINANCE_HEAD) takes over. 'The approver does not"
note "exist' is a declared, auditable decision — not an exception."

curl -s -X POST "$BASE/api/v1/workflows/expense_approval/simulate" \
  -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"requestedBy":"user-950","context":{"amount":5000}}' \
| python3 -c '
import sys, json
for s in json.load(sys.stdin)["stages"]:
    if s["applies"]:
        who = ", ".join("%s (%s)" % (a["approverId"], a["resolvedVia"]) for a in s["approvers"]) or "NOBODY"
        print("    %-9s  %s" % (s["stageId"], who))
'

step "7b. A missing context field says so, rather than silently skipping"
note "This distinction is the whole point of the trace. Without the note, a stage that"
note "vanished because a consuming app renamed a field looks identical to one that was"
note "legitimately not applicable."

curl -s -X POST "$BASE/api/v1/workflows/expense_approval/simulate" \
  -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"requestedBy":"user-100","context":{"department":"Engineering"}}' \
| python3 -c '
import sys, json
for s in json.load(sys.stdin)["stages"]:
    print("    %-9s  applies=%-5s  %s" % (s["stageId"], s["applies"], s["reason"]))
'

step "7c. A role held only by an INACTIVE user resolves to nobody"
note "user-700 holds ROLE(SECURITY) but has left. With no onUnresolved policy, resolution"
note "reports the stage cannot be staffed — which at execution time parks the request in"
note "NEEDS_ATTENTION for an operator rather than approving it unseen."

curl -s -X POST "$BASE/api/v1/workflows" \
  -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' -d '{
  "key": "security_review",
  "name": "Security Review",
  "stages": [ { "id": "security", "name": "Security",
                "approvers": [ { "type": "ROLE", "role": "SECURITY" } ] } ]
}' >/dev/null
curl -s -X POST "$BASE/api/v1/workflows/security_review/versions/1/publish" -H "X-Api-Key: $API_KEY" >/dev/null

run "curl -s -X POST $BASE/api/v1/workflows/security_review/simulate $AUTH \
  -H 'Content-Type: application/json' -d '{\"requestedBy\":\"user-100\",\"context\":{}}'"

# ============================================================================
# 8. Versioning
# ============================================================================
step "8. Publish v2 — v1 is archived automatically, in one transaction"
note "There is never a moment with two ACTIVE versions, or none."

curl -s -X POST "$BASE/api/v1/workflows" \
  -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' -d '{
  "key": "expense_approval",
  "name": "Expense Approval (stricter Finance threshold)",
  "stages": [
    { "id": "manager", "name": "Manager Approval",
      "approvers": [ { "type": "MANAGER_OF", "of": "$requestedBy" } ] },
    { "id": "finance", "name": "Finance",
      "condition": { "type": "compare", "path": "amount", "op": "GTE", "value": 5000 },
      "approvers": [ { "type": "ROLE", "role": "FINANCE" } ],
      "policy": { "type": "QUORUM", "count": 2 } }
  ]
}' | $JQ

run "curl -s -X POST $BASE/api/v1/workflows/expense_approval/versions/2/publish $AUTH"

step "8b. Every version of the workflow, newest first"
note "v1 is ARCHIVED but still readable — requests that pinned it are still executing it,"
note "and finished ones still need explaining."
run "curl -s $BASE/api/v1/workflows/expense_approval/versions $AUTH"

step "8c. An archived version cannot be re-published → 409"
note "Roll forward with a new version instead; published versions are immutable."
run "curl -s -X POST $BASE/api/v1/workflows/expense_approval/versions/1/publish $AUTH"

step "8d. v1 and v2 disagree at amount=5000 — and both remain simulatable"
for VERSION in 1 2; do
  printf '\n\033[1;37m  version %s, amount = 5000\033[0m\n' "$VERSION"
  curl -s -X POST "$BASE/api/v1/workflows/expense_approval/simulate" \
    -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
    -d "{\"version\":$VERSION,\"requestedBy\":\"user-100\",\"context\":{\"amount\":5000}}" \
  | python3 -c '
import sys, json
for s in json.load(sys.stdin)["stages"]:
    mark = "APPLIES" if s["applies"] else "SKIPPED"
    print("    %-7s  %-9s  %s" % (mark, s["stageId"], s["reason"]))
'
done

# ============================================================================
# 9. Not-found handling
# ============================================================================
step "9. Unknown workflow → 404"
run "curl -s $BASE/api/v1/workflows/does_not_exist/versions/1 $AUTH"

step "9b. Unknown version of a known workflow → 404"
run "curl -s $BASE/api/v1/workflows/expense_approval/versions/99 $AUTH"

# ============================================================================
step "Done"
cat <<EOF

  app id   : $APP_ID
  api key  : $API_KEY

  OpenAPI  : $BASE/v3/api-docs
  Swagger  : $BASE/swagger-ui.html
  Database : $BASE/h2-console   (JDBC jdbc:h2:file:./data/approvals, user 'sa', no password)

  Not yet implemented, so not exercised above:
    POST /api/v1/requests                  submit for approval
    POST /api/v1/requests/{id}/actions     approve / reject / request changes
    GET  /api/v1/inbox                     engine-hosted approver queue
    GET  /api/v1/admin/outbox              webhook delivery + dead letters
EOF
