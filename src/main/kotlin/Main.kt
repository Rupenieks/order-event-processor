import java.io.File

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "data/events.jsonl"
    val lines = File(path).readLines()
    println("Read ${lines.size} lines from $path")
}
