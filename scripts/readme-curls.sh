#!/usr/bin/env bash
# Runs the example curls from README.md against a running API (default http://localhost:8080).
# Usage: ./scripts/readme-curls.sh      (start the app first: mvn spring-boot:run)
cd "$(dirname "$0")/.." || exit 1
B=${B:-http://localhost:8080}
H='Content-Type: application/json'
getid() { grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4; }

# --- create (each type)
FLIGHT=$(curl -s -X POST $B/transactions -H "$H" -d @examples/flight.json         | getid)
HOTEL=$( curl -s -X POST $B/transactions -H "$H" -d @examples/hotel.json          | getid)
RAIL=$(  curl -s -X POST $B/transactions -H "$H" -d @examples/rail.json           | getid)
TRIP=$(  curl -s -X POST $B/transactions -H "$H" -d @examples/trip_fee.json       | getid)
CALL=$(  curl -s -X POST $B/transactions -H "$H" -d @examples/agent_call_fee.json | getid)
echo $FLIGHT $HOTEL $RAIL $TRIP $CALL

# --- duplicate create: same external_id twice (+ the thinner duplicate fixture) -> 409, exactly one row
curl -s -i -X POST $B/transactions -H "$H" -d @examples/flight.json            # 409, body has existing_id
curl -s -i -X POST $B/transactions -H "$H" -d @examples/duplicate_flight.json  # 409 as well (not 400)
curl -s "$B/transactions?external_id=flight-TXL-LHR-2026-03-11"                # "items": exactly one
#   or all of the above in one go (uses a fresh external_id each run):  ./scripts/demo-duplicate.sh

# --- get / list
curl -s $B/transactions/$FLIGHT                  # type, metadata.coupons, tax_lines, fee_lines …
curl -s $B/transactions/$HOTEL                   # metadata.name + metadata.address
curl -s $B/transactions/$RAIL                    # metadata.legs (2)
curl -s $B/transactions/$TRIP                    # metadata.fee_kind = trip_fee
curl -s "$B/transactions?limit=10&offset=0"
curl -s -i "$B/transactions?limit=1000"          # 400

# --- invoice by transaction id(s)
INV=$(curl -s -X POST $B/invoices -H "$H" -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$CALL\"]}" | getid)
curl -s -X POST $B/invoices -H "$H" -d "{\"transaction_id\":\"$HOTEL\"}"       # single-id form
curl -s $B/invoices/$INV                         # total 685.70, totals{…}, tax_rollup, fee_rollup, lines[]
curl -s "$B/invoices?limit=10"

# --- patch / delete a transaction that is on an invoice -> 409, nothing changes
curl -s -i -X PATCH  $B/transactions/$FLIGHT -H "$H" -d '{"total": 999.00}'
curl -s -i -X DELETE $B/transactions/$FLIGHT

# --- patch / delete a transaction that is NOT on an invoice
NEW=$(curl -s -X POST $B/transactions -H "$H" -d '{"type":"navan_fee","external_id":"fee-demo-1","currency":"EUR","occurred_at":"2026-03-12T10:00:00Z","total":10,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee"}}' | getid)
curl -s -X PATCH  $B/transactions/$NEW -H "$H" -d '{"total": 12.50, "metadata": {"fee_kind":"agent_call_fee","description":"updated"}}'
curl -s -i -X DELETE $B/transactions/$NEW        # 204

# --- patch an invoice (replace the set, rebuild snapshot) / mixed currency -> 409
curl -s -X PATCH $B/invoices/$INV -H "$H" -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\"]}"   # total 551.70
USD=$(curl -s -X POST $B/transactions -H "$H" -d '{"type":"navan_fee","external_id":"fee-usd-1","currency":"USD","occurred_at":"2026-03-12T10:00:00Z","total":10,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"trip_fee"}}' | getid)
curl -s -i -X PATCH $B/invoices/$INV -H "$H" -d "{\"transaction_ids\":[\"$FLIGHT\",\"$USD\"]}"  # 409, invoice unchanged

# --- delete an invoice (transactions survive)
curl -s -i -X DELETE $B/invoices/$INV            # 204
curl -s -o /dev/null -w '%{http_code}\n' $B/transactions/$FLIGHT   # 200
