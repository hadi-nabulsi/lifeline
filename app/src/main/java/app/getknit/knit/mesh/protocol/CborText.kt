package app.getknit.knit.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * Whether every CBOR text string in an encoded item is valid UTF-8 — the check kotlinx-cbor does not make. It decodes
 * text with `decodeToString()`, which swaps each bad sequence for U+FFFD and carries on, so a frame whose `id` holds a
 * bad byte would decode under a repaired id that no longer matches its signed bytes. Knit for iOS refuses the frame
 * outright (`String(validating:as:)` in its CBOR decoder), so the two would hold the frame under two different ids, or
 * one would hold it and the other not, and their digests would never agree (ADR 2026-10.qjap).
 *
 * The walk reads the raw bytes, so it sees what the encoder sent. Byte strings are skipped unread: a `payload`, a
 * signature or a sealed body is opaque here. An indefinite-length text string's chunks are joined before the check, as
 * iOS joins them. Strict UTF-8 is the Unicode standard's (Table 3-7): no overlong form, no surrogate, nothing past
 * U+10FFFF — hand-written rather than a `CharsetDecoder`, whose verdict would be the runtime's (the JDK's under the JVM
 * tests, ICU's on ART). Bytes that aren't well-formed CBOR, or nest deeper than [MAX_DEPTH], answer false: kotlinx would refuse
 * them anyway.
 */
object CborText {
    /** Deeper than any Knit type nests; a bound on the walk's stack for hostile input. */
    const val MAX_DEPTH = 32

    fun validUtf8(bytes: ByteArray): Boolean {
        val walk = Walk(bytes)
        return runCatching { walk.item(0) && walk.pos == bytes.size }.getOrDefault(false)
    }

    private class Malformed : RuntimeException()

    private class Walk(
        val b: ByteArray,
    ) {
        var pos = 0

        fun item(depth: Int): Boolean {
            if (depth > MAX_DEPTH) throw Malformed()
            val initial = byte()
            val major = initial ushr MAJOR_SHIFT
            val info = initial and INFO_MASK
            return when (major) {
                MAJOR_UNSIGNED, MAJOR_NEGATIVE -> {
                    argument(info)
                    true
                }

                MAJOR_BYTES -> {
                    if (info == INDEFINITE) chunks(MAJOR_BYTES) else skip(argument(info))
                    true
                }

                MAJOR_TEXT -> {
                    utf8(if (info == INDEFINITE) chunks(MAJOR_TEXT) else take(argument(info)))
                }

                MAJOR_ARRAY -> {
                    container(info, depth, perEntry = 1)
                }

                MAJOR_MAP -> {
                    container(info, depth, perEntry = 2)
                }

                MAJOR_TAG -> {
                    argument(info)
                    item(depth + 1)
                }

                else -> {
                    // Major 7: simple values and floats. A break outside an indefinite container is malformed.
                    if (info == INDEFINITE) throw Malformed()
                    argument(info)
                    true
                }
            }
        }

        private fun container(
            info: Int,
            depth: Int,
            perEntry: Int,
        ): Boolean {
            if (info == INDEFINITE) {
                while (peek() != BREAK) if (!item(depth + 1)) return false
                pos++
                return true
            }
            val entries = argument(info)
            // Every entry takes at least a byte, so a count past what is left (or a 64-bit one read as negative) is a lie.
            if (entries < 0 || entries > b.size - pos) throw Malformed()
            var left = entries * perEntry
            while (left > 0) {
                if (!item(depth + 1)) return false
                left--
            }
            return true
        }

        /** The definite chunks of an indefinite-length string of [major], joined, up to its break. */
        private fun chunks(major: Int): ByteArray {
            val out = ByteArrayOutputStream()
            while (peek() != BREAK) {
                val initial = byte()
                val info = initial and INFO_MASK
                if (initial ushr MAJOR_SHIFT != major || info == INDEFINITE) throw Malformed()
                out.write(take(argument(info)))
            }
            pos++
            return out.toByteArray()
        }

        /** The argument [info] announces, read from the bytes after the initial byte. */
        private fun argument(info: Int): Long =
            when {
                info < ONE_BYTE -> info.toLong()

                // 24..27 announce a 1-, 2-, 4- or 8-byte argument.
                info <= EIGHT_BYTES -> uint(1 shl (info - ONE_BYTE))

                else -> throw Malformed()
            }

        private fun uint(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl BYTE_BITS) or byte().toLong() }
            return v
        }

        private fun skip(n: Long) {
            if (n < 0 || n > b.size - pos) throw Malformed()
            pos += n.toInt()
        }

        private fun take(n: Long): ByteArray {
            val start = pos
            skip(n)
            return b.copyOfRange(start, pos)
        }

        private fun byte(): Int {
            if (pos >= b.size) throw Malformed()
            return b[pos++].toInt() and BYTE_MASK
        }

        private fun peek(): Int {
            if (pos >= b.size) throw Malformed()
            return b[pos].toInt() and BYTE_MASK
        }
    }

    /** Strict UTF-8 (Unicode Table 3-7). */
    @Suppress("MagicNumber", "CyclomaticComplexMethod", "ReturnCount") // the byte ranges are Table 3-7's, row by row
    internal fun utf8(s: ByteArray): Boolean {
        var i = 0
        while (i < s.size) {
            val c = s[i].toInt() and BYTE_MASK
            if (c < 0x80) {
                i++
                continue
            }
            // The lead byte fixes the length and the allowed range of the first continuation byte.
            val (len, lo, hi) =
                when (c) {
                    in 0xC2..0xDF -> Triple(2, 0x80, 0xBF)

                    0xE0 -> Triple(3, 0xA0, 0xBF)

                    in 0xE1..0xEC, 0xEE, 0xEF -> Triple(3, 0x80, 0xBF)

                    0xED -> Triple(3, 0x80, 0x9F)

                    // no surrogate
                    0xF0 -> Triple(4, 0x90, 0xBF)

                    in 0xF1..0xF3 -> Triple(4, 0x80, 0xBF)

                    0xF4 -> Triple(4, 0x80, 0x8F)

                    // nothing past U+10FFFF
                    else -> return false
                }
            if (i + len > s.size) return false
            val first = s[i + 1].toInt() and BYTE_MASK
            if (first < lo || first > hi) return false
            for (k in 2 until len) if ((s[i + k].toInt() and CONTINUATION_MASK) != CONTINUATION) return false
            i += len
        }
        return true
    }

    private const val MAJOR_SHIFT = 5
    private const val INFO_MASK = 0x1F
    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_NEGATIVE = 1
    private const val MAJOR_BYTES = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5
    private const val MAJOR_TAG = 6
    private const val ONE_BYTE = 24
    private const val EIGHT_BYTES = 27
    private const val INDEFINITE = 31
    private const val BREAK = 0xFF
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val CONTINUATION_MASK = 0xC0
    private const val CONTINUATION = 0x80
}
