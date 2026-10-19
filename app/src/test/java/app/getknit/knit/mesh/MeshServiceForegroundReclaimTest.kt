package app.getknit.knit.mesh

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.moderation.MlTextModerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * A start delivered to a *running* [MeshService] re-claims the foreground state (ADR 2026-09.f69x).
 *
 * The system can strip that state from a live service without telling it — a background-restricted app
 * loses it the moment it leaves the screen (`ActiveServices.stopAllForegroundServicesLocked`, a
 * `stopForeground` with no callback) — and the next `startForegroundService` into the demoted instance arms
 * the `startForeground()` deadline against `onStartCommand`. Before the fix that method returned without ever
 * calling it, and the process died 30 s later with `ForegroundServiceDidNotStartInTimeException` (Play,
 * 2.5.1, Android 15). `ShadowService` records both halves: `stopForeground` from the test is the app-side
 * shape of the system's silent demotion, and `isForegroundStopped` flips back only on a `startForeground`.
 *
 * The graph is the same set of fakes `GraphlessProcessTest` starts; the refusal arm uses the shadow's
 * `setThrowInStartForeground` because Robolectric never throws the real
 * `ForegroundServiceStartNotAllowedException` on its own.
 */
@RunWith(AndroidJUnit4::class)
class MeshServiceForegroundReclaimTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mesh = FakeMeshController()
    private lateinit var controller: ServiceController<MeshService>

    @Before
    fun setUp() {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.meshEnabled } returns flowOf(true)
        every { settings.meshPausedUntil } returns flowOf(null)
        startKoin {
            androidContext(app)
            modules(
                module {
                    single<MeshController> { mesh }
                    single { PowerMonitor(app, PowerStateSource()) }
                    single { settings }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                    // warmModelOnFirstPeer's lazy inject would throw inside an unhandled launch the day a peer
                    // came into range while a test ran; the Unconfined scope reports that at whichever runTest
                    // the fork runs next (CI: ImageScreeningServiceTest).
                    single { mockk<MlTextModerator>(relaxed = true) }
                },
            )
        }
        controller = Robolectric.buildService(MeshService::class.java).create()
    }

    @After
    fun tearDown() = stopKoin()

    private fun plainStart() = Intent(app, MeshService::class.java)

    @Test
    fun `a plain start into a demoted service takes the foreground state back`() {
        val service = controller.get()
        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        // The system's demotion, as the app would see it if it were ever told: state gone, notification kept.
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        assertTrue(shadow.isForegroundStopped)

        assertEquals(Service.START_STICKY, service.onStartCommand(plainStart(), 0, 2))

        // The one thing the crash needs to not happen: startForeground() was called again on this start.
        assertFalse(shadow.isForegroundStopped)
        assertNotNull(shadow.lastForegroundNotification)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals(1, mesh.startCount)
        controller.destroy()
        assertEquals(1, mesh.stopCount)
    }

    @Test
    fun `the claim is idempotent across starts`() {
        val service = controller.get()
        val shadow = shadowOf(service)
        // Every start claims the foreground again (the f69x re-claim); the notification it carries is a bare
        // row cancelled at once (see [MeshService.postForeground]), so there is no per-state text to tell the
        // claims apart — the observable is that the state is held after each one.
        assertEquals(Service.START_STICKY, service.onStartCommand(plainStart(), 0, 2))
        assertFalse(shadow.isForegroundStopped)
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(1, mesh.startCount)
        controller.destroy()
    }

    @Test
    fun `a refused re-claim stops the service but still brings the mesh down`() {
        val service = controller.get()
        val shadow = shadowOf(service)
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        shadow.setThrowInStartForeground(IllegalStateException("not allowed"))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(plainStart(), 0, 2))

        assertTrue(shadow.isStoppedBySelf)
        // The mesh was started by onCreate, so onDestroy must stop it — the refusal must not be mistaken for
        // a stillbirth, whose onDestroy skips the graph on purpose.
        controller.destroy()
        assertEquals(1, mesh.stopCount)
    }

    @Test
    fun `the claim carries the tiered type, connectedDevice alone on this sdk`() {
        // robolectric.properties pins sdk=36: the nearby-devices tier, where a runtime `location` type would be
        // a SecurityException without FOREGROUND_SERVICE_LOCATION. The 29-32 half of the mapping is pinned by
        // MeshForegroundServiceTypesTest; ShadowService records whatever bits startForeground was handed.
        val service = controller.get()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        service.onStartCommand(plainStart(), 0, 2)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        controller.destroy()
    }

    @Test
    fun `Stop does not re-claim`() {
        val service = controller.get()
        val shadow = shadowOf(service)
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)

        val stop = Intent(app, MeshService::class.java).setAction(MeshService.ACTION_STOP)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))

        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
        controller.destroy()
    }
}
