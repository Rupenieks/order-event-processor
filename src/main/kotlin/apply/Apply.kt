package apply

import db.EventState
import db.Events
import db.Orders
import db.RedisOutbox
import domain.Event
import domain.OrderProgress
import domain.OrderState
import domain.Outcome
import domain.RejectKind
import domain.processOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

data class ApplyResult(val applied: Int, val rejected: Int)

/**
 * Stage 2: PENDING events → orders + redis_outbox. Each chunk of orders is one transaction:
 * event states, order rows and outbox rows commit together or not at all.
 */
fun applyPending(db: Database, chunkSize: Int): ApplyResult {
    val orderIds = transaction(db) {
        Events.select(Events.orderId)
            .where { Events.state eq EventState.PENDING }
            .withDistinct()
            .orderBy(Events.orderId)
            .map { it[Events.orderId] }
    }
    var applied = 0
    var rejected = 0
    for (chunk in orderIds.chunked(chunkSize)) {
        val outcomes = transaction(db) { applyChunk(chunk) }.flatMap { it.processed }.map { it.outcome }
        applied += outcomes.count { it is Outcome.Applied }
        rejected += outcomes.count { it is Outcome.Rejected }
    }
    return ApplyResult(applied, rejected)
}

private fun applyChunk(orderIds: List<String>): List<OrderProgress> {
    val pending = loadPending(orderIds)
    val lastProcessed = loadLastProcessed(orderIds)
    val states = loadStates(orderIds)
    return orderIds.map { orderId ->
        processOrder(states[orderId], lastProcessed[orderId] ?: 0L, pending[orderId].orEmpty())
            .also { save(orderId, it) }
    }
}

private fun loadPending(orderIds: List<String>): Map<String, List<Event>> =
    Events.selectAll()
        .where { (Events.orderId inList orderIds) and (Events.state eq EventState.PENDING) }
        .map { Event(it[Events.eventId], it[Events.orderId], it[Events.sequence], it[Events.type], it[Events.payload]) }
        .groupBy { it.orderId }

/** Highest processed sequence per order; sequence-conflict losers never count. */
private fun loadLastProcessed(orderIds: List<String>): Map<String, Long> {
    val maxSequence = Events.sequence.max()
    return Events.select(Events.orderId, maxSequence)
        .where {
            (Events.orderId inList orderIds) and
                (Events.state inList listOf(EventState.APPLIED, EventState.REJECTED)) and
                (Events.rejectKind.isNull() or (Events.rejectKind neq RejectKind.SEQUENCE_CONFLICT))
        }
        .groupBy(Events.orderId)
        .associate { it[Events.orderId] to it[maxSequence]!! }
}

private fun loadStates(orderIds: List<String>): Map<String, OrderState> =
    Orders.selectAll()
        .where { Orders.orderId inList orderIds }
        .associate { it[Orders.orderId] to OrderState(it[Orders.status], it[Orders.totalCents]) }

private fun save(orderId: String, progress: OrderProgress) {
    if (progress.processed.isEmpty()) return

    for ((event, outcome) in progress.processed) {
        Events.update({ Events.eventId eq event.eventId }) {
            when (outcome) {
                is Outcome.Applied -> it[Events.state] = EventState.APPLIED
                is Outcome.Rejected -> {
                    it[Events.state] = EventState.REJECTED
                    it[Events.rejectKind] = outcome.kind
                    it[Events.reason] = outcome.reason
                }
            }
        }
    }

    // An order whose OrderCreated never applied has no row and nothing to show in Redis
    val state = progress.state ?: return
    Orders.upsert {
        it[Orders.orderId] = orderId
        it[Orders.status] = state.status
        it[Orders.totalCents] = state.totalCents
        it[Orders.lastSequence] = progress.lastProcessed
        it[Orders.updatedAt] = CurrentTimestampWithTimeZone
    }
    RedisOutbox.upsert {
        it[RedisOutbox.orderId] = orderId
        it[RedisOutbox.status] = state.status
    }
}
