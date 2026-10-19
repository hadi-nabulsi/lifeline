package app.getknit.knit.fieldlog

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.PlaneDelay
import app.getknit.knit.identity.NodeId
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The exported field log, built from the journal's lines — pure, so the whole shape is JVM-tested.
 *
 * The file is a `#` header (build, device, the window), then every kept line with its wall-clock `t` rebased to
 * milliseconds since the first one (durations stay exact; the calendar is only the header's hour), then the
 * export-time summaries. A last guard drops any line that carries a node-id-shaped token: the [FieldWriter]
 * contract already keeps every id out, so a dropped line is a bug, counted in the header rather than shipped.
 *
 * There is no contact-name pass (the crash report's phase 2): every token on a line is a field name, an enum
 * constant, a number or a pseudonym — code vocabulary — so a name match could only be a contact called `Max` or
 * `Radio` wiping out the lines that carry those words, never a leak caught.
 */
object FieldLogDocument {
    const val SCHEMA = 1

    data class Built(
        val text: String,
        val events: Int,
        val dropped: Int,
    )

    fun build(
        device: List<String>,
        lines: List<String>,
        summaries: List<FieldEvent>,
        now: Long,
    ): Built {
        val start = lines.firstNotNullOfOrNull(FieldLine::timeOf) ?: now
        val placeholder = Pseudonyms(ByteArray(Pseudonyms.SALT_BYTES))
        val body =
            lines.mapNotNull { line -> FieldLine.timeOf(line)?.let { FieldLine.withTime(line, it - start) } } +
                summaries.map { FieldLine.render(now - start, it, placeholder) }
        val kept = body.filterNot { NODE_ID.containsMatchIn(it) }
        val header =
            buildList {
                add("# knit field log, schema $SCHEMA")
                device.forEach { add("# $it") }
                add("# from: ${Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.HOURS)} (rounded down to the hour)")
                add("# span: ${(now - start) / MS_PER_MINUTE} min; t = ms since the first line")
                add("# peers: per-log pseudonyms; no names, node ids, messages or locations")
                add("# latency IN carries the clock difference between the author's phone and this one")
                if (body.size != kept.size) add("# withheld: ${body.size - kept.size} line(s) failed the check")
            }
        return Built((header + kept).joinToString(separator = "\n", postfix = "\n"), kept.size, body.size - kept.size)
    }

    /** Per-plane percentiles of [delays], one [FieldEvent.Latency] per plane that has any. */
    fun latency(
        direction: LatencyDirection,
        delays: List<PlaneDelay>,
    ): List<FieldEvent.Latency> =
        delays
            .groupBy { DeliveryPlane.fromCode(it.plane) }
            .toSortedMap()
            .map { (plane, rows) ->
                val sorted = rows.map { it.ms }.sorted()
                FieldEvent.Latency(
                    direction = direction,
                    plane = plane,
                    count = sorted.size,
                    p50Ms = sorted.percentile(P50),
                    p90Ms = sorted.percentile(P90),
                    maxMs = sorted.last(),
                )
            }

    private fun List<Long>.percentile(p: Int): Long = this[((size - 1) * p) / PERCENT]

    private val NODE_ID =
        Regex("(?<![${NodeId.ALPHABET}])[${NodeId.ALPHABET}]{${NodeId.LENGTH}}(?![${NodeId.ALPHABET}])")

    private const val MS_PER_MINUTE = 60_000L
    private const val P50 = 50
    private const val P90 = 90
    private const val PERCENT = 100
}
