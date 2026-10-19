package app.getknit.knit.mesh.lora

import app.getknit.knit.fieldlog.FieldEvent
import kotlin.math.roundToInt

/**
 * The field log's LoRa sample (ADR 2026-10.a239): nothing while no board's link is ready, else one
 * [FieldEvent.Lora]. Lives beside [LinkState], which the field log cannot name, and leaves the board's identity out.
 */
internal fun LoraStatus.fieldSample(): List<FieldEvent> {
    if (state !is LinkState.Ready) return emptyList()
    return listOf(
        FieldEvent.Lora(
            role = role,
            snrTenths = lastSnr?.let { (it * TENTHS).roundToInt() },
            rssi = lastRssi,
            queued = queued,
            heard = heard,
            boardsHeard = boardsHeard,
            airUsedMs = airtime?.liveUsedMs,
            airBudgetMs = airtime?.liveBudgetMs,
        ),
    )
}

private const val TENTHS = 10
