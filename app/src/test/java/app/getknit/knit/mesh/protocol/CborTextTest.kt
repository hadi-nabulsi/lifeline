package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raw-CBOR UTF-8 walk ([CborText], ADR 2026-10.qjap): what it refuses is what iOS's `String(validating:)` refuses. */
class CborTextTest {
    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `well-formed text passes, ascii to four-byte`() {
        assertTrue(CborText.validUtf8(WireCodec.encodePayload(ChatContent(body = "héllo ✓ 👋🏽"))))
        assertTrue(CborText.utf8("f09f918b".hex()))
        assertTrue(CborText.utf8("f48fbfbf".hex())) // U+10FFFF, the last code point
        assertTrue(CborText.utf8("ed9fbf".hex())) // U+D7FF, just under the surrogates
    }

    @Test
    fun `ill-formed sequences fail`() {
        assertFalse("lone continuation", CborText.utf8("80".hex()))
        assertFalse("overlong two-byte", CborText.utf8("c0af".hex()))
        assertFalse("overlong three-byte", CborText.utf8("e080af".hex()))
        assertFalse("overlong four-byte", CborText.utf8("f08fbfbf".hex()))
        assertFalse("encoded surrogate", CborText.utf8("eda080".hex()))
        assertFalse("past U+10FFFF", CborText.utf8("f4908080".hex()))
        assertFalse("truncated", CborText.utf8("e282".hex()))
        assertFalse("bad continuation", CborText.utf8("e228a1".hex()))
        assertFalse("0xFF", CborText.utf8("ff".hex()))
    }

    @Test
    fun `a byte string is opaque and a bad text string anywhere fails`() {
        // {"a": h'ff'} — bytes are never read as text.
        assertTrue(CborText.validUtf8("a1616141ff".hex()))
        // {"a": "\xff"}, a map key "\xff", and one nested in an array and a tag.
        assertFalse(CborText.validUtf8("a1616161ff".hex()))
        assertFalse(CborText.validUtf8("a161ff01".hex()))
        assertFalse(CborText.validUtf8("820161ff".hex()))
        assertFalse(CborText.validUtf8("c061ff".hex()))
    }

    @Test
    fun `indefinite text is checked joined, as iOS joins it`() {
        // (_ "\xe2\x82", "\xac") — a euro sign split across two chunks: valid once joined.
        assertTrue(CborText.validUtf8("7f62e28261acff".hex()))
        assertFalse(CborText.validUtf8("7f62e282ff".hex()))
        // A byte-string chunk inside a text string is malformed.
        assertFalse(CborText.validUtf8("7f41acff".hex()))
    }

    @Test
    fun `malformed or hostile CBOR fails`() {
        assertFalse("truncated text", CborText.validUtf8("63616263".hex().copyOf(3)))
        assertFalse("trailing bytes", CborText.validUtf8("0000".hex()))
        assertFalse("stray break", CborText.validUtf8("ff".hex()))
        assertFalse("reserved info", CborText.validUtf8("1c".hex()))
        assertFalse("a huge array count", CborText.validUtf8("9bffffffffffffffff".hex()))
        assertFalse("nested past the bound", CborText.validUtf8(ByteArray(CborText.MAX_DEPTH + 2) { 0x81.toByte() } + 0x00))
        assertTrue("nested to the bound", CborText.validUtf8(ByteArray(CborText.MAX_DEPTH) { 0x81.toByte() } + 0x00))
    }
}
