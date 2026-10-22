import apply.applyPending
import db.allTables
import ingest.ingestFile
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisException
import java.io.File
import java.sql.SQLException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: "data/events.jsonl")
    if (!file.isFile) fatal("File not found: $file", EXIT_USAGE)
    val batchSize = env("BATCH_SIZE", "500").toInt()

    val db = connectPostgres()
    val redis = connectRedis()

    redis.use {
        transaction(db) { SchemaUtils.create(*allTables) }

        val ingest = ingestFile(db, file, batchSize)
        println("Stage 1: ingested ${ingest.newLines} new lines from $file (resumed after line ${ingest.resumedAfterLine})")

        val applied = applyPending(db, batchSize)
        println("Stage 2: applied ${applied.applied} events, rejected ${applied.rejected}")
    }
}

private fun connectPostgres(): Database {
    val url = env("PG_URL", "jdbc:postgresql://localhost:5432/orders?connectTimeout=5")
    val db = Database.connect(
        url,
        driver = "org.postgresql.Driver",
        user = env("PG_USER", "orders"),
        password = env("PG_PASSWORD", "orders"),
        // Exposed retries a failed transaction 3 times by default; fail fast instead
        databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
    )
    return try {
        // Database.connect is lazy, so run a query to verify the connection
        transaction(db) { exec("SELECT 1") }
        println("Connected to PostgreSQL at $url")
        db
    } catch (e: SQLException) {
        // Exposed wraps the driver's exception; the innermost one has the readable message
        val cause = generateSequence<Throwable>(e) { it.cause }.last()
        fatal("Could not connect to PostgreSQL at $url: ${cause.message}", EXIT_POSTGRES)
    }
}

private fun connectRedis(): Jedis {
    val address = HostAndPort(env("REDIS_HOST", "localhost"), env("REDIS_PORT", "6379").toInt())
    val config = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(5000)
        .socketTimeoutMillis(5000)
        .build()
    return try {
        // The constructor opens the socket; ping confirms Redis actually answers commands
        Jedis(address, config).also {
            it.ping()
            println("Connected to Redis at $address")
        }
    } catch (e: JedisException) {
        fatal("Could not connect to Redis at $address: ${e.message}", EXIT_REDIS)
    }
}

private fun env(name: String, default: String): String = System.getenv(name) ?: default

private const val EXIT_USAGE = 1
private const val EXIT_POSTGRES = 2
private const val EXIT_REDIS = 3

private fun fatal(message: String, exitCode: Int): Nothing {
    System.err.println("ERROR: $message")
    exitProcess(exitCode)
}
