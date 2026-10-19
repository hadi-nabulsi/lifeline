package app.getknit.knit.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The allow-list a picture's metadata is stripped by when it skips the re-encode (ADR 2026-10.unqn). The property
 * that matters is the metadata one: nothing but the frame, its tables and its colour survives, the entropy-coded
 * data survives byte for byte, and anything the walk does not understand answers null — the long way — rather
 * than passing through half-read. Built from hand-made segments, so each case names exactly what it carries.
 */
class ImageStripTest {
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    // --- JPEG ---

    private fun segment(
        marker: Int,
        payload: ByteArray,
    ): ByteArray = bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private val soi = bytes(0xFF, 0xD8)
    private val eoi = bytes(0xFF, 0xD9)

    private val jfif = segment(0xE0, ascii("JFIF") + bytes(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val jfifWithThumbnail = segment(0xE0, ascii("JFIF") + bytes(0, 1, 1, 0, 0, 1, 0, 1, 1, 1) + ByteArray(3))
    private val exif = segment(0xE1, ascii("Exif") + bytes(0, 0) + ascii("MM GPS 37.42 -122.08 Pixel 8"))
    private val xmp = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/") + bytes(0) + ascii("<x:xmpmeta hdrgm/>"))
    private val icc1 = segment(0xE2, ascii("ICC_PROFILE") + bytes(0, 1, 2) + ascii("display-p3 part 1"))
    private val icc2 = segment(0xE2, ascii("ICC_PROFILE") + bytes(0, 2, 2) + ascii("display-p3 part 2"))
    private val mpf = segment(0xE2, ascii("MPF") + bytes(0) + ascii("gain map offset"))
    private val iptc = segment(0xED, ascii("Photoshop 3.0") + bytes(0) + ascii("byline: someone"))
    private val adobe = segment(0xEE, ascii("Adobe") + bytes(0, 100, 0, 0, 0, 0, 1))
    private val comment = segment(0xFE, ascii("shot at home"))

    /** libjpeg's `jpeg_set_quality` applied to the Annex K luminance table. */
    private fun scaledLuma(quality: Int): IntArray {
        val scale = if (quality < 50) 5000 / quality else 200 - 2 * quality
        return IntArray(64) { ((ImageStrip.ANNEX_K_LUMA[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    private fun dqt(quality: Int = 85): ByteArray = segment(0xDB, bytes(0x00) + ByteArray(64) { scaledLuma(quality)[it].toByte() })

    private fun sof(
        width: Int = 1000,
        height: Int = 800,
        marker: Int = 0xC0,
        precision: Int = 8,
        components: Int = 3,
    ): ByteArray {
        var payload = bytes(precision, height shr 8, height and 0xFF, width shr 8, width and 0xFF, components)
        repeat(components) { payload += bytes(it + 1, 0x11, 0) }
        return segment(marker, payload)
    }

    private val dht = segment(0xC4, bytes(0x00) + ByteArray(16) + bytes())
    private val sos = segment(0xDA, bytes(1, 1, 0, 0, 63, 0))

    /** Entropy-coded data with a stuffed `FF 00`, a restart marker and fill ahead of the next marker. */
    private val entropy = bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0x78, 0xFF, 0xD7, 0x9A, 0xFF)

    private fun jpeg(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    /** A plain baseline JPEG: what the ingest's own re-encode writes, give or take the tables. */
    private fun plain(quality: Int = 85) = jpeg(soi, jfif, dqt(quality), sof(), dht, sos, entropy, eoi)

    @Test
    fun everyMetadataSegmentGoesAndOnlyWhatTheDecodeNeedsStays() {
        val source =
            jpeg(soi, jfif, exif, xmp, icc1, icc2, mpf, comment, adobe, iptc, dqt(), sof(), dht, sos, entropy, eoi) +
                ascii("trailing gain map JPEG, motion photo MP4, vendor trailer")

        val stripped = checkNotNull(ImageStrip.of(source))

        assertArrayEquals(jpeg(soi, jfif, icc1, icc2, adobe, dqt(), sof(), dht, sos, entropy, eoi), stripped.bytes)
        assertEquals("image/jpeg", stripped.mime)
        assertEquals(1000, stripped.width)
        assertEquals(800, stripped.height)
    }

    @Test
    fun aJpegWithNoMetadataIsItsOwnStrip() {
        val source = plain()
        assertArrayEquals(source, checkNotNull(ImageStrip.of(source)).bytes)
    }

    @Test
    fun aJfifCarryingAThumbnailGoesWhole() {
        val stripped = checkNotNull(ImageStrip.of(jpeg(soi, jfifWithThumbnail, dqt(), sof(), dht, sos, entropy, eoi)))
        assertArrayEquals(jpeg(soi, dqt(), sof(), dht, sos, entropy, eoi), stripped.bytes)
    }

    @Test
    fun aProgressiveJpegKeepsEveryScanAndTheTablesBetweenThem() {
        val second = bytes(0xAB, 0xFF, 0x00, 0xCD)
        val source = jpeg(soi, exif, dqt(), sof(marker = 0xC2), dht, sos, entropy, dht, sos, second, eoi)
        val stripped = checkNotNull(ImageStrip.of(source))
        assertArrayEquals(jpeg(soi, dqt(), sof(marker = 0xC2), dht, sos, entropy, dht, sos, second, eoi), stripped.bytes)
    }

    @Test
    fun stripsAreIdempotent() {
        val once = checkNotNull(ImageStrip.of(jpeg(soi, exif, jfif, icc1, comment, dqt(), sof(), dht, sos, entropy, eoi)))
        assertArrayEquals(once.bytes, checkNotNull(ImageStrip.of(once.bytes)).bytes)
    }

    @Test
    fun theQualityReadsBackFromTheLuminanceTable() {
        // Below about 20 the scaled table clamps at 255 and reads high, which no threshold near 85 cares about.
        for (quality in listOf(25, 50, 75, 85, 90, 95, 100)) {
            val read = checkNotNull(ImageStrip.of(plain(quality))).quality
            assertNotNull(read)
            assertTrue("q$quality read as $read", abs(read!! - quality) <= 1)
        }
    }

    @Test
    fun aJpegThisWalkDoesNotFullyUnderstandTakesTheLongWay() {
        val refused =
            mapOf(
                "lossless (SOF3)" to jpeg(soi, dqt(), sof(marker = 0xC3), dht, sos, entropy, eoi),
                "arithmetic (SOF9)" to jpeg(soi, dqt(), sof(marker = 0xC9), dht, sos, entropy, eoi),
                "12-bit" to jpeg(soi, dqt(), sof(precision = 12), dht, sos, entropy, eoi),
                "CMYK" to jpeg(soi, dqt(), sof(components = 4), dht, sos, entropy, eoi),
                "two frames" to jpeg(soi, dqt(), sof(), sof(), dht, sos, entropy, eoi),
                "zero height (DNL)" to jpeg(soi, dqt(), sof(height = 0), dht, sos, entropy, eoi),
                "no tables" to jpeg(soi, sof(), dht, sos, entropy, eoi),
                "16-bit luminance table" to jpeg(soi, segment(0xDB, bytes(0x10) + ByteArray(128) { 1 }), sof(), dht, sos, entropy, eoi),
                "a DQT that does not tile" to jpeg(soi, segment(0xDB, bytes(0x00) + ByteArray(70) { 1 }), sof(), dht, sos, entropy, eoi),
                "no scan" to jpeg(soi, dqt(), sof(), dht, eoi),
                "a scan ahead of its frame" to jpeg(soi, dqt(), sos, entropy, sof(), eoi),
                "a restart marker between segments" to jpeg(soi, bytes(0xFF, 0xD0), dqt(), sof(), dht, sos, entropy, eoi),
                "an Adobe segment carrying more" to
                    jpeg(soi, segment(0xEE, ascii("Adobe") + ByteArray(20)), dqt(), sof(), dht, sos, entropy, eoi),
                "a length past the end" to jpeg(soi, dqt(), bytes(0xFF, 0xE1, 0x7F, 0xFF, 1, 2, 3)),
                "not a JPEG" to ascii("GIF89a not a jpeg at all"),
                "empty" to ByteArray(0),
            )
        for ((case, source) in refused) assertNull(case, ImageStrip.of(source))
    }

    @Test
    fun everyTruncationTakesTheLongWayWithoutThrowing() {
        val source = jpeg(soi, jfif, exif, icc1, adobe, dqt(), sof(), dht, sos, entropy, eoi)
        for (length in 0 until source.size) assertNull("cut at $length", ImageStrip.of(source.copyOf(length)))
    }

    // --- WebP ---

    private fun le32(value: Int) = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF, (value shr 24) and 0xFF)

    private fun chunk(
        tag: String,
        data: ByteArray,
    ): ByteArray = ascii(tag) + le32(data.size) + data + if (data.size % 2 == 1) bytes(0) else ByteArray(0)

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ascii("WEBP")) { acc, chunk -> acc + chunk }
        return ascii("RIFF") + le32(body.size) + body
    }

    /** A VP8 key frame's header (RFC 6386 §9.1) for a [width] × [height] picture, and a little of its data. */
    private fun vp8(
        width: Int = 400,
        height: Int = 300,
    ) = chunk("VP8 ", bytes(0x50, 0x02, 0x00, 0x9D, 0x01, 0x2A, width and 0xFF, width shr 8, height and 0xFF, height shr 8, 0x11, 0x22))

    private fun vp8x(
        flags: Int,
        width: Int = 400,
        height: Int = 300,
    ) = chunk(
        "VP8X",
        bytes(flags, 0, 0, 0) +
            bytes((width - 1) and 0xFF, (width - 1) shr 8, 0, (height - 1) and 0xFF, (height - 1) shr 8, 0),
    )

    private val iccp = chunk("ICCP", ascii("p3icc"))
    private val alph = chunk("ALPH", bytes(0, 1, 2, 3))
    private val webpExif = chunk("EXIF", ascii("MM GPS"))
    private val webpXmp = chunk("XMP ", ascii("<x:xmpmeta/>"))
    private val unknown = chunk("ZZZZ", ascii("vendor"))

    @Test
    fun aSimpleLossyWebpIsItsOwnStrip() {
        val source = riff(vp8())
        val stripped = checkNotNull(ImageStrip.of(source))
        assertArrayEquals(source, stripped.bytes)
        assertEquals("image/webp", stripped.mime)
        assertEquals(400 to 300, stripped.width to stripped.height)
        assertNull(stripped.quality)
    }

    @Test
    fun anExtendedWebpLosesItsMetadataChunksAndTheirFlags() {
        // ICC (0x20), alpha (0x10), EXIF (0x08), XMP (0x04).
        val source = riff(vp8x(0x3C), iccp, alph, vp8(), webpExif, webpXmp, unknown)

        val stripped = checkNotNull(ImageStrip.of(source))

        assertArrayEquals(riff(vp8x(0x30), iccp, alph, vp8()), stripped.bytes)
        assertEquals(400 to 300, stripped.width to stripped.height)
        assertArrayEquals(stripped.bytes, checkNotNull(ImageStrip.of(stripped.bytes)).bytes)
    }

    @Test
    fun aWebpThisWalkDoesNotStripTakesTheLongWay() {
        val refused =
            mapOf(
                "animated" to riff(vp8x(0x12), chunk("ANIM", ByteArray(6)), chunk("ANMF", ByteArray(16))),
                "lossless" to riff(chunk("VP8L", bytes(0x2F, 0, 0, 0, 0))),
                "lossless under VP8X" to riff(vp8x(0x08), chunk("VP8L", bytes(0x2F, 0, 0, 0, 0)), webpExif),
                "two frames" to riff(vp8x(0), vp8(), vp8()),
                "no frame" to riff(vp8x(0x08), webpExif),
                "an inter frame" to riff(chunk("VP8 ", bytes(0x01, 0x02, 0x00, 0x9D, 0x01, 0x2A, 1, 0, 1, 0))),
                "a RIFF size past the end" to riff(vp8()).copyOf(riff(vp8()).size - 4),
            )
        for ((case, source) in refused) assertNull(case, ImageStrip.of(source))
    }

    @Test
    fun everyWebpTruncationTakesTheLongWayWithoutThrowing() {
        val source = riff(vp8x(0x3C), iccp, alph, vp8(), webpExif, webpXmp)
        for (length in 0 until source.size) assertNull("cut at $length", ImageStrip.of(source.copyOf(length)))
    }

    // --- animated WebP (ADR 2026-10.8hmx) ---

    private fun u24(value: Int) = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF)

    /** An `ANMF` covering a [width] × [height] frame at the origin for [durationMs], holding [content]. */
    private fun anmf(
        vararg content: ByteArray,
        width: Int = 400,
        height: Int = 300,
        durationMs: Int = 100,
    ) = chunk(
        "ANMF",
        u24(0) + u24(0) + u24(width - 1) + u24(height - 1) + u24(durationMs) + bytes(0) + content.fold(ByteArray(0)) { a, c -> a + c },
    )

    private val anim = chunk("ANIM", bytes(0, 0, 0, 0, 0, 0))

    @Test
    fun anAnimatedWebpLosesItsMetadataAndKeepsEveryFrameByteForByte() {
        // ICC (0x20), alpha (0x10), EXIF (0x08), XMP (0x04), animation (0x02).
        val frames = arrayOf(anmf(alph, vp8()), anmf(vp8()), anmf(chunk("VP8L", bytes(0x2F, 0, 0, 0, 0))))
        val source = riff(vp8x(0x3E), iccp, anim, *frames, webpExif, webpXmp, unknown)

        val stripped = checkNotNull(ImageStrip.animatedWebp(source))

        assertArrayEquals(riff(vp8x(0x32), iccp, anim, *frames), stripped.bytes)
        assertEquals(400 to 300, stripped.width to stripped.height)
        assertEquals(3, stripped.frames)
        assertArrayEquals(stripped.bytes, checkNotNull(ImageStrip.animatedWebp(stripped.bytes)).bytes)
        // The still door still refuses it: an animation is the animated door's business.
        assertNull(ImageStrip.of(source))
    }

    @Test
    fun anAnimatedWebpThisWalkDoesNotFullyUnderstandIsNotStripped() {
        val refused =
            mapOf(
                "a still" to riff(vp8x(0x10), alph, vp8()),
                "no ANIM" to riff(vp8x(0x02), anmf(vp8())),
                "no frame" to riff(vp8x(0x02), anim),
                "a chunk it does not know inside a frame" to riff(vp8x(0x02), anim, anmf(vp8(), unknown)),
                "metadata inside a frame" to riff(vp8x(0x02), anim, anmf(webpExif, vp8())),
                "two bitstreams in a frame" to riff(vp8x(0x02), anim, anmf(vp8(), vp8())),
                "an empty frame" to riff(vp8x(0x02), anim, anmf()),
                "a still's bitstream outside a frame" to riff(vp8x(0x02), anim, anmf(vp8()), vp8()),
                "a second ANIM" to riff(vp8x(0x02), anim, anmf(vp8()), anim),
                "not a WebP" to ascii("GIF89a") + ByteArray(32),
            )
        for ((case, source) in refused) assertNull(case, ImageStrip.animatedWebp(source))
    }

    @Test
    fun everyAnimatedWebpTruncationIsRefusedWithoutThrowing() {
        val source = riff(vp8x(0x3E), iccp, anim, anmf(alph, vp8()), anmf(vp8()), webpExif)
        for (length in 0 until source.size) assertNull("cut at $length", ImageStrip.animatedWebp(source.copyOf(length)))
    }
}
