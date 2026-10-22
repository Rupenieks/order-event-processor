package input

import com.fasterxml.jackson.databind.node.NullNode
import domain.Outcome
import domain.RejectKind
import domain.applyEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LineParserTest {
    private fun assertMalformed(raw: String, reasonContains: String) {
        val parsed = parseLine(raw)
        assertIs<ParsedLine.Malformed>(parsed, "expected malformed: $raw")
        assertTrue(reasonContains in parsed.reason, "reason '${parsed.reason}' should mention '$reasonContains'")
    }

    private fun envelope(sequence: String = "1", extra: String = ""","payload":{}""") =
        """{"eventId":"e1","orderId":"ord-1","sequence":$sequence,"type":"OrderPaid"$extra}"""

    @Test
    fun `parses a valid line`() {
        val parsed = parseLine(
            """{"eventId":"e7f3c1","orderId":"ord-1042","sequence":3,"type":"OrderPaid",""" +
                """"occurredAt":"2026-10-20T14:02:11Z","payload":{"amountCents":12990,"currency":"EUR"}}"""
        )

        assertIs<ParsedLine.Valid>(parsed)
        val event = parsed.event
        assertEquals(listOf("e7f3c1", "ord-1042", "OrderPaid"), listOf(event.eventId, event.orderId, event.type))
        assertEquals(3, event.sequence)
        assertEquals(12990, event.payload.get("amountCents").intValue())
    }

    @Test
    fun `broken JSON from the sample file is malformed`() {
        assertMalformed("this is not json at all", "invalid JSON")
        assertMalformed("""{"eventId":"bad00003","orderId":"ord-1012",sequence:4}""", "invalid JSON")
        assertMalformed("""{"eventId":"bad00001","orderId":"ord-1007","sequence":9,"type":"OrderPaid","payload"""", "invalid JSON")
    }

    @Test
    fun `stricter than Jackson's defaults`() {
        assertMalformed(envelope() + """ {"eventId":"e2"}""", "invalid JSON")
        assertMalformed("""{"eventId":"e1","eventId":"e2","orderId":"ord-1","sequence":1,"type":"OrderPaid"}""", "invalid JSON")
    }

    @Test
    fun `not an object is malformed`() {
        for (raw in listOf("[1,2]", "\"text\"", "42", "null", "")) assertMalformed(raw, "not a JSON object")
    }

    @Test
    fun `missing or invalid envelope fields are malformed`() {
        assertMalformed("""{"eventId":"e1","sequence":2,"type":"ItemAdded","payload":{}}""", "orderId is missing")
        assertMalformed("""{"eventId":"e1","orderId":"ord-1","sequence":9,"payload":{}}""", "type is missing")
        assertMalformed("""{"orderId":"ord-1","sequence":1,"type":"OrderPaid"}""", "eventId is missing")
        assertMalformed("""{"eventId":42,"orderId":"ord-1","sequence":1,"type":"OrderPaid"}""", "eventId must be")
        assertMalformed("""{"eventId":"e1","orderId":" ","sequence":1,"type":"OrderPaid"}""", "orderId must be")
        assertMalformed("""{"eventId":"e1","orderId":"ord-1","type":"OrderPaid"}""", "sequence is missing")
        for (sequence in listOf("0", "-1", "1.0", "\"1\"", "true", "2147483648")) {
            assertMalformed(envelope(sequence), "sequence must be")
        }
    }

    @Test
    fun `missing payload is valid here and rejected by the state machine`() {
        val parsed = parseLine(envelope(extra = ""))

        assertIs<ParsedLine.Valid>(parsed)
        assertEquals(NullNode.instance, parsed.event.payload)
        val outcome = applyEvent(null, parsed.event)
        assertEquals(RejectKind.INVALID_PAYLOAD, (outcome as Outcome.Rejected).kind)
    }

    @Test
    fun `sample file has exactly the expected malformed lines`() {
        val malformed = File("data/events.jsonl").readLines()
            .withIndex()
            .filter { it.value.isNotBlank() && parseLine(it.value) is ParsedLine.Malformed }
            .map { it.index + 1 }

        assertEquals(listOf(114, 281, 902, 1210, 1416), malformed)
    }
}
