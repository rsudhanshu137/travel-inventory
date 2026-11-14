# Travel inventory — flight / hotel / rail / Navan-fee transactions and frozen invoices

Java 17 + Spring Boot 3.3, plain JDBC, file-backed H2 (`./data/travel.mv.db`). Design notes: [ARCHITECTURE.md](ARCHITECTURE.md).

## Run

Needs JDK 17+ and Maven 3.9+ (or run `mvn wrapper:wrapper` once to generate `./mvnw`).

```bash
mvn spring-boot:run          # API on http://localhost:8080  (PORT=9090 to change)
```

Optional environment overrides (nothing secret is committed; there is no LLM or vendor call anywhere):
`TRAVEL_DB_URL` (default `jdbc:h2:file:./data/travel;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE`), `TRAVEL_DB_USER`, `TRAVEL_DB_PASSWORD`, `PORT`.

## Test

```bash
mvn test
```

`TravelApiTests` boots the app on a random port against a **file** H2 database in a temp directory and drives it over real HTTP
(`java.net.http`, so PATCH works). Test names `t01`…`t10` map to the ten required cases in the brief; extras cover PATCH/DELETE
happy paths, invoice PATCH, validation errors and a concurrent-PATCH tear test. `ValidationAndRollupTests` are DB-free unit tests.

| # | Required case | Test |
|---|---|---|
| 1 | create flight/hotel/rail/trip_fee/agent_call_fee, GET returns structure | `t01_…` |
| 2 | duplicate `external_id` → one row, existing id | `t02_…` |
| 3 | trip invoice totals match `expected-invoice.json` (685.70 EUR) | `t03_…` |
| 4 | snapshot stays 220.00 after the stored flight changes | `t04_…` |
| 5 | EUR + USD invoice → 409 | `t05_…` |
| 6 | concurrent duplicate create → one row | `t06_…` (12 racing threads), `t06b_…` |
| 7 | PATCH invoiced transaction → 409, invoice unchanged | `t07_…` |
| 8 | DELETE invoiced transaction → 409, row still there | `t08_…` |
| 9 | DELETE invoice → transactions remain | `t09_…` |
| 10 | list paginated, default 50, `limit=1000` → 400 | `t10_…` |

## API

| Method & path | Notes |
|---|---|
| `POST /transactions` | **201** with stored record; **409** `duplicate_external_id` (body has `id` / `existing_id`); **400** invalid |
| `GET /transactions/{id}` | **404** if missing |
| `GET /transactions?limit=&offset=&external_id=` | default `limit=50`, **max 100 (400 above)**, `offset≥0`; `next_offset` absent on last page |
| `PATCH /transactions/{id}` | `total`, `occurred_at`, `tax_lines`, `fee_lines`, `metadata`; **409** `transaction_invoiced` if billed |
| `DELETE /transactions/{id}` | **204**; **409** if billed; **404** if missing |
| `POST /invoices` | `{"transaction_id": …}` or `{"transaction_ids": […]}`; **404** missing id, **409** mixed currency / same id twice |
| `GET /invoices/{id}`, `GET /invoices` | snapshot lines, `tax_rollup`, `fee_rollup`, `totals`; list is paginated like transactions |
| `PATCH /invoices/{id}` | replace the id set, rebuild the snapshot atomically |
| `DELETE /invoices/{id}` | removes invoice + snapshot only |
| `GET /health` | `{"status":"UP"}` |

Errors are always `{"error": "<code>", "message": "…"}`. Money is rendered at currency scale (e.g. `685.70`).

## Example curls

Payloads are in [`examples/`](examples) (the brief's seed fixtures, one file each). Run from the project root with the app running.

**Easiest:** `./scripts/readme-curls.sh` runs every command below (needs bash; works on macOS). If you paste the block into zsh instead, paste a few lines at a time.

```bash
setopt interactive_comments 2>/dev/null || true
B=http://localhost:8080
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
```

## Database, indexes and volume

Defined in `src/main/resources/schema.sql` (not just comments):

| Need | Index / constraint |
|---|---|
| no duplicate `external_id`, even under concurrency | `uq_transactions_external_id` — `UNIQUE (external_id)` on `transactions` |
| `GET /transactions/{id}` | primary key `transactions(id)` |
| `GET /invoices/{id}` | primary key `invoices(id)`; lines via `UNIQUE (invoice_id, transaction_id)` |
| "is this transaction on an invoice?" | `ix_invoice_lines_transaction_id` on `invoice_lines(transaction_id)` (+ `ON DELETE RESTRICT` FK) |
| stable, cheap list paging | `ux_transactions_seq`, `ux_invoices_seq` (`ORDER BY seq DESC LIMIT ? OFFSET ?`) |
| child rows (tax, fee, coupons, legs) | primary key `(transaction_id, position)` on each child table |

* Every list query is `LIMIT/OFFSET` in SQL (default 50, max 100). Nothing is paginated in memory and there is no `COUNT(*)`.
* Money columns are `NUMERIC(19,4)`, never floating point.
* What breaks first under load, and the fixes, are in [ARCHITECTURE.md](ARCHITECTURE.md#high-volume).

## Notes

* **Duplicate policy:** `409 Conflict` + the existing id. Same transaction id twice on one invoice: `409` (not silently deduped).
* **Pagination:** `limit` over 100 is rejected with `400`, not capped.
* **Layout:** `web/` → `service/` → `persistence/` + `validation/` + `model/`; schema in `src/main/resources/schema.sql`.
