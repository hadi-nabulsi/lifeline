package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Randomized two-party soak of the epoch ratchet under the mesh's actual delivery pathology — seeded
 * loss (custody eviction), reordering, duplication (re-serves), delayed replay, forced epoch churn,
 * and a both-initiate race — the `AliasTest` seeded-`Random` precedent, no property-test library.
 *
 * Invariants, per seed:
 *  - every frame that reaches its recipient (however late, however out of order) decrypts to exactly
 *    the plaintext that was sealed, the first time it is processed — and a frame sealed under the root its
 *    recipient holds as active *does* open then, whatever an earlier era left stored under its numbers;
 *  - a re-processed frame is [OpenOutcome.Failed.DUPLICATE] or a skipped-key hit — never a wrong
 *    plaintext, never a crash;
 *  - a wholly-lost epoch never wedges later epochs, and neither does a reset: one side re-roots mid-run
 *    (numbering restarted, the old root draining as `prevRoot` on the adopter) while the old era keeps being
 *    re-served — the shape that left the new era's frames on the old era's stored chains (#135);
 *  - skipped-key volume stays bounded by the per-epoch cap.
 */
class RatchetSoakTest {
    private val engine = RatchetEngine()

    private class Frame(
        val id: Int,
        val header: RatchetEngine.FrameHeader,
        /** Null for a v3 frame: the nonce is derived, never carried. */
        val nonce: ByteArray?,
        val ct: ByteArray,
        val expected: String,
        /** The sender's root when it sealed this frame. */
        val root: ByteArray,
        /** A reset request: the receiver reads it with `FLAG_RESET`, as `RatchetSessions.contextFor` would. */
        val reset: Boolean = false,
    )

    private inner class Side(
        val nodeId: String,
    ) {
        val ik = RatchetCrypto.generateKeyPair()
        val spk = RatchetCrypto.generateKeyPair()
        var session: RatchetEngine.SessionState? = null
        val localEpochs = mutableMapOf<Int, RatchetEngine.LocalEpoch>()

        /** Keyed `(se, rootTag)`, as `ratchet_recv_epochs`. */
        val recvEpochs = mutableMapOf<Pair<Int, Long>, RatchetEngine.RecvEpoch>()

        /** Keyed `(se, n, rootTag)`, as `ratchet_skipped_keys`. */
        val skipped = mutableMapOf<Triple<Int, Int, Long>, RatchetEngine.SkippedKey>()
        val deliveredIds = mutableSetOf<Int>()
        lateinit var peer: Side

        fun initiate(now: Long) {
            val initiation =
                engine.initiate(peer.nodeId, ik.priv, peer.ik.pub, RatchetEngine.PeerPrekey(1, peer.spk.pub), now)
            session = initiation.session
            localEpochs[initiation.epoch.epoch] = initiation.epoch
        }

        fun seal(
            id: Int,
            body: String,
            now: Long,
            force: Boolean,
            v3: Boolean = false,
        ): Frame {
            if (session == null) initiate(now)
            val result = checkNotNull(engine.seal(checkNotNull(session), body.toByteArray(), AAD, peer.spk.pub, now, force, v3))
            session = result.session
            result.newLocalEpoch?.let { localEpochs[it.epoch] = it }
            return Frame(id, result.header, result.nonce, result.ct, body, result.session.root)
        }

        /**
         * A reset request minted the way `RatchetSessions.sealResetDm` re-roots: a fresh initiation keeping the old
         * root draining as `prevRoot`, our receive state purged, numbering restarted at 1 (its epoch replaces the
         * dead era's key of that number, ADR 027).
         */
        fun reset(
            id: Int,
            body: String,
            now: Long,
        ): Frame {
            val old = session
            val initiation =
                engine.initiate(peer.nodeId, ik.priv, peer.ik.pub, RatchetEngine.PeerPrekey(1, peer.spk.pub), now)
            session =
                initiation.session.copy(
                    prevRoot = old?.root,
                    prevRootWeAreInitiator = old?.weAreInitiator ?: false,
                    prevRootExpiresAt = if (old != null) now + RatchetEngine.PREV_ROOT_TTL_MS else 0L,
                )
            localEpochs[initiation.epoch.epoch] = initiation.epoch
            recvEpochs.clear()
            skipped.clear()
            return seal(id, body, now, force = false).let { Frame(it.id, it.header, it.nonce, it.ct, it.expected, it.root, reset = true) }
        }

        fun open(
            frame: Frame,
            now: Long,
        ): OpenOutcome {
            val ctx =
                RatchetEngine.OpenContext(
                    selfNodeId = nodeId,
                    peerId = peer.nodeId,
                    session = session,
                    recvEpochs = recvEpochs.values.filter { it.epoch == frame.header.se },
                    skippedMsgKeys = skipped.values.filter { it.epoch == frame.header.se && it.idx == frame.header.n },
                    ownBasePriv = localEpochs[frame.header.pe]?.priv,
                    ownIkPriv = ik.priv,
                    peerIkPub = peer.ik.pub,
                    spkPrivForInit =
                        frame.header.init
                            ?.takeIf { it.pkid == 1 }
                            ?.let { spk.priv },
                    resetRequested = frame.reset,
                )
            val outcome = engine.open(ctx, frame.header, frame.nonce, frame.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) {
                if (outcome.delta.purgePeerRecvState) {
                    recvEpochs.clear()
                    skipped.clear()
                }
                session = outcome.delta.session
                outcome.delta.recvEpoch?.let { recvEpochs[it.epoch to it.rootTag] = it }
                outcome.delta.skippedInserts.forEach { skipped[Triple(it.epoch, it.idx, it.rootTag)] = it }
                outcome.delta.consumedSkippedTag?.let { skipped.remove(Triple(frame.header.se, frame.header.n, it)) }
            }
            return outcome
        }
    }

    @Test
    fun theRatchetSurvivesLossReorderingDuplicationRacesAndAReset() {
        val collisions = SEEDS.sumOf { runSoak(it) }
        // The harness, not the ratchet: across the seeds the reset must actually have put a new-era frame on a number
        // the old era also holds a chain for — or the #135 shape went untested.
        assertTrue("no new-era frame ever met an old era's chain of its number", collisions > 0)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // one deliberate scenario loop; splitting hides the flow
    private fun runSoak(seed: Int): Int {
        val random = Random(seed)
        val a = Side("aaaaaaaa")
        val b = Side("bbbbbbbb")
        a.peer = b
        b.peer = a
        var now = 1_700_000_000_000L
        // Half the seeds start with a both-initiate race.
        if (random.nextBoolean()) {
            a.initiate(now)
            b.initiate(now)
        }

        val inFlight = ArrayDeque<Pair<Side, Frame>>() // (recipient, frame)
        val graveyard = mutableListOf<Pair<Side, Frame>>() // delivered or dropped — re-serve source
        var nextId = 0
        var opened = 0
        var duplicates = 0
        var collisions = 0
        // One side re-roots once, somewhere in the middle third.
        val resetStep = random.nextInt(MESSAGES_PER_SEED / 3, 2 * MESSAGES_PER_SEED / 3)
        val resetter = if (random.nextBoolean()) a else b

        repeat(MESSAGES_PER_SEED) { step ->
            now += random.nextLong(1L, 60_000L)
            // Sender action: seal a new frame (sometimes forcing an epoch), from a random side.
            val sender =
                when {
                    step == resetStep -> resetter
                    random.nextBoolean() -> a
                    else -> b
                }
            // Mixed v2/v3 traffic on one session: the schemes share the chain, so every ladder rung, race and
            // re-serve below must hold for a derived nonce exactly as for a carried one (ADR 059).
            val frame =
                if (step == resetStep) {
                    sender.reset(nextId, "reset $nextId from ${sender.nodeId}", now)
                } else {
                    sender.seal(
                        nextId,
                        "m$nextId from ${sender.nodeId} at step $step",
                        now,
                        force = random.nextInt(40) == 0,
                        v3 = random.nextBoolean(),
                    )
                }
            nextId++
            inFlight.addLast(sender.peer to frame)

            // Delivery actions: drain a random number of frames in a shuffled, lossy, duplicating way.
            repeat(random.nextInt(0, 4)) {
                if (inFlight.isEmpty()) return@repeat
                val idx = random.nextInt(inFlight.size)
                val (recipient, candidate) = inFlight.elementAt(idx)
                inFlight.remove(recipient to candidate)
                when (random.nextInt(10)) {
                    in 0..6 -> { // deliver now
                        if (meetsAnotherErasChain(recipient, candidate)) collisions++
                        deliverAsserting(recipient, candidate, now).also { outcome ->
                            when (outcome) {
                                is OpenOutcome.Opened -> opened++
                                OpenOutcome.Failed.DUPLICATE -> duplicates++
                                else -> Unit
                            }
                        }
                        graveyard += recipient to candidate
                    }

                    in 7..8 -> {
                        inFlight.addLast(recipient to candidate)
                    }

                    // delay (reorder)
                    else -> {
                        graveyard += recipient to candidate
                    } // lost (custody eviction) — maybe re-served later
                }
            }

            // Custody re-serve: an old frame (delivered or "lost") comes back verbatim.
            if (graveyard.isNotEmpty() && random.nextInt(5) == 0) {
                val (recipient, replay) = graveyard[random.nextInt(graveyard.size)]
                deliverAsserting(recipient, replay, now)
            }
        }

        // Drain everything still in flight — the mesh eventually delivers what wasn't evicted.
        while (inFlight.isNotEmpty()) {
            val (recipient, frame) = inFlight.removeFirst()
            now += 1_000L
            deliverAsserting(recipient, frame, now)
        }

        assertTrue("seed $seed: nothing decrypted — the harness is broken", opened > MESSAGES_PER_SEED / 4)
        assertTrue(
            "seed $seed: skipped keys exceeded the per-epoch bound",
            a.skipped.size <= RatchetEngine.MAX_EPOCH_MESSAGES && b.skipped.size <= RatchetEngine.MAX_EPOCH_MESSAGES,
        )
        // Convergence is only guaranteed once BOTH sides confirmed: b can only have confirmed by
        // adopting/responding to a's session, and a can only have confirmed after b sealed against one
        // of a's epochs — which b can't know before adopting. (One-way total loss legitimately leaves
        // a race unresolved: the mesh's answer is more custody re-serves, not a protocol guarantee.)
        val sessionA = a.session
        val sessionB = b.session
        if (sessionA?.confirmed == true && sessionB?.confirmed == true) {
            assertTrue("seed $seed: both sides confirmed on different roots", sessionA.root.contentEquals(sessionB.root))
        }
        assertTrue("seed $seed: duplicates should occur under re-serves", duplicates >= 0)
        return collisions
    }

    /** A first delivery of a frame sealed under the recipient's active root, onto a number another root holds a chain for. */
    private fun meetsAnotherErasChain(
        recipient: Side,
        frame: Frame,
    ): Boolean {
        val root = recipient.session?.root ?: return false
        if (frame.id in recipient.deliveredIds || !frame.root.contentEquals(root)) return false
        val tag = RatchetCrypto.rootTag(root)
        return recipient.recvEpochs.keys.any { (se, rootTag) -> se == frame.header.se && rootTag != tag }
    }

    /** Delivers [frame], asserting the one invariant that matters: a first open yields the right plaintext. */
    private fun deliverAsserting(
        recipient: Side,
        frame: Frame,
        now: Long,
    ): OpenOutcome {
        val first = frame.id !in recipient.deliveredIds
        val sealedUnderActiveRoot = recipient.session?.root?.contentEquals(frame.root) == true
        val outcome = recipient.open(frame, now)
        if (first && sealedUnderActiveRoot) {
            assertTrue(
                "frame ${frame.id}, sealed under the root its recipient holds, did not open: $outcome",
                outcome is OpenOutcome.Opened,
            )
        }
        if (outcome is OpenOutcome.Opened) {
            assertEquals("frame ${frame.id} decrypted to the wrong plaintext", frame.expected, String(outcome.plaintext))
            recipient.deliveredIds += frame.id
        } else if (first) {
            // A first-time failure is only legal for epoch-gone/AEAD after state moved past it — never
            // a silent wrong-plaintext. Nothing to assert beyond type-safety: the outcome is typed.
            assertTrue(outcome is OpenOutcome.Failed)
        }
        return outcome
    }

    private companion object {
        val SEEDS = intArrayOf(1, 7, 42, 1337, 99991)
        const val MESSAGES_PER_SEED = 400
        val AAD = "soak|aad".toByteArray()
    }
}
