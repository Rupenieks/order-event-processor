package domain

import com.fasterxml.jackson.databind.ObjectMapper

private val mapper = ObjectMapper()

fun event(sequence: Int, type: String, payload: String) =
    Event("evt-$sequence", "ord-1", sequence, type, mapper.readTree(payload))

fun created(sequence: Int = 1) =
    event(sequence, "OrderCreated", """{"customerId":"c1","items":[{"sku":"A","qty":2,"unitPriceCents":500}]}""")

fun itemAdded(sequence: Int, qty: Int = 1, unitPriceCents: Int = 250) =
    event(sequence, "ItemAdded", """{"sku":"B","qty":$qty,"unitPriceCents":$unitPriceCents}""")

fun paid(sequence: Int, amountCents: Long) =
    event(sequence, "OrderPaid", """{"amountCents":$amountCents,"currency":"EUR"}""")

fun shipped(sequence: Int) = event(sequence, "OrderShipped", """{"trackingId":"TRK1"}""")

fun cancelled(sequence: Int) = event(sequence, "OrderCancelled", """{"reason":"changed mind"}""")
