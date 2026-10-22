import db.EventState
import db.Events
import db.IngestedLines
import db.LineStatus
import db.Orders
import domain.OrderStatus
import domain.RejectKind
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Counts over everything in the tables (all ingested files), so they are correct after any restart. */
data class Summary(
    val lines: Map<LineStatus, Long>,
    val events: Map<EventState, Long>,
    val rejected: Map<RejectKind, Long>,
    val orders: Map<OrderStatus, Long>,
)

fun loadSummary(db: Database): Summary = transaction(db) {
    val lineCount = IngestedLines.status.count()
    val lines = IngestedLines.select(IngestedLines.status, lineCount)
        .groupBy(IngestedLines.status)
        .associate { it[IngestedLines.status] to it[lineCount] }

    val eventCount = Events.eventId.count()
    val eventRows = Events.select(Events.state, Events.rejectKind, eventCount)
        .groupBy(Events.state, Events.rejectKind)
        .map { Triple(it[Events.state], it[Events.rejectKind], it[eventCount]) }

    val orderCount = Orders.orderId.count()
    val orders = Orders.select(Orders.status, orderCount)
        .groupBy(Orders.status)
        .associate { it[Orders.status] to it[orderCount] }

    Summary(
        lines = lines,
        events = eventRows.groupBy({ it.first }, { it.third }).mapValues { it.value.sum() },
        rejected = eventRows.mapNotNull { (_, kind, n) -> kind?.let { it to n } }.toMap(),
        orders = orders,
    )
}

fun Summary.print() {
    fun breakdown(counts: Map<out Enum<*>, Long>) =
        counts.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key} ${it.value}" }

    println("Summary (all ingested data):")
    println("  Lines read:             ${lines.values.sum()} (malformed ${lines[LineStatus.MALFORMED] ?: 0})")
    println("  Skipped as duplicates:  ${lines[LineStatus.DUPLICATE] ?: 0}")
    println("  Applied:                ${events[EventState.APPLIED] ?: 0}")
    println("  Rejected:               ${events[EventState.REJECTED] ?: 0} (${breakdown(rejected)})")
    println("  Still waiting:          ${events[EventState.PENDING] ?: 0}")
    println("  Orders:                 ${orders.values.sum()} (${breakdown(orders)})")
}
