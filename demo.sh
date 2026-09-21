#!/usr/bin/env bash
#
# End-to-end demonstration of the approval engine.
#
#   ./demo.sh
#
# Self-contained: builds the jar, starts the engine, runs every scenario, and stops
# cleanly. It owns the engine's lifecycle because two of the scenarios (crash recovery
# and notification retry) can only be shown by killing and restarting things.
#
# Scenarios map onto the hard cases in the problem statement:
#
#   1  define → submit → resolve → execute → approve → decide → audit + notify
#   2  a workflow changed while a request was already running
#   3  a duplicate request from a client retry
#   4  two approvers acting at the same instant
#   5  an approver who no longer exists
#   6  a notification that cannot be delivered
#   7  the engine restarting mid-workflow
#   8  an approver who never responds
#   9  rejection, and changes-requested → resubmit with re-planning
#
set -uo pipefail
cd "$(dirname "$0")"

PORT=8080
HOOK_PORT=9099
BASE="http://localhost:$PORT"
LOG=/tmp/approval-demo-engine.log
HOOK_LOG=/tmp/approval-demo-hooks.jsonl
ENGINE_PID=""
HOOK_PID=""

# ---------------------------------------------------------------------------
# presentation helpers
# ---------------------------------------------------------------------------
BOLD=$'\033[1m'; DIM=$'\033[0;90m'; CYAN=$'\033[1;36m'; GREEN=$'\033[0;32m'
YELLOW=$'\033[0;33m'; RED=$'\033[0;31m'; OFF=$'\033[0m'

scenario() { printf '\n%s╔══ %s ══%s\n' "$CYAN" "$1" "$OFF"; }
question() { printf '%s   ? %s%s\n' "$YELLOW" "$1" "$OFF"; }
answer()   { printf '%s   → %s%s\n\n' "$GREEN" "$1" "$OFF"; }
step()     { printf '%s   · %s%s\n' "$DIM" "$1" "$OFF"; }
ok()       { printf '     %s✓%s %s\n' "$GREEN" "$OFF" "$1"; }
bad()      { printf '     %s✗%s %s\n' "$RED" "$OFF" "$1"; }

cleanup() {
  # No bare `wait` here either — the webhook receiver has no exit condition of its own.
  [[ -n "$HOOK_PID" ]] && kill "$HOOK_PID" 2>/dev/null
  [[ -n "$ENGINE_PID" ]] && kill "$ENGINE_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# toolchain
# ---------------------------------------------------------------------------
resolve_java8() {
  if [[ -x /usr/libexec/java_home ]]; then
    /usr/libexec/java_home -v 1.8 2>/dev/null && return 0
  fi
  for dir in "$HOME/Library/Java/JavaVirtualMachines"/* /Library/Java/JavaVirtualMachines/*; do
    [[ -x "$dir/Contents/Home/bin/java" ]] || continue
    if "$dir/Contents/Home/bin/java" -version 2>&1 | grep -q '"1\.8'; then
      echo "$dir/Contents/Home"; return 0
    fi
  done
  return 1
}

JAVA_HOME="$(resolve_java8)" || { echo "error: no JDK 8 found" >&2; exit 1; }
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

MVN="./mvnw"; [[ -x "$MVN" ]] || MVN="mvn"

printf '%sBuilding…%s\n' "$BOLD" "$OFF"
"$MVN" -q -B clean package -DskipTests 2>&1 | tail -5
JAR=$(find target -maxdepth 1 -name 'approval-engine-*.jar' ! -name '*sources*' | head -1)
[[ -f "$JAR" ]] || { echo "error: build produced no jar" >&2; exit 1; }
ok "built $JAR"

# ---------------------------------------------------------------------------
# engine lifecycle
#
# Scheduler intervals and the retry backoff are compressed so a demo does not take
# an hour. Production defaults live in application.yml.
# ---------------------------------------------------------------------------
start_engine() {
  java -jar "$JAR" \
    --approval.outbox.poll-interval-ms=1000 \
    --approval.outbox.backoff-seconds=2,3,4,5 \
    --approval.outbox.max-attempts=6 \
    --approval.sla.poll-interval-ms=2000 \
    --logging.level.com.approvalengine=INFO \
    >> "$LOG" 2>&1 &
  ENGINE_PID=$!
  for _ in $(seq 1 60); do
    curl -fsS "$BASE/v3/api-docs" >/dev/null 2>&1 && return 0
    sleep 1
  done
  bad "engine failed to start — see $LOG"; tail -30 "$LOG"; exit 1
}

stop_engine() {
  [[ -n "$ENGINE_PID" ]] || return 0
  kill "$ENGINE_PID" 2>/dev/null
  wait "$ENGINE_PID" 2>/dev/null
  ENGINE_PID=""
}

sql() {
  local h2jar
  h2jar=$(find ~/.m2/repository/com/h2database -name 'h2-2.1.214.jar' 2>/dev/null | head -1)
  printf '%s\n' "$1" > /tmp/approval-demo.sql
  java -cp "$h2jar" org.h2.tools.RunScript \
    -url "jdbc:h2:file:./data/approvals;AUTO_SERVER=TRUE" -user sa \
    -script /tmp/approval-demo.sql -showResults 2>/dev/null | sed '1d;$d'
}

# ---------------------------------------------------------------------------
# webhook receiver — verifies the HMAC signature exactly as a consumer should
# ---------------------------------------------------------------------------
start_hook_receiver() {
  local secret="$1"
  : > "$HOOK_LOG"
  # Secret passed via a file rather than the environment. An env var that fails to
  # propagate into the receiver produces a silent all-signatures-invalid result, which
  # looks exactly like a signing bug in the engine — a file makes the failure loud instead.
  printf '%s' "$secret" > /tmp/approval-demo-secret
  HOOK_LOG="$HOOK_LOG" HOOK_PORT="$HOOK_PORT" python3 - <<'PY' &
import hashlib, hmac, json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

with open("/tmp/approval-demo-secret", "rb") as f:
    SECRET = f.read()
if not SECRET:
    sys.stderr.write("receiver: empty webhook secret — refusing to start\n")
    sys.exit(1)
LOG = os.environ["HOOK_LOG"]

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        ts   = self.headers.get("X-Approval-Timestamp", "")
        sent = self.headers.get("X-Approval-Signature", "")
        # The engine signs "{timestamp}.{body}", so a captured payload cannot be
        # replayed later with a fresh timestamp.
        expected = "sha256=" + hmac.new(SECRET, (ts + ".").encode() + body, hashlib.sha256).hexdigest()
        valid = hmac.compare_digest(sent, expected)
        if not valid:
            # Print the comparison rather than only a boolean, so a mismatch says which side
            # is wrong instead of leaving "signatures invalid" to be guessed at.
            sys.stderr.write("receiver: signature mismatch\n  sent     %s\n  expected %s\n"
                             % (sent, expected))
        event = json.loads(body)
        with open(LOG, "a") as f:
            f.write(json.dumps({
                "eventType":  event.get("eventType"),
                "entityId":   event.get("entityId"),
                "status":     event.get("requestStatus"),
                "eventId":    self.headers.get("X-Approval-Event-Id"),
                "sigValid":   valid,
            }) + "\n")
        self.send_response(200); self.end_headers(); self.wfile.write(b"{}")
    def log_message(self, *a): pass

HTTPServer(("127.0.0.1", int(os.environ["HOOK_PORT"])), Handler).serve_forever()
PY
  HOOK_PID=$!
  sleep 1
}

hooks_for() { grep -F "\"$1\"" "$HOOK_LOG" 2>/dev/null; }

# ---------------------------------------------------------------------------
# api helpers
# ---------------------------------------------------------------------------
api() {  # api METHOD PATH [BODY] [EXTRA_HEADERS...]
  local method="$1" path="$2" body="${3:-}"; shift 3 || shift 2
  local args=(-s -X "$method" "$BASE$path" -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json')
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" "$@"
}
jqf() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

# ===========================================================================
printf '\n%sStarting engine on a clean database…%s\n' "$BOLD" "$OFF"
rm -rf data; : > "$LOG"
start_engine
ok "engine up (pid $ENGINE_PID)"

REG=$(curl -s -X POST "$BASE/api/v1/admin/applications" -H 'Content-Type: application/json' \
  -d "{\"name\":\"expense-app\",\"webhookUrl\":\"http://localhost:$HOOK_PORT/hooks\"}")
API_KEY=$(echo "$REG" | jqf 'd["apiKey"]')
SECRET=$(echo "$REG" | jqf 'd["webhookSecret"]')
ok "registered expense-app"
start_hook_receiver "$SECRET"
ok "webhook receiver listening on :$HOOK_PORT (verifying HMAC)"

# ===========================================================================
scenario "1 · the whole flow: define → submit → resolve → execute → decide → audit + notify"

step "publish a workflow with the amount tiers from the problem statement"
api POST /api/v1/workflows '{
  "key":"expense_approval","name":"Expense Approval",
  "display":{"titleTemplate":"{{context.department}} expense — INR {{context.amount}}",
             "summaryFields":[{"path":"amount","label":"Amount","format":"CURRENCY_INR"},
                              {"path":"department","label":"Department","format":"TEXT"}]},
  "stages":[
    {"id":"manager","name":"Manager Approval",
     "approvers":[{"type":"MANAGER_OF","of":"$requestedBy",
                   "onUnresolved":{"action":"FALLBACK_TO","spec":{"type":"ROLE","role":"FINANCE_HEAD"}}}]},
    {"id":"finance","name":"Finance + Compliance","mode":"PARALLEL",
     "condition":{"type":"compare","path":"amount","op":"GTE","value":10000},
     "approvers":[{"type":"ROLE","role":"FINANCE"},{"type":"ROLE","role":"COMPLIANCE"}],
     "policy":{"type":"ALL"},"onReject":"TERMINATE"},
    {"id":"director","name":"Director",
     "condition":{"type":"compare","path":"amount","op":"GT","value":100000},
     "approvers":[{"type":"ROLE","role":"DIRECTOR"}],"policy":{"type":"ANY"}}]}' >/dev/null
api POST /api/v1/workflows/expense_approval/versions/1/publish '' >/dev/null
ok "expense_approval v1 is ACTIVE"

step "submit EXP-001 for 5,000 — below every threshold, so manager only"
R1=$(api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-001","requestedBy":"user-100","context":{"amount":5000,"department":"Engineering"}}')
RID1=$(echo "$R1" | jqf 'd["request"]["requestId"]')
echo "$R1" | python3 -c '
import sys,json
d=json.load(sys.stdin)
for s in d["stages"]:
    mark = "applies" if s["status"] != "SKIPPED" else "skipped"
    print("       %-8s %-9s %s" % (mark, s["stageId"], json.loads(json.dumps(s["conditionTrace"]))["expression"]))
print("       asked: " + ", ".join("%s (%s)" % (t["approverId"], t["resolvedVia"]) for t in d["tasks"]))'

step "the manager sees it in the engine-hosted inbox"
curl -s "$BASE/api/v1/inbox" -H "X-Api-Key: $API_KEY" -H 'X-Actor-Id: user-900' | python3 -c '
import sys,json
for i in json.load(sys.stdin):
    print("       %s" % i["title"])
    for f in i["fields"]: print("         %-12s %s" % (f["label"]+":", f["value"]))'

step "manager approves"
api POST "/api/v1/requests/$RID1/actions" '{"action":"APPROVE","comment":"within budget"}' \
  -H 'X-Actor-Id: user-900' | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       %s → request %s" % (d["stageOutcome"], d["requestStatus"]))'

sleep 3
step "audit trail (append-only, totally ordered):"
curl -s "$BASE/api/v1/requests/$RID1/audit" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json
for a in json.load(sys.stdin):
    print("       %2d  %-9s %-24s %s" % (a["seq"], a["actor"], a["action"], a.get("comment") or ""))'

step "webhooks delivered to the consuming application:"
hooks_for EXP-001 | python3 -c '
import sys,json
for l in sys.stdin:
    e=json.loads(l); print("       %-26s %-10s sig=%s" % (e["eventType"], e["status"], "valid" if e["sigValid"] else "INVALID"))'
answer "full lifecycle works, signed webhooks verified by the consumer"

# ===========================================================================
scenario "2 · a workflow is changed while a request is already running"
question "which definition should the in-flight request follow?"

step "EXP-002 starts on v1 (Finance applies at ≥10,000)"
R2=$(api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-002","requestedBy":"user-100","context":{"amount":9000,"department":"Engineering"}}')
RID2=$(echo "$R2" | jqf 'd["request"]["requestId"]')
echo "$R2" | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       pinned to %s v%d" % (d["request"]["workflow"]["key"], d["request"]["workflow"]["version"]))
for s in d["stages"]: print("       %-9s %s" % (s["stageId"], s["status"]))'

step "now publish v2, which lowers the Finance threshold to 1,000"
api POST /api/v1/workflows '{"key":"expense_approval","name":"Expense Approval v2",
  "stages":[{"id":"manager","approvers":[{"type":"MANAGER_OF","of":"$requestedBy"}]},
            {"id":"finance","condition":{"type":"compare","path":"amount","op":"GTE","value":1000},
             "approvers":[{"type":"ROLE","role":"FINANCE"}],"policy":{"type":"ANY"}}]}' >/dev/null
api POST /api/v1/workflows/expense_approval/versions/2/publish '' >/dev/null
ok "v2 ACTIVE, v1 ARCHIVED"

step "approve the in-flight request and see which plan it follows"
api POST "/api/v1/requests/$RID2/actions" '{"action":"APPROVE"}' -H 'X-Actor-Id: user-900' >/dev/null
curl -s "$BASE/api/v1/requests/$RID2" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       final: %s   (on v%d)" % (d["request"]["status"], d["request"]["workflow"]["version"]))
for s in d["stages"]: print("       %-9s %s" % (s["stageId"], s["status"]))'
answer "it finished on v1 — Finance stayed skipped. Versions are immutable and the request pinned one at creation, so there is nothing to migrate."

# ===========================================================================
scenario "3 · the client retries a request after a network failure"
question "how are duplicates prevented?"

step "submit EXP-003 with Idempotency-Key 'net-retry-1'"
A=$(api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-003","requestedBy":"user-100","context":{"amount":2000}}' \
  -H 'Idempotency-Key: net-retry-1')
step "send the byte-identical request again with the same key"
B=$(api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-003","requestedBy":"user-100","context":{"amount":2000}}' \
  -H 'Idempotency-Key: net-retry-1')
IDA=$(echo "$A" | jqf 'd["request"]["requestId"]')
IDB=$(echo "$B" | jqf 'd["request"]["requestId"]')
[[ "$IDA" == "$IDB" ]] && ok "same requestId returned ($IDA) — the stored response was replayed" \
                       || bad "created twice: $IDA vs $IDB"

step "same key, different body"
api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-003","requestedBy":"user-100","context":{"amount":999999}}' \
  -H 'Idempotency-Key: net-retry-1' | python3 -c '
import sys,json;d=json.load(sys.stdin);print("       %d %s" % (d["status"], d["type"]))'

step "and a retry that forgets the header entirely"
api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-003","requestedBy":"user-100","context":{"amount":2000}}' | python3 -c '
import sys,json;d=json.load(sys.stdin);print("       %d %s" % (d["status"], d["type"]))'
answer "replay on identical body, 422 on key reuse, and a database uniqueness guard catches retries with no key at all"

# ===========================================================================
scenario "4 · two approvers act at the same instant"
question "how is state kept consistent?"

api POST /api/v1/workflows '{"key":"parallel_review","name":"Parallel Review",
  "stages":[{"id":"review","mode":"PARALLEL",
             "approvers":[{"type":"ROLE","role":"FINANCE"},{"type":"ROLE","role":"COMPLIANCE"}],
             "policy":{"type":"ALL"},"onReject":"TERMINATE"}]}' >/dev/null
api POST /api/v1/workflows/parallel_review/versions/1/publish '' >/dev/null

R4=$(api POST /api/v1/requests '{"workflow":"parallel_review","entityType":"contract",
  "entityId":"CTR-004","requestedBy":"user-100","context":{}}')
RID4=$(echo "$R4" | jqf 'd["request"]["requestId"]')
step "3 approvers on one PARALLEL stage, policy ALL — firing simultaneously"

RACE_PIDS=()
for U in user-410 user-411 user-500; do
  ( api POST "/api/v1/requests/$RID4/actions" '{"action":"APPROVE","comment":"ok"}' \
      -H "X-Actor-Id: $U" > "/tmp/demo-race-$U.json" ) &
  RACE_PIDS+=($!)
done
# Waits on exactly these three. A bare `wait` would also wait on the webhook receiver,
# which never exits.
for p in "${RACE_PIDS[@]}"; do wait "$p"; done
for U in user-410 user-411 user-500; do
  printf '       %-9s → ' "$U"
  python3 -c "
import json; d=json.load(open('/tmp/demo-race-$U.json'))
print('%s (%s)' % (d['type'], d['status']) if 'type' in d else '%-26s request=%s' % (d['stageOutcome'], d['requestStatus']))"
done

curl -s "$BASE/api/v1/requests/$RID4/audit" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json
a=json.load(sys.stdin); seqs=[x["seq"] for x in a]
approvals=[x for x in a if x["action"]=="TASK_APPROVED"]
completions=[x for x in a if x["action"]=="STAGE_APPROVED"]
print("       audit seq %s  contiguous=%s" % (seqs, seqs==list(range(1,len(seqs)+1))))
print("       task approvals recorded: %d   stage advancements: %d" % (len(approvals), len(completions)))'
answer "all three approvals recorded exactly once, exactly one stage advancement, audit strictly ordered — the outcome equals a serial ordering of the same actions"

# ===========================================================================
scenario "5 · an approver no longer exists"
question "what happens to the approval?"

step "a workflow whose only approver role is held solely by an inactive user"
api POST /api/v1/workflows '{"key":"security_review","name":"Security Review",
  "stages":[{"id":"security","approvers":[{"type":"ROLE","role":"SECURITY"}]}]}' >/dev/null
api POST /api/v1/workflows/security_review/versions/1/publish '' >/dev/null

R5=$(api POST /api/v1/requests '{"workflow":"security_review","entityType":"access",
  "entityId":"ACC-005","requestedBy":"user-100","context":{}}')
RID5=$(echo "$R5" | jqf 'd["request"]["requestId"]')
echo "$R5" | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       request %s" % d["request"]["status"])
print("       reason: %s" % d["request"]["faultReason"])'

step "an operator grants the role through the directory API, then reassigns"
api POST "/api/v1/admin/directory/roles/SECURITY/members/user-500" '' \
  | python3 -c 'import sys,json;print("       SECURITY now has: %s" % json.load(sys.stdin)["members"])'
api POST "/api/v1/admin/requests/$RID5/reassign" '{"reason":"added user-500 to SECURITY"}' \
  -H 'X-Actor-Id: ops-1' | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       request %s" % d["request"]["status"])
for t in d["tasks"]:
    if t["status"]=="PENDING": print("       now asked: %s (%s)" % (t["approverId"], t["resolvedVia"]))'
answer "parked in NEEDS_ATTENTION with the reason, notified, and recoverable — never silently stalled, and never auto-approved"

step "the same model rejects a typo at PUBLISH time, rather than at runtime"
api POST /api/v1/workflows '{"key":"typo_demo",
  "stages":[{"id":"s","approvers":[{"type":"ROLE","role":"FINACE"}]}]}' | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       %d %s" % (d["status"], d["type"]))
for e in d.get("errors",[]): print("         %s" % e)'
answer "roles are entities with keys, so a misspelling is a 422 while the author is still looking at it — not a stuck request three weeks later"

step "and it answers 'what breaks if we retire this role?'"
for R in FINANCE FINANCE_HEAD; do
  printf '       %-13s ' "$R:"
  curl -s "$BASE/api/v1/admin/directory/roles/$R/routed-workflows" -H "X-Api-Key: $API_KEY" \
  | python3 -c '
import sys,json
rs=json.load(sys.stdin)
print(", ".join("%s v%d/%s%s" % (r["workflowKey"],r["version"],r["stageId"],
      " (fallback)" if r["asFallback"] else "") for r in rs) or "nothing")'
done

# ===========================================================================
scenario "5b · an approver loses the role that got them the task"
question "holding a task proves you were asked — does it prove you are still the right person?"

R5B=$(api POST /api/v1/requests '{"workflow":"parallel_review","entityType":"contract",
  "entityId":"CTR-005B","requestedBy":"user-100","context":{}}')
RID5B=$(echo "$R5B" | jqf 'd["request"]["requestId"]')
echo "$R5B" | python3 -c '
import sys,json
for t in json.load(sys.stdin)["tasks"]:
    print("       PENDING  %-9s via %s" % (t["approverId"], t["resolvedVia"]))'

step "user-410 leaves Finance while still holding a PENDING task"
api DELETE "/api/v1/admin/directory/roles/FINANCE/members/user-410" '' \
  | python3 -c 'import sys,json;print("       FINANCE now has: %s" % json.load(sys.stdin)["members"])'

step "user-410 tries to approve anyway"
api POST "/api/v1/requests/$RID5B/actions" '{"action":"APPROVE"}' -H 'X-Actor-Id: user-410' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("       %d %s — %s" % (d["status"],d["type"],d["detail"]))'

step "user-411, still in Finance, is unaffected"
api POST "/api/v1/requests/$RID5B/actions" '{"action":"APPROVE"}' -H 'X-Actor-Id: user-411' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("       -> %s" % (d.get("stageOutcome") or d.get("type")))'

step "and the refusal is in the audit trail, not just the HTTP response"
curl -s "$BASE/api/v1/requests/$RID5B/audit" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json
for a in json.load(sys.stdin):
    if a["action"] == "TASK_ACTION_REFUSED":
        print("       seq %d  %s  %s" % (a["seq"], a["actor"], (a.get("comment") or "")[:76]))'

step "restore the role for the rest of the demo"
api POST "/api/v1/admin/directory/roles/FINANCE/members/user-410" '' >/dev/null
answer "no. The engine takes the user id and looks entitlements up itself — never trusting roles from the caller — and re-checks at the moment of acting. A blocked approval is exactly what a reviewer needs to see, so it is audited."

# ===========================================================================
scenario "6 · the decision is made but the consumer is unreachable"
question "how does the system recover?"

step "point a request's callback at a dead port"
R6=$(api POST /api/v1/requests '{"workflow":"expense_approval","entityType":"expense",
  "entityId":"EXP-006","requestedBy":"user-100","context":{"amount":500},
  "callbackUrl":"http://localhost:9998/gone"}')
RID6=$(echo "$R6" | jqf 'd["request"]["requestId"]')
api POST "/api/v1/requests/$RID6/actions" '{"action":"APPROVE"}' -H 'X-Actor-Id: user-900' >/dev/null
ok "request APPROVED — the decision committed regardless of the consumer"

step "watching delivery attempts climb and back off…"
for _ in 1 2 3 4 5 6 7 8; do
  sleep 2
  curl -s "$BASE/api/v1/admin/outbox?status=PENDING&limit=20" -H "X-Api-Key: $API_KEY" | python3 -c "
import sys,json
for e in json.load(sys.stdin):
    if e['requestId']=='$RID6' and e['eventType']=='REQUEST_APPROVED':
        print('       attempts=%d  next in ~%ss  last=%s' % (e['attempts'], 'a few', (e['lastError'] or '')[:46]))"
done

step "the approval itself is untouched by the delivery failure"
curl -s "$BASE/api/v1/requests/$RID6" -H "X-Api-Key: $API_KEY" \
  | jqf '"       request still: " + d["request"]["status"]'
answer "the decision and the notification commit together, so the decision can never be lost — delivery just retries, then dead-letters visibly for operator replay"

step "dead-letter queue + replay (after the consumer is fixed)"
curl -s "$BASE/api/v1/admin/outbox/summary" -H "X-Api-Key: $API_KEY" | jqf '"       " + json.dumps(d)'

# ===========================================================================
scenario "7 · the engine goes down mid-workflow"
question "how does execution safely resume?"

R7=$(api POST /api/v1/requests '{"workflow":"parallel_review","entityType":"contract",
  "entityId":"CTR-007","requestedBy":"user-100","context":{}}')
RID7=$(echo "$R7" | jqf 'd["request"]["requestId"]')
ok "CTR-007 submitted, PARALLEL stage active with 3 approvers"

step "stopping the engine"
stop_engine

step "simulating a crash that landed between 'record the approval' and 'advance the stage'"
step "(editing the database directly — this state is unreachable while the engine runs,"
step " because both happen in one transaction)"
sql "UPDATE approval_task SET status='APPROVED', acted_by=approver_id, acted_at=CURRENT_TIMESTAMP
     WHERE request_id='$RID7' AND status='PENDING';" >/dev/null
sql "SELECT status, COUNT(*) FROM approval_task WHERE request_id='$RID7' GROUP BY status;" \
  | sed 's/^/       tasks: /'
sql "SELECT status FROM approval_request WHERE id='$RID7';" | sed 's/^/       request: /'
step "→ IN_PROGRESS, stage still ACTIVE, nothing outstanding. Stuck forever without recovery."

step "restarting the engine"
start_engine
sleep 3
grep -E "startup recovery|resumed request|stalled" "$LOG" | tail -4 | sed 's/^/       /'

curl -s "$BASE/api/v1/requests/$RID7" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       request now: %s" % d["request"]["status"])
for s in d["stages"]: print("       %-9s %s" % (s["stageId"], s["status"]))
print("       recovery recorded in audit: %s" % any(a["action"]=="REQUEST_RECOVERED" for a in d["audit"]))'
answer "no in-memory state to lose: 'where was it?' is a query, so recovery is a re-drive. Every step is idempotent, so replaying cannot duplicate tasks."

# ===========================================================================
scenario "8 · an approver never responds"
question "what stops the request stalling forever?"

step "a workflow with a 3-second SLA that escalates to the approver's manager"
api POST /api/v1/workflows '{"key":"sla_demo","name":"SLA Demo",
  "stages":[{"id":"review","approvers":[{"type":"USER","userId":"user-410"}],
             "slaSeconds":3,"onTimeout":"ESCALATE"}]}' >/dev/null
api POST /api/v1/workflows/sla_demo/versions/1/publish '' >/dev/null

R8=$(api POST /api/v1/requests '{"workflow":"sla_demo","entityType":"invoice",
  "entityId":"INV-008","requestedBy":"user-100","context":{}}')
RID8=$(echo "$R8" | jqf 'd["request"]["requestId"]')
echo "$R8" | python3 -c '
import sys,json;d=json.load(sys.stdin)
for t in d["tasks"]: print("       asked: %s (%s)" % (t["approverId"], t["resolvedVia"]))'

step "nobody acts. waiting for the sweeper…"
sleep 8
curl -s "$BASE/api/v1/requests/$RID8" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json;d=json.load(sys.stdin)
for t in d["tasks"]: print("       %-10s %-10s %s" % (t["status"], t["approverId"], t["resolvedVia"]))
print("       audit:")
for a in d["audit"]:
    if a["action"] in ("TASK_EXPIRED","TASK_ESCALATED"):
        print("         %-9s %-16s %s" % (a["actor"], a["action"], a.get("comment") or ""))'
answer "the deadline fires the stage's declared onTimeout, attributed to SYSTEM and audited exactly like a human decision"

# ===========================================================================
scenario "9 · rejection, and changes-requested → resubmit with re-planning"

step "a 3-tier workflow; submit 250,000 so the Director stage applies"
api POST /api/v1/workflows '{"key":"tiered","name":"Tiered",
  "stages":[
    {"id":"manager","approvers":[{"type":"MANAGER_OF","of":"$requestedBy"}]},
    {"id":"director","condition":{"type":"compare","path":"amount","op":"GT","value":100000},
     "approvers":[{"type":"ROLE","role":"DIRECTOR"}],"policy":{"type":"ANY"}}]}' >/dev/null
api POST /api/v1/workflows/tiered/versions/1/publish '' >/dev/null

R9=$(api POST /api/v1/requests '{"workflow":"tiered","entityType":"expense",
  "entityId":"EXP-009","requestedBy":"user-100","context":{"amount":250000}}')
RID9=$(echo "$R9" | jqf 'd["request"]["requestId"]')
echo "$R9" | python3 -c '
import sys,json
for s in json.load(sys.stdin)["stages"]: print("       %-9s %s" % (s["stageId"], s["status"]))'

step "the manager asks for changes: 'bring this under 1 lakh'"
api POST "/api/v1/requests/$RID9/actions" \
  '{"action":"REQUEST_CHANGES","comment":"bring this under 1 lakh"}' \
  -H 'X-Actor-Id: user-900' | jqf '"       request → " + d["requestStatus"]'

step "the requester complies and resubmits at 80,000"
api POST "/api/v1/requests/$RID9/resubmit" \
  '{"context":{"amount":80000},"comment":"reduced as requested"}' \
  -H 'X-Actor-Id: user-100' | python3 -c '
import sys,json;d=json.load(sys.stdin)
print("       request %s" % d["request"]["status"])
for s in d["stages"]:
    print("       [%d.%d] %-9s %-9s %s" % (s["index"], s["attempt"], s["stageId"], s["status"],
          (s["conditionTrace"] or {}).get("expression","")))'
answer "the Director stage was re-planned away — an approver who says 'reduce it below the threshold' gets compliance honoured. Attempt 2 of the manager stage keeps attempt 1 intact in the record."

step "and a straight rejection terminates the request"
R10=$(api POST /api/v1/requests '{"workflow":"tiered","entityType":"expense",
  "entityId":"EXP-010","requestedBy":"user-100","context":{"amount":50000}}')
RID10=$(echo "$R10" | jqf 'd["request"]["requestId"]')
api POST "/api/v1/requests/$RID10/actions" '{"action":"REJECT","comment":"not budgeted"}' \
  -H 'X-Actor-Id: user-900' | jqf '"       " + d["stageOutcome"] + " → request " + d["requestStatus"]'

sleep 3
step "terminal webhooks received across the whole demo:"
python3 - <<'PY'
import json, collections
counts = collections.Counter()
invalid = 0
for line in open("/tmp/approval-demo-hooks.jsonl"):
    e = json.loads(line)
    counts[e["eventType"]] += 1
    if not e["sigValid"]: invalid += 1
for k, v in sorted(counts.items()):
    print("       %-28s %d" % (k, v))
print("       signatures invalid: %d" % invalid)
PY

# ===========================================================================
printf '\n%s╔══ summary ══%s\n' "$CYAN" "$OFF"
curl -s "$BASE/api/v1/admin/outbox/summary" -H "X-Api-Key: $API_KEY" | jqf '"   outbox: " + json.dumps(d)'
curl -s "$BASE/api/v1/requests?limit=50" -H "X-Api-Key: $API_KEY" | python3 -c '
import sys,json,collections
c=collections.Counter(r["status"] for r in json.load(sys.stdin))
print("   requests: " + json.dumps(dict(c)))'
cat <<EOF

   API key : $API_KEY
   Swagger : $BASE/swagger-ui.html
   Database: $BASE/h2-console   (jdbc:h2:file:./data/approvals, user 'sa', no password)
   Engine log: $LOG
   Webhooks  : $HOOK_LOG

EOF
