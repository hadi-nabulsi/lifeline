package app.getknit.knit.mesh.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A repro for the interop audit (`test/interop-audit`): the payloads the iOS port emits that no vector file pins.
 * Each hex string is what knit-ios `9ac4cdf`'s `KnitWire` encoders wrote (`CBORRepresentable.encoded`, run on
 * Linux from an archive of `Sources/KnitWire`), for frame id `ICcuNTxDSlFYX2ZtdHuCiQ` and hash `ab` × 32. Each
 * must decode on Android into the values iOS meant and re-encode to its own bytes, since every one of these
 * decodes in `InboundPipeline` once returned silently on failure (each now counts `PAYLOAD_DECODE_FAILED`).
 *
 * The last two cases pin two kotlinx-cbor behaviours the iOS decoder does not share: an empty map read as null for a
 * nullable field (latent; docs/WIRE_COMPAT.md rule 6 keeps it so), and a 32-bit unsigned `ttl` read as negative, which
 * `WireCodec.decodeWire` now refuses as iOS does.
 */
class IosPayloadShapesTest {
    private val id = "ICcuNTxDSlFYX2ZtdHuCiQ"
    private val hash = "ab".repeat(32)

    private inline fun <reified T> roundTrip(hex: String): T {
        val bytes = hex.fromHex()
        val value = assertNotNullAnd(WireCodec.decodePayload<T>(bytes))
        assertArrayEquals("${T::class.simpleName} re-encodes differently", bytes, WireCodec.encodePayload(value))
        return value
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull("iOS's bytes do not decode", value)
        return checkNotNull(value)
    }

    @Test
    fun `a cleartext receipt`() {
        assertEquals(id, roundTrip<ReceiptContent>("a16561636b496476494363754e547844536c465958325a74644875436951").ackId)
    }

    @Test
    fun `a reaction and its retraction`() {
        val reaction =
            roundTrip<ReactionContent>(
                "a2696d657373616765496476494363754e547844536c465958325a7464487543695165656d6f6a6964f09f918d",
            )
        assertEquals(id, reaction.messageId)
        assertEquals("👍", reaction.emoji)
        val retraction = roundTrip<ReactionContent>("a1696d657373616765496476494363754e547844536c465958325a74644875436951")
        assertNull(retraction.emoji)
    }

    @Test
    fun `a key request`() {
        val req = roundTrip<KeyReqContent>("a1676e6f646549647381781a37766f33706e6c6b63666265337168617736376671626f793334")
        assertEquals(listOf("7vo3pnlkcfbe3qhaw67fqboy34"), req.nodeIds)
    }

    @Test
    fun `a blob request, whole and resumed`() {
        val whole = roundTrip<BlobReqContent>("a164686173687840" + "6162".repeat(32))
        assertEquals(hash, whole.hash)
        assertNull(whole.offset)
        val resumed = roundTrip<BlobReqContent>("a264686173687840" + "6162".repeat(32) + "666f6666736574191000")
        assertEquals(4096L, resumed.offset)
    }

    @Test
    fun `a holder query and its answers`() {
        assertEquals(listOf(hash), roundTrip<BlobHaveContent>("a166686173686573817840" + "6162".repeat(32)).hashes)
        val held =
            roundTrip<BlobHasContent>(
                "a3656974656d7381a364686173687840" + "6162".repeat(32) +
                    "657374617465016473697a651a0001e240646c6f61640062726576494363754e547844536c465958325a74644875436951",
            )
        assertEquals(0, held.load)
        assertEquals(id, held.re)
        assertEquals(BlobHasItem(hash, state = 1, size = 123_456L), held.items.single())
        val sending =
            roundTrip<BlobHasContent>(
                "a1656974656d7381a564686173687840" + "6162".repeat(32) +
                    "657374617465026473697a651a0001e2406468656c641a0001000063657461191388",
            )
        assertEquals(BlobHasItem(hash, state = 2, size = 123_456L, held = 65_536L, eta = 5_000L), sending.items.single())
    }

    @Test
    fun `a progress report and a cancel`() {
        val progress =
            roundTrip<BlobProgressContent>("a364686173687840" + "6162".repeat(32) + "6468656c641a00010000626d731907d0")
        assertEquals(BlobProgressContent(hash, held = 65_536L, ms = 2_000L), progress)
        assertEquals(hash, roundTrip<BlobCancelContent>("a164686173687840" + "6162".repeat(32)).hash)
    }

    @Test
    fun `a typing cue and a group leave`() {
        assertNull(roundTrip<TypingContent>("a0").groupId)
        assertEquals(
            "g-bec4e7dac6ce43deb38c74d7",
            roundTrip<GroupLeaveContent>("a16767726f75704964781a672d626563346537646163366365343364656233386337346437").groupId,
        )
    }

    @Serializable
    data class AllDefaults(
        val x: Int = 0,
    )

    @Serializable
    data class Outer(
        val inner: AllDefaults? = null,
    )

    /**
     * Latent: kotlinx-cbor reads an empty map as null for a nullable field (`{"inner": {}}`), where iOS's decoder
     * reads an object with its defaults. Today no nullable nested wire type is all-defaults, so nothing splits.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `an empty nested map decodes as null for a nullable field`() {
        val outer = WireCodec.cbor.decodeFromByteArray(Outer.serializer(), "a165696e6e6572a0".fromHex())
        assertNull("kotlinx reads {} as null; iOS reads it as AllDefaults()", outer.inner)
    }

    /** AND-11: a ttl of 0xFFFFFFFF, which kotlinx reads as -1, is refused, as iOS's int32 field refuses it. So is a negative hops. */
    @Test
    fun `a 32-bit unsigned ttl or a negative hops is refused`() {
        assertNull(WireCodec.decodeWire("a36374746c1affffffff6373696740667369676e656440".fromHex()))
        assertNull(WireCodec.decodeWire(WireCodec.encodeWire(WireEnvelope(hops = -1, sig = ByteArray(0), signed = ByteArray(0)))))
        assertEquals(0, WireCodec.decodeWire(WireCodec.encodeWire(WireEnvelope(ttl = 0, sig = ByteArray(0), signed = ByteArray(0))))?.ttl)
    }
}
