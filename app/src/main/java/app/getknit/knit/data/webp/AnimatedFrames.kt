package app.getknit.knit.data.webp

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * An APNG's or an animated WebP's frames, taken out of their container so [WebpTranscode] can composite and
 * resample them the way it resamples a GIF (#133, ADR 2026-10.8hmx). Each [Frame] carries a **standalone still**
 * — a one-frame PNG or WebP the platform decoder reads like any picture — with the rectangle it covers on the
 * canvas, how long it shows, and how it is blended in and disposed of afterwards.
 *
 * This exists because the platform can play an animated WebP but cannot hand over its frames (no seek on
 * `AnimatedImageDrawable`), and cannot play an APNG at all, so an APNG has to leave the phone as an animated WebP
 * to animate on an Android recipient.
 *
 * - **APNG**: each still is the signature, the `IHDR` rewritten to the frame's size, the ancillary chunks a decoder
 *   needs to draw the same colours (`PLTE`, `tRNS`, `gAMA`, `cHRM`, `sRGB`, `iCCP`, `sBIT`), the frame's data
 *   (`IDAT`, or `fdAT` with its sequence number taken off) as one `IDAT`, and `IEND`. A default image that sits
 *   before the first `fcTL` is not part of the animation and is left out.
 * - **WebP**: each `ANMF`'s bitstream, wrapped as a simple lossy file, an extended one with its `ALPH`, or a
 *   lossless one.
 *
 * Nothing here decodes a pixel, and none of these bytes are ever sent: they are only read back by the decoder,
 * which is why chunks this walk does not need are ignored rather than refused. The canvas and the frame count are
 * capped before anything is copied (ADR 051), and every walk is bounds-checked, so truncated or hostile bytes
 * answer null.
 *
 * Pure Kotlin, no Android — JVM-tested in `AnimatedFramesTest`.
 */
class AnimatedFrames(
    val width: Int,
    val height: Int,
    val loopCount: Int,
    val frames: List<Frame>,
) {
    /** How a frame's pixels go onto the canvas: over what is there ([Over]), or in place of it ([Source]). */
    enum class Blend { Over, Source }

    /** What happens to a frame's rectangle once it has shown, before the next frame draws. */
    enum class Dispose { None, Background, Previous }

    /** One frame: its [still], the rectangle it covers, how long it shows, and its [blend] and [dispose]. */
    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        val blend: Blend,
        val dispose: Dispose,
        val still: ByteArray,
    )

    /** The whole animation's length, once through. */
    val durationMs: Int get() = frames.sumOf { it.durationMs }

    @Suppress("TooManyFunctions") // two container walks and the byte readers they share; splitting would scatter one demux
    companion object {
        /** The most source frames one animation may have before it is not worth decoding them all. */
        const val MAX_SOURCE_FRAMES = 1000

        /** The longest canvas edge a demux accepts: past it, the source is not a sticker or a reaction image. */
        const val MAX_CANVAS = 4096

        /**
         * A frame's delay at or under this is read as [DEFAULT_DELAY_MS], as browsers read a GIF's: a zero or
         * near-zero delay is an encoder's shorthand, not a request for a hundred frames a second.
         */
        const val MIN_DELAY_MS = 10
        const val DEFAULT_DELAY_MS = 100

        /** One frame's longest delay, so the total cannot overflow whatever the container says. */
        const val MAX_DELAY_MS = 60_000

        /** [bytes]' frames, or null when they are not an APNG or animated WebP this walk understands. */
        @Suppress("MagicNumber") // the WebP brand sits at a fixed offset in its RIFF header
        fun of(bytes: ByteArray): AnimatedFrames? =
            runCatching {
                when {
                    bytes.matchesAt(PNG, 0) -> apng(bytes)
                    bytes.matchesAt(RIFF, 0) && bytes.matchesAt(WEBP, 8) -> webp(bytes)
                    else -> null
                }
            }.getOrNull()?.takeIf { it.frames.size > 1 }

        // --- APNG ---

        /** A frame being gathered: its `fcTL` facts and the data chunks that follow it. */
        private class Pending(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
            val durationMs: Int,
            val blend: Blend,
            val dispose: Dispose,
            val data: ByteArrayOutputStream = ByteArrayOutputStream(),
        )

        @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
        private fun apng(bytes: ByteArray): AnimatedFrames? {
            var at = PNG.size
            var ihdr: ByteArray? = null
            var plays = 0
            var declared = -1
            var seenData = false
            val ancillary = ArrayList<Pair<String, ByteArray>>()
            val done = ArrayList<Pending>()
            var current: Pending? = null
            while (at + 12 <= bytes.size) {
                val length = bytes.u32be(at)
                if (length < 0 || length > bytes.size - at - 12) return null
                val type = bytes.fourcc(at + 4)
                val data = at + 8
                when (type) {
                    "IHDR" -> {
                        if (ihdr != null || length != IHDR_SIZE) return null
                        ihdr = bytes.copyOfRange(data, data + length)
                    }

                    "acTL" -> {
                        if (length != 8) return null
                        declared = bytes.u32be(data)
                        plays = bytes.u32be(data + 4)
                        if (declared !in 1..MAX_SOURCE_FRAMES || plays < 0) return null
                    }

                    "fcTL" -> {
                        if (length != FCTL_SIZE) return null
                        current?.let(done::add)
                        if (done.size >= MAX_SOURCE_FRAMES) return null
                        val num = bytes.u16be(data + 20)
                        val den = bytes.u16be(data + 22).takeIf { it != 0 } ?: 100
                        current =
                            Pending(
                                x = bytes.u32be(data + 12),
                                y = bytes.u32be(data + 16),
                                width = bytes.u32be(data + 4),
                                height = bytes.u32be(data + 8),
                                durationMs = delay(num * 1000L / den),
                                dispose =
                                    when (bytes.u8(data + 24)) {
                                        0 -> Dispose.None
                                        1 -> Dispose.Background
                                        2 -> if (done.isEmpty()) Dispose.Background else Dispose.Previous
                                        else -> return null
                                    },
                                blend =
                                    when (bytes.u8(data + 25)) {
                                        0 -> Blend.Source
                                        1 -> Blend.Over
                                        else -> return null
                                    },
                            )
                    }

                    "IDAT" -> {
                        seenData = true
                        // Data ahead of every fcTL is a default image the animation does not show.
                        current?.data?.write(bytes, data, length)
                    }

                    "fdAT" -> {
                        if (length < 4) return null
                        (current ?: return null).data.write(bytes, data + 4, length - 4)
                    }

                    "IEND" -> {
                        break
                    }

                    in DECODE_ANCILLARY -> {
                        if (!seenData) ancillary += type to bytes.copyOfRange(data, data + length)
                    }
                }
                at = data + length + 4
            }
            current?.let(done::add)
            val header = ihdr ?: return null
            if (declared < 0) return null
            val width = header.u32be(0)
            val height = header.u32be(4)
            if (width !in 1..MAX_CANVAS || height !in 1..MAX_CANVAS) return null
            val frames =
                done.map { frame ->
                    if (frame.width < 1 || frame.height < 1) return null
                    if (frame.x < 0 || frame.y < 0) return null
                    if (frame.x.toLong() + frame.width > width || frame.y.toLong() + frame.height > height) return null
                    if (frame.data.size() == 0) return null
                    Frame(
                        x = frame.x,
                        y = frame.y,
                        width = frame.width,
                        height = frame.height,
                        durationMs = frame.durationMs,
                        blend = frame.blend,
                        dispose = frame.dispose,
                        still = pngStill(header, frame, ancillary),
                    )
                }
            return AnimatedFrames(width, height, plays, frames)
        }

        /** A one-frame PNG of [frame]: the source's header at the frame's size, its colour chunks, its data. */
        @Suppress("MagicNumber")
        private fun pngStill(
            header: ByteArray,
            frame: Pending,
            ancillary: List<Pair<String, ByteArray>>,
        ): ByteArray {
            val out = ByteArrayOutputStream(frame.data.size() + 256)
            out.write(PNG)
            val ihdr = header.copyOf()
            ihdr.putU32be(0, frame.width)
            ihdr.putU32be(4, frame.height)
            out.writeChunk("IHDR", ihdr)
            for ((type, data) in ancillary) out.writeChunk(type, data)
            out.writeChunk("IDAT", frame.data.toByteArray())
            out.writeChunk("IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun ByteArrayOutputStream.writeChunk(
            type: String,
            data: ByteArray,
        ) {
            val tag = type.toByteArray(Charsets.US_ASCII)
            writeU32be(data.size)
            write(tag)
            write(data)
            val crc = CRC32().apply { update(tag) }.apply { update(data) }
            writeU32be(crc.value.toInt())
        }

        // --- WebP ---

        @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LongMethod")
        private fun webp(bytes: ByteArray): AnimatedFrames? {
            val end = 8L + bytes.u32le(4)
            if (end > bytes.size || end < 30) return null
            if (bytes.fourcc(12) != "VP8X" || bytes.u32le(16) != 10L) return null
            if (bytes.u8(20) and VP8X_ANIMATION == 0) return null
            val width = 1 + bytes.u24le(24)
            val height = 1 + bytes.u24le(27)
            if (width > MAX_CANVAS || height > MAX_CANVAS) return null
            var loops = 0
            val frames = ArrayList<Frame>()
            var at = 30
            while (at < end) {
                if (at + 8 > end) return null
                val tag = bytes.fourcc(at)
                val size = bytes.u32le(at + 4)
                val dataEnd = at + 8L + size
                if (dataEnd > end) return null
                val data = at + 8
                when (tag) {
                    "ANIM" -> {
                        if (size != 6L) return null
                        loops = bytes.u16le(data + 4)
                    }

                    "ANMF" -> {
                        if (frames.size >= MAX_SOURCE_FRAMES) return null
                        frames += webpFrame(bytes, data, size.toInt(), width, height) ?: return null
                    }
                }
                at = (dataEnd + (size and 1L)).toInt()
            }
            return AnimatedFrames(width, height, loops, frames)
        }

        /** One `ANMF` as a [Frame]: its header's rectangle, time and flags, and its bitstream as a still. */
        @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
        private fun webpFrame(
            bytes: ByteArray,
            start: Int,
            size: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ): Frame? {
            if (size < ANMF_HEADER + 8) return null
            val x = 2 * bytes.u24le(start)
            val y = 2 * bytes.u24le(start + 3)
            val width = 1 + bytes.u24le(start + 6)
            val height = 1 + bytes.u24le(start + 9)
            if (x + width > canvasWidth || y + height > canvasHeight) return null
            val duration = delay(bytes.u24le(start + 12).toLong())
            val flags = bytes.u8(start + 15)
            var alph: IntRange? = null
            var image: Pair<String, IntRange>? = null
            var at = start + ANMF_HEADER
            val end = start + size
            while (at < end) {
                if (at + 8 > end) return null
                val tag = bytes.fourcc(at)
                val chunkSize = bytes.u32le(at + 4)
                if (at + 8L + chunkSize > end) return null
                val range = (at + 8) until (at + 8 + chunkSize.toInt())
                when (tag) {
                    "ALPH" -> if (image == null) alph = range
                    "VP8 ", "VP8L" -> if (image == null) image = tag to range
                }
                at = (at + 8L + chunkSize + (chunkSize and 1L)).toInt()
            }
            val (kind, bitstream) = image ?: return null
            val chunks = ArrayList<Pair<String, ByteArray>>()
            if (kind == "VP8 " && alph != null) {
                val vp8x = ByteArray(10)
                vp8x[0] = VP8X_ALPHA.toByte()
                vp8x.putU24le(4, width - 1)
                vp8x.putU24le(7, height - 1)
                chunks += "VP8X" to vp8x
                chunks += "ALPH" to bytes.copyOfRange(alph.first, alph.last + 1)
            }
            chunks += kind to bytes.copyOfRange(bitstream.first, bitstream.last + 1)
            return Frame(
                x = x,
                y = y,
                width = width,
                height = height,
                durationMs = duration,
                blend = if (flags and ANMF_NO_BLEND != 0) Blend.Source else Blend.Over,
                dispose = if (flags and ANMF_DISPOSE_BACKGROUND != 0) Dispose.Background else Dispose.None,
                still = riff(chunks),
            )
        }

        /** A RIFF/WEBP file of [chunks], each padded to an even length. */
        @Suppress("MagicNumber")
        private fun riff(chunks: List<Pair<String, ByteArray>>): ByteArray {
            val body = ByteArrayOutputStream()
            for ((tag, data) in chunks) {
                body.write(tag.toByteArray(Charsets.US_ASCII))
                body.writeU32le(data.size)
                body.write(data)
                if (data.size % 2 == 1) body.write(0)
            }
            val out = ByteArrayOutputStream(12 + body.size())
            out.write(RIFF)
            out.writeU32le(4 + body.size())
            out.write(WEBP)
            body.writeTo(out)
            return out.toByteArray()
        }

        /** A container's delay as the one shown: near-zero read as [DEFAULT_DELAY_MS], and capped. */
        private fun delay(ms: Long): Int = if (ms <= MIN_DELAY_MS) DEFAULT_DELAY_MS else ms.coerceAtMost(MAX_DELAY_MS.toLong()).toInt()

        // --- bytes ---

        @Suppress("MagicNumber")
        private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

        @Suppress("MagicNumber")
        private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

        /** A big-endian unsigned 32-bit field, or -1 when it overflows an Int (no real field is that large). */
        @Suppress("MagicNumber")
        private fun ByteArray.u32be(at: Int): Int {
            val value = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
            return if (value > Int.MAX_VALUE) -1 else value.toInt()
        }

        @Suppress("MagicNumber")
        private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

        @Suppress("MagicNumber")
        private fun ByteArray.u24le(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)

        @Suppress("MagicNumber")
        private fun ByteArray.u32le(at: Int): Long = u24le(at).toLong() or (u8(at + 3).toLong() shl 24)

        @Suppress("MagicNumber")
        private fun ByteArray.putU32be(
            at: Int,
            value: Int,
        ) {
            for (i in 0 until 4) this[at + i] = (value ushr (24 - 8 * i)).toByte()
        }

        @Suppress("MagicNumber")
        private fun ByteArray.putU24le(
            at: Int,
            value: Int,
        ) {
            for (i in 0 until 3) this[at + i] = (value ushr (8 * i)).toByte()
        }

        @Suppress("MagicNumber")
        private fun ByteArrayOutputStream.writeU32be(value: Int) {
            for (shift in 24 downTo 0 step 8) write((value ushr shift) and 0xFF)
        }

        @Suppress("MagicNumber")
        private fun ByteArrayOutputStream.writeU32le(value: Int) {
            for (shift in 0 until 32 step 8) write((value ushr shift) and 0xFF)
        }

        @Suppress("MagicNumber")
        private fun ByteArray.fourcc(at: Int): String = String(this, at, 4, Charsets.US_ASCII)

        private fun ByteArray.matchesAt(
            prefix: ByteArray,
            offset: Int,
        ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

        private const val IHDR_SIZE = 13
        private const val FCTL_SIZE = 26

        /** Frame position, size, duration and flags ahead of an `ANMF`'s bitstream. */
        private const val ANMF_HEADER = 16

        private const val VP8X_ANIMATION = 0x02
        private const val VP8X_ALPHA = 0x10
        private const val ANMF_NO_BLEND = 0x02
        private const val ANMF_DISPOSE_BACKGROUND = 0x01

        /** The chunks ahead of the image data that change how a PNG's pixels decode, and nothing else. */
        private val DECODE_ANCILLARY = setOf("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT")

        private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

        @Suppress("MagicNumber") // raw file-signature bytes
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
