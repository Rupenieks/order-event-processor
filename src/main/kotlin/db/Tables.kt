package db

import com.fasterxml.jackson.databind.JsonNode
import domain.OrderStatus
import domain.RejectKind
import input.jsonMapper
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

enum class LineStatus { OK, MALFORMED, DUPLICATE }

enum class EventState { PENDING, APPLIED, REJECTED }

/** Every non-blank line of every ingested file; the resume point for stage 1. */
object IngestedLines : Table("ingested_lines") {
    val fileHash = text("file_hash")
    val lineNo = long("line_no")
    val raw = text("raw")
    val status = enumerationByName<LineStatus>("status", 16)
    val reason = text("reason").nullable()
    val eventId = text("event_id").nullable()

    override val primaryKey = PrimaryKey(fileHash, lineNo)
}

/** One row per distinct eventId; stage 2 moves PENDING rows to APPLIED or REJECTED. */
object Events : Table("events") {
    val eventId = text("event_id")
    val orderId = text("order_id")
    val sequence = long("sequence")
    val type = text("type")
    val payload = jsonb<JsonNode>("payload", { jsonMapper.writeValueAsString(it) }, { jsonMapper.readTree(it) })
    val lineNo = long("line_no")
    val state = enumerationByName<EventState>("state", 16)
    val rejectKind = enumerationByName<RejectKind>("reject_kind", 32).nullable()
    val reason = text("reason").nullable()

    override val primaryKey = PrimaryKey(eventId)

    init {
        // One real event per (order, sequence); sequence-conflict losers are kept alongside it.
        uniqueIndex("events_order_sequence", orderId, sequence) {
            rejectKind.isNull() or (rejectKind neq RejectKind.SEQUENCE_CONFLICT)
        }
    }
}

object Orders : Table("orders") {
    val orderId = text("order_id")
    val status = enumerationByName<OrderStatus>("status", 16)
    val totalCents = long("total_cents")
    val lastSequence = long("last_sequence")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(orderId)
}

/** Statuses committed in Postgres but not yet written to Redis. */
object RedisOutbox : Table("redis_outbox") {
    val orderId = text("order_id")
    val status = enumerationByName<OrderStatus>("status", 16)

    override val primaryKey = PrimaryKey(orderId)
}

val allTables = arrayOf(IngestedLines, Events, Orders, RedisOutbox)
