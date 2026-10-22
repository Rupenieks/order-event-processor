# Order Event Processor

Kotlin CLI that processes `data/events.jsonl` into PostgreSQL (order state) and Redis (order status).
It can be killed with `kill -9` at any point and restarted with the same end result.

## How to run

Requirements: JDK 21 and Docker.

```bash
docker compose up -d                  # PostgreSQL on 5432, Redis on 6379
./gradlew run                         # processes data/events.jsonl
./gradlew run --args="other.jsonl"    # or another file
./gradlew test                        # unit tests, no database needed
scripts/kill-test.sh                  # crash test (wipes the dev tables and Redis keys)
```

Running it again is safe: nothing is processed twice. Output on the sample file:

```
Summary (all ingested data):
  Lines read:             1780 (malformed 5)
  Skipped as duplicates:  84
  Applied:                1643
  Rejected:               33 (AMOUNT_MISMATCH 11, INVALID_PAYLOAD 1, INVALID_TRANSITION 20, SEQUENCE_CONFLICT 1)
  Still waiting:          15
  Orders:                 392 (CANCELLED 38, CREATED 42, PAID 37, SHIPPED 275)
```

Configuration (env vars, defaults match `docker-compose.yml`): `PG_URL`, `PG_USER`, `PG_PASSWORD`,
`REDIS_HOST`, `REDIS_PORT`, `BATCH_SIZE` (500: lines per stage 1 transaction, orders per stage 2 transaction).

Exit codes: 1 = file not found, 2 = PostgreSQL unreachable, 3 = Redis unreachable.

## How it works

1. **Startup.** Connect to PostgreSQL and Redis, create the tables if missing, and publish any statuses a
   crashed run left in `redis_outbox`.
2. **Stage 1, ingest.** Read the file into `ingested_lines` (every line, with its outcome) and `events`
   (one row per distinct event, `PENDING`). One transaction per batch. The file is identified by its
   SHA-256 hash; a rerun resumes after the last stored line of that file.
3. **Stage 2, apply.** For each order with pending events, process them strictly in sequence order,
   starting after the last processed sequence, and stop at the first gap. Each chunk of orders is one
   transaction that updates `events`, upserts `orders` and upserts `redis_outbox` together.
4. **Redis.** After each stage 2 commit, the outbox rows are written to Redis (`order:{orderId}:status`)
   and then deleted. Redis can lag behind PostgreSQL after a crash, but is never ahead of it.
5. **Summary.** Counted from the tables, so it is correct after any restart.

Why this survives `kill -9`: every write is part of a transaction that commits all of its rows or
none, and every step can be repeated safely. A crash rolls back the open transaction and the next run
continues from what was committed.

## Tables

| Table | Key | Contents |
|---|---|---|
| `ingested_lines` | `(file_hash, line_no)` | raw line, status `OK` / `MALFORMED` / `DUPLICATE`, reason, event_id |
| `events` | `event_id` | order_id, sequence, type, payload (JSONB), line_no, state `PENDING` / `APPLIED` / `REJECTED`, reject_kind, reason |
| `orders` | `order_id` | status, total_cents, last_sequence, updated_at |
| `redis_outbox` | `order_id` | status not yet written to Redis |

- `events` has a partial unique index on `(order_id, sequence)` that skips sequence-conflict losers, so
  the database itself guarantees one real event per sequence.
- `ingested_lines` has no serial id on purpose: serial values are used up even when a transaction rolls
  back, so a killed run would end up with different ids than a clean one.
- Rejected events are kept in `events` with `reject_kind` and `reason`; malformed lines are kept in
  `ingested_lines` with `reason`.

## Rules

Where the task leaves a choice open, these rules apply.

**Lines**
- Blank lines are ignored.
- A line is `MALFORMED` (with a reason, and no event row) if it is invalid JSON, not an object, or has a
  missing or invalid `eventId`, `orderId`, `type` (non-empty strings) or `sequence` (integer ≥ 1).
  Duplicate keys or anything after the JSON object also make it malformed. Broken lines are never
  partially recovered.
- A malformed line fills no sequence, so if it was in the middle of an order, the later events wait.
- Same `eventId` again → `DUPLICATE`, even if the content differs.
- Two different events with the same `(orderId, sequence)` → the first one ingested wins; the other is
  rejected as `SEQUENCE_CONFLICT` and does not use up the sequence. This choice is arbitrary (the file
  order is not reliable) but deterministic. It ignores validity: if the winner is later rejected, the loser
  is still not used. A correction needs a new, higher sequence.

**Ordering**
- Events are applied strictly by `sequence`, starting at 1 per order. `occurredAt` is ignored.
- An event whose previous sequence is missing waits as `PENDING` and is applied once the gap is filled,
  also in a later run.
- A rejected event still counts: the order continues with the next sequence.

**State machine**

| Event | Allowed when | Result |
|---|---|---|
| `OrderCreated` | sequence is 1 (order does not exist) | `CREATED`, total = sum of items |
| `ItemAdded` | `CREATED` | `CREATED`, total + item |
| `OrderPaid` | `CREATED` and amount = total | `PAID` |
| `OrderShipped` | `PAID` | `SHIPPED` |
| `OrderCancelled` | `CREATED` or `PAID` | `CANCELLED` (total kept) |

- Anything else is rejected. Checks run in this order: `UNKNOWN_TYPE` (exact, case-sensitive type names,
  so `order_paid` is unknown), `INVALID_PAYLOAD`, `INVALID_TRANSITION`, `AMOUNT_MISMATCH`.
- An early event never gets rejected for arriving early: it waits until it is next in sequence. An
  `OrderCreated` with sequence > 1 is rejected only once its turn comes.
- If sequence 1 is rejected or is not an `OrderCreated`, the order never exists: it gets no `orders` row,
  and its later events are rejected as `INVALID_TRANSITION`.
- `orders.status` and `total_cents` change only through applied events. `last_sequence` is the last
  *processed* sequence, rejected events included.

**Payloads** (extra fields are ignored; no auto-correction)
- `OrderCreated`: `customerId`, `items` (non-empty list of `{sku, qty, unitPriceCents}`).
- `ItemAdded`: one flat item `{sku, qty, unitPriceCents}`.
- `OrderPaid`: `amountCents`, `currency` (must be exactly `EUR`).
- `OrderShipped`: `trackingId`.
- `OrderCancelled`: `reason` is optional, but must be a string if present.
- Money and quantities must be JSON integers (`5`, not `5.0` or `"5"`); `qty` ≥ 1, prices and amounts ≥ 0.
  Required strings must not be empty or whitespace. The payload must be a JSON object; a missing payload
  is `INVALID_PAYLOAD` (it uses up its sequence, unlike a malformed line).
- Money is in cents (64-bit); a total that would overflow is `INVALID_PAYLOAD`.

**Other**
- The summary counts everything in the tables, across all files ever ingested.
- Only one instance runs at a time; concurrent runs are not supported.

## Tests

- `./gradlew test`: unit tests for the line parser, payload validation, every state machine transition,
  stage 1 duplicate/conflict handling, and the ordering logic (events in reverse order, gaps, rejected
  events, early `OrderCreated`). Removing the ordering logic turns three of them red.
- `scripts/kill-test.sh`: makes an uninterrupted reference run, then wipes everything and kills runs at
  random moments until one completes. After each kill it checks that Redis is not ahead of PostgreSQL;
  at the end it checks that all tables (except `updated_at`) and Redis are identical to the reference, and
  again after one more run.

## Code layout

`src/main/kotlin/`: `Main.kt` (startup, wiring) · `Summary.kt` · `input/` (line parser) · `domain/`
(state machine, payloads, ordering) · `ingest/` (stage 1) · `apply/` (stage 2) · `db/` (tables) ·
`outbox/` (Redis publisher).
