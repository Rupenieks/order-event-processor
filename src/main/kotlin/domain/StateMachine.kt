package domain

import domain.OrderStatus.CANCELLED
import domain.OrderStatus.CREATED
import domain.OrderStatus.PAID
import domain.OrderStatus.SHIPPED

/**
 * Applies one event to an order's state. [state] is null when the order does not exist.
 * Checks run in this order: UNKNOWN_TYPE, INVALID_PAYLOAD, INVALID_TRANSITION, AMOUNT_MISMATCH.
 */
fun applyEvent(state: OrderState?, event: Event): Outcome {
    val decoded = try {
        decode(event.type, event.payload)
            ?: return Outcome.Rejected(RejectKind.UNKNOWN_TYPE, "unknown event type '${event.type}'")
    } catch (e: InvalidPayload) {
        return Outcome.Rejected(RejectKind.INVALID_PAYLOAD, e.message!!)
    }
    return try {
        transition(state, event.sequence, decoded)
    } catch (e: ArithmeticException) {
        Outcome.Rejected(RejectKind.INVALID_PAYLOAD, "order total overflows 64-bit cents")
    }
}

private fun transition(state: OrderState?, sequence: Int, event: OrderEvent): Outcome = when (event) {
    is OrderEvent.Created -> when {
        state != null -> invalid("order already exists")
        sequence != 1 -> invalid("OrderCreated must have sequence 1, had $sequence")
        else -> applied(CREATED, event.items.fold(0L) { sum, item -> Math.addExact(sum, item.totalCents()) })
    }

    is OrderEvent.ItemAdded -> inStatus(state, CREATED) {
        applied(CREATED, Math.addExact(it.totalCents, event.item.totalCents()))
    }

    is OrderEvent.Paid -> inStatus(state, CREATED) {
        if (event.amountCents == it.totalCents) applied(PAID, it.totalCents)
        else Outcome.Rejected(
            RejectKind.AMOUNT_MISMATCH,
            "amountCents ${event.amountCents} does not equal order total ${it.totalCents}",
        )
    }

    is OrderEvent.Shipped -> inStatus(state, PAID) { applied(SHIPPED, it.totalCents) }

    is OrderEvent.Cancelled -> inStatus(state, CREATED, PAID) { applied(CANCELLED, it.totalCents) }
}

/** Runs [block] if the order exists and is in one of [allowed], otherwise rejects as INVALID_TRANSITION. */
private inline fun inStatus(state: OrderState?, vararg allowed: OrderStatus, block: (OrderState) -> Outcome) =
    when {
        state == null -> invalid("order does not exist")
        state.status !in allowed -> invalid("not allowed when order is ${state.status}")
        else -> block(state)
    }

private fun Item.totalCents() = Math.multiplyExact(qty, unitPriceCents)

private fun applied(status: OrderStatus, totalCents: Long) = Outcome.Applied(OrderState(status, totalCents))

private fun invalid(reason: String) = Outcome.Rejected(RejectKind.INVALID_TRANSITION, reason)
