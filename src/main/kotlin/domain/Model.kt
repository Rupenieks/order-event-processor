package domain

import com.fasterxml.jackson.databind.JsonNode

/** An event as stored after ingest: the envelope is validated, the payload is not. */
data class Event(
    val eventId: String,
    val orderId: String,
    val sequence: Long,
    val type: String,
    val payload: JsonNode,
)

enum class OrderStatus { CREATED, PAID, SHIPPED, CANCELLED }

data class OrderState(val status: OrderStatus, val totalCents: Long)

enum class RejectKind { INVALID_TRANSITION, AMOUNT_MISMATCH, UNKNOWN_TYPE, INVALID_PAYLOAD, SEQUENCE_CONFLICT }

sealed interface Outcome {
    data class Applied(val state: OrderState) : Outcome
    data class Rejected(val kind: RejectKind, val reason: String) : Outcome
}
