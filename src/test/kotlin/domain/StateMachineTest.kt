package domain

import domain.OrderStatus.CANCELLED
import domain.OrderStatus.CREATED
import domain.OrderStatus.PAID
import domain.OrderStatus.SHIPPED
import domain.RejectKind.AMOUNT_MISMATCH
import domain.RejectKind.INVALID_PAYLOAD
import domain.RejectKind.INVALID_TRANSITION
import domain.RejectKind.UNKNOWN_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals

class StateMachineTest {
    private fun state(status: OrderStatus, totalCents: Long = 1000) = OrderState(status, totalCents)

    private fun assertApplied(expected: OrderState, outcome: Outcome) =
        assertEquals(Outcome.Applied(expected), outcome)

    private fun assertRejected(kind: RejectKind, outcome: Outcome, message: String? = null) =
        assertEquals(kind, (outcome as? Outcome.Rejected)?.kind, message ?: "expected $kind, got $outcome")

    @Test
    fun `OrderCreated at sequence 1 creates the order with the sum of its items`() {
        val event = event(1, "OrderCreated",
            """{"customerId":"c1","items":[{"sku":"A","qty":2,"unitPriceCents":500},{"sku":"B","qty":1,"unitPriceCents":250}]}""")
        assertApplied(state(CREATED, 1250), applyEvent(null, event))
    }

    @Test
    fun `OrderCreated is rejected after sequence 1 or when the order exists`() {
        assertRejected(INVALID_TRANSITION, applyEvent(null, created(sequence = 2)))
        assertRejected(INVALID_TRANSITION, applyEvent(state(CREATED), created(sequence = 1)))
    }

    @Test
    fun `ItemAdded adds to the total only while CREATED`() {
        assertApplied(state(CREATED, 1500), applyEvent(state(CREATED), itemAdded(2, qty = 2, unitPriceCents = 250)))
        assertRejected(INVALID_TRANSITION, applyEvent(null, itemAdded(2)))
        for (status in listOf(PAID, SHIPPED, CANCELLED)) {
            assertRejected(INVALID_TRANSITION, applyEvent(state(status), itemAdded(2)), "ItemAdded when $status")
        }
    }

    @Test
    fun `OrderPaid needs CREATED and the exact total`() {
        assertApplied(state(PAID), applyEvent(state(CREATED), paid(2, 1000)))
        assertRejected(AMOUNT_MISMATCH, applyEvent(state(CREATED), paid(2, 999)))
        assertRejected(INVALID_TRANSITION, applyEvent(null, paid(2, 1000)))
        for (status in listOf(PAID, SHIPPED, CANCELLED)) {
            assertRejected(INVALID_TRANSITION, applyEvent(state(status), paid(2, 1000)), "OrderPaid when $status")
        }
    }

    @Test
    fun `OrderShipped needs PAID`() {
        assertApplied(state(SHIPPED), applyEvent(state(PAID), shipped(3)))
        assertRejected(INVALID_TRANSITION, applyEvent(null, shipped(3)))
        for (status in listOf(CREATED, SHIPPED, CANCELLED)) {
            assertRejected(INVALID_TRANSITION, applyEvent(state(status), shipped(3)), "OrderShipped when $status")
        }
    }

    @Test
    fun `OrderCancelled is allowed before shipping and keeps the total`() {
        assertApplied(state(CANCELLED), applyEvent(state(CREATED), cancelled(2)))
        assertApplied(state(CANCELLED), applyEvent(state(PAID), cancelled(3)))
        assertRejected(INVALID_TRANSITION, applyEvent(null, cancelled(2)))
        for (status in listOf(SHIPPED, CANCELLED)) {
            assertRejected(INVALID_TRANSITION, applyEvent(state(status), cancelled(4)), "OrderCancelled when $status")
        }
    }

    @Test
    fun `OrderCancelled reason is optional`() {
        assertApplied(state(CANCELLED), applyEvent(state(CREATED), event(2, "OrderCancelled", "{}")))
    }

    @Test
    fun `unknown and wrongly cased types are rejected`() {
        for (type in listOf("order_paid", "OrderRefunded", "orderpaid")) {
            assertRejected(UNKNOWN_TYPE, applyEvent(state(CREATED), event(2, type, """{"amountCents":1000}""")), type)
        }
    }

    @Test
    fun `invalid payloads are rejected`() {
        val cases = mapOf(
            "payload not an object" to event(2, "ItemAdded", "[]"),
            "qty as float" to event(2, "ItemAdded", """{"sku":"B","qty":1.0,"unitPriceCents":250}"""),
            "qty as string" to event(2, "ItemAdded", """{"sku":"B","qty":"1","unitPriceCents":250}"""),
            "qty zero" to itemAdded(2, qty = 0),
            "qty negative" to itemAdded(2, qty = -2),
            "price negative" to itemAdded(2, unitPriceCents = -1),
            "sku missing" to event(2, "ItemAdded", """{"qty":1,"unitPriceCents":250}"""),
            "sku blank" to event(2, "ItemAdded", """{"sku":" ","qty":1,"unitPriceCents":250}"""),
            "amount as float" to event(2, "OrderPaid", """{"amountCents":1000.5,"currency":"EUR"}"""),
            "amount beyond 64 bits" to event(2, "OrderPaid", """{"amountCents":99999999999999999999,"currency":"EUR"}"""),
            "currency not EUR" to event(2, "OrderPaid", """{"amountCents":1000,"currency":"USD"}"""),
            "currency missing" to event(2, "OrderPaid", """{"amountCents":1000}"""),
            "reason not a string" to event(2, "OrderCancelled", """{"reason":42}"""),
        )
        for ((name, event) in cases) {
            assertRejected(INVALID_PAYLOAD, applyEvent(state(CREATED), event), name)
        }

        val createdCases = mapOf(
            "items empty" to """{"customerId":"c1","items":[]}""",
            "items missing" to """{"customerId":"c1"}""",
            "item not an object" to """{"customerId":"c1","items":[5]}""",
            "customerId missing" to """{"items":[{"sku":"A","qty":1,"unitPriceCents":1}]}""",
        )
        for ((name, payload) in createdCases) {
            assertRejected(INVALID_PAYLOAD, applyEvent(null, event(1, "OrderCreated", payload)), name)
        }
        assertRejected(INVALID_PAYLOAD, applyEvent(state(PAID), event(3, "OrderShipped", "{}")), "trackingId missing")
    }

    @Test
    fun `total overflow is rejected`() {
        val event = event(2, "ItemAdded", """{"sku":"B","qty":2,"unitPriceCents":${Long.MAX_VALUE}}""")
        assertRejected(INVALID_PAYLOAD, applyEvent(state(CREATED), event))
    }

    @Test
    fun `payload is checked before the transition`() {
        assertRejected(INVALID_PAYLOAD, applyEvent(state(SHIPPED), itemAdded(5, qty = 0)))
    }
}
