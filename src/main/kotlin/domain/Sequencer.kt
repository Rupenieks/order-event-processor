package domain

data class Sequenced(val ready: List<Event>, val waiting: List<Event>)

data class Processed(val event: Event, val outcome: Outcome)

data class OrderProgress(
    val state: OrderState?,
    val lastProcessed: Long,
    val processed: List<Processed>,
    val waiting: List<Event>,
)

/**
 * Splits one order's pending events into those that are next in line after [lastProcessed]
 * (consecutive sequences, in order) and those behind a gap, which keep waiting.
 */
fun nextInSequence(lastProcessed: Long, pending: List<Event>): Sequenced {
    require(pending.all { it.sequence > lastProcessed }) { "pending event at or before last processed $lastProcessed" }
    require(pending.distinctBy { it.sequence }.size == pending.size) { "two pending events share a sequence" }

    val bySequence = pending.associateBy { it.sequence }
    val ready = mutableListOf<Event>()
    var next = lastProcessed + 1
    while (true) {
        ready += bySequence[next] ?: break
        next++
    }
    val waiting = pending.filter { it.sequence >= next }.sortedBy { it.sequence }
    return Sequenced(ready, waiting)
}

/** Processes every event of one order that is next in line. Rejected events still advance the sequence. */
fun processOrder(state: OrderState?, lastProcessed: Long, pending: List<Event>): OrderProgress {
    val (ready, waiting) = nextInSequence(lastProcessed, pending)
    var current = state
    val processed = ready.map { event ->
        val outcome = applyEvent(current, event)
        if (outcome is Outcome.Applied) current = outcome.state
        Processed(event, outcome)
    }
    return OrderProgress(current, lastProcessed + ready.size, processed, waiting)
}
