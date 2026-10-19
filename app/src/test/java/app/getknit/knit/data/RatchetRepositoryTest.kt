package app.getknit.knit.data

import app.getknit.knit.data.ratchet.RatchetLocalEpochEntity
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.ratchet.RatchetSkippedKeyEntity
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RatchetRepository] over the real Room SQL (the [RoomDbTest] pattern): row mapping fidelity, the
 * applyOpen delta semantics (upsert + skipped-key lifecycle + replacement purge), and the retention
 * sweep that enforces the PFS window.
 */
class RatchetRepositoryTest : RoomDbTest() {
    private val repo by lazy { RatchetRepository(db.ratchetDao(), clock = { NOW }) }

    private fun session(
        peerId: String = "peer0000",
        sendEpoch: Int = 1,
        highestPeAcked: Int = 0,
    ) = RatchetEngine.SessionState(
        peerId = peerId,
        confirmed = true,
        weAreInitiator = true,
        root = ByteArray(32) { 1 },
        establishedAt = NOW,
        sendEpoch = sendEpoch,
        sendEpochPub = ByteArray(32) { 2 },
        sendChainKey = ByteArray(32) { 3 },
        sendEpochStartedAt = NOW,
        highestPeAcked = highestPeAcked,
    )

    @Test
    fun sessionRoundTripsEveryField() =
        runTest {
            val state =
                session().copy(
                    prevRoot = ByteArray(32) { 9 },
                    prevRootWeAreInitiator = true,
                    prevRootExpiresAt = NOW + 1,
                    initEphPub = ByteArray(32) { 4 },
                    initPkid = 7,
                    peerInitEphPub = ByteArray(32) { 5 },
                    peerBasePub = ByteArray(32) { 6 },
                    peerBaseEpoch = 3,
                    sendCount = 42,
                    sendEpochBaseEpoch = 2,
                    sendEpochExport = ByteArray(32) { 7 },
                    highestPeAcked = 2,
                    lastResetSentAt = NOW - 5,
                )
            repo.upsertSession(state)
            val loaded = checkNotNull(repo.session(state.peerId))
            // Data-class equality is reference-based on the ByteArray fields, so compare field-by-field.
            assertArrayEquals(state.root, loaded.root)
            assertArrayEquals(state.prevRoot, loaded.prevRoot)
            assertArrayEquals(state.initEphPub, loaded.initEphPub)
            assertArrayEquals(state.peerInitEphPub, loaded.peerInitEphPub)
            assertArrayEquals(state.peerBasePub, loaded.peerBasePub)
            assertArrayEquals(state.sendEpochPub, loaded.sendEpochPub)
            assertArrayEquals(state.sendChainKey, loaded.sendChainKey)
            assertArrayEquals(state.sendEpochExport, loaded.sendEpochExport)
            assertEquals(state.confirmed, loaded.confirmed)
            assertEquals(state.weAreInitiator, loaded.weAreInitiator)
            assertEquals(state.prevRootWeAreInitiator, loaded.prevRootWeAreInitiator)
            assertEquals(state.prevRootExpiresAt, loaded.prevRootExpiresAt)
            assertEquals(state.establishedAt, loaded.establishedAt)
            assertEquals(state.initPkid, loaded.initPkid)
            assertEquals(state.peerBaseEpoch, loaded.peerBaseEpoch)
            assertEquals(state.sendEpoch, loaded.sendEpoch)
            assertEquals(state.sendCount, loaded.sendCount)
            assertEquals(state.sendEpochStartedAt, loaded.sendEpochStartedAt)
            assertEquals(state.sendEpochBaseEpoch, loaded.sendEpochBaseEpoch)
            assertEquals(state.highestPeAcked, loaded.highestPeAcked)
            assertEquals(state.lastResetSentAt, loaded.lastResetSentAt)
        }

    @Test
    fun applyOpenPersistsChainAdvanceSkippedKeysAndConsumption() =
        runTest {
            val peer = "peer0000"
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = recvEpoch(next = 3),
                        skippedInserts = listOf(skippedKey(idx = 0, fill = 10), skippedKey(idx = 1, fill = 11)),
                    ),
                headerSe = 1,
                headerN = 2,
            )
            val stored = repo.recvEpochs(peer, 1).single()
            assertEquals(3, stored.next)
            assertEquals(TAG, stored.rootTag)
            assertArrayEquals(ByteArray(32) { 10 }, repo.skippedKeys(peer, 1, 0).single().msgKey)
            assertEquals(TAG, repo.skippedKeys(peer, 1, 0).single().rootTag)

            // Consuming skipped key (1, 0) deletes exactly that row.
            repo.applyOpen(
                peerId = peer,
                delta = RatchetEngine.OpenDelta(session = session(peer), recvEpoch = recvEpoch(next = 3), consumedSkippedTag = TAG),
                headerSe = 1,
                headerN = 0,
            )
            assertTrue(repo.skippedKeys(peer, 1, 0).isEmpty())
            assertEquals(1, repo.skippedKeys(peer, 1, 1).size)
        }

    /**
     * #135 (ADR 2026-10.gcrd): a reset restarts the peer's numbering, so two roots' chains can share an epoch number
     * and a skipped index. Both rows are kept side by side, each lookup returns both, and consuming one root's key
     * leaves the other root's alone.
     */
    @Test
    fun twoRootsKeepTheirOwnChainAndKeysForOneEpochNumber() =
        runTest {
            val peer = "peer0000"
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = recvEpoch(next = 4, tag = OLD_TAG),
                        skippedInserts = listOf(skippedKey(idx = 0, fill = 20, tag = OLD_TAG)),
                    ),
                headerSe = 1,
                headerN = 3,
            )
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = recvEpoch(next = 2),
                        skippedInserts = listOf(skippedKey(idx = 0, fill = 10)),
                    ),
                headerSe = 1,
                headerN = 1,
            )
            assertEquals(mapOf(OLD_TAG to 4, TAG to 2), repo.recvEpochs(peer, 1).associate { it.rootTag to it.next })
            assertEquals(setOf(OLD_TAG, TAG), repo.skippedKeys(peer, 1, 0).map { it.rootTag }.toSet())

            repo.applyOpen(
                peerId = peer,
                delta = RatchetEngine.OpenDelta(session = session(peer), recvEpoch = recvEpoch(next = 2), consumedSkippedTag = TAG),
                headerSe = 1,
                headerN = 0,
            )
            val left = repo.skippedKeys(peer, 1, 0).single()
            assertEquals(OLD_TAG, left.rootTag)
            assertArrayEquals(ByteArray(32) { 20 }, left.msgKey)
        }

    @Test
    fun aReplacementPurgeDropsRecvStateButKeepsLocalEpochs() =
        runTest {
            val peer = "peer0000"
            repo.commitSend(session(peer), RatchetEngine.LocalEpoch(epoch = 1, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW))
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = recvEpoch(next = 1, tag = OLD_TAG),
                        skippedInserts = listOf(skippedKey(idx = 5, fill = 0, tag = OLD_TAG)),
                    ),
                headerSe = 1,
                headerN = 0,
            )

            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = recvEpoch(next = 1),
                        purgePeerRecvState = true,
                    ),
                headerSe = 1,
                headerN = 0,
            )
            // The purge dropped the old skipped key; the new-era recv row (written after the purge) and
            // our own epoch privs survive.
            assertTrue(repo.skippedKeys(peer, 1, 5).isEmpty())
            assertEquals(listOf(TAG), repo.recvEpochs(peer, 1).map { it.rootTag })
            assertNotNull(repo.localEpochPriv(peer, 1))
        }

    @Test
    fun sweepEnforcesTtlsAndTheSkippedKeyCap() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer))
            db.ratchetDao().insertSkippedKeys(
                listOf(
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 0, rootTag = TAG, msgKey = ByteArray(32), createdAt = NOW - STALE),
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 1, rootTag = TAG, msgKey = ByteArray(32), createdAt = NOW),
                ),
            )
            db.ratchetDao().upsertRecvEpoch(
                app.getknit.knit.data.ratchet
                    .RatchetRecvEpochEntity(peer, epoch = 1, rootTag = TAG, chainKey = ByteArray(32), next = 1, lastUsedAt = NOW - STALE),
            )

            repo.sweep(NOW)

            assertTrue(repo.skippedKeys(peer, 1, 0).isEmpty()) // past the 48 h TTL
            assertEquals(1, repo.skippedKeys(peer, 1, 1).size) // fresh
            assertTrue(repo.recvEpochs(peer, 1).isEmpty()) // past the 48 h TTL
        }

    /** The global cap's eviction names a row by its whole key, root tag included: two roots' keys at one index both go. */
    @Test
    fun theSkippedKeyCapEvictsByTheWholeKey() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer))
            db.ratchetDao().insertSkippedKeys(
                listOf(
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 0, rootTag = OLD_TAG, msgKey = ByteArray(32), createdAt = NOW - 2),
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 0, rootTag = TAG, msgKey = ByteArray(32), createdAt = NOW - 1),
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 1, rootTag = TAG, msgKey = ByteArray(32), createdAt = NOW),
                ),
            )
            db.ratchetDao().evictOldestSkippedKeys(2)
            assertTrue(repo.skippedKeys(peer, 1, 0).isEmpty())
            assertEquals(1, repo.skippedKeys(peer, 1, 1).size)
        }

    @Test
    fun sweepRetiresAckedLocalEpochsButKeepsTheNewestThree() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer, sendEpoch = 6, highestPeAcked = 6))
            (1..6).forEach { epoch ->
                db.ratchetDao().insertLocalEpoch(
                    RatchetLocalEpochEntity(peer, epoch = epoch, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW - STALE),
                )
            }

            repo.sweep(NOW)

            // Epochs 1–3 are superseded + acked + past 48 h → retired (the PFS window closing);
            // the newest three always survive.
            assertNull(repo.localEpochPriv(peer, 1))
            assertNull(repo.localEpochPriv(peer, 3))
            assertNotNull(repo.localEpochPriv(peer, 4))
            assertNotNull(repo.localEpochPriv(peer, 6))
        }

    @Test
    fun aNumberingRestartDoesNotLetDeadEraRowsStarveTheLiveEra() =
        runTest {
            val peer = "peer0000"
            // A long-lived session banked 16 high-numbered epochs (the per-peer cap, exactly what the
            // field tables showed), then reset: numbering restarted at 1. Recent enough that no TTL or
            // acked-retirement applies — the cap is the only rule in play.
            repo.upsertSession(session(peer, sendEpoch = 3, highestPeAcked = 0))
            (47..62).forEach { epoch ->
                db.ratchetDao().insertLocalEpoch(
                    RatchetLocalEpochEntity(peer, epoch = epoch, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW - 3_600_000L),
                )
            }
            (1..3).forEach { epoch ->
                db.ratchetDao().insertLocalEpoch(
                    RatchetLocalEpochEntity(peer, epoch = epoch, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW),
                )
            }

            repo.sweep(NOW)

            // Ranked by epoch number, the dead era's 47..62 outrank the live era's 1..3 forever: the cap
            // keeps 16 dead keys and deletes every fresh epoch within one cycle of minting it — the peer
            // then bases its next epoch on a contribution whose priv is gone (EPOCH_GONE) and the pair
            // relapses on the reset floor's cadence. Ranked by mint time, the live rows survive and the
            // cap trims the OLDEST dead rows instead.
            assertNotNull(repo.localEpochPriv(peer, 1))
            assertNotNull(repo.localEpochPriv(peer, 2))
            assertNotNull(repo.localEpochPriv(peer, 3))
            assertNull(repo.localEpochPriv(peer, 47))
            assertNull(repo.localEpochPriv(peer, 49))
            assertNotNull(repo.localEpochPriv(peer, 50))
            assertNotNull(repo.localEpochPriv(peer, 62))
        }

    @Test
    fun reMintingACollidingEpochNumberReplacesTheDeadErasKey() =
        runTest {
            val peer = "peer0000"
            // Epoch 5 from the era being abandoned survives (inside its retention window), and the fresh
            // era's numbering climbs back to 5. The live key must win the collision: keeping the dead one
            // makes every peer frame based on the fresh epoch fail its AEAD with nothing in the header to
            // show why.
            db.ratchetDao().insertLocalEpoch(
                RatchetLocalEpochEntity(peer, epoch = 5, priv = ByteArray(32) { 1 }, pub = ByteArray(32), createdAt = NOW - 3_600_000L),
            )
            repo.commitSend(
                session(peer, sendEpoch = 5),
                RatchetEngine.LocalEpoch(epoch = 5, priv = ByteArray(32) { 2 }, pub = ByteArray(32), createdAt = NOW),
            )

            assertArrayEquals(ByteArray(32) { 2 }, repo.localEpochPriv(peer, 5))
        }

    @Test
    fun deletePeerDropsEverything() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer))
            repo.commitSend(session(peer), RatchetEngine.LocalEpoch(epoch = 1, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW))
            repo.deletePeer(peer)
            assertNull(repo.session(peer))
            assertNull(repo.localEpochPriv(peer, 1))
        }

    private fun recvEpoch(
        next: Int,
        tag: Long = TAG,
    ) = RatchetEngine.RecvEpoch(epoch = 1, rootTag = tag, chainKey = ByteArray(32) { 8 }, next = next, lastUsedAt = NOW)

    private fun skippedKey(
        idx: Int,
        fill: Int,
        tag: Long = TAG,
    ) = RatchetEngine.SkippedKey(epoch = 1, rootTag = tag, idx = idx, msgKey = ByteArray(32) { fill.toByte() }, createdAt = NOW)

    private companion object {
        const val TAG = 0x7EA2D2A5L
        const val OLD_TAG = -0x1051E503L
        const val NOW = 1_700_000_000_000L
        const val STALE = 49 * 60 * 60_000L // just past the 48 h retention windows
    }
}
