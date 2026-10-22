package outbox

import db.RedisOutbox
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import redis.clients.jedis.Jedis

fun statusKey(orderId: String) = "order:$orderId:status"

/**
 * Copies committed statuses from redis_outbox to Redis, then deletes those rows. Rows only exist
 * once their transaction has committed, so Redis never gets ahead of Postgres. A crash between the
 * SET and the delete just repeats the SET on the next flush, which is harmless.
 */
fun flushOutbox(db: Database, redis: Jedis): Int {
    val rows = transaction(db) {
        RedisOutbox.selectAll().map { it[RedisOutbox.orderId] to it[RedisOutbox.status] }
    }
    if (rows.isEmpty()) return 0

    redis.pipelined().use { pipeline ->
        for ((orderId, status) in rows) pipeline.set(statusKey(orderId), status.name)
        pipeline.sync()
    }
    transaction(db) {
        RedisOutbox.deleteWhere { RedisOutbox.orderId inList rows.map { it.first } }
    }
    return rows.size
}
