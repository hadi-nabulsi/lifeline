package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.PRESENCE_FRESH_MS
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.lora.LoraGatewayPolicy.Role
import app.getknit.knit.mesh.lora.LoraMeshTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two mesh pockets bridged by boards (ADR 044, ADR 054), with the real `LoraMeshTransport` composed into
 * real stacks over one [FakeMeshtasticAir] — the "two real stacks" leg the five two-board-rig verifications
 * (y8pu, rre4, t8t8, 7c8n, qsj6) still owe. A pocket is whatever a node holds links to; the boards elect a
 * gateway per pocket off those links. The Trickle interval is shortened through [LabLimits] so an OFFER
 * and the backfill it drives happen inside an await; nothing else about the plane is changed.
 */
@RunWith(RobolectricTestRunner::class)
class LoraPocketLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * A Trickle short enough for an OFFER inside an await, long enough not to spend the airtime window on
     * offers alone: at one a second the GOSSIP share ate the tail a tick needs (`loraTickDeferred`).
     */
    private val quick = LabLimits(loraGossipMinMs = 4_000, loraGossipMaxMs = 8_000)

    /**
     * Everyone meets by radio first, as people who later split into pockets did: profiles, custody parity,
     * then the cross-pocket links go. First contact by board alone is minutes, not seconds — a passive
     * gateway fans nothing and the bridge budget serves one profile per 15-min window — and is the clock
     * tier's to pin.
     */
    private suspend fun meetThenSplit(
        nodes: List<LabNode>,
        keep: List<Pair<LabNode, LabNode>>,
    ) {
        val all = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
        lab.linkAll(*all.toTypedArray())
        lab.awaitAcquainted(*nodes.toTypedArray())
        lab.awaitCustodyParity(*nodes.toTypedArray())
        all
            .filterNot { (a, b) ->
                keep.any { (x, y) -> (x === a && y === b) || (x === b && y === a) }
            }.forEach { (a, b) -> lab.unlink(a, b) }
    }

    /** Pocket A is Alice's board plus Bob; pocket B is Carol's board plus Dave. A post from a board-less phone crosses. */
    @Test
    fun aRoomPostCrossesTwoPocketsThroughTheGatewaysOnly() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", quick).apply { setDisplayName("Dave") }
            meetThenSplit(listOf(alice, bob, carol, dave), keep = listOf(alice to bob, carol to dave))

            val aliceFanned = alice.loraTx("fanout:chat")
            assertTrue(bob.sendRoom("from pocket a"))
            val post = bob.ownMessageId(Conversations.NEARBY, "from pocket a")
            assertTrue("dave never heard bob's post", lab.tryAwait(1) { dave.roomPosts()[bob.nodeId]?.size ?: 0 })
            // Waited, not read: the board logs its `lora tx` line after the air already holds the packet, so Dave can
            // have the post first. Live or as backfill (`loraAired`): Carol's OFFER can reach Alice's board between
            // her custody of the relayed post and its re-fan, and the bridge then airs it instead.
            assertTrue(
                "alice's board carried it: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraAired(post, "fanout:chat", aliceFanned) },
            )
            assertTrue("carol's board heard it", carol.metrics.lora().loraReceived >= 1)
            assertEquals("bob has no board", 0L, bob.metrics.lora().loraSent)

            // Custody agrees within a pocket, never across: a DM-form frame to a linked peer (Alice's own
            // tick to Bob) never rides the board. The far pocket's ticks are the next scenario's subject.
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.assertConverged(listOf(carol, dave), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * Issue #48, closed by the gateway taking the tick's last hop. Carol's ✓✓ for Bob's post leaves her board
     * as the targeted `send:chat` (the ride hold runs out, ADR 2026-09.y5f3) and Alice's board hears it. It is
     * a point-to-point `relay = false` frame, so Alice neither delivers it (not hers) nor floods it, and a room
     * tick never escalates into custody (ADR 2026-09.aa27) — but it is addressed to a peer Alice holds a live
     * link to, so `MeshRouter.handOn` hands it over that link. The oracle is the plane Bob records it on: the
     * last hop was the link, not the air.
     *
     * Dave's tick is the residual this does not close: an acker with no board and no spool has nowhere to send
     * one at all.
     */
    @Test
    fun aFarPocketsTickReachesABoardLessAuthorBehindTheGateway() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", quick).apply { setDisplayName("Dave") }
            meetThenSplit(listOf(alice, bob, carol, dave), keep = listOf(alice to bob, carol to dave))

            assertTrue(bob.sendRoom("from pocket a"))
            val post = bob.ownMessageId(Conversations.NEARBY, "from pocket a")
            assertTrue(lab.tryAwait(1) { carol.roomPosts()[bob.nodeId]?.size ?: 0 })
            val via = lab.awaitReceipt(bob, post, carol)
            assertTrue("the tick came the last hop over the link, not the board: $via", via != DeliveryPlane.LoRa)
            // Per pocket, as the scenario above: the tick Alice hands on is `relay = false`, so neither custody moves.
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.assertConverged(listOf(carol, dave), atLeast = 1) { Conversations.NEARBY }
        }

    /** ADR 054's recipient gate: a DM to someone on a live link never spends airtime. */
    @Test
    fun aDmToALinkedPeerNeverRidesTheBoard() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            assertTrue(alice.sendDm(bob, "over the link"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "and back"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("the gate never fired", alice.metrics.lora().loraSkippedLinked >= 1)
            assertEquals("nothing of the DM went on the air: ${alice.loraLog}", 0, alice.loraTx("far:chat"))
            assertEquals("nothing of the reply went on the air: ${bob.loraLog}", 0, bob.loraTx("far:chat"))
        }

    /**
     * ADR 2026-09.y8pu: a post fanned out while the far board could not hear is backfilled once it can —
     * the far gateway's OFFER names what it holds, and the near one serves what is missing.
     */
    @Test
    fun aPostFannedIntoAnEmptySkyIsBackfilledWhenTheFarBoardReturns() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            lab.link(alice, carol)
            lab.awaitAcquainted(alice, carol)
            lab.awaitCustodyParity(alice, carol)
            lab.unlink(alice, carol)

            air.lossy = { _, _ -> true }
            val fanned = alice.loraTx("fanout:chat")
            assertTrue(alice.sendRoom("into an empty sky"))
            val post = alice.ownMessageId(Conversations.NEARBY, "into an empty sky")
            // Into the empty sky either way: an OFFER Carol aired before the loss began can still have the bridge serve
            // the post between custody and fan-out (`loraAired`), and that copy is lost exactly as the fan-out's is.
            assertTrue("the post never left: ${alice.loraLog}", lab.tryAwait(1) { alice.loraAired(post, "fanout:chat", fanned) })
            air.lossy = { _, _ -> false }

            assertTrue("the backfill never repaired it", lab.tryAwait(1) { carol.roomPosts()[alice.nodeId]?.size ?: 0 })
            assertTrue("it came as a bridge serve", alice.metrics.lora().loraBridged >= 1)
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { Conversations.NEARBY }
        }

    /** Two boards on one link are one pocket: the election leaves one of them passive, and it stays quiet. */
    @Test
    fun aSecondBoardInAPocketGoesPassiveAndStaysQuiet() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val amber = lab.node("amber", quick, air = air).apply { setDisplayName("Amber") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            meetThenSplit(listOf(alice, amber, carol), keep = listOf(alice to amber))

            // The lowest publisher key speaks for a pocket (`LoraGatewayPolicy`), so of the pair still linked the
            // higher key stands down once it hears the other's OFFER. Not `loraPassive`: it counts every transmit a
            // board skipped since it booted, and in the three-board meeting both of these skip whenever Carol's key
            // is the lowest, so the board the pair then elects keeps its count.
            val (active, passive) = listOf(alice, amber).sortedBy { StoreDigest.hash64(it.nodeId) }
            assertTrue(
                "${passive.name} never stood down for ${active.name} (alice ${alice.loraRole()}, amber ${amber.loraRole()})",
                lab.tryAwait(1) { if (passive.loraRole() == Role.PASSIVE && active.loraRole() == Role.ACTIVE) 1 else 0 },
            )
            val activeFanned = active.loraTx("fanout:chat")

            assertTrue(passive.sendRoom("from the passive board's phone"))
            val post = passive.ownMessageId(Conversations.NEARBY, "from the passive board's phone")
            assertTrue("carol never heard it", lab.tryAwait(1) { carol.roomPosts()[passive.nodeId]?.size ?: 0 })
            // Live or as backfill (`loraAired`): Carol's OFFER can land between the active board's custody of the post
            // and its re-fan, and the bridge then airs it instead — one transmission either way.
            assertTrue(
                "the active board carried it: ${active.loraLog}",
                lab.tryAwait(1) { active.loraAired(post, "fanout:chat", activeFanned) },
            )
            assertEquals("the passive board fanned nothing", 0, passive.loraTx("fanout:chat"))
            assertEquals("the passive board served nothing of it", 0, passive.loraTx("bridge:$post"))
            lab.assertConverged(listOf(alice, amber), atLeast = 1) { Conversations.NEARBY }
        }

    /** The two-phone trial's last leg: a DM sent while the far board could not hear lands through the bridge. */
    @Test
    fun aDmSentWhileTheFarBoardWasOffLandsThroughTheBridge() =
        runBlocking {
            val air = FakeMeshtasticAir()
            // A DM that arrived over the board is ticked through DmAckCoalescer's 45 s hold (ADR 054); the
            // oracle waits for that tick, so the pair holds it for a second instead.
            val ticked = quick.copy(dmAckHoldMs = 1_000L)
            val alice = lab.node("alice", ticked, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", ticked, air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.awaitCustodyParity(alice, bob)
            lab.unlink(alice, bob)

            air.lossy = { _, _ -> true }
            val farBefore = alice.loraTx("far:chat")
            assertTrue(alice.sendDm(bob, "while your board was off"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "while your board was off")
            // Live as the probe if Bob's board had gone quiet on Alice's (ADR 2026-10.a4pm), live as an ordinary DM if
            // its hearing still lingers — or, as the empty-sky post above, from the bridge after an OFFER Bob's board
            // aired before the loss. Into the empty sky whichever it was.
            assertTrue("the DM never left: ${alice.loraLog}", lab.tryAwait(1) { alice.loraAired(dm, "far:chat", farBefore) })
            air.lossy = { _, _ -> false }

            // It lands: through the bridge's backfill, or through the re-offer once Alice hears Bob's board again.
            assertTrue("the DM never landed", lab.tryAwait(3) { bob.decrypted(bob.dmWith(alice)).size })
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The reach-gate scenarios' limits: a ✓✓ that comes back in a second rather than after DmAckCoalescer's 45 s,
     * and no bridge, so the held DMs can only come by the first-hearing re-offer — the path under test.
     */
    private val reach = quick.copy(dmAckHoldMs = 1_000L, loraBridge = false, loraLingerSweepMs = 500L)

    /**
     * Alice and Bob, each with a board, meet over a link and exchange a DM each way, then go quiet for longer
     * than the plane remembers a hearing (`REACHABLE_LINGER_MS`, 45 min — the calendar moves, as the field's
     * afternoon would) and split. The unlink is what re-reads the heard set, so each is unheard on the other's
     * board from that moment: the shape of two friends whose boards have not spoken all day.
     */
    private suspend fun quietPair(
        air: FakeMeshtasticAir,
        limits: LabLimits,
    ): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", limits, air = air).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", limits, air = air).apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        assertTrue(alice.sendDm(bob, "hello"))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
        assertTrue(bob.sendDm(alice, "hi"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        lab.awaitCustodyParity(alice, bob)
        // Split first, then move the calendar: a relay copy still in its 0–150 ms jitter lands inside the router's
        // 10-min SeenSet and is deduplicated, where landing after the jump it would be a re-delivery that mints a
        // fresh ✓✓. The shortened linger sweep then has each board forget the other inside the await.
        lab.unlink(alice, bob)
        lab.clock.advance(LoraMeshTransport.REACHABLE_LINGER_MS + MINUTE)
        lab.await(1) { if (alice.loraHears(bob) || bob.loraHears(alice)) 0 else 1 }
        return alice to bob
    }

    /**
     * ADR 2026-10.a4pm, end to end: a DM to a peer the board has not heard rides only as the window's one probe.
     * Alice texts a quiet Bob three times. The first goes out as `far:chat`; the others wait in custody. Bob's
     * board hears the probe, so Alice is heard there and his ✓✓ rides back; that makes Bob heard on Alice's
     * board, and her first hearing of him re-offers what she held. The bridge is off ([reach]), so nothing else
     * can carry it.
     */
    @Test
    fun aDmToAQuietFarPeerProbesOnceAndTheRestLandOnItsTick() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            val farBefore = alice.loraTx("far:chat")
            val probesBefore = alice.metrics.lora().loraDmProbes
            val heldBefore = alice.metrics.lora().loraUnheardHeld
            val reofferedBefore = alice.metrics.lora().loraReoffered

            listOf("are you up there", "it is windy", "bring a coat").forEach { assertTrue(alice.sendDm(bob, it)) }

            lab.assertConverged(listOf(alice, bob), atLeast = 5) { it.dmWith(if (it === alice) bob else alice) }
            val probes = alice.metrics.lora().loraDmProbes - probesBefore
            val held = alice.metrics.lora().loraUnheardHeld - heldBefore
            val live = alice.loraTx("far:chat") - farBefore
            val reoffered = alice.metrics.lora().loraReoffered - reofferedBefore
            assertEquals("exactly one probe: ${alice.loraLog}", 1L, probes)
            // Each DM aired once, live or re-offered: held behind the probe, or live because Bob's ✓✓ had already
            // made him heard when it was sent — and a DM held while that first hearing's re-offer was reading
            // custody is re-offered by it all the same. Which, the interleaving picks; the sum it cannot.
            assertEquals("each DM aired once: live=$live reoffered=$reoffered", 3L, live + reoffered)
            assertTrue("the probe is the first thing aired: ${alice.loraLog}", live >= 1)
            assertTrue("a held DM is never left behind: held=$held reoffered=$reoffered", held <= reoffered)
        }

    /**
     * The cost side of the same rule: Bob's board is off (the air loses everything), and Alice texts him
     * anyway. One probe per window reaches the air, not one per DM. Once the window has ended and Bob's board
     * is back, the next DM probes again, his ✓✓ makes him heard, and the re-offer carries the backlog.
     */
    @Test
    fun aDmToAnAbsentPeerSpendsOneProbePerWindow() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            val farBefore = alice.loraTx("far:chat")
            val probesBefore = alice.metrics.lora().loraDmProbes
            val reofferedBefore = alice.metrics.lora().loraReoffered

            val heldBefore = alice.metrics.lora().loraUnheardHeld
            air.lossy = { _, _ -> true }
            listOf("one", "two", "three").forEach { assertTrue(alice.sendDm(bob, it)) }
            lab.await(2) { (alice.metrics.lora().loraUnheardHeld - heldBefore).toInt() }
            assertTrue("the probe never left: ${alice.loraLog}", lab.tryAwait(1) { alice.loraTx("far:chat") - farBefore })
            assertEquals("one probe for three DMs", 1L, alice.metrics.lora().loraDmProbes - probesBefore)

            lab.clock.advance(LoraMeshTransport.DM_PROBE_WINDOW_MS)
            air.lossy = { _, _ -> false }
            assertTrue(alice.sendDm(bob, "four"))

            lab.assertConverged(listOf(alice, bob), atLeast = 6) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("the window reopened once: ${alice.loraLog}", 2L, alice.metrics.lora().loraDmProbes - probesBefore)
            // The first probe, lost to the dark board, comes back too: its dedup slot lapsed inside the window.
            assertEquals("the backlog came by the re-offer", 3L, alice.metrics.lora().loraReoffered - reofferedBefore)
        }

    /**
     * The same three DMs with the bridge on, as the field runs it. Bob's OFFER can have Alice's bridge serve the
     * held DMs before his ✓✓ makes him heard, onto a BRIDGE share that carries about three one-packet frames a
     * window at the lab's preset; the re-offer then lifts a copy still waiting there onto LIVE rather than being
     * turned away by the dedup slot it took. Converging inside the await — no calendar jump — is the proof that
     * nothing sat out a window on BRIDGE. Which path carried which DM, the interleaving picks.
     */
    @Test
    fun aDmToAQuietFarPeerLandsWithTheBridgeOnWithoutWaitingOutTheBridgeShare() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, quick.copy(dmAckHoldMs = 1_000L, loraLingerSweepMs = 500L))
            val probesBefore = alice.metrics.lora().loraDmProbes
            val farBefore = alice.loraTx("far:chat")
            val bodies = listOf("are you up there", "it is windy", "bring a coat")
            bodies.forEach { assertTrue(alice.sendDm(bob, it)) }

            lab.assertConverged(listOf(alice, bob), atLeast = 5) { it.dmWith(if (it === alice) bob else alice) }
            // At most one probe, and not always the first DM's: an OFFER of Bob's landing in the gap between a DM's
            // custody and its fan-out has the bridge serve it first and take its slot, so the next DM probes — and
            // if the bridge's serves make Bob's ✓✓ come back before any fan-out, none does.
            assertTrue("one probe at most: ${alice.loraLog}", alice.metrics.lora().loraDmProbes - probesBefore in 0L..1L)
            // Each of the three left the board once: by id as `reoffer:` or `bridge:`, or live as `far:chat`.
            val ids = bodies.map { alice.ownMessageId(alice.dmWith(bob), it) }
            val byId = ids.count { id -> alice.loraTx("reoffer:$id") + alice.loraTx("bridge:$id") > 0 }
            val live = alice.loraTx("far:chat") - farBefore
            assertTrue("every DM left the board: byId=$byId live=$live ${alice.loraLog}", byId + live >= 3)
        }

    /**
     * The gateway half (ADR 2026-10.a4pm): Bob has no board and rides Alice's; Carol's board is far and quiet. A DM
     * of Bob's reaches Alice's board relayed and unhinted, so it may take the probe because Alice holds a live
     * link to its author; Carol's ✓✓ then makes her heard, and Alice's first hearing re-offers Bob's held DMs. The
     * bridge is off, so only the probe and the re-offer can carry them — within one probe window, no clock jump.
     */
    @Test
    fun aBoardLessPocketMatesDmsToAQuietFarPeerProbeThroughTheGateway() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", reach, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", reach).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", reach, air = air).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(bob.sendDm(carol, "hello"))
            lab.assertConverged(listOf(bob, carol), atLeast = 1) { it.dmWith(if (it === bob) carol else bob) }
            lab.awaitCustodyParity(alice, bob, carol)
            // Cut Carol first, then move the calendar, as quietPair does and for its reason.
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.clock.advance(LoraMeshTransport.REACHABLE_LINGER_MS + MINUTE)
            lab.await(1) { if (alice.loraHears(carol) || carol.loraHears(alice)) 0 else 1 }
            val probesBefore = alice.metrics.lora().loraDmProbes

            listOf("are you up there", "it is windy", "bring a coat").forEach { assertTrue(bob.sendDm(carol, it)) }

            // Custody across all three: Alice carries the pocket's frames, and everything either side sent crossed the
            // hop — the DMs by probe and re-offer, Carol's ✓✓s ungated.
            lab.assertConverged(listOf(bob, carol), atLeast = 4, carriers = listOf(alice)) { it.dmWith(if (it === bob) carol else bob) }
            assertEquals(
                "the gateway probed once for its pocket-mate: ${alice.loraLog}",
                1L,
                alice.metrics.lora().loraDmProbes - probesBefore,
            )
            assertEquals("bob has no board", 0L, bob.metrics.lora().loraSent)
        }

    /**
     * The absent peer comes back long after: Bob's board was dark while Alice texted, and returns more than
     * `PRESENCE_FRESH_MS` later with a post in the room — the first fresh frame of his Alice's board hears. Alice
     * writes nothing new. (Not a profile edit: Bob's first-hearing beacon can take the 5-min floor ahead of the edit,
     * and the edited profile then waits out the floor — a residual of ADR 038 §6, not this scenario's.) Her first hearing
     * re-offers the old DMs — too old to make *her* heard on Bob's board — so Bob's ✓✓s ride only because our own
     * ticks are not gated (ADR 2026-10.a4pm); gated, Alice would show one tick until radio contact.
     */
    @Test
    fun anAbsentPeerThatReturnsLongAfterTicksEveryDmWithoutANewOne() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            air.lossy = { _, _ -> true }
            listOf("one", "two").forEach { assertTrue(alice.sendDm(bob, it)) }
            assertTrue(
                "the probe never left: ${alice.loraLog}",
                lab.tryAwait(1) {
                    alice.metrics
                        .lora()
                        .loraDmProbes
                        .toInt()
                },
            )

            lab.clock.advance(PRESENCE_FRESH_MS + MINUTE)
            air.lossy = { _, _ -> false }
            assertTrue(bob.sendRoom("back in range")) // live traffic: his board is heard again

            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** Whether this node's board has heard [peer] within the linger — the reach the DM gate reads. */
    private fun LabNode.loraHears(peer: LabNode): Boolean =
        checkNotNull(lora) { "$name has no board" }.reachable.value.any { it.nodeId == peer.nodeId }

    /** The board's gateway role, the field its plane transmits by (`LoraMeshTransport.gatewayRole`), not its status. */
    private fun LabNode.loraRole(): Role = checkNotNull(lora) { "$name has no board" }.gatewayRole

    private companion object {
        const val MINUTE = 60_000L
    }
}
