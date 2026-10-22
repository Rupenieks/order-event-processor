#!/usr/bin/env bash
# Crash-safety check: kill -9 the processor at random moments until a run completes, then compare
# every table and every Redis key with an uninterrupted reference run. After each kill it also checks
# that Redis is never ahead of Postgres.
#
# WARNING: wipes the dev Postgres tables and the order:*:status keys in Redis.
#
# Usage: scripts/kill-test.sh [file.jsonl]    (env: BATCH_SIZE, default 10; MAX_KILLS, default 200)
set -euo pipefail
cd "$(dirname "$0")/.."

FILE=${1:-data/events.jsonl}
BATCH=${BATCH_SIZE:-10}
MAX_KILLS=${MAX_KILLS:-200}
BIN=build/install/order-event-processor/bin/order-event-processor
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export LC_ALL=C

psql() { docker compose exec -T postgres psql -U orders -d orders -qAt "$@"; }
redis() { docker compose exec -T redis redis-cli "$@"; }

status_keys() { redis --scan --pattern 'order:*:status' | sort; }

dump_tables() {
    psql -c "SELECT '-- ingested_lines'" -c "COPY (SELECT * FROM ingested_lines ORDER BY file_hash, line_no) TO STDOUT" \
         -c "SELECT '-- events'" -c "COPY (SELECT * FROM events ORDER BY event_id) TO STDOUT" \
         -c "SELECT '-- orders'" -c "COPY (SELECT order_id, status, total_cents, last_sequence FROM orders ORDER BY order_id) TO STDOUT" \
         -c "SELECT '-- redis_outbox'" -c "COPY (SELECT * FROM redis_outbox ORDER BY order_id) TO STDOUT"
    # orders.updated_at is wall-clock time, so it is left out
}

dump_redis() {
    local keys
    keys=$(status_keys)
    [ -z "$keys" ] && return 0
    # shellcheck disable=SC2086
    paste -d' ' <(echo "$keys") <(redis MGET $keys)
}

reset() {
    psql -c "TRUNCATE ingested_lines, events, orders, redis_outbox" 2>/dev/null || true
    local keys
    keys=$(status_keys)
    # shellcheck disable=SC2086
    [ -z "$keys" ] || redis DEL $keys > /dev/null
}

# Every Redis status must equal the committed one, unless a newer one is still queued in redis_outbox
check_never_ahead() {
    psql -F' ' -c "SELECT 'order:' || o.order_id || ':status', o.status, (b.order_id IS NOT NULL)
                   FROM orders o LEFT JOIN redis_outbox b USING (order_id)" > "$WORK/pg"
    dump_redis > "$WORK/redis"
    awk 'NR == FNR { status[$1] = $2; queued[$1] = $3; next }
         !($1 in status) { print "  " $1 "=" $2 ", but no committed order"; bad = 1; next }
         $2 != status[$1] && queued[$1] != "t" { print "  " $1 "=" $2 ", but Postgres has " status[$1]; bad = 1 }
         END { exit bad }' "$WORK/pg" "$WORK/redis"
}

echo "Building..."
./gradlew -q installDist

echo "Reference run (uninterrupted, default batch size)..."
reset
"$BIN" "$FILE" > /dev/null
dump_tables > "$WORK/ref_tables"
dump_redis > "$WORK/ref_redis"

echo "Killing runs with BATCH_SIZE=$BATCH at random moments..."
reset
kills=0
while true; do
    BATCH_SIZE=$BATCH "$BIN" "$FILE" > "$WORK/run.log" 2>&1 &
    pid=$!
    sleep "$(awk -v r=$RANDOM 'BEGIN { printf "%.2f", 0.5 + r / 32767 * 2.5 }')"
    kill -9 "$pid" 2> /dev/null || true
    status=0
    wait "$pid" 2> /dev/null || status=$?
    if [ "$status" -eq 0 ]; then
        echo "  run completed after $kills kills"
        break
    fi
    [ "$status" -eq 137 ] || { echo "FAIL: run exited with $status"; cat "$WORK/run.log"; exit 1; }
    kills=$((kills + 1))
    check_never_ahead || { echo "FAIL: Redis ahead of Postgres after kill $kills"; exit 1; }
    [ "$kills" -lt "$MAX_KILLS" ] || { echo "FAIL: no run completed within $MAX_KILLS kills"; exit 1; }
done

compare() {
    dump_tables > "$WORK/tables"
    dump_redis > "$WORK/redis_now"
    diff "$WORK/ref_tables" "$WORK/tables" > /dev/null || { echo "FAIL: tables differ $1"; diff "$WORK/ref_tables" "$WORK/tables" | head -20; exit 1; }
    diff "$WORK/ref_redis" "$WORK/redis_now" > /dev/null || { echo "FAIL: Redis differs $1"; diff "$WORK/ref_redis" "$WORK/redis_now" | head -20; exit 1; }
    psql -F' ' -c "SELECT 'order:' || order_id || ':status', status FROM orders" | sort > "$WORK/expected_redis"
    diff "$WORK/expected_redis" "$WORK/redis_now" > /dev/null || { echo "FAIL: Redis does not match Postgres $1"; exit 1; }
}

compare "after the killed runs"
echo "Running once more on the finished state..."
"$BIN" "$FILE" > /dev/null
compare "after rerunning"

echo "PASS: $kills kills; tables and Redis identical to the reference, also after a rerun; Redis never ahead."
