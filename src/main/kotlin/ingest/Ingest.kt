package ingest

import db.Events
import db.IngestedLines
import domain.RejectKind
import input.ParsedLine
import input.parseLine
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

data class IngestResult(val fileHash: String, val resumedAfterLine: Long, val newLines: Int)

/**
 * Stage 1: file → ingested_lines + events. Each batch is one transaction, so after a crash the
 * stored lines end at a batch boundary and the next run resumes right after the last stored line.
 */
fun ingestFile(db: Database, file: File, batchSize: Int): IngestResult {
    val fileHash = sha256(file)
    val resumeAfter = transaction(db) { lastStoredLine(fileHash) }
    var newLines = 0

    file.useLines { lines ->
        lines.mapIndexed { i, raw -> Line(i + 1L, raw) }
            .filter { it.lineNo > resumeAfter && it.raw.isNotBlank() }
            .chunked(batchSize)
            .forEach { batch ->
                transaction(db) { ingestBatch(fileHash, batch) }
                newLines += batch.size
            }
    }
    return IngestResult(fileHash, resumeAfter, newLines)
}

private fun lastStoredLine(fileHash: String): Long {
    val maxLine = IngestedLines.lineNo.max()
    return IngestedLines.select(maxLine).where { IngestedLines.fileHash eq fileHash }.single()[maxLine] ?: 0L
}

private fun ingestBatch(fileHash: String, batch: List<Line>) {
    val candidates = batch.mapNotNull { (parseLine(it.raw) as? ParsedLine.Valid)?.event }
    val classified = classify(
        batch,
        knownIds = existingEventIds(candidates.map { it.eventId }),
        slotHolders = existingSlotHolders(candidates.map { it.slot }.toSet()),
    )

    IngestedLines.batchInsert(classified.lines) { row ->
        this[IngestedLines.fileHash] = fileHash
        this[IngestedLines.lineNo] = row.line.lineNo
        this[IngestedLines.raw] = row.line.raw
        this[IngestedLines.status] = row.status
        this[IngestedLines.reason] = row.reason
        this[IngestedLines.eventId] = row.eventId
    }
    Events.batchInsert(classified.events) { row ->
        this[Events.eventId] = row.event.eventId
        this[Events.orderId] = row.event.orderId
        this[Events.sequence] = row.event.sequence
        this[Events.type] = row.event.type
        this[Events.payload] = row.event.payload
        this[Events.lineNo] = row.lineNo
        this[Events.state] = row.state
        this[Events.rejectKind] = row.rejectKind
        this[Events.reason] = row.reason
    }
}

private fun existingEventIds(ids: List<String>): Set<String> {
    if (ids.isEmpty()) return emptySet()
    return Events.select(Events.eventId).where { Events.eventId inList ids }.map { it[Events.eventId] }.toSet()
}

/** Which event already holds each of [slots]; sequence-conflict losers never hold a slot. */
private fun existingSlotHolders(slots: Set<Slot>): Map<Slot, String> {
    if (slots.isEmpty()) return emptyMap()
    return Events.select(Events.orderId, Events.sequence, Events.eventId)
        .where {
            (Events.orderId inList slots.map { it.first }.distinct()) and
                (Events.rejectKind.isNull() or (Events.rejectKind neq RejectKind.SEQUENCE_CONFLICT))
        }
        .map { (it[Events.orderId] to it[Events.sequence]) to it[Events.eventId] }
        .filter { (slot, _) -> slot in slots }
        .toMap()
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
