# Order Event Processor — build spec

Kotlin CLI that ingests a JSONL file of order events into PostgreSQL in two stages, keeps order state in
Postgres, mirrors order status to Redis, and is safe to kill -9 and rerun at any point.
Read the task PDF and events.jsonl first. If field names or rules there differ from this spec, the task wins;
note any difference in the README.

## Stack
- JDK 21, Kotlin 2.3.21, Gradle 9.3 (wrapper), application plugin
- Exposed 1.6.0 (exposed-core, exposed-jdbc, exposed-json; DSL only, no DAO; packages org.jetbrains.exposed.v1.*)
- PostgreSQL JDBC driver, Jedis (Redis), Jackson 2.x (JSON), JUnit 5. No kotlinx-serialization plugin.
- events.payload is JSONB via exposed-json's `jsonb(name, serialize, deserialize)` with Jackson
  (JsonNode ↔ String). JSONB normalizes key order and whitespace; the exact original line stays in
  ingested_lines.raw.
- docker-compose.yml: postgres:16, redis:7
- Config via env vars with defaults: PG_URL, PG_USER, PG_PASSWORD, REDIS_HOST, REDIS_PORT
- Run: `order-event-processor <file.jsonl>`

## Startup
1. Connect to Postgres (run `SELECT 1`) and Redis (`PING`). On failure exit with a clear message:
   code 2 = Postgres, 3 = Redis.
2. Create tables if missing.
3. Flush redis_outbox (see Redis).

## Tables
- ingested_lines: file_hash TEXT, line_no BIGINT, raw TEXT, status TEXT (OK | MALFORMED | DUPLICATE),
  reason TEXT NULL, event_id TEXT NULL; PRIMARY KEY (file_hash, line_no). No surrogate id: a serial id
  burns values on rollback, so a killed-and-resumed run would differ from a clean one.
- events: event_id TEXT PK, order_id TEXT, sequence INT, type TEXT, payload JSONB, line_no BIGINT,
  state TEXT (PENDING | APPLIED | REJECTED), reject_kind TEXT NULL, reason TEXT NULL;
  partial UNIQUE (order_id, sequence) WHERE reject_kind IS DISTINCT FROM 'SEQUENCE_CONFLICT'
  (the database enforces one real event per sequence; also serves the stage 2 lookups)
- orders: order_id PK, status TEXT, total_cents BIGINT, last_sequence INT, updated_at TIMESTAMPTZ.
  status and total_cents come from applied events only (a rejected event never changes them).
  last_sequence = last processed sequence, rejected events included: after a rejected seq 2,
  the row reads CREATED / last_sequence 2, and seq 3 is next.
- redis_outbox: order_id PK, status TEXT

## Stage 1 — ingest (file → ingested_lines + events)
- file_hash = SHA-256 of the file content. Resume point = max(line_no) in ingested_lines for that hash;
  skip those lines.
- Read line by line; ignore blank lines. Batches of 500 lines, one transaction per batch.
- Per line:
    - Invalid JSON, not an object, or missing/invalid eventId (non-empty string), orderId (non-empty string),
      sequence (JSON integer ≥ 1), type (non-empty string) → ingested_lines MALFORMED + reason. Nothing in events.
      No attempt to salvage fields from broken lines. Consequence: a malformed line never fills a sequence,
      so if it was mid-order, the order's later events stay PENDING.
    - eventId already in events → ingested_lines DUPLICATE, event_id set. (Same eventId = duplicate, even if
      the content differs.)
    - Another event already holds (order_id, sequence) → ingested_lines OK, plus an events row with
      state REJECTED, reject_kind SEQUENCE_CONFLICT. The first one ingested wins. Does not use up a sequence.
      This rule is arbitrary (file order is not reliable), but deterministic; the README must say so.
      It ignores validity: if the winner is later rejected, the loser is still not used. A correction must
      come with a new, higher sequence.
    - Otherwise → ingested_lines OK + events row PENDING.
- Duplicate and conflict checks must also cover earlier lines in the same batch.

## Stage 2 — apply (events → orders + redis_outbox)
- Runs after stage 1, on every run (it also picks up PENDING events from earlier runs).
- Last processed sequence of an order = max(sequence) of its APPLIED/REJECTED events, excluding
  SEQUENCE_CONFLICT (0 if none). Load it for all orders in a chunk with one grouped query.
- For each order with PENDING events, in sequence order, starting at last processed + 1: apply the event
  whose sequence is exactly next, repeat; stop at the first gap. Events after a gap stay PENDING (= waiting).
- Each next event goes through a pure state machine `apply(state: OrderState?, event): Apply | Reject`.
    - APPLIED: new state.
    - REJECTED: reject_kind + reason; still advances the sequence.
- Process 500 orders per transaction. In the same transaction: update events, upsert orders
  (status, total_cents, last_sequence), upsert redis_outbox(order_id, status).
- An orders row exists only after a successful OrderCreated. If seq 1 is rejected (or is not an
  OrderCreated), the order never exists, so the state machine rejects every later event with
  INVALID_TRANSITION; each still advances the sequence.

## State machine
States: CREATED, PAID, SHIPPED, CANCELLED (+ "does not exist"). SHIPPED and CANCELLED are final.
| Event          | Allowed when                 | Result                        |
| OrderCreated   | sequence == 1 (order does not exist) | CREATED, total = sum of items |
| ItemAdded      | CREATED                      | CREATED, total += item        |
| OrderPaid      | CREATED and amount == total  | PAID                          |
| OrderShipped   | PAID                         | SHIPPED                       |
| OrderCancelled | CREATED or PAID              | CANCELLED                     |
The state machine only ever sees an event when it is next in sequence (stage 2), so arrival order never
causes a rejection: an event that arrives early, including an OrderCreated, waits as PENDING. An OrderCreated
with sequence > 1 is rejected (INVALID_TRANSITION) only once its turn comes.
reject_kind values: INVALID_TRANSITION (event not allowed in current state), AMOUNT_MISMATCH, UNKNOWN_TYPE,
INVALID_PAYLOAD.
Event types match exactly, case-sensitive; anything else (e.g. `order_paid`, `OrderRefunded`) → UNKNOWN_TYPE.
Payloads (extra fields are ignored):
| Event          | Payload                                                                   |
| OrderCreated   | customerId: string, items: non-empty list of { sku: string, qty, unitPriceCents } |
| ItemAdded      | flat, one item: sku: string, qty, unitPriceCents (no items list)          |
| OrderPaid      | amountCents, currency: exactly "EUR"                                      |
| OrderShipped   | trackingId: string                                                        |
| OrderCancelled | reason: string, optional                                                  |
Payload validation is strict, no auto-correction: money and quantities must be JSON integers (floats or
strings → INVALID_PAYLOAD); qty ≥ 1, prices/amounts ≥ 0; required strings non-empty; currency other than
"EUR" or missing → INVALID_PAYLOAD. Money is Long cents with overflow-checked arithmetic. Ignore occurredAt.
- Check order: UNKNOWN_TYPE → INVALID_PAYLOAD → INVALID_TRANSITION → AMOUNT_MISMATCH (a bad payload on a
  shipped order is INVALID_PAYLOAD).
- A total that overflows Long → INVALID_PAYLOAD.
- Whitespace-only strings count as empty.
- The payload must be a JSON object for every type (`{}` is fine for OrderCancelled); `reason`, if present,
  must be a string.
- The function is named `applyEvent` (Kotlin already has a built-in `apply`).
- The sequencer throws if given a pending event at or before last processed, or two pending events with the
  same sequence; either means a bug in stage 1 or 2.

## Redis
- Key `order:{orderId}:status` = status. Write only after the Postgres commit.
- After each stage-2 commit (and at startup): for each redis_outbox row, SET in Redis, then delete the row.
- Redis may be behind Postgres, never ahead.

## Output (queried from the database, so it's correct after restarts)
Counts cover everything in the tables, across all ingested files (no per-file filter). The tables are
the source of truth.
Lines read, malformed, duplicates, events applied, rejected (per reject_kind incl. SEQUENCE_CONFLICT),
waiting (PENDING), orders per status.

## Tests
Unit tests only, no database or Redis (`./gradlew test` needs no infrastructure). The "which event is next"
logic is a pure function next to the state machine: given the last processed sequence and an order's
pending events, it returns the events to process in order and the ones that keep waiting. Stage 2 calls it.
- Unit: state machine (every allowed and rejected transition, payload validation, amount mismatch).
- Ordering: one order's events fed in reverse → ends SHIPPED with the correct total. Must fail if the
  ordering logic is removed; verify this manually once.
- Gap: seq 1, 3 → 3 stays PENDING; feeding seq 2 later applies 2 and 3.
- Rejected event advances the sequence (bad OrderPaid at seq 2, valid one at seq 3 → PAID).
- Rejected OrderCreated: no orders row, later events rejected, sequence still advances.
- Early OrderCreated: OrderCreated at seq 2 arrives first and stays PENDING; when seq 1 arrives, seq 1 is
  processed, then seq 2 is rejected (INVALID_TRANSITION).
- scripts/kill-test.sh (manual, against the dev Postgres and Redis; covers crash safety and idempotency):
  reference run; wipe; rerun with repeated `kill -9` + restart until it completes; run once more on the
  finished state; after each, diff all tables against the reference (excluding orders.updated_at, which is
  wall-clock time) and check every Redis key matches Postgres.
- Only one instance runs at a time; concurrent runs are out of scope (no locking).

## Structure
Main.kt (CLI, wiring, summary) · input/ (JSONL line parser) · domain/ (state machine, types) ·
ingest/ (stage 1) · apply/ (stage 2) · db/ (Exposed tables, queries) · redis/ (outbox publisher).
README: how to run, the table design, every rule above stated explicitly, and the data notes below.

## Data notes (events.jsonl, expected outcomes)
- ord-1390 seq 3 conflict: OrderPaid (line 1155) vs OrderCancelled (line 1255, duplicated on 1256).
  OrderPaid is ingested first and wins → seq 4 OrderShipped applies → SHIPPED. Had the cancel come
  first, the ship would have been rejected.
- ord-1391 has no seq 1 (starts with OrderCreated at seq 2) → all its events stay PENDING.
- Line 1210 is broken JSON claiming ord-1012 seq 4; a valid seq 4 exists. MALFORMED, no conflict.
- Line 902 (no type) and line 114 (no orderId) → MALFORMED; neither blocks an order in this file.
- `order_paid` and `OrderRefunded` → UNKNOWN_TYPE.
- One ItemAdded with qty -2 (line 1441) → INVALID_PAYLOAD.