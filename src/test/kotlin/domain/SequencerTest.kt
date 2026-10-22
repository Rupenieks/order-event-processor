package domain

import domain.OrderStatus.CREATED
import domain.OrderStatus.PAID
import domain.OrderStatus.SHIPPED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SequencerTest {
    private fun OrderProgress.kinds() = processed.map { (it.outcome as? Outcome.Rejected)?.kind }

    @Test
    fun `events fed in reverse are applied in sequence order`() {
        val events = listOf(created(1), itemAdded(2), paid(3, 1250), shipped(4)).reversed()

        val progress = processOrder(null, 0, events)

        assertEquals(OrderState(SHIPPED, 1250), progress.state)
        assertEquals(4, progress.lastProcessed)
        assertEquals(listOf(1, 2, 3, 4), progress.processed.map { it.event.sequence })
        assertTrue(progress.processed.all { it.outcome is Outcome.Applied })
    }

    @Test
    fun `an event after a gap waits until the gap is filled`() {
        val first = processOrder(null, 0, listOf(itemAdded(3), created(1)))

        assertEquals(OrderState(CREATED, 1000), first.state)
        assertEquals(1, first.lastProcessed)
        assertEquals(listOf(3), first.waiting.map { it.sequence })

        val second = processOrder(first.state, first.lastProcessed, first.waiting + itemAdded(2))

        assertEquals(OrderState(CREATED, 1500), second.state)
        assertEquals(3, second.lastProcessed)
        assertEquals(listOf(2, 3), second.processed.map { it.event.sequence })
        assertTrue(second.waiting.isEmpty())
    }

    @Test
    fun `a rejected event still advances the sequence`() {
        val progress = processOrder(null, 0, listOf(created(1), paid(2, 999), paid(3, 1000)))

        assertEquals(OrderState(PAID, 1000), progress.state)
        assertEquals(3, progress.lastProcessed)
        assertEquals(listOf(null, RejectKind.AMOUNT_MISMATCH, null), progress.kinds())
    }

    @Test
    fun `when OrderCreated is rejected the order never exists`() {
        val badCreated = event(1, "OrderCreated", """{"customerId":"c1","items":[]}""")

        val progress = processOrder(null, 0, listOf(badCreated, itemAdded(2), paid(3, 250)))

        assertNull(progress.state)
        assertEquals(3, progress.lastProcessed)
        assertEquals(
            listOf(RejectKind.INVALID_PAYLOAD, RejectKind.INVALID_TRANSITION, RejectKind.INVALID_TRANSITION),
            progress.kinds(),
        )
    }

    @Test
    fun `an early OrderCreated waits and is rejected once its turn comes`() {
        val first = processOrder(null, 0, listOf(created(2)))

        assertTrue(first.processed.isEmpty())
        assertEquals(0, first.lastProcessed)
        assertEquals(listOf(2), first.waiting.map { it.sequence })

        val second = processOrder(first.state, first.lastProcessed, first.waiting + itemAdded(1))

        assertNull(second.state)
        assertEquals(2, second.lastProcessed)
        assertEquals(listOf(RejectKind.INVALID_TRANSITION, RejectKind.INVALID_TRANSITION), second.kinds())
    }

    @Test
    fun `nothing pending changes nothing`() {
        val state = OrderState(PAID, 1000)
        assertEquals(OrderProgress(state, 3, emptyList(), emptyList()), processOrder(state, 3, emptyList()))
    }

    @Test
    fun `pending events must be after last processed and have distinct sequences`() {
        assertFailsWith<IllegalArgumentException> { nextInSequence(2, listOf(itemAdded(2))) }
        assertFailsWith<IllegalArgumentException> { nextInSequence(0, listOf(created(1), itemAdded(1))) }
    }
}
