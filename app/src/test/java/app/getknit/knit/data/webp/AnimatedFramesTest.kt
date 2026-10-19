package app.getknit.knit.data.webp

import app.getknit.knit.data.webp.AnimatedFrames.Blend
import app.getknit.knit.data.webp.AnimatedFrames.Dispose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.zip.CRC32

/**
 * The demux an APNG or an animated WebP is resampled from (ADR 2026-10.8hmx): every frame's rectangle, time and
 * blend and disposal rules read as the container gives them, and each frame rebuilt as a still the platform decoder
 * reads like any picture — a PNG with the source's colour chunks and none of its text, a WebP with its alpha. Built
 * from hand-made chunks, so each case names exactly what it carries; the pixels are the ingest test's business.
 */
class AnimatedFramesTest {
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun join(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    // --- APNG ---

    private fun be32(value: Int) = bytes(value ushr 24, (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)

    private fun be16(value: Int) = bytes(value shr 8, value and 0xFF)

    private fun png(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val crc = CRC32().apply { update(ascii(type)) }.apply { update(data) }
        return be32(data.size) + ascii(type) + data + be32(crc.value.toInt())
    }

    private val signature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun ihdr(
        width: Int = 100,
        height: Int = 80,
    ) = png("IHDR", be32(width) + be32(height) + bytes(8, 6, 0, 0, 0))

    private fun actl(
        frames: Int,
        plays: Int = 0,
    ) = png("acTL", be32(frames) + be32(plays))

    @Suppress("LongParameterList") // an fcTL is exactly these fields
    private fun fctl(
        sequence: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        delayNum: Int = 1,
        delayDen: Int = 10,
        dispose: Int = 0,
        blend: Int = 0,
    ) = png(
        "fcTL",
        be32(sequence) + be32(width) + be32(height) + be32(x) + be32(y) + be16(delayNum) + be16(delayDen) + bytes(dispose, blend),
    )

    private fun fdat(
        sequence: Int,
        data: ByteArray,
    ) = png("fdAT", be32(sequence) + data)

    private val gama = png("gAMA", be32(45455))
    private val text = png("tEXt", ascii("Author") + bytes(0) + ascii("someone at home"))
    private val iend = png("IEND", ByteArray(0))

    /** A PNG's chunks as (type, data), checking each CRC on the way. */
    private fun chunksOf(still: ByteArray): List<Pair<String, ByteArray>> {
        assertArrayEquals(signature, still.copyOf(8))
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var at = 8
        while (at < still.size) {
            val length =
                ((still[at].toInt() and 0xFF) shl 24) or ((still[at + 1].toInt() and 0xFF) shl 16) or
                    ((still[at + 2].toInt() and 0xFF) shl 8) or (still[at + 3].toInt() and 0xFF)
            val type = String(still, at + 4, 4, Charsets.US_ASCII)
            val data = still.copyOfRange(at + 8, at + 8 + length)
            val crc = CRC32().apply { update(ascii(type)) }.apply { update(data) }
            assertArrayEquals("$type CRC", be32(crc.value.toInt()), still.copyOfRange(at + 8 + length, at + 12 + length))
            chunks += type to data
            at += 12 + length
        }
        return chunks
    }

    private fun apng(): ByteArray =
        join(
            listOf(
                signature,
                ihdr(),
                gama,
                text,
                actl(frames = 3, plays = 2),
                fctl(0, 100, 80, dispose = 2, blend = 1),
                png("IDAT", ascii("frame0")),
                fctl(1, 30, 40, x = 10, y = 20, delayNum = 3, delayDen = 100, dispose = 2, blend = 0),
                fdat(2, ascii("frame1")),
                fctl(3, 50, 50, x = 50, y = 30, delayNum = 0, delayDen = 0, dispose = 1, blend = 1),
                fdat(4, ascii("frame")),
                fdat(5, ascii("2")),
                iend,
            ),
        )

    @Test
    fun anApngsFramesAreReadAsItsControlChunksSayAndEachIsAStillOfItsOwn() {
        val frames = checkNotNull(AnimatedFrames.of(apng()))

        assertEquals(100 to 80, frames.width to frames.height)
        assertEquals(2, frames.loopCount)
        assertEquals(listOf(100, 30, 100), frames.frames.map { it.durationMs }) // 1/10 s, 3/100 s, and 0 read as 100
        assertEquals(listOf(0 to 0, 10 to 20, 50 to 30), frames.frames.map { it.x to it.y })
        assertEquals(listOf(100 to 80, 30 to 40, 50 to 50), frames.frames.map { it.width to it.height })
        assertEquals(listOf(Blend.Over, Blend.Source, Blend.Over), frames.frames.map { it.blend })
        // The first frame's "previous" has nothing to go back to, so it clears, as the APNG spec says.
        assertEquals(listOf(Dispose.Background, Dispose.Previous, Dispose.Background), frames.frames.map { it.dispose })
        assertEquals(230, frames.durationMs)

        for ((frame, data) in frames.frames.zip(listOf("frame0", "frame1", "frame2"))) {
            val chunks = chunksOf(frame.still)
            assertEquals(listOf("IHDR", "gAMA", "IDAT", "IEND"), chunks.map { it.first })
            assertArrayEquals(be32(frame.width) + be32(frame.height) + bytes(8, 6, 0, 0, 0), chunks[0].second)
            // The sequence numbers are gone and a frame split over two fdATs is one stream again.
            assertArrayEquals(ascii(data), chunks[2].second)
        }
    }

    @Test
    fun aDefaultImageAheadOfTheFirstControlChunkIsNotAFrame() {
        val source =
            join(
                listOf(
                    signature,
                    ihdr(),
                    actl(frames = 2),
                    png("IDAT", ascii("default")),
                    fctl(0, 100, 80),
                    fdat(1, ascii("first")),
                    fctl(2, 100, 80),
                    fdat(3, ascii("second")),
                    iend,
                ),
            )

        val frames = checkNotNull(AnimatedFrames.of(source))

        assertEquals(2, frames.frames.size)
        assertArrayEquals(ascii("first"), chunksOf(frames.frames[0].still)[1].second)
    }

    @Test
    fun anApngThisWalkCannotDrawIsRefused() {
        val refused =
            mapOf(
                "a plain PNG" to join(listOf(signature, ihdr(), png("IDAT", ascii("still")), iend)),
                "one frame" to join(listOf(signature, ihdr(), actl(1), fctl(0, 100, 80), png("IDAT", ascii("x")), iend)),
                "a frame past the canvas" to
                    join(
                        listOf(
                            signature,
                            ihdr(),
                            actl(2),
                            fctl(0, 100, 80),
                            png("IDAT", ascii("x")),
                            fctl(1, 60, 10, x = 50),
                            fdat(2, ascii("y")),
                            iend,
                        ),
                    ),
                "fdAT ahead of any fcTL" to join(listOf(signature, ihdr(), actl(2), fdat(0, ascii("x")), iend)),
                "a frame with no data" to
                    join(listOf(signature, ihdr(), actl(2), fctl(0, 100, 80), png("IDAT", ascii("x")), fctl(1, 10, 10), iend)),
                "a canvas past the cap" to
                    join(
                        listOf(
                            signature,
                            ihdr(width = AnimatedFrames.MAX_CANVAS + 1),
                            actl(2),
                            fctl(0, 10, 10),
                            png("IDAT", ascii("x")),
                            fctl(1, 10, 10),
                            fdat(2, ascii("y")),
                            iend,
                        ),
                    ),
                "too many frames declared" to join(listOf(signature, ihdr(), actl(AnimatedFrames.MAX_SOURCE_FRAMES + 1), iend)),
                "a bad dispose op" to
                    join(listOf(signature, ihdr(), actl(2), fctl(0, 100, 80, dispose = 3), png("IDAT", ascii("x")), iend)),
            )
        for ((case, source) in refused) assertNull(case, AnimatedFrames.of(source))
    }

    @Test
    fun everyApngTruncationAnswersWithoutThrowing() {
        val source = apng()
        // A cut inside the last frame can still leave two frames standing; what matters is that no cut throws.
        for (length in 0 until source.size) AnimatedFrames.of(source.copyOf(length))
        assertNull(AnimatedFrames.of(source.copyOf(40)))
    }

    // --- WebP ---

    private fun le32(value: Int) = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF, value ushr 24)

    private fun u24(value: Int) = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF)

    private fun chunk(
        tag: String,
        data: ByteArray,
    ): ByteArray = ascii(tag) + le32(data.size) + data + if (data.size % 2 == 1) bytes(0) else ByteArray(0)

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ascii("WEBP")) { acc, chunk -> acc + chunk }
        return ascii("RIFF") + le32(body.size) + body
    }

    private fun vp8x(
        flags: Int,
        width: Int,
        height: Int,
    ) = chunk("VP8X", bytes(flags, 0, 0, 0) + u24(width - 1) + u24(height - 1))

    @Suppress("LongParameterList") // an ANMF header is exactly these fields
    private fun anmf(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        durationMs: Int,
        flags: Int,
        vararg content: ByteArray,
    ) = chunk("ANMF", u24(x / 2) + u24(y / 2) + u24(width - 1) + u24(height - 1) + u24(durationMs) + bytes(flags) + join(content.toList()))

    private val vp8 = chunk("VP8 ", ascii("lossy-frame"))
    private val alph = chunk("ALPH", ascii("alpha"))
    private val vp8l = chunk("VP8L", ascii("lossless"))

    @Test
    fun anAnimatedWebpsFramesKeepTheirRectanglesFlagsAndAlpha() {
        val source =
            riff(
                vp8x(0x12, 400, 300),
                chunk("ANIM", bytes(0, 0, 0, 0, 3, 0)),
                anmf(0, 0, 400, 300, 80, 0x00, alph, vp8),
                anmf(20, 10, 100, 50, 0, 0x03, vp8l, chunk("ZZZZ", ascii("vendor"))),
                chunk("EXIF", ascii("MM GPS")),
            )

        val frames = checkNotNull(AnimatedFrames.of(source))

        assertEquals(400 to 300, frames.width to frames.height)
        assertEquals(3, frames.loopCount)
        assertEquals(listOf(80, 100), frames.frames.map { it.durationMs })
        assertEquals(listOf(0 to 0, 20 to 10), frames.frames.map { it.x to it.y })
        assertEquals(listOf(Blend.Over, Blend.Source), frames.frames.map { it.blend })
        assertEquals(listOf(Dispose.None, Dispose.Background), frames.frames.map { it.dispose })
        // A lossy frame with alpha is an extended still; a lossless one is a simple one.
        assertArrayEquals(riff(vp8x(0x10, 400, 300), alph, vp8), frames.frames[0].still)
        assertArrayEquals(riff(vp8l), frames.frames[1].still)
    }

    @Test
    fun anAnimatedWebpThisWalkCannotDrawIsRefused() {
        val anim = chunk("ANIM", ByteArray(6))
        val refused =
            mapOf(
                "a still" to riff(vp8x(0x10, 400, 300), alph, vp8),
                "one frame" to riff(vp8x(0x02, 400, 300), anim, anmf(0, 0, 400, 300, 80, 0, vp8)),
                "a frame past the canvas" to
                    riff(vp8x(0x02, 400, 300), anim, anmf(0, 0, 400, 300, 80, 0, vp8), anmf(200, 0, 300, 300, 80, 0, vp8)),
                "a frame with no bitstream" to
                    riff(vp8x(0x02, 400, 300), anim, anmf(0, 0, 400, 300, 80, 0, vp8), anmf(0, 0, 400, 300, 80, 0, alph)),
                "a canvas past the cap" to
                    riff(vp8x(0x02, AnimatedFrames.MAX_CANVAS + 1, 300), anim, anmf(0, 0, 4, 3, 80, 0, vp8), anmf(0, 0, 4, 3, 80, 0, vp8)),
            )
        for ((case, source) in refused) assertNull(case, AnimatedFrames.of(source))
    }

    @Test
    fun everyAnimatedWebpTruncationIsRefusedWithoutThrowing() {
        val source =
            riff(
                vp8x(0x12, 400, 300),
                chunk("ANIM", ByteArray(6)),
                anmf(0, 0, 400, 300, 80, 0, alph, vp8),
                anmf(0, 0, 400, 300, 80, 0, vp8),
            )
        for (length in 0 until source.size) assertNull("cut at $length", AnimatedFrames.of(source.copyOf(length)))
        assertEquals(2, AnimatedFrames.of(source)?.frames?.size)
    }
}
