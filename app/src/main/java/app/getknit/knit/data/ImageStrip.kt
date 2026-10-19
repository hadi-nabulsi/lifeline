package app.getknit.knit.data

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * A picture's own bytes with every metadata segment taken out and nothing else touched — the lossless half of
 * [AttachmentStore]'s still-image door (ADR 2026-10.unqn). A picture that is already bounded, upright and no
 * sharper than the ingest's own output goes out as these bytes instead of a decode and a fresh lossy encode, so
 * a picture saved and re-sent keeps its pixels and its hash however many times it travels.
 *
 * The metadata invariant rests on an **allow-list**, not a deny-list: what is kept is only what a decoder needs
 * to draw the same pixels in the same colours, and anything this walk does not fully understand answers null,
 * which sends the picture the long way (decoded and re-encoded, as before). So the worst a surprise can cost is
 * the generation this exists to save, never a GPS position.
 *
 * - **JPEG**: kept are SOI, the tables (DQT, DHT, DRI), one baseline / extended / progressive Huffman frame at 8
 *   bits with 1 or 3 components, every scan with its entropy-coded data, EOI — and three marker segments that
 *   change how the pixels decode while saying nothing about the photographer: a thumbnail-less `JFIF` APP0, the
 *   `ICC_PROFILE` APP2 chunks (a phone's Display P3 photo shifts colour without them) and the `Adobe` APP14 (its
 *   transform flag decides how the components are read). EXIF, XMP, MPF, comments and every other APPn go, and
 *   so does everything after the primary image's EOI (an Ultra HDR gain map, a Motion Photo's video, a vendor
 *   trailer). Its [Stripped.quality] is estimated from the luminance table, as libjpeg would have scaled it.
 * - **WebP**: a simple lossy file carries no metadata and is kept whole; an extended one keeps `VP8X` (its EXIF
 *   and XMP bits cleared), `ICCP`, `ALPH` and the one `VP8 ` frame. Lossless (`VP8L`) and animated WebP answer
 *   null from [of]: the first is PNG-sized, and the second has a door of its own, [animatedWebp]
 *   (ADR 2026-10.8hmx), which keeps `VP8X`, `ICCP`, `ANIM` and the `ANMF` frames under the same allow-list rule.
 *
 * Every walk is bounds-checked, so truncated or hostile bytes answer null rather than throwing. Stripping is
 * idempotent: the stripped bytes strip to themselves, which is what keeps a re-picked picture's hash.
 *
 * Pure Kotlin, no Android — JVM-tested in `ImageStripTest`.
 */
@Suppress("TooManyFunctions") // a JPEG walk, a WebP walk and the byte readers both share; splitting would scatter one allow-list
object ImageStrip {
    /**
     * [bytes] with their metadata gone, the [mime] they are, the frame's [width] and [height], and for a JPEG the
     * [quality] its luminance table was scaled to (null for WebP, whose quantizer this does not read).
     */
    class Stripped(
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val quality: Int?,
    )

    /** [bytes] stripped, or null when they are not a JPEG or still lossy WebP this walk fully understands. */
    @Suppress("MagicNumber") // the WebP brand sits at a fixed offset in its RIFF header
    fun of(bytes: ByteArray): Stripped? =
        runCatching {
            when {
                bytes.size > 3 && bytes.u8(0) == 0xFF && bytes.u8(1) == SOI && bytes.u8(2) == 0xFF -> jpeg(bytes)
                bytes.matchesAt(RIFF, 0) && bytes.matchesAt(WEBP, 8) -> webp(bytes)
                else -> null
            }
        }.getOrNull()

    // --- JPEG ---

    /** One frame header's facts: its size, as the SOF segment declares it. */
    private class Frame(
        val width: Int,
        val height: Int,
    )

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    private fun jpeg(bytes: ByteArray): Stripped? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(0xFF)
        out.write(SOI)
        var at = 2
        var frame: Frame? = null
        var luma: IntArray? = null
        var scanned = false
        while (true) {
            if (at >= bytes.size || bytes.u8(at) != 0xFF) return null
            while (at < bytes.size && bytes.u8(at) == 0xFF) at++ // fill bytes ahead of a marker
            if (at >= bytes.size) return null
            val marker = bytes.u8(at++)
            if (marker == SOI || marker == TEM || marker in RST0..RST7) return null // standalone, out of place
            if (marker == EOI) {
                if (frame == null || !scanned || luma == null) return null
                out.write(0xFF)
                out.write(EOI)
                return Stripped(out.toByteArray(), "image/jpeg", frame.width, frame.height, qualityOf(luma))
            }
            if (at + 2 > bytes.size) return null
            val length = bytes.u16be(at)
            if (length < 2 || at + length > bytes.size) return null
            val payload = at + 2
            val payloadSize = length - 2
            val keep =
                when (marker) {
                    DQT -> {
                        luma = lumaTable(bytes, payload, payloadSize) ?: luma
                        true
                    }

                    DHT, DRI -> {
                        true
                    }

                    SOF0, SOF1, SOF2 -> {
                        if (frame != null || payloadSize < 6) return null
                        val components = bytes.u8(payload + 5)
                        val precision = bytes.u8(payload)
                        if (precision != 8 || components !in setOf(1, 3) || payloadSize != 6 + 3 * components) return null
                        val height = bytes.u16be(payload + 1)
                        val width = bytes.u16be(payload + 3)
                        if (width == 0 || height == 0) return null
                        frame = Frame(width, height)
                        true
                    }

                    SOS -> {
                        if (frame == null) return null
                        true
                    }

                    APP0 -> {
                        // JFIF\0, version (2), units, x/y density (2 + 2), thumbnail width and height: 14 bytes, and a
                        // zero-by-zero thumbnail. Any more is a thumbnail or JFXX, which goes.
                        payloadSize == JFIF_SIZE && bytes.matchesAt(JFIF, payload) &&
                            bytes.u8(payload + 12) == 0 && bytes.u8(payload + 13) == 0
                    }

                    APP2 -> {
                        bytes.matchesAt(ICC_PROFILE, payload)
                    }

                    APP14 -> {
                        val adobe = bytes.matchesAt(ADOBE, payload)
                        if (adobe && payloadSize != ADOBE_SIZE) return null // the decode depends on it, and it carries more than it should
                        adobe
                    }

                    in APP1..APP15, COM -> {
                        false
                    }

                    else -> {
                        return null // arithmetic, lossless, hierarchical, DNL, extensions: the long way
                    }
                }
            if (keep) {
                out.write(0xFF)
                out.write(marker)
                out.write(bytes, at, length)
            }
            at += length
            if (marker == SOS) {
                val end = entropyEnd(bytes, at) ?: return null
                out.write(bytes, at, end - at)
                at = end
                scanned = true
            }
        }
    }

    /**
     * Where a scan's entropy-coded data ends: the first `FF` that starts a real marker. A stuffed `FF 00` and a
     * restart marker (`FF D0`..`FF D7`) belong to the scan; an `FF` followed by another is fill ahead of one.
     * Null when the bytes end first — a JPEG with no EOI.
     */
    @Suppress("MagicNumber")
    private fun entropyEnd(
        bytes: ByteArray,
        start: Int,
    ): Int? {
        var i = start
        while (i + 1 < bytes.size) {
            if (bytes.u8(i) != 0xFF) {
                i++
                continue
            }
            val next = bytes.u8(i + 1)
            when (next) {
                0x00, in RST0..RST7 -> i += 2
                0xFF -> i++
                else -> return i
            }
        }
        return null
    }

    /**
     * Table 0's 64 entries when this DQT segment defines it at 8-bit precision; null when it defines only other
     * tables. Throws — which [of] turns into the long way — when the tables do not tile the segment exactly or
     * table 0 is 16-bit.
     */
    @Suppress("MagicNumber")
    private fun lumaTable(
        bytes: ByteArray,
        start: Int,
        size: Int,
    ): IntArray? {
        var at = start
        var found: IntArray? = null
        while (at < start + size) {
            val precision = bytes.u8(at) shr 4
            val id = bytes.u8(at) and 0x0F
            val entries = if (precision == 0) 64 else 128
            require(at + 1 + entries <= start + size) { "DQT overruns its segment" }
            if (id == 0) {
                require(precision == 0) { "16-bit luminance table" }
                found = IntArray(64) { bytes.u8(at + 1 + it) }
            }
            at += 1 + entries
        }
        require(at == start + size) { "DQT tables do not tile the segment" }
        return found
    }

    /**
     * The libjpeg quality [table] was scaled to from the Annex K luminance table: `scale` is the table's sum over
     * the standard's (a percentage), and quality inverts libjpeg's `q < 50 ? 5000 / q : 200 - 2q`. Sums are
     * order-free, so the zigzag order on the wire does not matter. A vendor table that is not a scaled Annex K
     * table still lands near the quality its coarseness amounts to, which is all a threshold needs.
     */
    @Suppress("MagicNumber")
    internal fun qualityOf(table: IntArray): Int {
        val scale = 100.0 * table.sum() / ANNEX_K_LUMA.sum()
        val quality = if (scale <= 100.0) (200.0 - scale) / 2.0 else 5000.0 / scale
        return quality.roundToInt().coerceIn(1, 100)
    }

    // --- WebP ---

    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    private fun webp(bytes: ByteArray): Stripped? {
        val end = 8L + bytes.u32le(4)
        if (end > bytes.size || end < 20) return null
        val first = bytes.fourcc(12)
        if (first == "VP8 ") {
            val size = bytes.u32le(16)
            if (20L + size > end) return null
            val (width, height) = vp8Size(bytes, 20, size.toInt()) ?: return null
            val out = riff(listOf(Chunk(first, 20, size.toInt())), bytes)
            return Stripped(out, "image/webp", width, height, null)
        }
        if (first != "VP8X" || bytes.u32le(16) != 10L) return null
        if (bytes.u8(20) and VP8X_ANIMATION != 0) return null
        val width = 1 + bytes.u24le(24)
        val height = 1 + bytes.u24le(27)
        val kept = ArrayList<Chunk>()
        var frames = 0
        var at = 12
        while (at < end) {
            if (at + 8 > end) return null
            val tag = bytes.fourcc(at)
            val size = bytes.u32le(at + 4)
            val dataEnd = at + 8L + size
            if (dataEnd > end) return null
            when (tag) {
                "VP8X", "ICCP", "ALPH" -> {
                    kept += Chunk(tag, at + 8, size.toInt())
                }

                "VP8 " -> {
                    if (++frames > 1) return null
                    kept += Chunk(tag, at + 8, size.toInt())
                }

                "VP8L", "ANIM", "ANMF" -> {
                    return null
                }

                else -> {
                    // EXIF, XMP and anything unknown: dropped
                }
            }
            at = (dataEnd + (size and 1L)).toInt()
        }
        if (frames != 1) return null
        val out = riff(kept, bytes)
        // The VP8X flags byte sits at the same offset in the output: VP8X is the first chunk in both.
        out[20] = (out[20].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
        return Stripped(out, "image/webp", width, height, null)
    }

    /**
     * An animated WebP's own bytes with its metadata gone, the canvas [width] and [height], and how many [frames] it
     * holds — the pass-through half of the animated door (ADR 2026-10.8hmx).
     */
    class Animated(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val frames: Int,
    )

    /**
     * [bytes] stripped as an animated WebP, or null when they are not one this walk fully understands. Kept are
     * `VP8X` (its EXIF and XMP bits cleared), `ICCP`, `ANIM` and every `ANMF`, whose payload must hold only a
     * frame's `ALPH` and `VP8 ` / `VP8L` bitstream: an `ANMF` carrying anything else answers null rather than
     * being rebuilt, so nothing this walk does not understand ever goes out. `EXIF`, `XMP ` and unknown top-level
     * chunks are dropped, as in [of]. Idempotent, like [of].
     */
    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    fun animatedWebp(bytes: ByteArray): Animated? =
        runCatching {
            if (!bytes.matchesAt(RIFF, 0) || !bytes.matchesAt(WEBP, 8)) return@runCatching null
            val end = 8L + bytes.u32le(4)
            if (end > bytes.size || end < 30) return@runCatching null
            if (bytes.fourcc(12) != "VP8X" || bytes.u32le(16) != 10L) return@runCatching null
            if (bytes.u8(20) and VP8X_ANIMATION == 0) return@runCatching null
            val width = 1 + bytes.u24le(24)
            val height = 1 + bytes.u24le(27)
            val kept = arrayListOf(Chunk("VP8X", 20, 10))
            var animation = false
            var frames = 0
            var at = 30
            while (at < end) {
                if (at + 8 > end) return@runCatching null
                val tag = bytes.fourcc(at)
                val size = bytes.u32le(at + 4)
                val dataEnd = at + 8L + size
                if (dataEnd > end) return@runCatching null
                when (tag) {
                    "ICCP" -> {
                        kept += Chunk(tag, at + 8, size.toInt())
                    }

                    "ANIM" -> {
                        if (animation || frames > 0 || size != 6L) return@runCatching null
                        animation = true
                        kept += Chunk(tag, at + 8, size.toInt())
                    }

                    "ANMF" -> {
                        if (!animation || !frameHoldsOnlyImage(bytes, at + 8, size.toInt())) return@runCatching null
                        frames++
                        kept += Chunk(tag, at + 8, size.toInt())
                    }

                    "VP8X", "VP8 ", "VP8L", "ALPH" -> {
                        return@runCatching null // a second header, or a still's bitstream outside a frame
                    }

                    else -> {
                        // EXIF, XMP and anything unknown: dropped
                    }
                }
                at = (dataEnd + (size and 1L)).toInt()
            }
            if (frames == 0) return@runCatching null
            val out = riff(kept, bytes)
            out[20] = (out[20].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
            Animated(out, width, height, frames)
        }.getOrNull()

    /** Whether the `ANMF` payload at [start] is its 16-byte header and then only `ALPH`, `VP8 ` or `VP8L`. */
    @Suppress("MagicNumber", "ReturnCount")
    private fun frameHoldsOnlyImage(
        bytes: ByteArray,
        start: Int,
        size: Int,
    ): Boolean {
        var at = start + ANMF_HEADER
        val end = start.toLong() + size
        var image = false
        while (at < end) {
            if (at + 8 > end) return false
            val tag = bytes.fourcc(at)
            val chunkSize = bytes.u32le(at + 4)
            if (at + 8L + chunkSize > end) return false
            when (tag) {
                "ALPH" -> if (image) return false
                "VP8 ", "VP8L" -> if (image) return false else image = true
                else -> return false
            }
            at = (at + 8L + chunkSize + (chunkSize and 1L)).toInt()
        }
        return image
    }

    /** One chunk to copy: its [tag], and where its data sits in the source and how long it is. */
    private class Chunk(
        val tag: String,
        val offset: Int,
        val size: Int,
    )

    /** A RIFF/WEBP file holding [chunks] copied from [source], each padded to an even length. */
    @Suppress("MagicNumber")
    private fun riff(
        chunks: List<Chunk>,
        source: ByteArray,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        for (chunk in chunks) {
            body.write(chunk.tag.toByteArray(Charsets.US_ASCII))
            body.writeU32le(chunk.size.toLong())
            body.write(source, chunk.offset, chunk.size)
            if (chunk.size % 2 == 1) body.write(0)
        }
        val out = ByteArrayOutputStream(12 + body.size())
        out.write(RIFF)
        out.writeU32le(4L + body.size())
        out.write(WEBP)
        body.writeTo(out)
        return out.toByteArray()
    }

    /** A lossy key frame's width and height from its header (RFC 6386 §9.1); null for anything else. */
    @Suppress("MagicNumber")
    private fun vp8Size(
        bytes: ByteArray,
        start: Int,
        size: Int,
    ): Pair<Int, Int>? {
        if (size < 10 || bytes.u8(start) and 0x01 != 0) return null // an inter frame
        if (bytes.u8(start + 3) != 0x9D || bytes.u8(start + 4) != 0x01 || bytes.u8(start + 5) != 0x2A) return null
        val width = bytes.u16le(start + 6) and 0x3FFF
        val height = bytes.u16le(start + 8) and 0x3FFF
        return if (width == 0 || height == 0) null else width to height
    }

    // --- bytes ---

    @Suppress("MagicNumber")
    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    @Suppress("MagicNumber")
    private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

    @Suppress("MagicNumber")
    private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    @Suppress("MagicNumber")
    private fun ByteArray.u24le(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)

    @Suppress("MagicNumber")
    private fun ByteArray.u32le(at: Int): Long = u24le(at).toLong() or (u8(at + 3).toLong() shl 24)

    @Suppress("MagicNumber")
    private fun ByteArrayOutputStream.writeU32le(value: Long) {
        for (shift in 0 until 32 step 8) write(((value shr shift) and 0xFF).toInt())
    }

    @Suppress("MagicNumber")
    private fun ByteArray.fourcc(at: Int): String = String(this, at, 4, Charsets.US_ASCII)

    private fun ByteArray.matchesAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private const val TEM = 0x01
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOF0 = 0xC0
    private const val SOF1 = 0xC1
    private const val SOF2 = 0xC2
    private const val DHT = 0xC4
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
    private const val SOS = 0xDA
    private const val DQT = 0xDB
    private const val DRI = 0xDD
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val APP15 = 0xEF
    private const val COM = 0xFE

    /** `JFIF\0`, version, units, densities and a 0×0 thumbnail. */
    private const val JFIF_SIZE = 14

    /** `Adobe`, version, two flag words and the transform byte. */
    private const val ADOBE_SIZE = 12

    /** Frame position, size, duration and flags ahead of an `ANMF`'s bitstream. */
    private const val ANMF_HEADER = 16

    private const val VP8X_ANIMATION = 0x02
    private const val VP8X_XMP = 0x04
    private const val VP8X_EXIF = 0x08

    private val JFIF = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0)
    private val ICC_PROFILE = ascii("ICC_PROFILE") + byteArrayOf(0)
    private val ADOBE = ascii("Adobe")
    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")

    /** ITU T.81 Annex K, Table K.1: the luminance quantization table libjpeg scales by quality. */
    internal val ANNEX_K_LUMA =
        intArrayOf(
            16,
            11,
            10,
            16,
            24,
            40,
            51,
            61,
            12,
            12,
            14,
            19,
            26,
            58,
            60,
            55,
            14,
            13,
            16,
            24,
            40,
            57,
            69,
            56,
            14,
            17,
            22,
            29,
            51,
            87,
            80,
            62,
            18,
            22,
            37,
            56,
            68,
            109,
            103,
            77,
            24,
            35,
            55,
            64,
            81,
            104,
            113,
            92,
            49,
            64,
            78,
            87,
            103,
            121,
            120,
            101,
            72,
            92,
            95,
            98,
            112,
            100,
            103,
            99,
        )
}
