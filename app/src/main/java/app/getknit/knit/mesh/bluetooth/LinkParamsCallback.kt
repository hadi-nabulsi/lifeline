package app.getknit.knit.mesh.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import androidx.annotation.Keep

/** A link's connection parameters as the controller reports them: the interval, the peripheral latency, the timeout. */
internal data class LinkParams(
    val intervalUs: Int,
    val latency: Int,
    val timeoutMs: Int,
)

/**
 * A GATT callback that hears the link's connection parameters change (#120). The framework reports them through
 * `onConnectionUpdated`, hidden on [BluetoothGattCallback] through compileSdk 37.1, so the method is declared here
 * once and not as an `override`: the framework calls it virtually, and [Keep] stops R8 dropping a method nothing
 * here calls. The 37.2 stub makes it public, and that bump turns this declaration (and `BleDoorbell.Client`'s, #126)
 * into an `override`. Subclasses read [onLinkParams] in milliseconds instead of the HCI units.
 */
internal abstract class LinkParamsCallback : BluetoothGattCallback() {
    /** The link's parameters changed, or an update failed ([ok] false, [status] the controller's); any thread. */
    protected abstract fun onLinkParams(
        params: LinkParams,
        ok: Boolean,
        status: Int,
    )

    /** Interval in 1.25 ms units, timeout in 10 ms units. See the class KDoc for why this is not an `override`. */
    @Keep
    @Suppress("unused", "UNUSED_PARAMETER", "UnusedParameter")
    fun onConnectionUpdated(
        g: BluetoothGatt,
        interval: Int,
        latency: Int,
        timeout: Int,
        status: Int,
    ) {
        val params = LinkParams(interval * INTERVAL_UNIT_US, latency, timeout * TIMEOUT_UNIT_MS)
        onLinkParams(params, ok = status == BluetoothGatt.GATT_SUCCESS, status = status)
    }

    private companion object {
        // The HCI units `onConnectionUpdated` reports in.
        const val INTERVAL_UNIT_US = 1_250
        const val TIMEOUT_UNIT_MS = 10
    }
}
