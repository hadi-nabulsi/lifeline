package app.getknit.knit.fieldlog

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.power.PowerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FieldLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val metrics = MeshMetrics()
    private val power = MutableStateFlow(PowerState(interactive = true))
    private val enabled = MutableStateFlow(true)
    private val statuses = MutableStateFlow(listOf(bt(linked = 0, nearby = 0)))
    private var now = 1_000_000L
    private val staged get() = File(tmp.root, "staged").apply { mkdirs() }

    private fun bt(
        linked: Int,
        nearby: Int,
        health: TransportHealth = TransportHealth.Healthy,
    ) = TransportStatus(TransportKind.Bluetooth, health, linked, nearby)

    private fun TestScope.newLog() =
        FieldLog(
            journal = FieldJournal(tmp.root),
            metrics = metrics::snapshot,
            power = power,
            enabled = enabled,
            versionCode = 31,
            clock = { now },
            io = UnconfinedTestDispatcher(testScheduler),
            staged = staged,
        )

    private fun List<String>.kinds() = map { Regex(""""e":"(\w+)"""").find(it)!!.groupValues[1] }

    @Test
    fun aSessionWritesItsStartPowerAndRadiosThenItsStop() =
        runTest {
            val log = newLog()
            log.start(backgroundScope, statuses)
            runCurrent()
            log.stop()

            assertEquals(listOf("mesh_on", "power", "radio", "radio", "mesh_off"), log.lines().kinds())
        }

    @Test
    fun aNearbyCountMovingAloneIsNotAnEventButALinkIs() =
        runTest {
            val log = newLog()
            log.start(backgroundScope, statuses)
            runCurrent()
            statuses.value = listOf(bt(linked = 0, nearby = 4)) // smoothed reach: rides the next sample
            runCurrent()
            statuses.value = listOf(bt(linked = 1, nearby = 4)) // a link came up: an event
            runCurrent()

            val radios = log.lines().filter { it.contains("\"e\":\"radio\"") }
            assertEquals(2, radios.size)
            assertTrue(radios.last(), radios.last().contains("\"linked\":1"))
        }

    @Test
    fun theCountersLineCarriesOnlyWhatMovedSinceTheLastOne() =
        runTest {
            val log = newLog()
            log.start(backgroundScope, statuses)
            runCurrent()
            repeat(3) { metrics.onRelayed() }
            now += FieldLog.COUNTERS_EVERY_MS
            advanceTimeBy(FieldLog.FLUSH_EVERY_MS + 1)
            metrics.onRelayed()
            log.sample()

            val counters = log.lines().filter { it.contains("\"e\":\"counters\"") }
            assertEquals(listOf(""""relayed":3""", """"relayed":1"""), counters.map { Regex(""""relayed":\d+""").find(it)!!.value })
        }

    @Test
    fun aSamplerRunsOnItsCadenceAndAThrowingOneCostsOnlyItsOwnLines() =
        runTest {
            val log = newLog()
            var calls = 0
            log.sampler("ok") { listOf(FieldEvent.BtScan(1, 1, 2, hunting = false, codedMode = TransportHealth.Healthy)).also { calls++ } }
            log.sampler("broken") { error("a radio read that threw") }
            log.start(backgroundScope, statuses)
            advanceTimeBy(FieldLog.FLUSH_EVERY_MS + 1) // first tick: never sampled yet, so it samples
            now += FieldLog.FLUSH_EVERY_MS
            advanceTimeBy(FieldLog.FLUSH_EVERY_MS) // a minute on: not due again
            log.sampler("ok", null)
            now += FieldLog.SAMPLE_EVERY_MS
            log.sampleRadios() // removed: takes nothing

            assertEquals(1, calls)
            assertEquals(1, log.lines().count { it.contains("bt_scan") })
        }

    @Test
    fun clearAndTheSwitchTurnedOffAlsoDeleteTheCopyStagedForAShare() =
        runTest {
            val log = newLog()
            File(staged, "knit-fieldlog-2.9.0-20261007-1200.jsonl").writeText("{}")
            log.clear()
            assertEquals(0, staged.listFiles()!!.size)

            File(staged, "knit-fieldlog-2.9.0-20261007-1300.jsonl").writeText("{}")
            log.setEnabled(false) { enabled.value = it }
            assertEquals(0, staged.listFiles()!!.size)
        }

    @Test
    fun aLogThatIsOffRecordsAndSamplesNothing() =
        runTest {
            enabled.value = false
            val log = newLog()
            var calls = 0
            log.sampler("bt") { emptyList<FieldEvent>().also { calls++ } }
            log.start(backgroundScope, statuses)
            advanceTimeBy(FieldLog.FLUSH_EVERY_MS + 1)
            log.stop()

            assertEquals(0, calls)
            assertEquals(emptyList<String>(), log.lines())
        }

    @Test
    fun aLogSwitchedOffKeepsNothingAndDeletesWhatItHad() =
        runTest {
            val log = newLog()
            log.record(FieldEvent.MeshOff)
            log.flush()
            assertEquals(1, log.lines().size)

            log.setEnabled(false) { enabled.value = it }
            log.record(FieldEvent.MeshOff)

            assertEquals(emptyList<String>(), log.lines())
        }

    @Test
    fun aRelinkCarriesHowLongThePeerWasWithoutALink() =
        runTest {
            val peer = "abcdefghijklmnopqrstuvwxyz"
            val log = newLog()
            log.record(FieldEvent.BtUp(peer, dialMs = 900, sighted = true, links = 1))
            log.record(FieldEvent.BtDown(peer, BtDownReason.EOF, lifetimeMs = 5_000, links = 0))
            now += 42_000
            log.record(FieldEvent.BtUp(peer, dialMs = 700, sighted = true, links = 1))

            val ups = log.lines().filter { it.contains("bt_up") }
            assertTrue(ups.first(), !ups.first().contains("relink"))
            assertTrue(ups.last(), ups.last().contains("\"relink\":42000"))
        }

    @Test
    fun aRestartedProcessKeepsTheSamePseudonymsAndAClearChangesThem() =
        runTest {
            val peer = "abcdefghijklmnopqrstuvwxyz"
            val up = FieldEvent.BtUp(peer, dialMs = null, sighted = true, links = 1)

            fun String.pseudonym() = Regex(""""peer":"(p[0-9a-f]{8})"""").find(this)!!.groupValues[1]
            val first =
                newLog()
                    .apply { record(up) }
                    .lines()
                    .single()
                    .pseudonym()
            val again =
                newLog()
                    .apply { record(up) }
                    .lines()
                    .last()
                    .pseudonym()
            assertEquals(first, again)

            val log = newLog()
            log.clear()
            log.record(up)
            val cleared = log.lines().single()
            assertTrue(cleared, cleared.pseudonym() != first)
            assertTrue(cleared, !cleared.contains(peer))
        }
}
