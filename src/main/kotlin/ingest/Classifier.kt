package ingest

import db.EventState
import db.LineStatus
import domain.Event
import domain.RejectKind
import input.ParsedLine
import input.parseLine

data class Line(val lineNo: Long, val raw: String)

/** An (orderId, sequence) pair; at most one non-conflict event may hold it. */
typealias Slot = Pair<String, Long>

data class LineRow(val line: Line, val status: LineStatus, val reason: String?, val eventId: String?)

data class EventRow(
    val event: Event,
    val lineNo: Long,
    val state: EventState,
    val rejectKind: RejectKind?,
    val reason: String?,
)

data class Classified(val lines: List<LineRow>, val events: List<EventRow>)

val Event.slot: Slot get() = orderId to sequence

/**
 * Decides what each line of a batch becomes, in file order. [knownIds] and [slotHolders] describe what
 * is already in the database; lines earlier in the batch are added as we go, so duplicates and
 * conflicts within the batch are caught too.
 */
fun classify(batch: List<Line>, knownIds: Set<String>, slotHolders: Map<Slot, String>): Classified {
    val ids = knownIds.toMutableSet()
    val holders = slotHolders.toMutableMap()
    val lineRows = mutableListOf<LineRow>()
    val eventRows = mutableListOf<EventRow>()

    for (line in batch) {
        val parsed = parseLine(line.raw)
        if (parsed is ParsedLine.Malformed) {
            lineRows += LineRow(line, LineStatus.MALFORMED, parsed.reason, null)
            continue
        }
        val event = (parsed as ParsedLine.Valid).event
        val holder = holders[event.slot]
        when {
            // Same eventId = duplicate, even if the content differs
            event.eventId in ids ->
                lineRows += LineRow(line, LineStatus.DUPLICATE, "eventId already ingested", event.eventId)

            // First one ingested wins; the loser is kept but does not use up the sequence
            holder != null -> {
                lineRows += LineRow(line, LineStatus.OK, null, event.eventId)
                eventRows += EventRow(
                    event, line.lineNo, EventState.REJECTED, RejectKind.SEQUENCE_CONFLICT,
                    "sequence ${event.sequence} of ${event.orderId} is already held by event $holder",
                )
                ids += event.eventId
            }

            else -> {
                lineRows += LineRow(line, LineStatus.OK, null, event.eventId)
                eventRows += EventRow(event, line.lineNo, EventState.PENDING, null, null)
                ids += event.eventId
                holders[event.slot] = event.eventId
            }
        }
    }
    return Classified(lineRows, eventRows)
}
