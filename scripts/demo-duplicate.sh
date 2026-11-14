#!/usr/bin/env bash
# Posts the SAME external_id twice (plus the brief's thinner duplicate body) and shows exactly one row exists.
# Usage: start the API (mvn spring-boot:run), then: ./scripts/demo-duplicate.sh [base-url]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
EXT="demo-flight-$(date +%s)"   # unique per run so the demo is repeatable against a persistent DB
body() { sed "s/flight-TXL-LHR-2026-03-11/$EXT/" "examples/$1.json"; }

echo "== 1st POST (expect 201)"
body flight | curl -s -w '\nHTTP %{http_code}\n' -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @-
echo; echo "== 2nd POST, identical body (expect 409 + existing_id)"
body flight | curl -s -w '\nHTTP %{http_code}\n' -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @-
echo; echo "== 3rd POST, the 'duplicate_flight' fixture body (empty coupons) (expect 409, NOT 400)"
body duplicate_flight | curl -s -w '\nHTTP %{http_code}\n' -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @-
echo; echo "== rows with that external_id (expect exactly 1 item)"
curl -s "$BASE/transactions?external_id=$EXT"; echo
