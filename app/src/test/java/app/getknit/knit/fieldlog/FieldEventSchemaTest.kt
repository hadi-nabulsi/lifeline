package app.getknit.knit.fieldlog

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.lora.LoraGatewayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field log's line format is a schema other people's analysis reads (`docs/FIELD_LOG.md`,
 * `scripts/fieldlog.py`): every event kind has its golden line here, so a renamed field or kind fails a test
 * rather than a contributor's merge. A new kind adds its line.
 */
class FieldEventSchemaTest {
    private val names = Pseudonyms(ByteArray(Pseudonyms.SALT_BYTES))

    private fun line(event: FieldEvent) = FieldLine.render(42, event, names)

    @Test
    fun everyKindKeepsItsLine() {
        assertEquals("""{"t":42,"e":"mesh_on","vc":31}""", line(FieldEvent.MeshOn(31)))
        assertEquals("""{"t":42,"e":"mesh_off"}""", line(FieldEvent.MeshOff))
        assertEquals(
            """{"t":42,"e":"power","screen":true,"charging":false,"low":true}""",
            line(FieldEvent.Power(screenOn = true, charging = false, batteryLow = true)),
        )
        assertEquals(
            """{"t":42,"e":"radio","radio":"Bluetooth","health":"Healthy","linked":2,"nearby":3,"contended":false,"held":false}""",
            line(FieldEvent.Radio(TransportKind.Bluetooth, TransportHealth.Healthy, 2, 3, contended = false, initiatorHeld = false)),
        )
        assertEquals(
            """{"t":42,"e":"counters","relayed":4,"btConnectFails":1}""",
            line(FieldEvent.Counters(linkedMapOf("relayed" to 4L, "btConnectFails" to 1L))),
        )
        assertEquals(
            """{"t":42,"e":"latency","dir":"ACK","plane":"Nearby","n":7,"p50":900,"p90":4000,"max":61000}""",
            line(FieldEvent.Latency(LatencyDirection.ACK, DeliveryPlane.Nearby, 7, 900, 4_000, 61_000)),
        )
    }

    @Test
    fun theBluetoothLinkKindsKeepTheirLines() {
        val peer = "abcdefghijklmnopqrstuvwxyz"
        val p = names.of(peer)
        assertEquals(
            """{"t":42,"e":"bt_up","peer":"$p","dialed":true,"dial":850,"sighted":true,"links":2,"relink":61000}""",
            line(FieldEvent.BtUp(peer, dialMs = 850, sighted = true, links = 2, relinkMs = 61_000)),
        )
        assertEquals(
            """{"t":42,"e":"bt_up","peer":"$p","dialed":false,"sighted":false,"links":1}""",
            line(FieldEvent.BtUp(peer, dialMs = null, sighted = false, links = 1)),
        )
        assertEquals(
            """{"t":42,"e":"bt_down","peer":"$p","reason":"EOF","life":300000,"links":0,"phy":"CODED","rssi":-88}""",
            line(FieldEvent.BtDown(peer, BtDownReason.EOF, lifetimeMs = 300_000, links = 0, phy = LinkPhy.CODED, rssi = -88)),
        )
        assertEquals(
            """{"t":42,"e":"bt_fail","peer":"$p","reason":"TIMEOUT","dur":12000,"streak":3}""",
            line(FieldEvent.BtFail(peer, ConnectFailReason.TIMEOUT, durationMs = 12_000, streak = 3)),
        )
    }

    @Test
    fun theSampleAndOtherRadioKindsKeepTheirLines() {
        val peer = "abcdefghijklmnopqrstuvwxyz"
        val p = names.of(peer)
        assertEquals(
            """{"t":42,"e":"bt_phy","peer":"$p","coded":true,"rssi":-84}""",
            line(FieldEvent.BtPhy(peer, toCoded = true, rssi = -84)),
        )
        assertEquals(
            """{"t":42,"e":"bt_link","peer":"$p","age":60000,"tx":true,"rx":false,"phy":"TWO_M","rssi":-61,""" +
                """"interval":15000,"mtu":517,"earned":true}""",
            line(FieldEvent.BtLink(peer, 60_000, feeding = true, receiving = false, LinkPhy.TWO_M, -61, 15_000, 517, earned = true)),
        )
        assertEquals(
            """{"t":42,"e":"bt_link","peer":"$p","age":5,"tx":false,"rx":false}""",
            line(FieldEvent.BtLink(peer, 5, feeding = false, receiving = false)),
        )
        assertEquals(
            """{"t":42,"e":"bt_peer","peer":"$p","linked":false,"rssi":-79,"rssi1m":-91,"rssiCoded":-91,"codedLag":12000}""",
            line(FieldEvent.BtPeer(peer, linked = false, rssi = -79, rssi1m = -91, rssiCoded = -91, codedLagMs = 12_000)),
        )
        assertEquals(
            """{"t":42,"e":"bt_scan","links":2,"le":3,"sighted":5,"hunting":false,"coded":"OFF"}""",
            line(FieldEvent.BtScan(links = 2, leLinks = 3, sighted = 5, hunting = false, codedMode = CodedPhyMode.OFF)),
        )
        assertEquals(
            """{"t":42,"e":"bt_orphan","peer":"$p","linked":true,"armed":true,"tries":2,"cleared":false,"age":181000}""",
            line(FieldEvent.BtOrphan(peer, linked = true, armed = true, tries = 2, cleared = false, ageMs = 181_000)),
        )
        assertEquals(
            """{"t":42,"e":"nan_up","peer":"$p","initiator":true,"links":1}""",
            line(FieldEvent.NanUp(peer, initiator = true, links = 1)),
        )
        assertEquals(
            """{"t":42,"e":"nan_down","peer":"$p","initiator":false,"life":4200,"clean":true}""",
            line(FieldEvent.NanDown(peer, initiator = false, lifetimeMs = 4_200, clean = true)),
        )
        assertEquals("""{"t":42,"e":"nan_cycle"}""", line(FieldEvent.NanCycle))
        assertEquals(
            """{"t":42,"e":"lora","role":"ACTIVE","snr10":-75,"rssi":-118,"queued":2,"heard":9,"boards":3,""" +
                """"airUsed":4100,"airBudget":36000}""",
            line(FieldEvent.Lora(LoraGatewayPolicy.Role.ACTIVE, -75, -118, 2, 9, 3, 4_100, 36_000)),
        )
    }

    @Test
    fun aPeerIsWrittenAsItsPseudonymNeverItsNodeId() {
        val nodeId = "abcdefghijklmnopqrstuvwxyz"

        val rendered = line(FieldEvent.BtUp(nodeId, dialMs = null, sighted = true, links = 1))

        assertTrue(rendered, Regex(""""peer":"p[0-9a-f]{8}"""").containsMatchIn(rendered))
        assertTrue(rendered, !rendered.contains(nodeId))
    }

    @Test
    fun aFieldNameCannotBreakOutOfItsQuotes() {
        assertEquals("""{"t":42,"e":"counters","xy":1}""", line(FieldEvent.Counters(mapOf("x\",\"y" to 1L))))
    }
}
