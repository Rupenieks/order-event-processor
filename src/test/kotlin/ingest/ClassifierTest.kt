package ingest

import db.EventState
import db.LineStatus
import domain.RejectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassifierTest {
    private fun line(lineNo: Long, eventId: String, orderId: String = "ord-1", sequence: Long = 1) =
        Line(lineNo, """{"eventId":"$eventId","orderId":"$orderId","sequence":$sequence,"type":"OrderPaid","payload":{}}""")

    private fun Classified.statuses() = lines.map { it.status }

    @Test
    fun `new events become PENDING`() {
        val result = classify(listOf(line(1, "a", sequence = 1), line(2, "b", sequence = 2)), emptySet(), emptyMap())

        assertEquals(listOf(LineStatus.OK, LineStatus.OK), result.statuses())
        assertEquals(listOf("a", "b"), result.events.map { it.event.eventId })
        assertEquals(listOf(EventState.PENDING, EventState.PENDING), result.events.map { it.state })
        assertEquals(listOf(1L, 2L), result.events.map { it.lineNo })
    }

    @Test
    fun `malformed lines get a reason and no event`() {
        val result = classify(listOf(Line(7, "not json")), emptySet(), emptyMap())

        assertEquals(listOf(LineStatus.MALFORMED), result.statuses())
        assertEquals(true, result.lines.single().reason?.startsWith("invalid JSON"))
        assertNull(result.lines.single().eventId)
        assertEquals(emptyList(), result.events)
    }

    @Test
    fun `duplicates are caught against the database and within the batch`() {
        val result = classify(
            listOf(line(1, "old", sequence = 1), line(2, "new", sequence = 2), line(3, "new", sequence = 2)),
            knownIds = setOf("old"),
            slotHolders = mapOf(("ord-1" to 1L) to "old"),
        )

        assertEquals(listOf(LineStatus.DUPLICATE, LineStatus.OK, LineStatus.DUPLICATE), result.statuses())
        assertEquals(listOf("old", "new", "new"), result.lines.map { it.eventId })
        assertEquals(listOf("new"), result.events.map { it.event.eventId })
    }

    @Test
    fun `duplicate wins over conflict when the eventId is known`() {
        val result = classify(listOf(line(1, "a", sequence = 1)), setOf("a"), mapOf(("ord-1" to 1L) to "a"))

        assertEquals(listOf(LineStatus.DUPLICATE), result.statuses())
        assertEquals(emptyList(), result.events)
    }

    @Test
    fun `a second event for a taken sequence is a conflict, and the first one keeps it`() {
        val result = classify(
            listOf(line(1, "first", sequence = 3), line(2, "second", sequence = 3), line(3, "second", sequence = 3)),
            emptySet(),
            emptyMap(),
        )

        assertEquals(listOf(LineStatus.OK, LineStatus.OK, LineStatus.DUPLICATE), result.statuses())
        val (winner, loser) = result.events
        assertEquals(EventState.PENDING to null, winner.state to winner.rejectKind)
        assertEquals(EventState.REJECTED to RejectKind.SEQUENCE_CONFLICT, loser.state to loser.rejectKind)
        assertEquals("sequence 3 of ord-1 is already held by event first", loser.reason)
    }

    @Test
    fun `a sequence held in the database is a conflict`() {
        val result = classify(listOf(line(5, "late", sequence = 3)), setOf("early"), mapOf(("ord-1" to 3L) to "early"))

        assertEquals(RejectKind.SEQUENCE_CONFLICT, result.events.single().rejectKind)
    }

    @Test
    fun `same sequence on different orders is not a conflict`() {
        val result = classify(
            listOf(line(1, "a", orderId = "ord-1", sequence = 1), line(2, "b", orderId = "ord-2", sequence = 1)),
            emptySet(),
            emptyMap(),
        )

        assertEquals(listOf(EventState.PENDING, EventState.PENDING), result.events.map { it.state })
    }
}
