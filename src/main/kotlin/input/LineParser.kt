package input

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.NullNode
import domain.Event

/** Rejects duplicate keys and anything after the first JSON value; Jackson silently accepts both by default. */
val jsonMapper: JsonMapper = JsonMapper.builder()
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .build()

sealed interface ParsedLine {
    data class Valid(val event: Event) : ParsedLine
    data class Malformed(val reason: String) : ParsedLine
}

private class MalformedLine(message: String) : Exception(message)

/**
 * Validates the envelope only (eventId, orderId, sequence, type). The payload is kept as-is and
 * checked later by the state machine, so a bad payload still uses up its sequence.
 * A missing payload becomes JSON null, which the state machine rejects as INVALID_PAYLOAD.
 */
fun parseLine(raw: String): ParsedLine {
    val node = try {
        jsonMapper.readTree(raw)
    } catch (e: JsonProcessingException) {
        return ParsedLine.Malformed("invalid JSON: ${e.originalMessage}")
    }
    if (node == null || !node.isObject) return ParsedLine.Malformed("not a JSON object")

    return try {
        ParsedLine.Valid(
            Event(
                eventId = node.string("eventId"),
                orderId = node.string("orderId"),
                sequence = node.sequence(),
                type = node.string("type"),
                payload = node.get("payload") ?: NullNode.instance,
            )
        )
    } catch (e: MalformedLine) {
        ParsedLine.Malformed(e.message!!)
    }
}

private fun JsonNode.string(name: String): String {
    val node = get(name) ?: throw MalformedLine("$name is missing")
    if (!node.isTextual || node.textValue().isBlank()) throw MalformedLine("$name must be a non-empty string")
    return node.textValue()
}

private fun JsonNode.sequence(): Int {
    val node = get("sequence") ?: throw MalformedLine("sequence is missing")
    if (!node.isIntegralNumber || !node.canConvertToInt() || node.intValue() < 1) {
        throw MalformedLine("sequence must be an integer >= 1, was $node")
    }
    return node.intValue()
}
