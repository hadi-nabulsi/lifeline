package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("LargeClass") // one exchange's whole contract: the serve, the walk, the round of asks and the resume
@OptIn(ExperimentalCoroutinesApi::class)
class BlobExchangeTest {
    /** A store for the prefixes of cut transfers, kept from a single byte so a test needs no 16 KiB file. */
    private fun partials(): PartialBlobs = PartialBlobs(Files.createTempDirectory("parts").toFile(), minBytes = 1)

    /** In-memory, content-addressed [BlobStore] backed by a temp directory. */
    private class FakeBlobStore(
        private val dir: File,
    ) : BlobStore {
        private val mimes = ConcurrentHashMap<String, String>()

        /**
         * What the real `MeshBlobStore` does on ingest: resolve the type from local state rather than the
         * serving peer's header. Set it to make the stored mime differ from the wire's, which is the only
         * way to see which of the two [BlobExchange.onReceived] hands the next hop.
         */
        var normalizeTo: String? = null

        fun seed(
            hash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            File(dir, hash).writeBytes(bytes)
            mimes[hash] = mime
        }

        /** Bytes held with no row naming their type — a blob whose message row went before it did. */
        fun seedWithoutMime(
            hash: String,
            bytes: ByteArray,
        ) {
            File(dir, hash).writeBytes(bytes)
        }

        /** Runs while [has] is being read, after it has looked: a slow store read the bytes move on under (#138). */
        var whileReadingHas: (suspend (String) -> Unit)? = null

        override suspend fun has(hash: String): Boolean = File(dir, hash).exists().also { whileReadingHas?.invoke(hash) }

        /** What the real store reports while a blob is past the link's end but screened and not yet held. */
        val storingNow: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun storing(hash: String): Boolean = hash in storingNow

        override suspend fun fileFor(hash: String): File? = File(dir, hash).takeIf { it.exists() }

        override suspend fun mimeFor(hash: String): String? = mimes[hash]

        /** What the real `MeshBlobStore` does with bytes that do not hash to their address: refuse them. */
        var checkHashes = false

        override suspend fun saveIncoming(
            hash: String,
            mime: String,
            srcPath: String,
        ): File? {
            if (checkHashes && sha256Hex(File(srcPath).readBytes()) != hash) return null
            val dest = File(dir, hash)
            File(srcPath).copyTo(dest, overwrite = true)
            mimes[hash] = normalizeTo ?: mime
            return dest
        }
    }

    /** A node: its transport, its store, and a [BlobExchange] wired to route requests + incoming files. */
    private class Node(
        val id: String,
        scope: CoroutineScope,
    ) {
        val transport = FakeLoopTransport(id)
        val store = FakeBlobStore(Files.createTempDirectory("blob-$id").toFile())
        val partials = PartialBlobs(Files.createTempDirectory("parts-$id").toFile(), minBytes = 1)
        var now = 0L
        val exchange =
            BlobExchange(
                transport = transport,
                store = store,
                selfId = { id },
                onObtained = { _, _ -> },
                now = { now },
                partials = partials,
            )
        private val router =
            MeshRouter(transport, scope) { _, env, fromNodeId, _ ->
                if (env.type == FrameType.BLOB_REQ) {
                    WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { exchange.onRequest(it.hash, fromNodeId, it.offset) }
                }
            }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch {
                transport.incomingFiles.collect {
                    exchange.onReceived(it.key, it.mime, it.path, it.resumedFrom)
                }
            }
        }
    }

    @Test
    fun repeatedRequestWithinTheServeMemoShipsOneCopy() =
        runTest(UnconfinedTestDispatcher()) {
            // Server a linked to requester b; count the copies b actually receives.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-serve").toFile())
            store.seed("H", "image/jpeg", "img".toByteArray())
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val received = mutableListOf<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b")
            exchange.onRequest("H", "b") // the re-ask storm around a slow transfer (re-offer / new link)
            assertEquals("second ask within the memo is a no-op", listOf("H"), received)

            now = BlobExchange.SERVE_MEMO_MS
            exchange.onRequest("H", "b")
            assertEquals("memo expired → the periodic re-ask is served again", listOf("H", "H"), received)
        }

    @Test
    fun refusedServeIsRetriableImmediately() =
        runTest(UnconfinedTestDispatcher()) {
            // No link to b yet: sendFile reports false, so the memo must not swallow the next ask.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            val store = FakeBlobStore(Files.createTempDirectory("blob-refuse").toFile())
            store.seed("H", "image/jpeg", "img".toByteArray())
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val received = mutableListOf<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b") // no live link — nothing went out
            assertTrue(received.isEmpty())

            server.connect(requester)
            exchange.onRequest("H", "b") // same instant: un-stamped refusal ⇒ served now
            assertEquals(listOf("H"), received)
        }

    @Test
    fun blobPropagatesHopByHopToOutOfRangeRequester() =
        runTest(UnconfinedTestDispatcher()) {
            // Topology: a — b — c. Only a holds the blob; c (out of a's direct range) requests it.
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            b.transport.connect(c.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            c.start(backgroundScope)

            val bytes = "an-image-blob".toByteArray()
            a.store.seed("H", "image/jpeg", bytes)

            c.exchange.want("H")

            // b pulled it from a on c's behalf — and did not push it on: b cannot tell an asker that still
            // lacks the bytes from one whose copy is already arriving from somebody else (#79).
            assertTrue("b should have cached the blob in transit", b.store.has("H"))
            assertTrue("c is served on its next ask, never pushed", !c.store.has("H"))

            c.now += BlobExchange.ROUND_MS
            c.exchange.reask() // the 60 s re-offer tick, a round later

            assertTrue("c should have obtained the blob", c.store.has("H"))
            assertArrayEquals(bytes, c.store.fileFor("H")!!.readBytes())
        }

    @Test
    fun aRelayedBlobIsForwardedUnderTheMimeTheCarrierStoredNotTheOneItWasServed() =
        runTest(UnconfinedTestDispatcher()) {
            // The header mime is unauthenticated and any holder writes it, so a carrier must not pass the
            // claim along: b re-serves what its own store resolved (knit/knit-next#30).
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            b.transport.connect(c.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            c.start(backgroundScope)

            a.store.seed("H", "audio/aac", "an-image-blob".toByteArray()) // a's claim: not what it is
            b.store.normalizeTo = "image/webp" // what b's own row says once the bytes land

            c.exchange.want("H")
            c.now += BlobExchange.ROUND_MS
            c.exchange.reask() // the re-ask that b now holds an answer to

            assertEquals("b must forward its resolved type, not a's header", "image/webp", c.store.mimeFor("H"))
        }

    @Test
    fun aBlobObtainedOffTheMeshIsNotReRequestedOnALinkUp() =
        runTest(UnconfinedTestDispatcher()) {
            // onReceived clears its own mark, but it is not the only way a want is satisfied: the spool saves
            // an attachment in ScopeSync.fetchAttachment and a direct avatar push is ingested by
            // InboundPipeline.onAvatarReceived, neither of which routes through it. A neighbor joining after
            // one of those must not be asked to re-serve bytes we already hold.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-offmesh").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val asked = CopyOnWriteArrayList<String>() // hashes n was asked for
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("spooled") // no neighbors yet — both recorded as fetching, nothing broadcast
            exchange.want("stillMissing")
            store.seed("spooled", "image/jpeg", "bytes".toByteArray()) // obtained over the Internet plane

            r.connect(n)
            exchange.reask()

            assertEquals("only the blob we still lack is asked for", listOf("stillMissing"), asked)
        }

    @Test
    fun aHeldBlobWithNoStoredMimeIsStillServed() =
        runTest(UnconfinedTestDispatcher()) {
            // Gating the serve on the mime as well as the bytes refused an ask for a blob we hold: want()
            // returns at once for a hash the store has, so nothing was ever in flight for it.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-nomime").toFile())
            store.seedWithoutMime("H", "img".toByteArray())
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val received = CopyOnWriteArrayList<Pair<String, String>>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key to it.mime } }

            exchange.onRequest("H", "b")

            assertEquals("served under the fallback type", listOf("H" to "image/jpeg"), received)
        }

    @Test
    fun fetchingGlobalCapEvictsOldestFirst() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-fetchcap").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    maxFetching = 2,
                    partials = partials(),
                )
            val asked = CopyOnWriteArrayList<String>() // hashes n was asked for
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("a") // no neighbors yet — recorded as fetching
            exchange.want("b")
            exchange.want("c") // over the cap → oldest ("a") evicted

            r.connect(n)
            exchange.reask()

            assertEquals("oldest fetch evicted; the newest two are re-asked, oldest-first", listOf("b", "c"), asked)
        }

    @Test
    fun aWantForAnArrivingBlobIsSilent() =
        runTest(UnconfinedTestDispatcher()) {
            // Its FILE_HEADER is in and the chunks are streaming: asking every neighbor now buys a second full
            // copy from each holder while the first is still on the wire (#79). The hash is not even marked —
            // if the transfer dies, the database re-arms it on the next tick, never this memo.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-arriving").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val asked = CopyOnWriteArrayList<String>()
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            r.arriving += "H"
            exchange.want("H")
            assertTrue("a blob already streaming in is not asked for", asked.isEmpty())

            r.arriving.clear() // the transfer died
            exchange.reask()
            assertTrue("it was never marked as fetching — the database re-arms it", asked.isEmpty())

            exchange.want("H") // rewantMissingBlobs on the next tick
            assertEquals(listOf("H"), asked)
        }

    @Test
    fun theTickReAskSkipsAnArrivingBlob() =
        runTest(UnconfinedTestDispatcher()) {
            // The 60 s re-offer re-asks every linked neighbor for everything still in the memo. A hash whose
            // bytes are on the way stays in it (the transfer may still die) but is not asked for.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-tick").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val asked = CopyOnWriteArrayList<String>()
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("H") // nobody linked yet — marked, nothing sent
            r.connect(n)
            r.arriving += "H" // n's serve has begun by the time the tick comes round
            exchange.reask()
            assertTrue("the tick stays quiet while the bytes stream in", asked.isEmpty())

            r.arriving.clear() // the link died mid-stream
            exchange.reask()
            assertEquals("the next tick asks again", listOf("H"), asked)
        }

    @Test
    fun aBlobTheStoreIsStillScreeningIsNeitherAskedForNorReAsked() =
        runTest(UnconfinedTestDispatcher()) {
            // #109: the store screens a blob before it holds it, so past the link's end — no longer arriving — a
            // whole classification can pass before `has`. A tick or a want in that gap bought a second copy.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-storing").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val asked = CopyOnWriteArrayList<String>()
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("H") // nobody linked yet — marked, nothing sent
            r.connect(n)
            store.storingNow += "H" // its copy came in and is being screened
            exchange.reask()
            store.storingNow += "G"
            exchange.want("G")
            assertTrue("nothing is asked for a blob the store is storing", asked.isEmpty())

            store.storingNow.clear() // the store refused both copies
            exchange.reask()
            assertEquals("the memo still holds H, so the next tick asks again; G was never marked", listOf("H"), asked)
        }

    @Test
    fun aPeerAskedBeforeIsNotAskedAgainNorPassedOverWhileItsFileStreamsIn() =
        runTest(UnconfinedTestDispatcher()) {
            // #121: n was asked for H and is streaming us G, and H may already sit in the stack's buffer behind it. H is
            // not arriving and n no longer counts it in flight, so asking n again — or asking m, who holds it too —
            // would buy a second copy. The round waits for n's stream, then gives H its grace to show up.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val m = FakeLoopTransport("m")
            r.connect(n)
            r.connect(m)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-busy").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfN = backgroundScope.asksArrivingAt(n)
            val askedOfM = backgroundScope.asksArrivingAt(m)

            exchange.want("H", author = "n")
            assertEquals(listOf("H" to null), askedOfN)
            r.arrivingFrom += "n"
            now += 3 * BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertTrue("nobody else is asked while the first holder streams to us: $askedOfM", askedOfM.isEmpty())

            r.arrivingFrom.clear() // G landed, and H did not follow it
            exchange.reask()
            assertTrue("the grace starts again when the stream ends", askedOfM.isEmpty())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals("then the round widens", listOf("H" to null), askedOfM)

            r.arrivingFrom += "n" // streaming again when the next round opens
            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals("a peer asked before is not asked while its file streams in", listOf("H" to null), askedOfN)
            assertEquals("the quiet neighbour opens the round", listOf("H" to null, "H" to null), askedOfM)

            r.arrivingFrom.clear()
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals("the first step after the stream asks it", listOf("H" to null, "H" to null), askedOfN)
        }

    @Test
    fun aRoundAsksTheAuthorAloneThenEveryOtherNeighbourOnceItsGraceIsUp() =
        runTest(UnconfinedTestDispatcher()) {
            // #136: every holder answers a fresh ask, so asking all of them at once bought a whole copy from each.
            val r = FakeLoopTransport("r")
            val peers = listOf("a", "b", "c").map { FakeLoopTransport(it).also(r::connect) }
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-round").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val asked = peers.associate { it.nodeId to backgroundScope.asksArrivingAt(it) }

            fun counts() = asked.mapValues { it.value.size }

            exchange.want("H", author = "c")
            exchange.want("H", author = "c") // the frame again, re-served by custody
            exchange.reask()
            assertEquals("the author alone, once", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            now += BlobExchange.FIRST_ASK_GRACE_MS - 1
            exchange.reask()
            assertEquals("still inside its grace", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            now += 1
            exchange.reask()
            assertEquals("then everyone else at once", mapOf("a" to 1, "b" to 1, "c" to 1), counts())
            exchange.reask()
            assertEquals("and nobody twice in a round", mapOf("a" to 1, "b" to 1, "c" to 1), counts())

            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals("the next round opens with the author again", mapOf("a" to 1, "b" to 1, "c" to 2), counts())
        }

    @Test
    fun theFirstAskGoesToTheAuthorThenTheFastPlaneThenTheSourceHop() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val byId = listOf("author", "fast", "reach", "hop", "other").associateWith { FakeLoopTransport(it).also(r::connect) }
            r.bulkRoutes["fast"] = BulkRoute.Linked
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            r.bulkRoutes["author"] = BulkRoute.None
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-rank").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val asked = byId.mapValues { (_, t) -> backgroundScope.asksArrivingAt(t) }

            fun firstAskOf(hash: String) = asked.filterValues { list -> list.any { it.first == hash } }.keys

            exchange.want("H1", author = "author", via = "hop")
            exchange.want("H2", author = "gone", via = "hop") // an author who is not a neighbour ranks nobody
            r.disconnect(byId.getValue("fast"))
            exchange.want("H3", via = "hop")
            r.disconnect(byId.getValue("reach"))
            exchange.want("H4", via = "hop")
            exchange.want("H5")

            assertEquals("the author, over a Bluetooth link", setOf("author"), firstAskOf("H1"))
            assertEquals("a neighbour linked over the fast plane", setOf("fast"), firstAskOf("H2"))
            assertEquals("one the fast plane can reach", setOf("reach"), firstAskOf("H3"))
            assertEquals("where the frame came from", setOf("hop"), firstAskOf("H4"))
            assertEquals("else the lowest id, so the choice is stable", setOf("author"), firstAskOf("H5"))
            assertEquals("only a reachable first choice is armed", listOf("reach"), r.bulkArmed)
        }

    @Test
    fun aFirstChoiceWhoseLinkIsGoneWidensTheRoundAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            // It can serve nothing on a link that is gone; waiting out its grace only delays the holders still in reach.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-gone").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)

            exchange.want("H", author = "a")
            assertTrue(askedOfB.isEmpty())
            r.disconnect(a)
            exchange.reask() // the link-down's next step, still inside the grace
            assertEquals(listOf("H" to null), askedOfB)
        }

    @Test
    fun aNeighbourThatLinksAgainIsAskedAgainAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            // An ask sent before the link dropped may have died with it. Before #136 every link-up re-asked its
            // newcomer; a round that still counts the old ask would wait out a grace, then the round, for nothing.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-relink").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val askedOfA = backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)

            exchange.want("H", author = "a")
            r.disconnect(a)
            r.connect(a) // the link drops and comes back inside the first ask's grace
            exchange.linked(listOf("a"))
            exchange.reask()
            assertEquals("asked again on the new link", listOf("H" to null, "H" to null), askedOfA)
            assertTrue("and alone: the round's first ask starts again", askedOfB.isEmpty())

            exchange.want("G", author = "a")
            r.disconnect(b) // b is asked once the round widens; then its link drops and comes back
            r.connect(b)
            exchange.linked(listOf("b"))
            exchange.reask()
            assertTrue("a link-up inside a first ask's grace waits for the widening", askedOfB.none { it.first == "G" })
        }

    @Test
    fun aReachableFirstChoiceGetsTheBulkGraceOnTopBeforeTheRoundWidens() =
        runTest(UnconfinedTestDispatcher()) {
            // A holder that can raise the Wi-Fi Aware link waits up to the composite's bulk grace for it before it falls
            // back to Bluetooth; widening inside that wait would buy a second copy over Bluetooth.
            val r = FakeLoopTransport("r")
            val reach = FakeLoopTransport("reach").also(r::connect)
            val other = FakeLoopTransport("other").also(r::connect)
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-reach").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfReach = backgroundScope.asksArrivingAt(reach)
            val askedOfOther = backgroundScope.asksArrivingAt(other)

            exchange.want("H")
            assertEquals(listOf("H" to null), askedOfReach)
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertTrue("the fast link may still be coming up", askedOfOther.isEmpty())
            now += CompositeMeshTransport.BULK_GRACE_MS
            exchange.reask()
            assertEquals(listOf("H" to null), askedOfOther)
        }

    @Test
    fun aNeighbourThatLinksWaitsOutAFirstAsksGraceButIsAskedAtOnceInAWidenedRound() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b")
            val c = FakeLoopTransport("c")
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-join").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            val askedOfC = backgroundScope.asksArrivingAt(c)

            exchange.want("H", author = "a")
            r.connect(b)
            exchange.reask() // the link-up
            assertTrue("a's ask still has its grace", askedOfB.isEmpty())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals(listOf("H" to null), askedOfB)

            r.connect(c)
            exchange.reask()
            assertEquals("the round is already wide", listOf("H" to null), askedOfC)
        }

    @Test
    fun theGraceIsTimedOnTheSessionSoTheRoundWidensWithoutATick() =
        runTest {
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-timer").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { testScheduler.currentTime },
                    partials = partials(),
                    timers = { backgroundScope },
                )
            val askedOfA = backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            runCurrent()

            exchange.want("H", author = "a")
            runCurrent()
            assertEquals(listOf("H" to null), askedOfA)
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS - 1)
            runCurrent()
            assertTrue(askedOfB.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals("widened by the timer alone", listOf("H" to null), askedOfB)
        }

    @Test
    fun aTimerArmedOnAStoppedSessionDoesNotBlockTheNextOne() =
        runTest {
            // A timer launched on a cancelled scope never runs its body; the want must still get a timer next time.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            var session: CoroutineScope = CoroutineScope(Job().apply { cancel() })
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-dead-timer").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { testScheduler.currentTime },
                    partials = partials(),
                    timers = { session },
                )
            backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            runCurrent()

            exchange.want("H", author = "a") // its grace timer lands on the stopped session
            session = backgroundScope // the mesh starts again
            exchange.reask() // still inside the grace: arms a timer on the live session
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS + 1)
            runCurrent()
            assertEquals("widened by the new session's timer", listOf("H" to null), askedOfB)
        }

    @Test
    fun aBlobPulledForANeighbourIsNotAskedOfItThatRoundButIsTheNext() =
        runTest(UnconfinedTestDispatcher()) {
            // It asked us because it lacks the bytes, so asking it back costs the round a grace for nothing. By the next
            // round it may hold them, and it may be the one path they have to us (a relay, or the only link).
            val r = FakeLoopTransport("r")
            val asker = FakeLoopTransport("a").also(r::connect)
            val other = FakeLoopTransport("z").also(r::connect)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-asker").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfAsker = backgroundScope.asksArrivingAt(asker)
            val askedOfOther = backgroundScope.asksArrivingAt(other)

            exchange.onRequest("H", "a")
            exchange.want("H", author = "a") // its frame lands too: still not this round
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertTrue("not asked back the round we pull for it: $askedOfAsker", askedOfAsker.isEmpty())
            assertEquals(listOf("H" to null), askedOfOther)

            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals("the next round opens with it, the author", listOf("H" to null), askedOfAsker)
        }

    @Test
    fun aReAskWhileTheCopyIsQueuedOnTheLinkShipsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            // The memo is 45 s from the enqueue; a serve queued behind a multi-minute blob to the same peer,
            // or an older build's 60 s re-ask against a slow transfer, outlives it. The link knows what it
            // still holds for that peer, and that is what refuses the second copy.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-inflight").toFile())
            store.seed("H", "image/jpeg", "img".toByteArray())
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val received = CopyOnWriteArrayList<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b")
            assertEquals(listOf("H"), received)

            now = BlobExchange.SERVE_MEMO_MS // memo expired
            server.inFlight += "b" to "H" // ...but the copy is still queued or streaming on the link
            exchange.onRequest("H", "b")
            assertEquals("the in-flight copy is enough", listOf("H"), received)

            server.inFlight.clear() // the stream ended (or the link, with it)
            exchange.onRequest("H", "b")
            assertEquals("a fresh ask after the stream is served", listOf("H", "H"), received)
        }

    @Test
    fun aRequesterWeLackForIsServedOnItsNextAskNotPushed() =
        runTest(UnconfinedTestDispatcher()) {
            // A neighbor asked while we lacked the bytes; whichever plane hands them to us later, we do not
            // push: it may hold them by now, or be receiving them from the author — only its next ask says it
            // still lacks them (ADR 2026-09.4tx5, superseding the wanter drain of ADR 2026-09.ywzn).
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-nopush").toFile())
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val received = CopyOnWriteArrayList<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b") // we lack it: pulled on b's behalf, nothing remembered about b
            assertTrue("nothing to serve yet", received.isEmpty())

            val src = Files.createTempFile("blob-src", ".bin").toFile().apply { writeBytes("img".toByteArray()) }
            exchange.onReceived("H", "image/jpeg", src.absolutePath) // a neighbor served our pull
            assertTrue("obtaining the bytes pushes nothing", received.isEmpty())

            exchange.onRequest("H", "b") // b's own 60 s tick, while it still lacks them
            assertEquals(listOf("H"), received)
        }

    // --- A blob that lands while it is being read (#138) ---

    @Test
    fun aBlobThatLandsWhileItsReadsRunIsNotAskedForAgain() =
        runTest(UnconfinedTestDispatcher()) {
            // The bytes move link → store → held, and every read goes the other way round: a blob that moves on between
            // two reads is seen by the later one. Read store-first, the tick below saw it neither held (the store's
            // answer predates the landing) nor arriving (it had left the link), and asked the holder for a second copy.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-landing").toFile())
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val asked = backgroundScope.asksArrivingAt(n)
            exchange.want(PICTURE_HASH)
            assertEquals(listOf(PICTURE_HASH to null), asked)

            r.arriving += PICTURE_HASH // n's copy streams in
            now += BlobExchange.ROUND_MS // and the next round is due
            store.whileReadingHas = { hash ->
                store.whileReadingHas = null
                store.seed(hash, "image/jpeg", PICTURE) // it lands, and is held,
                r.arriving -= hash // as it leaves the link
            }
            exchange.reask()
            exchange.reask()
            assertEquals("asked once, while it was the only way to the bytes", listOf(PICTURE_HASH to null), asked)
        }

    @Test
    fun aFileIsTakenOffTheLinkOnceTheStoreHasAnsweredSoARefusedSpliceStillAsksFromZero() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-taken").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            exchange.want(PICTURE_HASH)
            assertTrue(partials.keep(PICTURE_HASH, cutFile(ByteArray(100)), basedOn = null)) // a bad prefix
            asked.clear()

            r.arriving += PICTURE_HASH // the spliced file, landing: the link counts it until it is taken
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(ByteArray(PICTURE.size)).absolutePath, resumedFrom = 100, from = "n")
            assertEquals("taken off n's link once the store refused it", listOf("n" to PICTURE_HASH), r.taken)
            assertEquals("so its own landing never kept the from-0 ask quiet", listOf(PICTURE_HASH to null), asked)

            r.arriving += PICTURE_HASH
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(PICTURE).absolutePath, from = "n")
            assertTrue(store.has(PICTURE_HASH))
            assertEquals("and once it held it", listOf("n" to PICTURE_HASH, "n" to PICTURE_HASH), r.taken)
            assertTrue(PICTURE_HASH !in r.arrivingFiles())
        }

    // --- Resuming a transfer a link drop cut off (#116, ADR 2026-10.wtyc) ---

    /** Collects every blob request [peer] receives as (hash, offset). */
    private fun CoroutineScope.asksArrivingAt(peer: FakeLoopTransport): MutableList<Pair<String, Long?>> {
        val asked = CopyOnWriteArrayList<Pair<String, Long?>>()
        launch {
            peer.inbound.collect { f ->
                if (f.envelope.type == FrameType.BLOB_REQ) {
                    WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash to it.offset }
                }
            }
        }
        return asked
    }

    /** A temp file holding [bytes], as a link's cut would leave it. */
    private fun cutFile(bytes: ByteArray): File = Files.createTempFile("cut", ".bin").toFile().apply { writeBytes(bytes) }

    @Test
    fun theFirstAskAndTheTickBothCarryTheOffset() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val partials = partials()
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-resume-tick").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            exchange.want(PICTURE_HASH)
            assertEquals("nothing kept: an ask for the whole file, byte-identical to an older build's", listOf(PICTURE_HASH to null), asked)
            assertTrue(partials.keep(HASH_H, cutFile(ByteArray(300)), basedOn = null)) // a link was cut 300 B in

            exchange.want(HASH_H)
            now += BlobExchange.ROUND_MS
            exchange.reask() // the 60 s tick, a round later
            assertEquals(listOf(PICTURE_HASH to null, HASH_H to 300L, PICTURE_HASH to null, HASH_H to 300L), asked)
        }

    @Test
    fun aServeStreamsFromTheAskersOffsetUpToTheWholeLength() =
        runTest(UnconfinedTestDispatcher()) {
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-resume-serve").toFile())
            store.seed("H", "image/jpeg", "image".toByteArray()) // five bytes
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            listOf(2L, 5L, 6L, 0L, -1L, null).forEach { asked ->
                exchange.onRequest("H", "b", asked)
                now += BlobExchange.SERVE_MEMO_MS // each ask a fresh one
            }
            assertEquals(
                "a resume, an empty tail for a prefix already complete, and the peer's nonsense as the whole file",
                listOf(2L, 5L, 0L, 0L, 0L, 0L),
                server.filesSent.map { it.offset },
            )
            assertEquals(FileMeta(FileKind.ATTACHMENT, "H", "image/jpeg", offset = 2L), server.filesSent.first())
        }

    @Test
    fun aSpliceThatFailsItsHashDropsThePrefixAndOpensARoundForTheWholeBlobAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n1 = FakeLoopTransport("n1")
            val n2 = FakeLoopTransport("n2")
            r.connect(n1)
            r.connect(n2)
            val store = FakeBlobStore(Files.createTempDirectory("blob-splice-bad").toFile()).apply { checkHashes = true }
            val partials = partials()
            var refused = 0
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials,
                    onSpliceRefused = { refused++ },
                )
            val asked1 = backgroundScope.asksArrivingAt(n1)
            val asked2 = backgroundScope.asksArrivingAt(n2)
            exchange.want(PICTURE_HASH)
            assertTrue(partials.keep(PICTURE_HASH, cutFile(ByteArray(100)), basedOn = null)) // a bad prefix
            asked1.clear()
            asked2.clear()

            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(ByteArray(PICTURE.size)).absolutePath, resumedFrom = 100)

            assertEquals(1, refused)
            assertEquals("the prefix may be what was wrong", 0L, partials.length(PICTURE_HASH))
            assertEquals("asked again from byte 0, now, inside the first ask's grace", listOf(PICTURE_HASH to null), asked1)
            assertTrue("a new round opens with one ask", asked2.isEmpty())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals(listOf(PICTURE_HASH to null), asked2)
            assertTrue("still wanted", !store.has(PICTURE_HASH))
        }

    @Test
    fun aFailedSpliceStaysQuietWhileTheBlobIsArrivingOrWhenNoPrefixWasLeft() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-splice-quiet").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            val bad = { cutFile(ByteArray(PICTURE.size)).absolutePath }

            partials.keep(PICTURE_HASH, cutFile(ByteArray(100)), basedOn = null)
            r.arriving += PICTURE_HASH // another link is streaming it in right now (ADR 2026-09.4tx5)
            exchange.onReceived(PICTURE_HASH, "image/jpeg", bad(), resumedFrom = 100)
            assertEquals("the prefix still goes", 0L, partials.length(PICTURE_HASH))
            assertTrue("but nobody is asked while it arrives", asked.isEmpty())

            r.arriving.clear()
            exchange.onReceived(PICTURE_HASH, "image/jpeg", bad(), resumedFrom = 100)
            assertTrue("no live prefix dropped, so no ask a peer could trigger at will", asked.isEmpty())
        }

    @Test
    fun aWholeFileThatFailsItsHashLeavesTheKeptPrefixAlone() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-whole-bad").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            partials.keep(PICTURE_HASH, cutFile(PICTURE.copyOf(100)), basedOn = null)

            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(ByteArray(PICTURE.size)).absolutePath)
            assertEquals("the holder's bytes were wrong, not ours", 100L, partials.length(PICTURE_HASH))
            assertTrue("the tick asks again, as it always has", asked.isEmpty())
        }

    @Test
    fun aStoredBlobOrOneObtainedOffMeshDropsItsKeptPrefix() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-landed").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )

            partials.keep(PICTURE_HASH, cutFile(PICTURE.copyOf(100)), basedOn = null)
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(PICTURE).absolutePath, resumedFrom = 100)
            assertTrue(store.has(PICTURE_HASH))
            assertEquals("landed: nothing left to resume", 0L, partials.length(PICTURE_HASH))

            // The spool delivered another one while a prefix was kept: the next re-ask finds it held.
            val other = "photo two".toByteArray()
            val otherHash = sha256Hex(other)
            exchange.want(otherHash)
            partials.keep(otherHash, cutFile(other.copyOf(4)), basedOn = null)
            store.seed(otherHash, "image/jpeg", other)
            exchange.reask()
            assertEquals(0L, partials.length(otherHash))
        }

    private companion object {
        val PICTURE = ByteArray(2_000) { (it % 97).toByte() }
        val PICTURE_HASH = sha256Hex(PICTURE)
        val HASH_H = "e".repeat(64)
    }
}
