package app.getknit.knit.mesh.power

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration

/**
 * Knit is on screen while any of its activities is started, whatever order the platform runs two instances'
 * callbacks in (#170). Driven through the real `ProcessLifecycleOwner`, as `KnitApplication` wires it: a test of
 * the flag against the one activity's own hooks would pass and still read false on a lit screen.
 */
@RunWith(AndroidJUnit4::class)
class AppVisibilityTest {
    private val visibility = AppVisibility()
    private val seen = mutableListOf<Boolean>()
    private val collector = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun attach() {
        // Robolectric runs no androidx.startup provider, so attach the process lifecycle to this test's Application by
        // hand, as ProcessLifecycleInitializer does at a real start. Its init is internal, hence reflection; a rename
        // fails here, loudly.
        ProcessLifecycleOwner::class.java
            .getMethod("init\$lifecycle_process", Context::class.java)
            .invoke(null, ApplicationProvider.getApplicationContext<Application>())
        // The process lifecycle is a JVM static, shared with every test in this sandbox: start from no started activity.
        assertEquals(Lifecycle.State.CREATED, ProcessLifecycleOwner.get().lifecycle.currentState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibility)
        collector.launch { visibility.visible.collect { seen += it } }
    }

    @After
    fun detach() {
        collector.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibility)
    }

    /** Past the process lifecycle's 700 ms stop debounce. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun open(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()

    @Test
    fun aMainActivityReplacedByAnotherStaysVisible() {
        val old = open()
        settle()
        assertTrue(visibility.visible.value)

        // The order the lab Pixel 7's event log showed for a task clear and for SystemUI's relaunch after an update:
        // the old instance pauses, the new one starts and resumes, and only then does the old one stop.
        old.pause()
        val new = open()
        old.stop().destroy()
        settle()

        assertTrue("the old instance's late stop must not hide a started one", visibility.visible.value)
        assertEquals(listOf(false, true), seen)

        new.pause().stop().destroy()
        settle()
        assertFalse(visibility.visible.value)
    }

    @Test
    fun aScreenOverMainActivityKeepsKnitVisible() {
        val main = open()
        // The call screen opens over MainActivity: MainActivity stops, the call screen is started.
        main.pause()
        val call = open()
        main.stop()
        settle()
        assertTrue(visibility.visible.value)

        call.pause().stop().destroy()
        main.restart().start().resume()
        settle()
        assertTrue(visibility.visible.value)
        assertEquals(listOf(false, true), seen)

        main.pause().stop().destroy()
        settle()
    }

    @Test
    fun aRotationDoesNotBlink() {
        val main = open()
        settle()

        val landscape = Configuration(main.get().resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        main.configurationChange(landscape)
        settle()

        assertEquals(listOf(false, true), seen)
        main.pause().stop().destroy()
        settle()
    }

    @Test
    fun leavingKnitHidesIt() {
        val main = open()
        settle()

        main.pause().stop()
        assertTrue("the process stop waits out its debounce", visibility.visible.value)
        settle()

        assertFalse(visibility.visible.value)
        assertEquals(listOf(false, true, false), seen)
        main.destroy()
    }
}
