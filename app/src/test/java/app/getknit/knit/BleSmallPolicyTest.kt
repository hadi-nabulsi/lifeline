package app.getknit.knit

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertisingSetCallback
import app.getknit.knit.mesh.bluetooth.BleSideChannel
import app.getknit.knit.mesh.bluetooth.BtAudioState
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.linkPhyOf
import app.getknit.knit.mesh.bluetooth.wear.WearStatusServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The small decisions the Bluetooth plane's Android classes hand to pure functions: the audio state that contends the
 * radio ([BtAudioState.of]), the side channel's page size and its answer to a slot's status ([BleSideChannel]), the PHY
 * an Android constant names ([linkPhyOf]), and the Wear status server's answer to a read ([WearStatusServer.readAnswer]).
 * Robolectric because the side channel's companion sits in a class that touches the framework.
 */
@RunWith(RobolectricTestRunner::class)
class BleSmallPolicyTest {
    // --- BtAudioState.of ---

    @Test
    fun audioStreamsWhileA2dpPlaysOrMusicPlaysToABluetoothOutput() {
        assertEquals(BtAudioState.Streaming, BtAudioState.of(playing = true, connected = true, btOutputPresent = false) { false })
        // The A2DP playing edge missed (the monitor started mid-stream): music to a Bluetooth output is streaming.
        assertEquals(BtAudioState.Streaming, BtAudioState.of(playing = false, connected = false, btOutputPresent = true) { true })
    }

    @Test
    fun aBluetoothOutputOrA2dpWithNoMusicIsConnectedElseIdle() {
        assertEquals(BtAudioState.Connected, BtAudioState.of(playing = false, connected = true, btOutputPresent = false) { true })
        assertEquals(BtAudioState.Connected, BtAudioState.of(playing = false, connected = false, btOutputPresent = true) { false })
        assertEquals(
            "music on the phone's speaker",
            BtAudioState.Idle,
            BtAudioState.of(playing = false, connected = false, btOutputPresent = false) { true },
        )
    }

    @Test
    fun musicIsAskedAboutOnlyWithABluetoothOutputAndNoA2dpPlaying() {
        var reads = 0
        BtAudioState.of(playing = true, connected = false, btOutputPresent = true) { reads++ > 0 }
        BtAudioState.of(playing = false, connected = true, btOutputPresent = false) { reads++ > 0 }
        assertEquals(0, reads)
    }

    // --- BleSideChannel sizing and slot status ---

    @Test
    fun aPageIsTheLargestAdvertLessItsHeaderUpToThePageSize() {
        assertEquals(BleSideChannel.PAGE_BYTES, BleSideChannel.pageFor(maxData = 1_650))
        assertEquals(200 - BleSideChannel.AD_OVERHEAD, BleSideChannel.pageFor(maxData = 200))
        assertEquals("a legacy-only controller: dark", -BleSideChannel.AD_OVERHEAD, BleSideChannel.pageFor(maxData = 0))
    }

    @Test
    fun eachSlotStatusHasItsOwnAnswer() {
        assertEquals(BleSideChannel.SlotReaction.None, BleSideChannel.slotReaction(AdvertisingSetCallback.ADVERTISE_SUCCESS))
        assertEquals(
            BleSideChannel.SlotReaction.Dark,
            BleSideChannel.slotReaction(AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED),
        )
        assertEquals(
            BleSideChannel.SlotReaction.HalvePage,
            BleSideChannel.slotReaction(AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE),
        )
        listOf(
            AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
            AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED,
            AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
            99,
        ).forEach { assertEquals("status $it", BleSideChannel.SlotReaction.SitOut, BleSideChannel.slotReaction(it)) }
    }

    // --- linkPhyOf ---

    @Test
    fun eachAndroidPhyConstantNamesItsLinkPhy() {
        assertEquals(LinkPhy.ONE_M, linkPhyOf(BluetoothDevice.PHY_LE_1M))
        assertEquals(LinkPhy.TWO_M, linkPhyOf(BluetoothDevice.PHY_LE_2M))
        assertEquals(LinkPhy.CODED, linkPhyOf(BluetoothDevice.PHY_LE_CODED))
        assertEquals(LinkPhy.UNKNOWN, linkPhyOf(0))
    }

    // --- WearStatusServer.readAnswer ---

    private val snapshot = ByteArray(20) { it.toByte() }

    @Test
    fun onlyABondedWatchReadsTheStatusCharacteristic() {
        assertEquals(
            BluetoothGatt.GATT_READ_NOT_PERMITTED to null,
            WearStatusServer.readAnswer(isStatus = false, bonded = true, offset = 0) { snapshot },
        )
        assertEquals(
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION to null,
            WearStatusServer.readAnswer(isStatus = true, bonded = false, offset = 0) { snapshot },
        )
    }

    @Test
    fun aReadGetsTheSnapshotFromItsOffset() {
        val (status, value) = WearStatusServer.readAnswer(isStatus = true, bonded = true, offset = 0) { snapshot }
        assertEquals(BluetoothGatt.GATT_SUCCESS, status)
        assertArrayEquals(snapshot, value)
        val (_, tail) = WearStatusServer.readAnswer(isStatus = true, bonded = true, offset = 15) { snapshot }
        assertArrayEquals(snapshot.copyOfRange(15, 20), tail)
        val (atEnd, empty) = WearStatusServer.readAnswer(isStatus = true, bonded = true, offset = 20) { snapshot }
        assertEquals("the end itself is a valid, empty read", BluetoothGatt.GATT_SUCCESS, atEnd)
        assertEquals(0, empty!!.size)
    }

    @Test
    fun pastTheEndOrWithNoMeshTheReadIsRefused() {
        assertEquals(
            BluetoothGatt.GATT_INVALID_OFFSET to null,
            WearStatusServer.readAnswer(isStatus = true, bonded = true, offset = 21) { snapshot },
        )
        val (status, value) = WearStatusServer.readAnswer(isStatus = true, bonded = true, offset = 0) { null }
        assertEquals(BluetoothGatt.GATT_FAILURE, status)
        assertNull(value)
    }

    @Test
    fun theSnapshotIsComputedOnlyForABondedStatusRead() {
        var reads = 0
        WearStatusServer.readAnswer(isStatus = false, bonded = true, offset = 0) { reads++.let { snapshot } }
        WearStatusServer.readAnswer(isStatus = true, bonded = false, offset = 0) { reads++.let { snapshot } }
        assertEquals(0, reads)
    }
}
