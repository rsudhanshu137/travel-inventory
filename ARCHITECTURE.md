# Architecture

Spring Boot 3 / Java 17, plain JDBC (`JdbcTemplate`) on a **file-backed H2** database (`./data/travel.mv.db`;
never `jdbc:h2:mem:`). The SQL is deliberately portable — pointing `TRAVEL_DB_URL` at Postgres needs only a driver.

## Seams

| Layer | Classes | Owns |
|---|---|---|
| HTTP | `TransactionController`, `InvoiceController`, `ApiExceptionHandler`, `JsonBodies` | parsing, status codes, one JSON error shape |
| Validation | `TransactionValidator`, `MetadataValidator`, `Fields` | per-type shape rules, money/date normalisation (no DB) |
| Duplicate identity | `DuplicateIdentity` + `UNIQUE(external_id)` | "which id owns this external_id?" |
| Transaction persistence | `TransactionRepository`, `MetadataMapper` | all SQL for transactions + child tables, row locks |
| Invoice snapshot / rollup | `InvoiceService`, `InvoiceRepository`, `RollupCalculator` | freezing, mixed-currency rule, rollups (pure) |
| Services | `TransactionService`, `InvoiceService` | orchestration + DB transaction boundaries (`TransactionTemplate`) |

## `external_id` uniqueness and concurrent POSTs

* The guarantee is the database constraint `uq_transactions_external_id UNIQUE (external_id)`, not application code.
* `create` = cheap shape check → **index lookup** (fast path → `409`) → deep validation → one DB transaction inserting
  header + tax lines + fee lines + type detail + coupons/legs (**atomic**: all or nothing).
* Two overlapping POSTs both pass the fast path; both `INSERT`. The unique index makes the second one wait for the first
  to commit, then fail. The service catches that failure, re-reads the winner by `external_id` and answers **409** with
  the **existing id** (`id` and `existing_id`). The loser's transaction rolled back, so no orphan child rows exist.
* Choice: **409 Conflict**, not 200. The duplicate check runs *before* deep validation on purpose: the brief's
  `duplicate_flight` fixture has `coupons: []`, which would be a 400 on its own — a replay is a duplicate first.
* Test `t06` fires 12 simultaneous POSTs and asserts one 201, eleven 409s with the same existing id, one row, one set of children.

## Money

`BigDecimal` end to end; stored as `NUMERIC(19,4)`. Every amount is normalised to the currency's minor-unit scale
(`java.util.Currency`: EUR/USD 2, JPY 0, KWD 3). Input with more decimals than the currency allows is a **400** instead of
being silently rounded. JSON numbers are converted via their decimal text (`331.7`, not binary `331.69999…`). Amounts
and tax rates are rendered at their scale (`"685.70"`). Amounts must be ≥ 0. Rates are 0–1 with ≤ 6 decimals.
`total` is the **gross** amount as sent (e.g. flight 220 = fare 180 + tax 15 + YQ 25); it is not recomputed or cross-checked,
and tax/fee rollups are a breakdown *inside* the totals, never added on top.

## Tables and models

Single-table inheritance for the header + typed columns, and real child tables for repeating groups:

```
transactions            id, seq, type, external_id UNIQUE, currency, occurred_at, total, created/updated_at
                        flight_origin, flight_destination, flight_fare                      (type = flight)
                        hotel_name, hotel_street/city/country, hotel_check_in/out,
                          hotel_nights, hotel_room_rate, hotel_extras_json (+address extras) (type = hotel)
                        fee_kind, fee_description, fee_related_external_id                  (type = navan_fee)
                        extra_json   -- additional client keys we do not model
transaction_tax_lines   (transaction_id, position) name, rate NULL, amount     } separate lists,
transaction_fee_lines   (transaction_id, position) name, amount                } never one "extras" column
flight_coupons          (transaction_id, position) carrier, flight_number, from_airport, to_airport, departure, arrival
rail_legs               (transaction_id, position) origin, destination, departure, arrival, train_number, travel_class
```

`CHECK` constraints back the type rules in the DB itself (a `flight` row cannot exist without origin/destination/fare, a
`hotel` row without name + address, a `navan_fee` row must have `fee_kind IN ('trip_fee','agent_call_fee')`).
Why STI + child tables: flights, hotels and fees have few type-specific scalars (cheap as nullable columns, queryable,
indexable), while coupons and legs are genuinely 1-to-N and must keep order. `GET` rebuilds the `metadata` object from these
columns, so nothing is an opaque blob that is never queried. Only "extra fields" and hotel extras use small JSON text columns.
`navan_fee` rows are first-class transactions (`type = navan_fee`) with their own `fee_kind`, not a note on the flight.

## Invoice snapshot

```
invoices                    id, seq, currency, total, created/updated_at
invoice_lines               line_id, invoice_id → invoices ON DELETE CASCADE,
                            transaction_id → transactions ON DELETE RESTRICT,
                            position, type, external_id, currency, occurred_at, total, metadata_json   (COPY)
                            UNIQUE (invoice_id, transaction_id)      INDEX (transaction_id)
invoice_line_tax_lines      (line_id, position) name, rate, amount    (COPY, cascades with the line)
invoice_line_fee_lines      (line_id, position) name, amount          (COPY)
```

* Creating an invoice **copies** type, totals, tax lines, fee lines and the full metadata JSON into `invoice_*` tables.
  `GET /invoices/{id}` reads only those tables — there is no join back to `transactions`, so it cannot drift.
* Rollups are computed from the snapshot at read time: tax grouped by `(name, normalised rate)` (0.07 ≡ 0.070, "no rate" is
  its own group), fees grouped by `name`. A `navan_fee` *transaction* is an invoice line, not a `fee_line`, so it is
  intentionally absent from the fee rollup. `totals` additionally reports each line's contribution
  (`flight`, `hotel`, `rail`, `trip_fee`, `agent_call_fee`, `grand_total`).
* Same transaction twice in one request → **409** `duplicate_transaction_id` (also enforced by `UNIQUE(invoice_id, transaction_id)`).
  Mixed currencies → **409** `currency_mismatch`. Missing id → **404** (checked before the currency rule).
* A transaction may appear on several invoices, so `invoice_lines.transaction_id` has a plain index, not a unique one.

### Frozen means frozen

* `PATCH`/`DELETE /transactions/{id}` → **409** `transaction_invoiced` if any invoice line references it (nothing persisted).
  `ON DELETE RESTRICT` is the backstop if app code ever forgot.
* `PATCH /invoices/{id}` replaces the set of ids and **rebuilds** the snapshot from the *current* stored rows in one DB transaction.
  Any 409/404 rolls the whole thing back and the invoice is unchanged.
* `DELETE /invoices/{id}` cascades to snapshot rows only; transactions are untouched (and become editable again).
* Because the API blocks editing billed rows, the snapshot test (`t04`) mutates the stored row **directly in SQL** to prove
  the invoice is not a live join.

### Locking (PATCH / DELETE / invoicing)

Every mutation takes a row lock first: `SELECT … FOR UPDATE` on the transaction row (PATCH, DELETE) or on **all**
referenced transaction rows in `id` order (invoice create / rebuild — a consistent order avoids deadlocks), and on the
invoice row for invoice PATCH/DELETE. So "is it invoiced?" and "add it to an invoice" cannot interleave, and two overlapping
PATCHes serialise: last write wins, but each write replaces header + tax + fee + coupon/leg rows inside one transaction, so a
reader can never see a torn list (`t06b` asserts this).

## High volume

What breaks first, in order, and what is done about it:

1. **Duplicate-POST storms** (retries, double-clicks, many clients): without a constraint, check-then-insert races create twins.
   → `UNIQUE(external_id)` is the arbiter; the fast-path lookup is an index hit and removes most inserts from contention.
2. **Lock contention on hot rows** (PATCH vs invoicing the same booking): row locks are per transaction row, held only for one
   short DB transaction, taken in a consistent order; H2 `LOCK_TIMEOUT` is set and a lock timeout is returned as a retryable 409 `busy`.
3. **Unbounded lists**: `limit` default 50, **max 100** (`400` above — rejected, not silently capped), `offset ≥ 0`.
   Always `LIMIT/OFFSET` in SQL, ordered by the unique `seq` column (stable, index-backed). The page fetches `limit+1` rows to
   say whether `next_offset` exists, so there is **no `COUNT(*)` scan**. List rows are summaries — no child-table loads, no N+1.
   Very deep `OFFSET` still degrades linearly; the next step would be keyset pagination on `seq` (`WHERE seq < ?`).
4. **Lookups**: `GET /transactions/{id}` → PK; duplicate check → unique `external_id` index; "is it invoiced?" →
   `ix_invoice_lines_transaction_id`; `GET /invoices/{id}` → PK + `(invoice_id, transaction_id)` unique index + PK of child
   tables `(line_id, position)`; transaction children → PK `(transaction_id, position)`.
5. **Write amplification**: children are written with JDBC batches; one DB transaction per request; no read-modify-write in the app.
6. **Single-node file DB**: H2 is the zero-setup choice the brief allows. For real volume use Postgres (same SQL; swap
   `TRAVEL_DB_URL`, add the driver) — the constraints, indexes and row locks above carry over unchanged.

## Decisions / limits worth knowing

* Bodies are parsed from raw text so malformed JSON is a clean 400 and ordering of checks (duplicate before deep validation) is explicit.
* `PATCH /transactions`: `type`, `external_id`, `currency` are immutable (400 if changed); omitted fields keep their value;
  supplied `tax_lines` / `fee_lines` / `metadata` replace the stored ones whole and are re-validated for the transaction's type.
* Unknown top-level `metadata` keys are preserved (`extra_json`); unknown keys *inside* individual coupons / legs are not.
* Timestamps must carry an offset (`…Z`); they are stored and returned as UTC. Hotel `nights` is not cross-checked against the dates.
* No LLM is called anywhere; no secrets exist in the repo (the only env vars are optional DB overrides).
