package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileFeed
import app.getknit.knit.mesh.FileProgress
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.protocol.BlobCancelContent
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHasItem
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobProgressContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The holder query (ADR 2026-10.6e5t): a neighbour whose link advertises [Protocol.CAP_BLOB_HAVE] is asked which blobs
 * it holds before anyone is asked for one, the best holder is asked alone, one that brings nothing is queried again
 * and then passed over, and the neighbours nobody can query keep ADR 2026-10.f7gg's blind round (pinned in
 * [BlobExchangeTest], whose fakes advertise nothing).
 */
@Suppress("LargeClass") // one query's contract, both ends of it
@OptIn(ExperimentalCoroutinesApi::class)
class BlobHolderQueryTest {
    /** In-memory, content-addressed [BlobStore]; [lying] answers a query as holding blobs it cannot serve. */
    private class Store(
        private val dir: File,
    ) : BlobStore {
        val blobs = ConcurrentHashMap<String, ByteArray>()
        val storingNow = ConcurrentHashMap.newKeySet<String>()
        val lying = ConcurrentHashMap<String, Long>()

        fun seed(bytes: ByteArray): String = sha256Hex(bytes).also { blobs[it] = bytes }

        override suspend fun has(hash: String) = blobs.containsKey(hash)

        override suspend fun fileFor(hash: String): File? = blobs[hash]?.let { b -> File(dir, "$hash.tx").apply { writeBytes(b) } }

        override suspend fun mimeFor(hash: String): String? = if (blobs.containsKey(hash)) "image/jpeg" else null

        override suspend fun saveIncoming(
            hash: String,
            mime: String,
            srcPath: String,
        ): File? {
            val bytes = File(srcPath).readBytes()
            if (sha256Hex(bytes) != hash) return null
            blobs[hash] = bytes
            return File(dir, "$hash.in").apply { writeBytes(bytes) }
        }

        override fun storing(hash: String): Boolean = hash in storingNow

        override suspend fun servable(hashes: List<String>): Map<String, Long> =
            hashes.mapNotNull { h -> (blobs[h]?.size?.toLong() ?: lying[h])?.let { h to it } }.toMap()
    }

    /** One node: its transport, store and exchange, a router that hands it the three blob types, and its counters. */
    private inner class Node(
        val id: String,
        scope: CoroutineScope,
        query: Boolean = true,
        answers: Boolean = true,
        val timers: CoroutineScope? = null,
        recheckMs: Long = BlobExchange.FETCHING_RECHECK_MS,
    ) {
        val transport = FakeLoopTransport(id).apply { if (answers) advertises = Protocol.CAP_BLOB_HAVE }
        val store = Store(Files.createTempDirectory("hq-$id").toFile())
        var now = 0L
        val partials = PartialBlobs(Files.createTempDirectory("hq-parts-$id").toFile(), minBytes = 1)
        val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()

        // Whom the conversation naming any blob here is addressed to (null: unknown, everyone a member).
        var to: Set<String>? = null

        // While set, this node leaves queries unanswered: an answer that comes in late is the test's to deliver.
        @Volatile var mute = false
        val exchange =
            BlobExchange(
                transport = transport,
                store = store,
                selfId = { id },
                onObtained = { _, _ -> },
                now = { now },
                partials = partials,
                holderQuery = BlobExchange.HolderQuery(enabled = query, fetchingRecheckMs = recheckMs),
                onEvent = { events += it },
                timers = { timers },
                addressees = { to },
            )

        // Every blob frame this node received: type, sender, payload.
        val heard = CopyOnWriteArrayList<Triple<String, String, RelayEnvelope>>()

        private val router =
            MeshRouter(transport, scope) { _, env, from, _ ->
                heard += Triple(env.type, from, env)
                when (env.type) {
                    FrameType.BLOB_REQ -> {
                        WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { exchange.onRequest(it.hash, from, it.offset) }
                    }

                    FrameType.BLOB_HAVE -> {
                        val query = WireCodec.decodePayload<BlobHaveContent>(env.payload)
                        if (answers && !mute && query != null) exchange.onHaveQuery(query.hashes, from, env.id)
                    }

                    FrameType.BLOB_HAS -> {
                        WireCodec.decodePayload<BlobHasContent>(env.payload)?.let { exchange.onHaveAnswer(it, from) }
                    }

                    FrameType.BLOB_CANCEL -> {
                        WireCodec.decodePayload<BlobCancelContent>(env.payload)?.let { exchange.onCancel(it.hash, from) }
                    }
                }
            }

        init {
            router.start()
            scope.launch {
                transport.incomingFiles.collect { exchange.onReceived(it.key, it.mime, it.path, it.resumedFrom, it.fromNodeId) }
            }
        }

        /** The asks for [hash] this node received, by sender. */
        fun asksFrom(hash: String): List<String> =
            heard
                .filter { (type, _, env) ->
                    type == FrameType.BLOB_REQ && WireCodec.decodePayload<BlobReqContent>(env.payload)?.hash == hash
                }.map { it.second }

        /** The cancels for [hash] this node received, by sender. */
        fun cancelsFrom(hash: String): List<String> =
            heard
                .filter { (type, _, env) ->
                    type == FrameType.BLOB_CANCEL && WireCodec.decodePayload<BlobCancelContent>(env.payload)?.hash == hash
                }.map { it.second }

        /** The queries this node received, by sender, each as the hashes it named. */
        fun queriesFrom(from: String): List<List<String>> =
            heard
                .filter { it.first == FrameType.BLOB_HAVE && it.second == from }
                .mapNotNull { WireCodec.decodePayload<BlobHaveContent>(it.third.payload)?.hashes }

        /** The answers this node received, by sender. */
        fun answersFrom(from: String): List<BlobHasContent> =
            heard
                .filter { it.first == FrameType.BLOB_HAS && it.second == from }
                .mapNotNull { WireCodec.decodePayload<BlobHasContent>(it.third.payload) }

        /** How many copies of [hash] this node served to [to]. */
        fun served(
            hash: String,
            to: String,
        ): Int = filesTo.count { it == "$to $hash" }

        val filesTo = CopyOnWriteArrayList<String>()
    }

    private fun picture(size: Int = 2_000) = Random(size).nextBytes(size)

    /** A live copy being fed, [etaMs] from its end. */
    private fun feeding(etaMs: Long) = FileFeed(etaMs = etaMs, live = true, measured = true)

    /** Records every file [holder] streams to anyone, as "to hash". */
    private fun CoroutineScope.recordServes(
        holder: Node,
        vararg to: Node,
    ) {
        for (n in to) launch { n.transport.incomingFiles.collect { if (it.fromNodeId == holder.id) holder.filesTo += "${n.id} ${it.key}" } }
    }

    @Test
    fun onlyTheNeighbourThatSaysItHoldsTheBlobIsAsked() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            backgroundScope.recordServes(b, a)
            val hash = b.store.seed(picture())

            a.exchange.want(hash, author = "c")

            assertEquals("each capable neighbour is queried once", listOf(listOf(hash)), b.queriesFrom("a"))
            assertEquals(listOf(listOf(hash)), c.queriesFrom("a"))
            assertEquals("the holder alone is asked — not the author, who said it lacks it", listOf("a"), b.asksFrom(hash))
            assertTrue(c.asksFrom(hash).isEmpty())
            assertEquals(1, b.served(hash, "a"))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.Fetch })
        }

    @Test
    fun aQueryServesNothingPullsNothingAndStampsNoMemo() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val held = b.store.seed(picture())
            val absent = sha256Hex(picture(3_000))

            b.exchange.onHaveQuery(listOf(held, absent), "a", "q1")

            assertTrue("a query serves nothing", b.transport.filesSent.isEmpty())
            assertTrue("and pulls nothing: no ask goes out for the hash it lacks", a.asksFrom(absent).isEmpty())
            val answer = a.answersFrom("b").single()
            assertEquals("q1", answer.re)
            assertEquals(listOf(BlobHasItem.HAVE, BlobHasItem.NONE), answer.items.map { it.state })
            b.exchange.onRequest(held, "a")
            assertEquals("no memo was stamped: a fresh ask is served at once", 1, b.transport.filesSent.size)
            b.exchange.onHaveQuery(listOf(held), "a", "q2")
            val after =
                a
                    .answersFrom("b")
                    .last()
                    .items
                    .single()
            assertEquals("and now the copy is enqueued, the answer says so", BlobHasItem.SENDING, after.state)
        }

    @Test
    fun theAnswerNamesEachStateTheSizeAndTheLoad() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val have = b.store.seed(picture(1_000))
            val sending = b.store.seed(picture(1_200))
            val arriving = sha256Hex(picture(1_300))
            val storing = sha256Hex(picture(1_400))
            val none = sha256Hex(picture(1_500))
            b.transport.inFlight += "a" to sending
            b.transport.arriving += arriving
            b.store.storingNow += storing
            b.transport.feeding = 3

            b.exchange.onHaveQuery(listOf(have, sending, arriving, storing, none), "a", "q1")

            val answer = a.answersFrom("b").single()
            assertEquals(
                listOf(
                    BlobHasItem(have, BlobHasItem.HAVE, 1_000L),
                    BlobHasItem(sending, BlobHasItem.SENDING, 1_200L),
                    BlobHasItem(arriving, BlobHasItem.FETCHING),
                    BlobHasItem(storing, BlobHasItem.FETCHING),
                    BlobHasItem(none, BlobHasItem.NONE),
                ),
                answer.items,
            )
            assertEquals(3, answer.load)
        }

    @Test
    fun aQueryIsCappedAndAFloodIsAnsweredWithinItsBudget() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val many = (1..40).map { sha256Hex(picture(100 + it)) }

            b.exchange.onHaveQuery(many, "a", "q0")
            assertEquals(
                "the first 32 hashes are answered, the rest ignored",
                32,
                a
                    .answersFrom("b")
                    .single()
                    .items.size,
            )
            repeat(30) { b.exchange.onHaveQuery(listOf(many[0]), "a", "q${it + 1}") }

            assertEquals("a burst of 16, then nothing until the budget refills", 16, a.answersFrom("b").size)
            assertEquals(15, b.events.count { it == BlobExchange.BlobEvent.QueryRefused })
            b.now += 2_000
            b.exchange.onHaveQuery(listOf(many[0]), "a", "q99")
            assertEquals(17, a.answersFrom("b").size)
        }

    @Test
    fun queriesAreBatchedOneFramePerNeighbourAtMostThirtyTwoHashes() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val wanted = (1..40).map { sha256Hex(picture(200 + it)) to null }

            a.exchange.wantAll(wanted)

            assertEquals(listOf(32, 8), b.queriesFrom("a").map { it.size })
            assertEquals(wanted.map { it.first }.toSet(), b.queriesFrom("a").flatten().toSet())
        }

    @Test
    fun timersArmedTogetherWakeTogetherAndQueryInOneFramePerNeighbour() =
        runTest(UnconfinedTestDispatcher()) {
            // Forty hashes b is fetching itself: each arms its recheck at once, and the rechecks must not come due one
            // hash at a time — forty one-hash queries would trip b's own flood budget (the bluetooth-reviewer's finding).
            val a = Node("a", backgroundScope, timers = backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val wanted = (1..40).map { sha256Hex(picture(300 + it)) }
            b.transport.arriving += wanted

            a.exchange.wantAll(wanted.map { it to null })
            assertEquals(listOf(32, 8), b.queriesFrom("a").map { it.size })
            a.now += BlobExchange.FETCHING_RECHECK_MS
            advanceTimeBy(BlobExchange.FETCHING_RECHECK_MS + 1)
            runCurrent()

            assertEquals("the rechecks woke as one batch", listOf(32, 8, 32, 8), b.queriesFrom("a").map { it.size })
            assertTrue(b.events.none { it == BlobExchange.BlobEvent.QueryRefused })
            a.now += BlobExchange.CYCLE_MS
            a.exchange.reask() // the tick opens the next cycle: every hash, one frame per neighbour again
            assertEquals(listOf(32, 8, 32, 8, 32, 8), b.queriesFrom("a").map { it.size })
            assertTrue(b.events.none { it == BlobExchange.BlobEvent.QueryRefused })
        }

    @Test
    fun theBestPlaneIsAskedAndALargeBlobArmsTheFastLink() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            listOf(b, c, d).forEach { a.transport.connect(it.transport) }
            val big = picture(140_000)
            val hash = b.store.seed(big)
            c.store.seed(big)
            d.store.seed(big)
            a.transport.bulkRoutes["c"] = BulkRoute.Reachable
            a.transport.slowLinks += "b"

            a.exchange.want(hash)

            assertEquals("a holder Wi-Fi Aware can reach, for a blob big enough to raise it", listOf("a"), c.asksFrom(hash))
            assertEquals(listOf("c"), a.transport.bulkArmed)
            assertTrue(b.asksFrom(hash).isEmpty())
            assertTrue(d.asksFrom(hash).isEmpty())
        }

    @Test
    fun aSmallBlobIsNotArmedAndTheLessLoadedLinkWins() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            listOf(b, c, d).forEach { a.transport.connect(it.transport) }
            val small = picture(4_000)
            val hash = b.store.seed(small)
            c.store.seed(small)
            d.store.seed(small)
            a.transport.bulkRoutes["b"] = BulkRoute.Reachable // reachable, but a small blob never raises the link
            b.transport.feeding = 2
            c.transport.feeding = 1
            d.transport.feeding = 0
            a.transport.arrivingFrom += "d" // d streams something else to us: its link is busy

            a.exchange.want(hash)

            assertTrue("nothing armed for a small blob", a.transport.bulkArmed.isEmpty())
            assertEquals("the free link with the lighter load", listOf("a"), c.asksFrom(hash))
            assertTrue(b.asksFrom(hash).isEmpty())
            assertTrue(d.asksFrom(hash).isEmpty())
        }

    @Test
    fun theChoiceWaitsForEveryAnswerUnlessTheBestRidesALiveFastLink() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false) // capable on the wire, but never answers
            c.transport.advertises = Protocol.CAP_BLOB_HAVE
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = b.store.seed(picture())

            a.exchange.want(hash)
            assertTrue("c's answer is still due: nobody is asked yet", b.asksFrom(hash).isEmpty())
            a.now += BlobExchange.QUERY_WAIT_MS
            a.exchange.reask()
            assertEquals("past the wait, the holder that answered is asked", listOf("a"), b.asksFrom(hash))
            assertTrue("and the silent one is not asked blind past a confirmed holder", c.asksFrom(hash).isEmpty())

            val a2 = Node("a2", backgroundScope)
            val b2 = Node("b2", backgroundScope)
            val c2 = Node("c2", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a2.transport.connect(b2.transport)
            a2.transport.connect(c2.transport)
            a2.transport.bulkRoutes["b2"] = BulkRoute.Linked
            val hash2 = b2.store.seed(picture(2_500))
            a2.exchange.want(hash2)
            assertEquals("a holder on a live fast link is asked at once", listOf("a2"), b2.asksFrom(hash2))

            // ADR 2026-10.g24x: the frame's author is the holder the rest would only relay for — its HAVE decides.
            val a3 = Node("a3", backgroundScope)
            val b3 = Node("b3", backgroundScope)
            val c3 = Node("c3", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a3.transport.connect(b3.transport)
            a3.transport.connect(c3.transport)
            val hash3 = b3.store.seed(picture(2_700))
            a3.exchange.want(hash3, author = "b3")
            assertEquals("the linked author that says it holds it is asked at once", listOf("a3"), b3.asksFrom(hash3))
            assertTrue(a3.store.has(hash3))

            // An author on a Coded link waits for a neighbour still to answer that may hold it on a better one.
            val a4 = Node("a4", backgroundScope)
            val b4 = Node("b4", backgroundScope)
            val c4 = Node("c4", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a4.transport.connect(b4.transport)
            a4.transport.connect(c4.transport)
            a4.transport.slowLinks += "b4"
            val hash4 = b4.store.seed(picture(2_800))
            a4.exchange.want(hash4, author = "b4")
            assertTrue("c4's answer is still due", b4.asksFrom(hash4).isEmpty())
        }

    /**
     * ADR 2026-10.g24x: a holder feeds one shared-budget Bluetooth copy of a blob at a time. An ask that comes in while
     * another copy is being fed is parked — its asker is told SENDING — and served once that feed ends.
     */
    @Test
    fun anAskWhileAnotherCopyIsFedWaitsForThatFeedToEnd() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            backgroundScope.recordServes(h, a, b)
            val hash = h.store.seed(picture(3_100))
            h.transport.feeds["a" to hash] = feeding(60_000) // a's copy is streaming on its Bluetooth link

            h.exchange.onRequest(hash, "b")
            assertEquals("parked behind a's feed", 0, h.served(hash, "b"))
            h.exchange.onHaveQuery(listOf(hash), "b", "q1")
            assertEquals(
                BlobHasItem.SENDING,
                b
                    .answersFrom("h")
                    .last()
                    .items
                    .single()
                    .state,
            )

            h.exchange.onRequest(hash, "b") // a re-ask only refreshes it
            advanceTimeBy(BlobExchange.PARK_POLL_MS * 3)
            runCurrent()
            assertEquals("still behind the feed", 0, h.served(hash, "b"))

            h.transport.feeds -= "a" to hash
            advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
            runCurrent()
            assertEquals("served once that feed ended, once", 1, h.served(hash, "b"))
            advanceTimeBy(BlobExchange.PARK_POLL_MS * 3)
            runCurrent()
            assertEquals(1, h.served(hash, "b"))
        }

    /**
     * ADR 2026-10.nsr7: a parked ask has no bound of its own — it waits as long as the feed ahead of it is live, however
     * long that takes, and goes once the feed stalls or runs past its deadline ([FileFeed.live]).
     */
    @Test
    fun aParkedAskWaitsAsLongAsTheFeedIsLiveAndGoesWhenItIsNot() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            backgroundScope.recordServes(h, b)
            val hash = h.store.seed(picture(3_150))
            h.transport.feeds["a" to hash] = feeding(8 * 60_000) // an 8 MiB file at 18 KiB/s

            h.exchange.onRequest(hash, "b")
            repeat(5) {
                h.now += BlobExchange.SERVE_MEMO_MS - 1
                h.exchange.onRequest(hash, "b") // its asker goes on asking
                advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
                runCurrent()
            }
            assertEquals("minutes in, still behind the live feed", 0, h.served(hash, "b"))
            h.transport.feeds["a" to hash] = FileFeed(etaMs = 8 * 60_000, live = false) // stalled, or past its deadline
            advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
            runCurrent()
            assertEquals(1, h.served(hash, "b"))
        }

    /**
     * Behind a slower feed — an iPhone's at the base pace — an asker whose own copy would end before it goes beside it,
     * and an asker whose copy would not, or whose rate nobody knows, still waits (ADR 2026-10.nsr7; rep 65's four
     * copies at once).
     */
    @Test
    fun anAskWhoseCopyWouldEndFirstGoesBesideASlowerFeedAndTheRestWait() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val i = Node("i", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            for (n in listOf(i, b, c, d)) h.transport.connect(n.transport)
            backgroundScope.recordServes(h, b, c, d)
            val bytes = picture(18_432)
            val hash = h.store.seed(bytes)
            h.transport.feeds["i" to hash] = feeding(100_000) // the iPhone's copy, about 100 s from its end
            h.transport.rates["b"] = 18_432 // b's copy: about 1 s
            h.transport.rates["c"] = 128 // c's: about 144 s

            h.exchange.onRequest(hash, "b")
            assertEquals("b's copy would end long before the iPhone's: beside it", 1, h.served(hash, "b"))
            h.transport.feeds["b" to hash] = feeding(1_000)
            h.exchange.onRequest(hash, "c")
            h.exchange.onRequest(hash, "d")
            assertEquals("c's would not end first", 0, h.served(hash, "c"))
            assertEquals("and d's rate is not known", 0, h.served(hash, "d"))
        }

    /**
     * Going beside splits the budget once more, so it must save the asker more than it costs the feeds already running
     * (device rep 72): behind two feeds 100 s from their ends, a 60 s copy goes and an 80 s one, which would end first
     * too, waits.
     */
    @Test
    fun anAskGoesBesideOnlyWhenItSavesMoreThanItCostsTheRunningFeeds() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val x = Node("x", backgroundScope)
            val y = Node("y", backgroundScope)
            val slow = Node("slow", backgroundScope)
            val fast = Node("fast", backgroundScope)
            for (n in listOf(x, y, slow, fast)) h.transport.connect(n.transport)
            backgroundScope.recordServes(h, slow, fast)
            val hash = h.store.seed(picture(18_432))
            h.transport.feeds["x" to hash] = feeding(100_000)
            h.transport.feeds["y" to hash] = feeding(100_000)
            h.transport.feeding = 2
            h.transport.rates["slow"] = 230 // ~80 s: saves 100 − 80 × 2/3 ≈ 47 s, costs 80 × 2/3 ≈ 53 s
            h.transport.rates["fast"] = 307 // ~60 s: saves 100 − 40 = 60 s, costs 40 s

            h.exchange.onRequest(hash, "slow")
            assertEquals("its copy ends first, but the feeds lose more than it saves", 0, h.served(hash, "slow"))
            h.exchange.onRequest(hash, "fast")
            assertEquals(1, h.served(hash, "fast"))
        }

    /** When a feed ends, the parked ask whose link is expected to feed fastest goes next, one per blob a pass. */
    @Test
    fun theFastestParkedAskIsServedFirst() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val slow = Node("slow", backgroundScope)
            val fast = Node("fast", backgroundScope)
            for (n in listOf(a, slow, fast)) h.transport.connect(n.transport)
            backgroundScope.recordServes(h, slow, fast)
            val hash = h.store.seed(picture(3_180))
            h.transport.feeds["a" to hash] = feeding(10)
            h.transport.rates["slow"] = 2_048
            h.transport.rates["fast"] = 18_432

            h.exchange.onRequest(hash, "slow") // parked first
            h.exchange.onRequest(hash, "fast")
            assertEquals(0, h.served(hash, "slow") + h.served(hash, "fast"))
            h.transport.feeds -= "a" to hash
            h.exchange.feedsChanged()
            assertEquals("the fast one goes first", 1, h.served(hash, "fast"))
            assertEquals("and alone", 0, h.served(hash, "slow"))
            h.transport.feeds["fast" to hash] = feeding(10)
            advanceTimeBy(BlobExchange.PARK_POLL_MS * 3)
            runCurrent()
            assertEquals("the slow one waits behind it", 0, h.served(hash, "slow"))
        }

    /**
     * A parked asker's SENDING answer says when its copy should land: the feed ahead's ETA plus its own copy's, and the
     * holder keeps the ask that long past the serve memo (ADR 2026-10.nsr7).
     */
    @Test
    fun aParkedAskersAnswerCarriesItsEtaAndTheAskIsKeptThatLong() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            backgroundScope.recordServes(h, b)
            val bytes = picture(20_480)
            val hash = h.store.seed(bytes)
            h.transport.feeds["a" to hash] = feeding(60_000)
            h.transport.rates["b"] = 256 // its own copy: 80 s, so it does not go beside the 60 s feed

            h.exchange.onRequest(hash, "b")
            h.exchange.onHaveQuery(listOf(hash), "b", "q1")
            val item =
                b
                    .answersFrom("h")
                    .last()
                    .items
                    .single()
            assertEquals(BlobHasItem.SENDING, item.state)
            assertEquals("the feed's 60 s, then its own 80 s", 140_000L, item.eta)

            h.now += BlobExchange.SERVE_MEMO_MS + 1 // past the memo, inside the promise
            advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
            runCurrent()
            h.transport.feeds -= "a" to hash
            advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
            runCurrent()
            assertEquals("kept, and served when the feed ended", 1, h.served(hash, "b"))
        }

    /**
     * Members first (cell (d), 2026-10-07: a member parked 287 s behind a carrier's lapsed pull): a member goes beside a
     * carrier's copy even when its own would not end first, and a carrier waits behind a member's, however fast it is.
     */
    @Test
    fun aMemberGoesBesideACarriersCopyAndACarrierWaitsBehindAMembers() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val nodes = listOf("c", "m1", "c2").map { Node(it, backgroundScope) }
            nodes.forEach { h.transport.connect(it.transport) }
            backgroundScope.recordServes(h, nodes[1], nodes[2])
            val hash = h.store.seed(picture(18_432))
            h.to = setOf("h", "m1", "m2")
            h.transport.feeds["c" to hash] = feeding(100_000) // a carrier's copy, 100 s from its end
            h.transport.rates["m1"] = 128 // about 144 s: it would not end first

            h.exchange.onRequest(hash, "m1")
            assertEquals("a carrier's copy holds no member", 1, h.served(hash, "m1"))
            h.transport.feeds["m1" to hash] = feeding(144_000)
            h.transport.rates["c2"] = 18_432
            h.exchange.onRequest(hash, "c2")
            assertEquals("a carrier waits behind a member's copy, however fast", 0, h.served(hash, "c2"))
        }

    @Test
    fun aParkedMemberIsServedBeforeAFasterParkedCarrier() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val nodes = listOf("m1", "c", "m2").map { Node(it, backgroundScope) }
            nodes.forEach { h.transport.connect(it.transport) }
            backgroundScope.recordServes(h, nodes[1], nodes[2])
            val hash = h.store.seed(picture(18_432))
            h.to = setOf("h", "m1", "m2")
            h.transport.feeds["m1" to hash] = feeding(10)
            h.transport.rates["c"] = 18_432
            h.transport.rates["m2"] = 2_048 // about 9 s: it waits behind the member's copy

            h.exchange.onRequest(hash, "c")
            h.exchange.onRequest(hash, "m2")
            assertEquals(0, h.served(hash, "c") + h.served(hash, "m2"))
            h.transport.feeds -= "m1" to hash
            h.exchange.feedsChanged()
            assertEquals("the member goes first", 1, h.served(hash, "m2"))
            assertEquals("and the carrier waits its turn", 0, h.served(hash, "c"))
        }

    /** A cancel drops a parked ask, and a copy queued to its sender and not yet streaming (ADR 2026-10.nsr7). */
    @Test
    fun aCancelDropsAParkedAskAndAQueuedCopy() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val nodes = listOf("a", "b", "q").map { Node(it, backgroundScope) }
            nodes.forEach { h.transport.connect(it.transport) }
            backgroundScope.recordServes(h, nodes[1])
            val hash = h.store.seed(picture(18_432))
            h.transport.feeds["a" to hash] = feeding(60_000)
            h.transport.rates["b"] = 256

            h.exchange.onRequest(hash, "b") // parked
            h.exchange.onCancel(hash, "b")
            h.transport.feeds -= "a" to hash
            h.exchange.feedsChanged()
            assertEquals("its ask was withdrawn: nothing is served on the feed's end", 0, h.served(hash, "b"))

            h.transport.queuedFiles += "q" to hash // a copy to q, queued behind another file
            h.exchange.onCancel(hash, "q")
            assertEquals(listOf("q" to hash), h.transport.cancelled)
            h.exchange.onCancel(hash, "q") // nothing left to withdraw
            assertEquals(1, h.transport.cancelled.size)
        }

    /** A blob that lands withdraws the asker's other asks: a holder that queued or parked one sends no second copy. */
    @Test
    fun aBlobThatLandsWithdrawsTheOtherAsks() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val phone = Node("phone", backgroundScope).apply { transport.advertises = Protocol.CAP_BLOB_HAVE or Protocol.CAP_DOORBELL }
            listOf(b, c, phone).forEach { a.transport.connect(it.transport) }
            val bytes = picture()
            val hash = sha256Hex(bytes)

            a.exchange.want(hash) // the blind round: one first, then everyone
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask()
            assertEquals(3, listOf(b, c, phone).count { it.asksFrom(hash).isNotEmpty() })
            // Widening keeps the first pick's ask: it may hold the closest copy.
            assertTrue(listOf(b, c).all { it.cancelsFrom(hash).isEmpty() })
            val path = File.createTempFile("land", ".bin").apply { writeBytes(bytes) }.path
            a.exchange.onReceived(hash, "image/jpeg", path, from = "b")
            assertTrue("b brought it", b.cancelsFrom(hash).isEmpty())
            assertEquals("withdrawn once", listOf("a"), c.cancelsFrom(hash))
            a.exchange.reask()
            assertEquals(listOf("a"), c.cancelsFrom(hash))
            assertEquals("an iPhone too: its port takes the type", listOf("a"), phone.cancelsFrom(hash))
        }

    /** A parked ask is kept for its expected turn from the park itself, not only once its asker queries (cell (c)). */
    @Test
    fun aParkedAskIsKeptForItsTurnWithoutAQuery() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            backgroundScope.recordServes(h, b)
            val hash = h.store.seed(picture(20_480))
            h.transport.feeds["a" to hash] = feeding(60_000)
            h.transport.rates["b"] = 256 // its own copy: 80 s

            h.exchange.onRequest(hash, "b") // parked; b goes quiet
            h.now += BlobExchange.SERVE_MEMO_MS + 1
            advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
            runCurrent()
            h.transport.feeds -= "a" to hash
            h.exchange.feedsChanged()
            assertEquals("kept past the memo with no query, and served on the feed's end", 1, h.served(hash, "b"))
        }

    /** The poll is a `delay`, which stops while the CPU sleeps: a feed's end serves its parked ask on the edge. */
    @Test
    fun aFeedsEndServesItsParkedAskWithoutWaitingForThePoll() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            backgroundScope.recordServes(h, b)
            val hash = h.store.seed(picture(3_170))
            h.transport.feeds["a" to hash] = feeding(60_000)

            h.exchange.onRequest(hash, "b")
            assertEquals(0, h.served(hash, "b"))
            h.transport.feeds -= "a" to hash
            h.exchange.feedsChanged() // no virtual time passes
            assertEquals(1, h.served(hash, "b"))
        }

    @Test
    fun anAskIsServedAtOnceWhenItsCopyWouldNotShareTheBudget() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            h.transport.connect(c.transport)
            backgroundScope.recordServes(h, a, b, c)
            val hash = h.store.seed(picture(3_200))
            h.transport.feeds["a" to hash] = feeding(60_000)
            h.transport.bulkRoutes["b"] = BulkRoute.Linked // b's copy rides Wi-Fi Aware
            h.transport.slowLinks += "c" // c's rides Coded, outside the split

            h.exchange.onRequest(hash, "b")
            h.exchange.onRequest(hash, "c")
            assertEquals(1, h.served(hash, "b"))
            assertEquals(1, h.served(hash, "c"))

            val lone = Node("lone", backgroundScope) // no session: nothing would serve a parked ask later
            val d = Node("d", backgroundScope)
            lone.transport.connect(d.transport)
            backgroundScope.recordServes(lone, d)
            val hash2 = lone.store.seed(picture(3_300))
            lone.transport.feeds["x" to hash2] = feeding(60_000)
            lone.exchange.onRequest(hash2, "d")
            assertEquals(1, lone.served(hash2, "d"))
        }

    @Test
    fun aParkedAskIsDroppedWhenItsAskerUnlinksOrStopsAsking() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Node("h", backgroundScope, timers = backgroundScope)
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            h.transport.connect(a.transport)
            h.transport.connect(b.transport)
            h.transport.connect(c.transport)
            backgroundScope.recordServes(h, b, c)
            val hash = h.store.seed(picture(3_400))
            h.transport.feeds["a" to hash] = feeding(60_000)

            h.exchange.onRequest(hash, "b")
            h.exchange.onRequest(hash, "c")
            h.transport.disconnect(b.transport)
            h.now += BlobExchange.SERVE_MEMO_MS // c has moved on to another holder by now
            advanceTimeBy(BlobExchange.PARK_POLL_MS + 1)
            runCurrent()
            h.transport.feeds -= "a" to hash
            advanceTimeBy(BlobExchange.PARK_POLL_MS * 3)
            runCurrent()
            assertEquals(0, h.served(hash, "b"))
            assertEquals(0, h.served(hash, "c"))
        }

    /**
     * ADR 2026-10.nsr7: the receiver reports a file streaming in back to its sender, but only to one whose link carries
     * the bit; the sender's exchange hands a report to the link that feeds it.
     */
    @Test
    fun aFileStreamingInIsReportedToACapableSenderAndAReportReachesItsLink() =
        runTest(UnconfinedTestDispatcher()) {
            val r = Node("r", backgroundScope)
            val s = Node("s", backgroundScope)
            val old = Node("old", backgroundScope, answers = false)
            val phone = Node("phone", backgroundScope).apply { transport.advertises = Protocol.CAP_BLOB_HAVE or Protocol.CAP_DOORBELL }
            r.transport.connect(s.transport)
            r.transport.connect(old.transport)
            r.transport.connect(phone.transport)
            val hash = "a".repeat(64)

            r.exchange.onFileProgress(FileProgress("s", hash, 40_960, 2_000))
            r.exchange.onFileProgress(FileProgress("old", hash, 40_960, 2_000))
            r.exchange.onFileProgress(FileProgress("phone", hash, 40_960, 2_000))
            val sent = s.heard.single { it.first == FrameType.BLOB_PROGRESS }.third
            assertEquals(BlobProgressContent(hash, 40_960, 2_000), WireCodec.decodePayload<BlobProgressContent>(sent.payload))
            assertTrue("a neighbour without the bit is never sent one", old.heard.none { it.first == FrameType.BLOB_PROGRESS })
            assertEquals("an iPhone is: its port takes the type", 1, phone.heard.count { it.first == FrameType.BLOB_PROGRESS })

            s.exchange.onProgressReport(BlobProgressContent(hash, 40_960, 2_000), "r")
            assertEquals(listOf(FileProgress("r", hash, 40_960, 2_000)), s.transport.progressTaken.toList())
        }

    @Test
    fun aHolderThatBringsNothingIsQueriedAgainThenTheNextIsAsked() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.store.lying[hash] = bytes.size.toLong() // b says it holds it, then cannot serve it
            c.store.seed(bytes)
            c.transport.feeding = 1 // so b, unloaded, is the better choice

            a.exchange.want(hash)
            assertEquals(listOf("a"), b.asksFrom(hash))
            assertTrue(c.asksFrom(hash).isEmpty())

            b.store.lying.clear() // by the confirm, b knows it lacks it
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask()
            assertEquals("its grace up, b is queried again", 2, b.queriesFrom("a").size)
            assertEquals("and saying it lacks it, the next holder is asked", listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.Fallback })
        }

    @Test
    fun aHolderWhoseAskWasLostIsAskedAgainOnceThenPassedOver() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.store.lying[hash] = bytes.size.toLong() // a holder whose serves never arrive
            c.store.seed(bytes)
            c.transport.feeding = 1

            a.exchange.want(hash)
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask() // the confirm: still HAVE, to a query newer than our ask — the ask was lost
            assertEquals("asked again once", listOf("a", "a"), b.asksFrom(hash))
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask() // confirm again
            assertEquals("not a third time", 2, b.asksFrom(hash).size)
            assertEquals("the next holder", listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aHolderAlreadySendingUsTheBlobIsWaitedOnAndNobodyElseIsAsked() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = b.store.seed(bytes)
            c.store.seed(bytes)
            b.transport.inFlight += "a" to hash // b's copy for us is already queued on the link

            a.exchange.want(hash)

            assertTrue("no ask at all: our copy is coming", b.asksFrom(hash).isEmpty() && c.asksFrom(hash).isEmpty())
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask() // confirm: still sending
            assertTrue(c.asksFrom(hash).isEmpty())
        }

    @Test
    fun theGraceCountsFromTheEndOfTheHoldersStreamNotItsLastLook() =
        runTest(UnconfinedTestDispatcher()) {
            // The blind round's first ask (f7gg), the trial's shape: b streams something else to us, our copy behind it.
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope, answers = false)
            val c = Node("c", backgroundScope, answers = false)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash, author = "b")
            assertEquals(listOf("a"), b.asksFrom(hash))
            a.transport.arrivingFrom += "b"
            a.now = 4_000
            a.exchange.reask() // the last look that sees b streaming
            a.transport.arrivingFrom -= "b" // its stream ends at 4.5 s
            a.now = 9_000
            a.exchange.reask() // a whole grace after that last look, 4.5 s after the end
            assertTrue("the grace starts at the first quiet look, not the last streaming one", c.asksFrom(hash).isEmpty())
            a.now = 14_000
            a.exchange.reask()
            assertEquals(listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun anAskThatFoundItsNeighbourLackingTheBlobIsNotWaitedOutBehindItsStream() =
        runTest(UnconfinedTestDispatcher()) {
            // Cell (d), 2026-10-07: asked while it was still fetching the blob, b remembers no asker, so nothing of ours
            // is queued behind the other file it streams us. The fetch lets b go, and a holder linked later is asked.
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope).apply { mute = true } // its answer comes late, as on the phones (7 s)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            val hash = c.store.seed(picture())
            b.store.storingNow += hash // b says FETCHING

            a.exchange.want(hash, author = "b")
            a.now += BlobExchange.QUERY_WAIT_MS
            a.exchange.reask() // no answer in time: the author is asked first
            assertEquals(listOf("a"), b.asksFrom(hash))
            val query =
                b.heard
                    .last { it.first == FrameType.BLOB_HAVE }
                    .third.id
            a.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.FETCHING)), 0, query), "b") // after our ask
            a.transport.arrivingFrom += "b" // b streams something else to us, for minutes
            a.transport.connect(c.transport)
            a.exchange.linked(listOf("c"))
            a.now += 1_000
            a.exchange.reask()
            a.exchange.reask()
            assertEquals("c holds it: asked, rather than b's stream waited out", listOf("a"), c.asksFrom(hash))
        }

    /**
     * Interop audit (`test/interop-audit`, knit-ios IOS-1): knit-ios `InteropAuditMeshTests`' sequence through the
     * reference. The author is asked blind, then answers NONE while it streams us another file, and a linked holder
     * answers HAVE with nothing feeding: the fetch drops the author ([askedLacking]) and the holder is asked.
     */
    @Test
    fun interopAuditTheHolderIsAskedOnceTheAuthorAnsweredLackingWhileItStreams() =
        runTest(UnconfinedTestDispatcher()) {
            val me = Node("a", backgroundScope)
            val author = Node("b", backgroundScope).apply { mute = true }
            val holder = Node("c", backgroundScope).apply { mute = true }
            me.transport.connect(author.transport)
            me.transport.connect(holder.transport)
            val hash = holder.store.seed(picture())

            me.now = 0
            me.exchange.want(hash, author = "b") // both queried at t = 0, neither answers
            me.now = BlobExchange.QUERY_WAIT_MS
            me.exchange.reask()
            assertEquals("the blind ask goes to the author", listOf("a"), author.asksFrom(hash))
            assertTrue(holder.asksFrom(hash).isEmpty())

            fun queryTo(node: Node) =
                node.heard
                    .last { it.first == FrameType.BLOB_HAVE }
                    .third.id
            me.now = 6_000
            me.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.NONE)), 0, queryTo(author)), "b")
            me.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.HAVE)), 0, queryTo(holder)), "c")
            me.transport.arrivingFrom += "b" // the author streams something else to us; the holder is quiet
            assertTrue("not on the answers alone", holder.asksFrom(hash).isEmpty())
            me.now = 6_500
            me.exchange.reask()
            assertEquals("the holder is asked at the last step", listOf("a"), holder.asksFrom(hash))
        }

    @Test
    fun aFetchLeavesAHolderWhoseOtherStreamHasLongToRunForAnIdleOne() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            val bytes = picture()
            val hash = c.store.seed(bytes)
            b.store.lying[hash] = bytes.size.toLong() // b holds it, our copy queued behind the file it streams us

            a.exchange.want(hash, author = "b")
            assertEquals(listOf("a"), b.asksFrom(hash))
            a.transport.arrivingFrom += "b"
            a.transport.arrivingLeft["b"] = BlobExchange.STREAM_SWITCH_MS // not past the bound: wait it out
            a.transport.connect(c.transport)
            a.exchange.linked(listOf("c"))
            a.now += 1_000
            a.exchange.reask()
            a.exchange.reask()
            assertTrue("b's stream ends soon enough: our copy may be queued behind it", c.asksFrom(hash).isEmpty())
            a.transport.arrivingLeft["b"] = 10 * 60_000L // a slow carrier copy with minutes to run
            a.now += 1_000
            a.exchange.reask()
            a.exchange.reask()
            assertEquals("c holds it and feeds nothing: asked instead", listOf("a"), c.asksFrom(hash))
            assertEquals("and b's ask is withdrawn, so a copy it queued is not a second", 1, b.cancelsFrom(hash).size)
        }

    /**
     * A neighbour that lacked the blob when queried, and holds it a minute later, is queried again while our fetch waits
     * behind a long stream — and only then — so the switch to an idle holder learns of it (the iPhone smoke of
     * 2026-10-07: the P9 answered NONE 0.4 s before its own fetch, and the P8 sat 8 min behind a DM file).
     */
    @Test
    fun aNeighbourThatLackedTheBlobIsQueriedAgainWhileTheFetchWaitsBehindALongStream() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.store.lying[hash] = bytes.size.toLong() // b holds it, our copy queued behind the file it streams us

            a.exchange.want(hash, author = "b")
            assertEquals(listOf("a"), b.asksFrom(hash))
            assertEquals("c was queried once, and lacked it", 1, c.queriesFrom("a").count { hash in it })
            a.transport.arrivingFrom += "b"
            a.transport.arrivingLeft["b"] = BlobExchange.STREAM_SWITCH_MS // not past the bound: no recheck
            a.now += BlobExchange.FETCHING_RECHECK_MS
            a.exchange.reask()
            assertEquals("b's stream ends soon enough: c's NONE stands", 1, c.queriesFrom("a").count { hash in it })

            a.transport.arrivingLeft["b"] = 10 * 60_000L // minutes to run
            a.exchange.reask()
            assertEquals("stuck, and c's NONE is a recheck old: queried again", 2, c.queriesFrom("a").count { hash in it })
            assertTrue("still lacking: b keeps the fetch", c.asksFrom(hash).isEmpty())
            c.store.seed(bytes) // c's own fetch lands
            a.now += BlobExchange.FETCHING_RECHECK_MS / 2
            a.exchange.reask()
            assertEquals("not due yet", 2, c.queriesFrom("a").count { hash in it })
            a.now += BlobExchange.FETCHING_RECHECK_MS / 2
            a.exchange.reask()
            a.exchange.reask()
            assertEquals("queried again a recheck later", 3, c.queriesFrom("a").count { hash in it })
            assertEquals("c holds it and feeds nothing: asked instead", listOf("a"), c.asksFrom(hash))
            assertEquals("and b's ask is withdrawn", 1, b.cancelsFrom(hash).size)
        }

    @Test
    fun theStreamPollIsTimedOnTheSession() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false, timers = backgroundScope)
            val b = Node("b", backgroundScope, answers = false)
            val c = Node("c", backgroundScope, answers = false)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())
            a.transport.arrivingFrom += "b"

            a.exchange.want(hash, author = "b")
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS + 1)
            runCurrent()
            assertTrue("b still streams to us: the round waits", c.asksFrom(hash).isEmpty())
            a.transport.arrivingFrom -= "b"
            a.now += BlobExchange.STREAM_POLL_MS
            advanceTimeBy(BlobExchange.STREAM_POLL_MS + 1)
            runCurrent() // the poll's first quiet look starts the grace
            assertTrue(c.asksFrom(hash).isEmpty())
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS + 1)
            runCurrent()
            assertEquals("widened a whole grace after the stream's end", listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun aNeighbourThatCannotBeQueriedIsAskedBlindOnceTheAnswersAreIn() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val u = Node("u", backgroundScope, answers = false) // an iPhone, or an older build
            a.transport.connect(b.transport)
            a.transport.connect(u.transport)
            val hash = u.store.seed(picture())

            a.exchange.want(hash)

            assertTrue("never queried", u.queriesFrom("a").isEmpty())
            assertEquals("b said it lacks it, so the blind round runs at once", listOf("a"), u.asksFrom(hash))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.BlindAsk })
        }

    @Test
    fun aConfirmedHolderBeatsAnAuthorThatCannotBeQueried() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val u = Node("u", backgroundScope, answers = false)
            a.transport.connect(b.transport)
            a.transport.connect(u.transport)
            val bytes = picture()
            val hash = b.store.seed(bytes)
            u.store.seed(bytes)

            a.exchange.want(hash, author = "u")

            assertEquals(listOf("a"), b.asksFrom(hash))
            assertTrue("the author is asked blind only once no holder said so", u.asksFrom(hash).isEmpty())
        }

    @Test
    fun aCapableNeighbourStillSilentPastTheWaitIsAskedBlind() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val s = Node("s", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(s.transport)
            val hash = s.store.seed(picture())

            a.exchange.want(hash)
            assertEquals(1, s.queriesFrom("a").size)
            assertTrue(s.asksFrom(hash).isEmpty())
            a.now += BlobExchange.QUERY_WAIT_MS
            a.exchange.reask()
            assertEquals("a bit that answers nothing degrades to today's round", listOf("a"), s.asksFrom(hash))
        }

    /** Answers, as [holder], the latest query this node sent it, with [state] for [hash]: a late answer, by hand. */
    private suspend fun Node.answerFor(
        holder: Node,
        hash: String,
        state: Int = BlobHasItem.HAVE,
        load: Int = 0,
        eta: Long? = null,
    ) {
        val query = holder.heard.last { it.first == FrameType.BLOB_HAVE && it.second == id }.third
        exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, state, 2_000, eta = eta)), load = load, re = query.id), holder.id)
    }

    /** #140's shape, held: [a]'s blind ask stands on the author [u], whose late HAVE said it holds [hash]; [b] answers late. */
    private inner class HeldOnTheAuthor(
        val a: Node,
        val u: Node,
        val b: Node,
        val hash: String,
    )

    /**
     * The 2026-10-06 trace: the asker's own frames are queued, so the author [HeldOnTheAuthor.u] and the holder
     * [HeldOnTheAuthor.b] both miss the wait, the author is asked blind, says HAVE inside the grace, and brings no header
     * by its end. Its confirm is silent too, and the ask is held to its floor while b's late HAVE lands.
     */
    private suspend fun TestScope.heldOnTheAuthor(): HeldOnTheAuthor {
        val a = Node("a", backgroundScope)
        val u = Node("u", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
        val b = Node("b", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
        a.transport.connect(u.transport)
        a.transport.connect(b.transport)
        val hash = b.store.seed(picture()) // the author's copy is late on the link: modelled as never arriving

        a.exchange.want(hash, author = "u")
        a.now = BlobExchange.QUERY_WAIT_MS
        a.exchange.reask()
        assertEquals("both silent past the wait: the author is asked blind", listOf("a"), u.asksFrom(hash))

        a.now = 7_000
        a.answerFor(u, hash) // HAVE, to the query sent before the ask: 7 s on the way
        a.now = BlobExchange.QUERY_WAIT_MS + BlobExchange.FIRST_ASK_GRACE_MS
        a.exchange.reask()
        assertEquals("its grace up, the author that said it holds it is confirmed", 2, u.queriesFrom("a").size)
        assertEquals("not widened: nobody else is asked blind", 1, a.events.count { it == BlobExchange.BlobEvent.BlindAsk })

        a.now = 12_000
        a.answerFor(b, hash) // b's late HAVE, 12 s on the way
        assertTrue("the author's ask still stands: b is not asked", b.asksFrom(hash).isEmpty())

        a.now = 22_000 // past the confirm's wait, stretched to the author's 7 s round trip
        a.exchange.reask()
        assertTrue("a silent confirm holds the ask to its floor", b.asksFrom(hash).isEmpty())
        assertEquals(0, a.events.count { it == BlobExchange.BlobEvent.Fallback })
        return HeldOnTheAuthor(a, u, b, hash)
    }

    @Test
    fun aLateHaveFromASecondHolderWaitsOnTheAskStandingOnTheFirstUntilItsLinkGoes() =
        runTest(UnconfinedTestDispatcher()) {
            val held = heldOnTheAuthor()
            val (a, u, b) = Triple(held.a, held.u, held.b)
            val hash = held.hash

            a.transport.disconnect(u.transport)
            a.exchange.unlinked(listOf("u"))
            assertEquals("the author's link gone, the next holder is asked at once", listOf("a"), b.asksFrom(hash))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.Fallback })
        }

    @Test
    fun aHeldAskEndsAtItsFloorAndTheNextCycleAsksTheHolderThatAnswers() =
        runTest(UnconfinedTestDispatcher()) {
            val held = heldOnTheAuthor()
            val (a, b, hash) = Triple(held.a, held.b, held.hash)

            a.now = BlobExchange.QUERY_WAIT_MS + BlobExchange.SERVE_MEMO_MS - 1
            a.exchange.reask()
            assertTrue("inside the floor, still held", b.asksFrom(hash).isEmpty())
            a.now += 1
            a.exchange.reask() // the floor: the hold ends, and the cycle that has stood since 0 opens again
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.Fallback })
            a.answerFor(b, hash)
            assertTrue("the author's answer to the new cycle is still due", b.asksFrom(hash).isEmpty())
            a.now += 7_000 + BlobExchange.QUERY_WAIT_MS // its wait, stretched by its 7 s round trip
            a.exchange.reask()
            assertEquals("the holder that answered the new cycle is asked", listOf("a"), b.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aHolderWhoseConfirmIsSilentIsHeldToItsFloorNotPassedOver() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            c.store.seed(bytes)
            c.transport.feeding = 1 // so b, unloaded, is the better choice

            a.exchange.want(hash)
            a.now = 1_000
            a.answerFor(b, hash) // b says it holds it, 1 s on the way; its copy never comes
            assertEquals("b, the better holder, is asked", listOf("a"), b.asksFrom(hash))

            a.now = 1_000 + BlobExchange.FIRST_ASK_GRACE_MS + 1_000 // the grace, stretched by b's round trip
            a.exchange.reask()
            assertEquals("its grace up, b is confirmed", 2, b.queriesFrom("a").size)
            a.now += BlobExchange.QUERY_WAIT_MS + 1_000
            a.exchange.reask()
            assertTrue("silence on a live link is a late answer: c is not asked", c.asksFrom(hash).isEmpty())
            assertEquals(0, a.events.count { it == BlobExchange.BlobEvent.Fallback })

            a.now = 1_000 + BlobExchange.SERVE_MEMO_MS
            a.exchange.reask() // b's floor; the cycle opens again and queries both
            a.now += BlobExchange.QUERY_WAIT_MS + 1_000
            a.exchange.reask() // b still silent past its wait
            assertEquals("past b's floor, the next holder is asked", listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    /**
     * ADR 2026-10.nsr7: a holder still SENDING that says when the copy should land holds the asker that long, past its
     * ask's 45 s floor, and only then is the next holder asked.
     */
    @Test
    fun aSendingAnswersEtaHoldsTheAskerPastItsFloor() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            c.store.seed(bytes)
            c.transport.feeding = 1 // so b, unloaded, is the better choice

            a.exchange.want(hash)
            a.now = 1_000
            a.answerFor(b, hash)
            assertEquals("b is asked", listOf("a"), b.asksFrom(hash))
            val eta = 3 * 60_000L // b parked our ask behind a feed of three minutes
            repeat(BlobExchange.MAX_CONFIRMS + 1) {
                a.now += BlobExchange.FIRST_ASK_GRACE_MS + 1_000
                a.exchange.reask() // its grace up: confirmed, or held once confirmed enough
                if (b.queriesFrom("a").size > it + 1) a.answerFor(b, hash, BlobHasItem.SENDING, eta = eta)
            }
            val promisedUntil = a.now + eta + BlobExchange.HOLD_SLACK_MS

            a.now = 1_000 + BlobExchange.SERVE_MEMO_MS + 1 // the ask's floor: the old hold would end here
            a.exchange.reask()
            a.now += BlobExchange.QUERY_WAIT_MS + 1_000
            a.exchange.reask()
            assertTrue("inside the promise, c is not asked", c.asksFrom(hash).isEmpty())

            a.now = promisedUntil + 1
            a.exchange.reask() // the promise is spent: the cycle opens again
            a.now += BlobExchange.QUERY_WAIT_MS + 1_000
            a.exchange.reask()
            assertEquals("past it, the next holder is asked", listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun theWaitForAnAnswerStretchesToTheNeighboursLastRoundTrip() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val s = Node("s", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(s.transport)
            val first = s.store.seed(picture(2_000))
            val second = s.store.seed(picture(2_100))
            val third = s.store.seed(picture(2_200))

            a.exchange.want(first)
            a.now = 20_000
            a.answerFor(s, first) // a 20 s round trip: our frames to s are queued
            assertTrue(a.store.has(first))

            a.exchange.want(second)
            a.now += 15_000
            a.exchange.reask()
            assertTrue("15 s is inside s's lag: not silence, no blind ask", s.asksFrom(second).isEmpty())
            a.now += 10_000
            a.exchange.reask()
            assertEquals("past the round trip plus the wait, it is", listOf("a"), s.asksFrom(second))

            a.exchange.linked(listOf("s")) // a new link, a new queue: the lag is forgotten
            a.exchange.want(third)
            a.now += BlobExchange.QUERY_WAIT_MS
            a.exchange.reask()
            assertEquals(listOf("a"), s.asksFrom(third))
        }

    @Test
    fun aRoundTripStretchesTheWaitOnlyUpToItsCap() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val s = Node("s", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(s.transport)
            val first = s.store.seed(picture(2_000))
            val second = s.store.seed(picture(2_100))

            a.exchange.want(first)
            a.now = 60_000
            a.answerFor(s, first)

            a.exchange.want(second)
            a.now += BlobExchange.QUERY_WAIT_CAP_MS - 1
            a.exchange.reask()
            assertTrue(s.asksFrom(second).isEmpty())
            a.now += 1
            a.exchange.reask()
            assertEquals("a 60 s round trip waits the cap, no longer", listOf("a"), s.asksFrom(second))
        }

    @Test
    fun anAnswerNobodyAskedForIsIgnored() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture())

            a.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.HAVE, 10))), "b")
            assertTrue("not wanted: nothing asked", b.asksFrom(hash).isEmpty())
            assertEquals(listOf(BlobExchange.BlobEvent.AnswerIgnored), a.events.filter { it == BlobExchange.BlobEvent.AnswerIgnored })

            val c = Node("c", backgroundScope, answers = false) // never queried: no CAP
            a.transport.connect(c.transport)
            a.exchange.want(hash)
            a.events.clear()
            a.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.HAVE, 10))), "c")
            assertEquals("an answer from a neighbour we never queried", listOf(BlobExchange.BlobEvent.AnswerIgnored), a.events.toList())
        }

    @Test
    fun aStateThisBuildDoesNotKnowIsNeverTakenForHave() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash)
            val query = b.heard.single { it.first == FrameType.BLOB_HAVE }.third
            a.exchange.onHaveAnswer(
                BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.PARTIAL, size = 2_000, held = 400)), re = query.id),
                "b",
            )

            // A PARTIAL holder is not asked for the whole: it lacks it, so it is asked to pull it through.
            assertEquals(1, b.asksFrom(hash).size)
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.PullThrough })
            assertEquals(0, a.events.count { it == BlobExchange.BlobEvent.Fetch })
        }

    @Test
    fun nobodyHoldsItSoTheHopPullsItThroughThenEveryoneNextCycle() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            b.transport.connect(d.transport) // d, the holder, is two hops from a, behind b
            backgroundScope.recordServes(d, b)
            backgroundScope.recordServes(b, a)
            val hash = d.store.seed(picture())

            a.exchange.want(hash, via = "b")

            assertEquals("the hop alone is asked to pull it through", listOf("a"), b.asksFrom(hash))
            assertTrue(c.asksFrom(hash).isEmpty())
            assertEquals("b pulled it from d", 1, d.served(hash, "b"))
            assertTrue(b.store.has(hash))
            b.now += BlobExchange.CYCLE_MS
            a.now += BlobExchange.CYCLE_MS
            a.exchange.reask() // the next cycle queries again: b holds it now
            assertEquals(1, b.served(hash, "a"))
            assertTrue(a.store.has(hash))
            assertTrue("c was never asked", c.asksFrom(hash).isEmpty())
        }

    @Test
    fun withNoHopAmongThemEveryNeighbourThatLacksItPullsItThrough() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash)

            assertEquals(listOf("a"), b.asksFrom(hash))
            assertEquals(listOf("a"), c.asksFrom(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.PullThrough })
        }

    @Test
    fun aNeighbourFetchingItHoldsThePullAndIsQueriedAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.transport.arriving += hash // b's own copy is streaming in

            a.exchange.want(hash, via = "b")
            assertTrue("b is fetching it: no pull-through", b.asksFrom(hash).isEmpty())
            b.transport.arriving -= hash
            b.store.seed(bytes)
            a.now += BlobExchange.FETCHING_RECHECK_MS
            a.exchange.reask()
            assertEquals(2, b.queriesFrom("a").size)
            assertEquals("its copy landed: asked for it", listOf("a"), b.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun theHopAskedToPullItThroughIsQueriedAgainShortlyAndAskedOnceItHoldsIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val d = Node("d", backgroundScope)
            a.transport.connect(b.transport)
            backgroundScope.recordServes(b, a)
            val hash = d.store.seed(picture())

            a.exchange.want(hash, via = "b")
            assertEquals("b lacks it, so it is asked to pull it through", listOf("a"), b.asksFrom(hash))
            a.now += BlobExchange.FETCHING_RECHECK_MS / 2
            a.exchange.reask()
            assertEquals("its recheck is not due yet", 1, b.queriesFrom("a").size)

            b.transport.connect(d.transport) // b meets the holder, and its pull for a lands
            b.exchange.linked(listOf("d"))
            b.exchange.reask()
            assertTrue(b.store.has(hash))

            a.now = BlobExchange.FETCHING_RECHECK_MS
            a.exchange.reask() // a third of the way into the cycle: the recheck finds it, not the next cycle
            assertEquals(2, b.queriesFrom("a").size)
            assertEquals("the pull, then the fetch", listOf("a", "a"), b.asksFrom(hash))
            assertEquals(1, b.served(hash, "a"))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aNeighbourAskedToPullItThroughIsQueriedAgainOnlyAFewTimesACycle() =
        runTest(UnconfinedTestDispatcher()) {
            val recheck = 5_000L
            val a = Node("a", backgroundScope, recheckMs = recheck)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash, via = "b")
            repeat(BlobExchange.PULL_RECHECKS + 3) {
                a.now += recheck
                a.exchange.reask()
            }

            assertTrue("still inside the first cycle", a.now < BlobExchange.CYCLE_MS)
            assertEquals("the query, then its rechecks", 1 + BlobExchange.PULL_RECHECKS, b.queriesFrom("a").size)
            assertEquals("and one pull", listOf("a"), b.asksFrom(hash))
        }

    @Test
    fun aNeighbourAskedToPullItThroughIsNotRecheckedOnceTheBlobHasBeenDeadTwoCyclesInARow() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash, via = "b")
            for (t in 10_000L..700_000L step 10_000L) { // a step every 10 s: every recheck that is owed goes out
                listOf(a, b).forEach { it.now = t }
                a.exchange.reask()
            }

            // Cycles open at 0, 50, 140, 320 and 680 s. The first two each query b and recheck it four times; the
            // three dead ones after them query it once.
            assertEquals(5, a.events.count { it == BlobExchange.BlobEvent.PullThrough })
            assertEquals(2 * (1 + BlobExchange.PULL_RECHECKS) + 3, b.queriesFrom("a").size)
        }

    @Test
    fun rechecksDueWithinASecondOfEachOtherShareOneFrame() =
        runTest(UnconfinedTestDispatcher()) {
            // Two dead hashes pulled 0.5 s apart rechecked in a frame each: the timer stepped the later one early, and
            // its recheck was not due yet (the 2026-10-05 re-run, on P8).
            val a = Node("a", backgroundScope, timers = backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val first = sha256Hex(picture(401))
            val second = sha256Hex(picture(402)) // nobody holds either

            a.exchange.want(first, via = "b")
            a.now += 500
            a.exchange.want(second, via = "b")
            assertEquals(listOf(listOf(first), listOf(second)), b.queriesFrom("a"))
            assertEquals(listOf("a"), b.asksFrom(first))
            assertEquals(listOf("a"), b.asksFrom(second))
            a.now = BlobExchange.FETCHING_RECHECK_MS // the first's recheck is due, the second's half a second off
            advanceTimeBy(BlobExchange.FETCHING_RECHECK_MS + 1)
            runCurrent()

            val rechecks = b.queriesFrom("a").drop(2)
            assertEquals("one frame for both rechecks: $rechecks", setOf(first, second), rechecks.single().toSet())
        }

    @Test
    fun aPullLateInACycleIsFollowedByItsRechecksNotASecondPull() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false) // asked blind first: the pull waits for that round
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash, via = "b")
            assertEquals("c, blind", listOf("a"), c.asksFrom(hash))
            a.now = 40_000L // the first step past c's grace: the pull comes 40 s into the cycle
            a.exchange.reask()
            assertEquals(listOf("a"), b.asksFrom(hash))
            for (t in 50_000L..80_000L step 10_000L) {
                a.now = t
                a.exchange.reask()
            }

            assertEquals("its rechecks", 1 + BlobExchange.PULL_RECHECKS, b.queriesFrom("a").size)
            assertEquals("one pull", listOf("a"), b.asksFrom(hash))
            assertEquals("and no second blind round: the cycle stands a whole one from its pull", listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun cyclesInWhichEveryNeighbourLacksItBackOffAndANewcomerIsStillQueriedAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes) // nobody in reach holds it

            a.exchange.want(hash, via = "b")
            val pulledIn = mutableListOf(0L)
            for (minute in 1L..25L) { // the 60 s tick, for 25 minutes
                listOf(a, b, c).forEach { it.now = minute * 60_000L }
                val before = a.events.count { it == BlobExchange.BlobEvent.PullThrough }
                a.exchange.reask()
                if (a.events.count { it == BlobExchange.BlobEvent.PullThrough } > before) pulledIn += minute
            }
            // 45 s, 90 s, 3 min, then 6 min between cycles, each opened by the tick: seven where every tick was one.
            assertEquals(listOf(0L, 1L, 3L, 6L, 12L, 18L, 24L), pulledIn)
            // A query a cycle, and one recheck on minute 2 of the second; the dead ones after it are not rechecked.
            assertEquals(7 + 1, b.queriesFrom("a").size)
            assertEquals(7 + 1, c.queriesFrom("a").size)

            val e = Node("e", backgroundScope)
            e.store.seed(bytes)
            backgroundScope.recordServes(e, a)
            a.transport.connect(e.transport)
            a.exchange.linked(listOf("e"))
            a.exchange.reask() // mid-backoff, a minute into a six-minute cycle
            // Its answer ends the backoff without opening the next cycle: b and c are not queried again for it.
            assertEquals("a newcomer is queried at once, and once", listOf(listOf(hash)), e.queriesFrom("a"))
            assertEquals(1, e.served(hash, "a"))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aCycleThatAskedANeighbourBlindIsNotDeadAndKeepsItsPace() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false) // the iOS port today: asked blind
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture()) // nobody holds it, but c cannot say so

            a.exchange.want(hash)
            for (minute in 1L..5L) {
                a.now = minute * 60_000L
                a.exchange.reask()
            }

            assertEquals("ADR 2026-10.f7gg's round every tick: c may hold it", 6, c.asksFrom(hash).size)
        }

    @Test
    fun aNeighbourWhoseAskOpensTheNextCycleIsNotQueriedInIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash) // b says it lacks it, and is asked to pull it through
            assertEquals(1, b.queriesFrom("a").size)
            a.now += BlobExchange.CYCLE_MS
            a.exchange.onRequest(hash, "b") // b's own ask comes due first: it lacks it too

            assertEquals("b is not queried back in the cycle its ask opened", 1, b.queriesFrom("a").size)
        }

    @Test
    fun theNeighbourWePullForIsNeitherQueriedNorAskedThatCycle() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = c.store.seed(picture())

            a.exchange.onRequest(hash, "b") // b asks us for a blob we lack: we pull it for b

            assertTrue("b lacks it: not queried", b.queriesFrom("a").isEmpty())
            assertEquals(listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aNeighbourThatLinksAgainIsQueriedAgainAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash) // c's answer is due; b says it lacks it
            a.transport.disconnect(b.transport)
            a.transport.connect(b.transport)
            a.exchange.linked(listOf("b"))
            a.exchange.reask()

            assertEquals("its old answer died with the link", 2, b.queriesFrom("a").size)
        }

    @Test
    fun withQueryingOffNoQueryIsSentAndTheRoundIsBlind() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = b.store.seed(picture())

            a.exchange.want(hash, author = "b")

            assertTrue(b.queriesFrom("a").isEmpty())
            assertEquals(listOf("a"), b.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aRefusedSpliceIsNotAskedOfTheHolderThatSentIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = b.store.seed(bytes)
            c.store.seed(bytes)
            c.transport.feeding = 1 // b would be the first choice
            val keptPrefix = Files.createTempFile("cut", ".bin").toFile().apply { writeBytes(ByteArray(100)) }
            assertTrue(a.partials.keep(hash, keptPrefix, basedOn = null))
            val bad = Files.createTempFile("bad", ".bin").toFile().apply { writeBytes(ByteArray(bytes.size)) }

            a.exchange.onReceived(hash, "image/jpeg", bad.absolutePath, resumedFrom = 100, from = "b")

            assertTrue("b sent the bad tail: the from-0 ask goes elsewhere", b.asksFrom(hash).isEmpty())
            assertEquals(listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }
}
