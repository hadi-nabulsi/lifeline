package app.getknit.knit.fieldlog

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.PlaneDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldLogDocumentTest {
    private val start = 1_759_830_000_000L // 2025-10-07T09:40:00Z

    @Test
    fun linesAreRebasedToTheFirstAndTheHeaderKeepsOnlyTheHour() {
        val doc =
            FieldLogDocument.build(
                device = listOf("app: 2.9.0 (31) release"),
                lines = listOf("""{"t":$start,"e":"mesh_on","vc":31}""", """{"t":${start + 1_500},"e":"mesh_off"}"""),
                summaries = emptyList(),
                now = start + 3 * 60_000,
            )

        val lines = doc.text.lines()
        assertTrue(lines.contains("# app: 2.9.0 (31) release"))
        assertTrue(lines.contains("# from: 2025-10-07T09:00:00Z (rounded down to the hour)"))
        assertTrue(lines.contains("# span: 3 min; t = ms since the first line"))
        assertTrue(lines.contains("""{"t":0,"e":"mesh_on","vc":31}"""))
        assertTrue(lines.contains("""{"t":1500,"e":"mesh_off"}"""))
        assertEquals(2, doc.events)
    }

    @Test
    fun aLineCarryingANodeIdIsWithheldAndCounted() {
        val leak = """{"t":$start,"e":"probe","x":"abcdefghijklmnopqrstuvwxyz"}"""
        val doc = FieldLogDocument.build(emptyList(), listOf(leak), emptyList(), now = start)

        assertTrue(!doc.text.contains("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(1, doc.dropped)
        assertTrue(doc.text.contains("# withheld: 1 line(s) failed the check"))
    }

    @Test
    fun latencyIsSummarisedPerPlane() {
        val delays =
            (1..10).map { PlaneDelay(DeliveryPlane.Bluetooth.code, it * 100L) } +
                PlaneDelay(DeliveryPlane.LoRa.code, 60_000L)

        val summary = FieldLogDocument.latency(LatencyDirection.IN, delays)

        assertEquals(
            listOf(
                FieldEvent.Latency(LatencyDirection.IN, DeliveryPlane.Bluetooth, 10, p50Ms = 500, p90Ms = 900, maxMs = 1_000),
                FieldEvent.Latency(LatencyDirection.IN, DeliveryPlane.LoRa, 1, p50Ms = 60_000, p90Ms = 60_000, maxMs = 60_000),
            ),
            summary,
        )
    }
}
