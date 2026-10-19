package app.getknit.knit.mesh.power

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Knit's own UI is on screen: at least one of its activities started, as the process lifecycle counts them
 * (`KnitApplication` observes `ProcessLifecycleOwner` with this). Together with [PowerState.interactive] it says the
 * person is using Knit now, not merely using the phone, which is the case the Bluetooth scan hunts harder for (ADR
 * 2026-10.yvn6, the 2026-10-04 amendment).
 *
 * Process-wide, not `MainActivity`'s own `onStart`/`onStop` (#170). When one `MainActivity` replaces another — a task
 * clear, or an app update landing while Knit is on top, which makes SystemUI relaunch it — the platform starts the new
 * instance before it stops the old one, so the old one's stop landed last and left this false on a lit Knit screen
 * until the next Home and back. A count of started activities cannot be ordered wrong, a configuration change does not
 * blink it (the process `ON_STOP` waits 700 ms for the next activity), and a Knit screen over `MainActivity` counts.
 *
 * Kept apart from [PowerStateSource] on purpose: the Wi-Fi Aware transport heals on every [PowerState] change, and
 * opening the app is not a reason to re-arm NAN.
 */
class AppVisibility : DefaultLifecycleObserver {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _visible.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _visible.value = false
    }
}
