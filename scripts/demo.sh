#!/usr/bin/env bash
# End-to-end demo against a running NMS instance: happy path, idempotent replay, conflict,
# retries, failures, and the main error responses. Requires curl and jq.
#
#   docker compose up -d --wait
#   java -jar target/notification-management-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
#   scripts/demo.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BILLING_KEY="billing-demo-key-7f3a9c2e5b8d4f1a"
RUN="demo-$(date +%s)-$RANDOM"

section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# submit <idempotency-key> <json-body>  -> prints "<http-status> <response-body>"
submit() {
  curl -s -o /tmp/nms-demo-body.$$ -w '%{http_code}' -X POST "$BASE_URL/api/v1/notifications" \
    -H "X-API-Key: $BILLING_KEY" -H "Idempotency-Key: $1" -H 'Content-Type: application/json' -d "$2"
  printf ' '
  cat /tmp/nms-demo-body.$$
  echo
  rm -f /tmp/nms-demo-body.$$
}

body() { # body <recipient> [extra jq assignment]
  jq -nc --arg r "$1" --arg e "$RUN-$1" '{
    sourceSystem: "billing", eventId: $e, type: "TRANSACTIONAL", severity: "MEDIUM", priority: "NORMAL",
    recipients: [$r], channels: ["EMAIL"], subject: "Invoice ready", body: "Your invoice is ready."
  }' | jq -c "${2:-.}"
}

get() { curl -s "$BASE_URL$1" -H "X-API-Key: $BILLING_KEY"; }

# wait_for <notification-id> <status...>: poll until the overall status is one of the given values.
wait_for() {
  local id="$1"; shift
  for _ in $(seq 1 60); do
    local status
    status=$(get "/api/v1/notifications/$id" | jq -r .status)
    for want in "$@"; do [[ "$status" == "$want" ]] && { echo "$status"; return 0; }; done
    sleep 0.5
  done
  echo "timed out waiting for $* (last: $status)" >&2
  return 1
}

expect() { # expect <actual-status> <expected-status> <label>
  if [[ "$1" == "$2" ]]; then echo "  ok   $3 -> $1"; else echo "  FAIL $3 -> $1 (expected $2)"; exit 1; fi
}

section "Health"
curl -s "$BASE_URL/actuator/health" | jq -c .

section "Happy path: submit, then read status and audit"
read -r code resp < <(submit "$RUN-happy" "$(body cust-1001)")
expect "$code" 202 "submit to cust-1001"
ID=$(jq -r .id <<<"$resp")
echo "  final status: $(wait_for "$ID" COMPLETED)"
get "/api/v1/notifications/$ID" | jq '{status, selectedChannels, deliveries: [.deliveries[] | {channel, address, status, attemptCount}]}'
get "/api/v1/notifications/$ID/audit" | jq -r '.events[] | "  \(.eventType)"'

section "Idempotent replay (same key, same body)"
read -r code resp < <(submit "$RUN-happy" "$(body cust-1001)")
expect "$code" 200 "replay"
expect "$(jq -r .id <<<"$resp")" "$ID" "same notification ID"

section "Conflict (same key, different body)"
read -r code resp < <(submit "$RUN-happy" "$(body cust-1001 '.subject = "Changed"')")
expect "$code" 409 "reuse key with different body"

section "Retry: +flaky fails once, then succeeds"
read -r code resp < <(submit "$RUN-flaky" "$(body cust-2007)")
FLAKY=$(jq -r .id <<<"$resp")
echo "  final status: $(wait_for "$FLAKY" COMPLETED)"
get "/api/v1/notifications/$FLAKY" | jq -c '.deliveries[0] | {status, attemptCount, lastFailureClass}'
get "/api/v1/notifications/$FLAKY/audit" | jq -r '[.events[].eventType] | join(" -> ")'

section "Failures"
for recipient in cust-2005 cust-2006 cust-2004; do
  read -r code resp < <(submit "$RUN-$recipient" "$(body "$recipient")")
  FID=$(jq -r .id <<<"$resp")
  wait_for "$FID" FAILED >/dev/null
  get "/api/v1/notifications/$FID" | jq -c --arg r "$recipient" '{recipient: $r, status, delivery: (.deliveries[0] | {status, attemptCount, lastFailureClass})}'
done
read -r code resp < <(submit "$RUN-ratelimit" "$(body cust-2003)")
RID=$(jq -r .id <<<"$resp")
wait_for "$RID" IN_PROGRESS >/dev/null
get "/api/v1/notifications/$RID" | jq -c '{recipient: "cust-2003", status, delivery: (.deliveries[0] | {status, lastFailureClass, lastAttemptAt, nextAttemptAt})}'

section "Error responses"
read -r code _ < <(submit "$RUN-bad" "$(body cust-1001 '.severity = "URGENT" | .recipients = []')")
expect "$code" 400 "invalid severity and empty recipients"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/notifications/$ID")
expect "$code" 401 "no API key"
read -r code _ < <(submit "$RUN-forbidden" "$(body cust-1001 '.sourceSystem = "trading"')")
expect "$code" 403 "sourceSystem mismatch"
read -r code _ < <(submit "$RUN-none" "$(body cust-1003 '.channels = ["PUSH"]')")
expect "$code" 422 "no eligible channel"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/notifications/$(uuidgen | tr A-Z a-z)" -H "X-API-Key: $BILLING_KEY")
expect "$code" 404 "unknown notification"

section "Done"
