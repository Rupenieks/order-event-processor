import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisException
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "data/events.jsonl"

    val db = connectPostgres()
    val redis = connectRedis()

    db.use {
        redis.use {
            val lines = File(path).readLines()
            println("Read ${lines.size} lines from $path")
        }
    }
}

private fun connectPostgres(): Connection {
    val url = env("PG_URL", "jdbc:postgresql://localhost:5432/orders?connectTimeout=5")
    return try {
        DriverManager.getConnection(url, env("PG_USER", "orders"), env("PG_PASSWORD", "orders"))
            .also { println("Connected to PostgreSQL at $url") }
    } catch (e: SQLException) {
        fatal("Could not connect to PostgreSQL at $url: ${e.message}", EXIT_POSTGRES)
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

private const val EXIT_POSTGRES = 2
private const val EXIT_REDIS = 3

private fun fatal(message: String, exitCode: Int): Nothing {
    System.err.println("ERROR: $message")
    exitProcess(exitCode)
}
