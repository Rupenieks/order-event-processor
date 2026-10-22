package domain

import com.fasterxml.jackson.databind.JsonNode

data class Item(val sku: String, val qty: Long, val unitPriceCents: Long)

/** A payload that passed validation, typed per event type. */
sealed interface OrderEvent {
    data class Created(val customerId: String, val items: List<Item>) : OrderEvent
    data class ItemAdded(val item: Item) : OrderEvent
    data class Paid(val amountCents: Long) : OrderEvent
    data class Shipped(val trackingId: String) : OrderEvent
    data class Cancelled(val reason: String?) : OrderEvent
}

class InvalidPayload(message: String) : Exception(message)

// Type names match exactly and case-sensitively; extra payload fields are ignored.
private val decoders: Map<String, (JsonNode) -> OrderEvent> = mapOf(
    "OrderCreated" to { p -> OrderEvent.Created(p.string("customerId"), p.items("items")) },
    "ItemAdded" to { p -> OrderEvent.ItemAdded(p.item()) },
    "OrderPaid" to { p -> p.requireEur(); OrderEvent.Paid(p.long("amountCents", min = 0)) },
    "OrderShipped" to { p -> OrderEvent.Shipped(p.string("trackingId")) },
    "OrderCancelled" to { p -> OrderEvent.Cancelled(p.optionalString("reason")) },
)

/** Returns null for an unknown type; throws [InvalidPayload] if the payload doesn't fit the type. */
fun decode(type: String, payload: JsonNode): OrderEvent? {
    val decoder = decoders[type] ?: return null
    if (!payload.isObject) throw InvalidPayload("payload must be a JSON object")
    return decoder(payload)
}

private fun JsonNode.string(name: String): String {
    val node = get(name) ?: throw InvalidPayload("$name is missing")
    if (!node.isTextual || node.textValue().isBlank()) throw InvalidPayload("$name must be a non-empty string")
    return node.textValue()
}

private fun JsonNode.optionalString(name: String): String? {
    val node = get(name)
    if (node == null || node.isNull) return null
    if (!node.isTextual) throw InvalidPayload("$name must be a string")
    return node.textValue()
}

// Strict: 5 is accepted; 5.0, "5" and integers beyond 64 bits are not.
private fun JsonNode.long(name: String, min: Long): Long {
    val node = get(name) ?: throw InvalidPayload("$name is missing")
    if (!node.isIntegralNumber || !node.canConvertToLong()) throw InvalidPayload("$name must be an integer")
    val value = node.longValue()
    if (value < min) throw InvalidPayload("$name must be >= $min, was $value")
    return value
}

private fun JsonNode.requireEur() {
    val currency = string("currency")
    if (currency != "EUR") throw InvalidPayload("currency must be EUR, was $currency")
}

private fun JsonNode.item() = Item(string("sku"), long("qty", min = 1), long("unitPriceCents", min = 0))

private fun JsonNode.items(name: String): List<Item> {
    val node = get(name) ?: throw InvalidPayload("$name is missing")
    if (!node.isArray || node.isEmpty) throw InvalidPayload("$name must be a non-empty list")
    return node.mapIndexed { i, itemNode ->
        try {
            if (!itemNode.isObject) throw InvalidPayload("must be an object")
            itemNode.item()
        } catch (e: InvalidPayload) {
            throw InvalidPayload("$name[$i]: ${e.message}")
        }
    }
}
