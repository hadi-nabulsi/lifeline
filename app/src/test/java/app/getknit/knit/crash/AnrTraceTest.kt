package app.getknit.knit.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrTraceTest {
    @Test
    fun `opens with the subject in the exception's place`() {
        val body = AnrTrace.condense(SAMPLE_DUMP, description = "ignored when the dump has a subject")
        assertEquals(
            "ApplicationNotResponding: executing service app.getknit.knit/.mesh.MeshService",
            body.lineSequence().first(),
        )
    }

    @Test
    fun `puts the main thread first whatever order the dump has`() {
        val body = AnrTrace.condense(SAMPLE_DUMP, description = null)
        val threadHeaders = body.lines().filter { it.startsWith("\"") }
        assertEquals(listOf("\"main\" prio=5 tid=1 Native", "\"Signal Catcher\" daemon prio=10 tid=6 Runnable"), threadHeaders)
        val firstFrame = body.lines().first { it.trimStart().startsWith("at ") }
        assertEquals("  at android.os.MessageQueue.nativePollOnce(Native method)", firstFrame)
    }

    @Test
    fun `keeps the subject, memory, frozen and process lines and drops runtime noise and other apps`() {
        val body = AnrTrace.condense(SAMPLE_DUMP, description = null)
        assertTrue(body.contains("RssHwmKb: 254880"))
        assertTrue(body.contains("Frozen: true"))
        assertTrue(body.contains("----- pid 12345 at 2026-10-06 21:00:01.234+0000 -----"))
        assertTrue(body.contains("DALVIK THREADS (2):"))
        assertFalse("ART statistics are noise", body.contains("suspend all histogram"))
        assertFalse("another app's ANR is not ours to copy", body.contains("com.example.other"))
        assertFalse("the build fingerprint is already in the header", body.contains("Build fingerprint"))
    }

    @Test
    fun `keeps the waiting channels, which show a frozen process`() {
        val body = AnrTrace.condense(SAMPLE_DUMP, description = null)
        assertTrue(body.contains("----- Waiting Channels: pid 12345 at 2026-10-06 21:00:01.300+0000 -----"))
        assertTrue(body.contains("sysTid=12345     do_freezer_trap"))
        assertTrue(body.trimEnd().endsWith("----- end 12345 -----"))
    }

    @Test
    fun `collapses the install directory and other on-device paths in native frames`() {
        val body = AnrTrace.condense(SAMPLE_DUMP, description = null)
        assertTrue(body.contains("native: #01 pc 00000000001c2e10  [app]/base.apk!libsqlcipher.so (sqlite3_step+20)"))
        assertTrue(body.contains("native: #02 pc 0000000000003a10  [path] (???)"))
        assertTrue("system libraries stay readable", body.contains("/apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8)"))
        assertFalse(body.contains("~~"))
        assertFalse(body.contains("/data/user"))
    }

    @Test
    fun `falls back to the description when the dump is gone`() {
        val body = AnrTrace.condense(null, description = "Input dispatching timed out")
        assertEquals(
            "ApplicationNotResponding: Input dispatching timed out\n(the system kept no thread dump for this ANR)\n",
            body,
        )
    }

    @Test
    fun `a dump with no subject line takes the description`() {
        val body = AnrTrace.condense(SAMPLE_DUMP.lines().drop(1).joinToString("\n"), description = "executing service x")
        assertEquals("ApplicationNotResponding: executing service x", body.lineSequence().first())
    }

    @Test
    fun `survives the redactor with every frame intact`() {
        val body = CrashRedactor.redact(AnrTrace.condense(SAMPLE_DUMP, description = null))
        assertTrue(body.contains("native: #00 pc 00000000000a6b48  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8)"))
        assertTrue(body.contains("  at android.os.Looper.loop(Looper.java:317)"))
    }

    @Test
    fun `a process held frozen has no threads, and keeps why and its first waiting channels`() {
        val body = AnrTrace.condense(FROZEN_DUMP, description = null)
        assertEquals(
            """
            ApplicationNotResponding: Input dispatching timed out (f6aac8e app.getknit.knit/app.getknit.knit.MainActivity is not responding.)
            RssHwmKb: 416636
            VmSwapKb: 22108
            ----- dumping pid: 4316 at 105973
            libdebuggerd_client: failed to read status response from tombstoned: timeout reached?

            ----- Waiting Channels: pid 4316 at 2026-10-08 15:32:21.552646166-0700 -----
            sysTid=4316      do_freezer_trap
            sysTid=4318      do_freezer_trap
            ----- end 4316 -----

            """.trimIndent(),
            body,
        )
    }

    private companion object {
        /**
         * An emulator's (API 36) `ApplicationExitInfo` dump of an input ANR in a process held by `am freeze --sticky`,
         * cut down: debuggerd cannot reach a frozen process, so there is no thread section, and the platform
         * retries the waiting channels once.
         */
        val FROZEN_DUMP =
            """
            Subject: Input dispatching timed out (f6aac8e app.getknit.knit/app.getknit.knit.MainActivity is not responding.)
            RssHwmKb: 416636
            VmSwapKb: 22108


            --- CriticalEventLog ---
            capacity: 20
            events <
              timestamp_ms: 1791498679057
              install_packages <
              >
            >
            timestamp_ms: 1791498741552
            window_ms: 300000

            ----- dumping pid: 4316 at 105973
            libdebuggerd_client: failed to read status response from tombstoned: timeout reached?

            ----- Waiting Channels: pid 4316 at 2026-10-08 15:32:21.552646166-0700 -----
            Cmd line: app.getknit.knit

            sysTid=4316      do_freezer_trap
            sysTid=4318      do_freezer_trap

            ----- end 4316 -----

            libdebuggerd_client: failed to read status response from tombstoned: Try again

            ----- Waiting Channels: pid 4316 at 2026-10-08 15:32:31.568051066-0700 -----
            Cmd line: app.getknit.knit

            sysTid=4316      do_freezer_trap

            ----- end 4316 -----
            """.trimIndent()

        /** The shape of an Android 14+ ANR dump, threads out of order, with an app frame from the install dir. */
        val SAMPLE_DUMP =
            """
            Subject: executing service app.getknit.knit/.mesh.MeshService
            RssHwmKb: 254880
            RssKb: 201234
            VmSwapKb: 10240
            Frozen: true

            --- CriticalEventLog ---
            capacity: 20
            events <
              anr <
                process: "com.example.other"
              >
            >

            ----- pid 12345 at 2026-10-06 21:00:01.234+0000 -----
            Cmd line: app.getknit.knit
            Build fingerprint: 'realme/RMX3938/RE6095:16/AP3A/1:user/release-keys'
            ABI: 'arm64'
            Build type: optimized
            suspend all histogram:	Sum: 1.2ms 99% C.I. 2us-200us Avg: 20us Max: 300us
            DALVIK THREADS (2):
            "Signal Catcher" daemon prio=10 tid=6 Runnable
              | group="system" sCount=0 ucsCount=0 flags=0 obj=0x12c80250 self=0xb400007a2c4f6000
              native: #00 pc 0000000000561f4c  /apex/com.android.art/lib64/libart.so (art::DumpNativeStack+108)
              (no managed stack frames)

            "main" prio=5 tid=1 Native
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72f0f0a8 self=0xb400007a2c4f2000
              | sysTid=12345 nice=-10 cgrp=top-app sched=0/0 handle=0x7b3c2a4f58
              native: #00 pc 00000000000a6b48  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8)
              native: #01 pc 00000000001c2e10  /data/app/~~Ab3dEf==/app.getknit.knit-Gh5iJk==/base.apk!libsqlcipher.so (sqlite3_step+20)
              native: #02 pc 0000000000003a10  /data/user/0/app.getknit.knit/files/x.so (???)
              at android.os.MessageQueue.nativePollOnce(Native method)
              at android.os.MessageQueue.next(MessageQueue.java:339)
              at android.os.Looper.loop(Looper.java:317)

            ----- end 12345 -----

            ----- Waiting Channels: pid 12345 at 2026-10-06 21:00:01.300+0000 -----
            Cmd line: app.getknit.knit

            sysTid=12345     do_freezer_trap
            sysTid=12350     do_freezer_trap

            ----- end 12345 -----

            ----- pid 999 at 2026-10-06 21:00:02.000+0000 -----
            Cmd line: com.example.other
            ----- end 999 -----
            """.trimIndent()
    }
}
