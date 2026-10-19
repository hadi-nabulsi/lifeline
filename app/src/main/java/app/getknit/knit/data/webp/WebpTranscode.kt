package app.getknit.knit.data.webp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.data.downscale
import app.getknit.knit.data.lossyWebpFormat
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Re-encodes an animation into a smaller **animated WebP** for faster mesh transmit: resamples its frames at a
 * capped frame rate, downscales them, compresses each with the built-in `Bitmap.compress(WEBP_LOSSY, …)`
 * (hardware VP8 — far more efficient than GIF's LZW), and muxes them into one animated WebP via
 * [AnimatedWebpMuxer].
 *
 * Two sources feed the one sampler. A GIF ([shrink]) is decoded with [android.graphics.Movie]: it renders the
 * composited frame at any time offset, exactly the fixed-rate resampling decimation needs. It's deprecated but the
 * only built-in GIF frame sampler, and still functional. An APNG or an animated WebP ([shrinkAnimated], ADR
 * 2026-10.8hmx) has no such sampler on the platform, so its frames come out of the container ([AnimatedFrames])
 * and are composited here, at the output size, by the blend and disposal rules their container gives.
 *
 * The output is `image/webp`, which the app already stores, transmits, and displays (Coil's
 * `AnimatedImageDecoder` plays animated WebP, and static-WebP attachments already exist), so only the
 * attachment mime changes. Only [app.getknit.knit.data.AttachmentStore] calls this, on the send side. Neither
 * entry ever throws: a codec failure answers null and the caller keeps what it had.
 *
 * Limitation: `Movie` renders full composited frames, correct for the full-frame GIFs that dominate
 * chat use; a partial-frame ("dirty rectangle") GIF could show minor artifacts.
 */
object WebpTranscode {
    private const val TAG = "WebpTranscode"

    /** Cap on emitted frames so a very long animation can't blow up encode time / output size. */
    private const val MAX_FRAMES = 200

    /** The largest `loopCount` an `ANIM` chunk can say; an APNG's play count is clamped to it. */
    private const val MAX_LOOPS = 0xFFFF

    /** A successful re-encode plus the before/after stats logged for it. */
    private class Encoded(
        val bytes: ByteArray,
        val srcW: Int,
        val srcH: Int,
        val outW: Int,
        val outH: Int,
        val frames: Int,
        val durationMs: Int,
    )

    /**
     * Re-encodes [bytes] (an animated GIF) into a smaller animated WebP bounded by [maxDim] (longest
     * edge) and [maxFps], at WebP [quality] (0–100). Returns the smaller WebP, or null if decode/encode
     * fails, the timing is unknown, or the result isn't smaller than the input — the caller keeps the GIF.
     */
    fun shrink(
        bytes: ByteArray,
        maxDim: Int,
        maxFps: Int,
        quality: Int,
    ): ByteArray? = logged("gif", bytes.size, quality) { shrinkGif(bytes, maxDim, maxFps, quality) }

    /**
     * Re-encodes [frames] (an APNG's or an animated WebP's, from [AnimatedFrames.of]) into an animated WebP bounded
     * by [maxDim] and [maxFps], at WebP [quality], keeping the source's loop count. [sourceSize] is the picked
     * file's length, for the log. Null if a frame will not decode or encode. Unlike [shrink] it does not insist on
     * coming out smaller — an APNG has to become a WebP to animate on an Android recipient at all — so the caller
     * decides what to compare it against.
     */
    fun shrinkAnimated(
        frames: AnimatedFrames,
        sourceSize: Int,
        maxDim: Int,
        maxFps: Int,
        quality: Int,
    ): ByteArray? = logged("animation", sourceSize, quality) { composite(frames, maxDim, maxFps, quality) }

    /** Runs [encode], logging its outcome against the [kind] of source, its [sourceSize] and the [quality]; never throws. */
    private inline fun logged(
        kind: String,
        sourceSize: Int,
        quality: Int,
        encode: () -> Encoded?,
    ): ByteArray? {
        val result =
            try {
                encode()
            } catch (ignored: Throwable) {
                // A codec failure must never break sending — the caller falls back to what it had.
                Log.i(
                    TAG,
                    "$kind kept as it was ($sourceSize B) — webp transcode threw ${ignored.javaClass.simpleName}: ${ignored.message}",
                )
                null
            }
        // A null result already logged its specific reason (undecodable / not smaller / threw) below.
        if (result == null) return null
        val pct = 100 - (result.bytes.size * 100L / sourceSize.coerceAtLeast(1))
        val change = if (pct >= 0) "$pct% smaller" else "${-pct}% larger" // an APNG must become a WebP, smaller or not
        val fps = result.frames * 1000 / result.durationMs.coerceAtLeast(1)
        Log.i(
            TAG,
            "$kind -> webp $sourceSize B -> ${result.bytes.size} B ($change): " +
                "${result.srcW}x${result.srcH} -> ${result.outW}x${result.outH}, " +
                "${result.frames} frames @ ~$fps fps q$quality",
        )
        return result.bytes
    }

    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private fun shrinkGif(
        bytes: ByteArray,
        maxDim: Int,
        maxFps: Int,
        quality: Int,
    ): Encoded? {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
        if (movie == null) {
            Log.i(TAG, "gif kept verbatim (${bytes.size} B) — Movie could not decode it")
            return null
        }
        val srcW = movie.width()
        val srcH = movie.height()
        val duration = movie.duration() // 0 ⇒ unknown timing, can't resample reliably
        if (srcW <= 0 || srcH <= 0 || duration <= 0) {
            Log.i(TAG, "gif kept verbatim (${bytes.size} B) — unknown size/timing (${srcW}x$srcH, ${duration}ms)")
            return null
        }
        val frames = MovieSource(movie, maxDim).use { sample(it, duration, maxFps, quality) }
        val muxed = frames?.let { AnimatedWebpMuxer.mux(it.webp, it.durations, it.outW, it.outH) }
        // Never regress: only replace the original when the WebP actually saved bytes. Real GIFs
        // (already-optimized Tenor/Gboard ones included) reliably shrink here, but keep the guard.
        if (frames == null || muxed == null || muxed.size >= bytes.size) {
            Log.i(
                TAG,
                "gif kept verbatim (${bytes.size} B) — webp not smaller (${muxed?.size ?: 0} B, ${srcW}x$srcH" +
                    " -> ${frames?.outW ?: 0}x${frames?.outH ?: 0}, ${frames?.webp?.size ?: 0} frames)",
            )
            return null
        }
        return Encoded(muxed, srcW, srcH, frames.outW, frames.outH, frames.webp.size, duration)
    }

    private fun composite(
        source: AnimatedFrames,
        maxDim: Int,
        maxFps: Int,
        quality: Int,
    ): Encoded? {
        val duration = source.durationMs
        val frames = Compositor(source, maxDim).use { sample(it, duration, maxFps, quality) }
        val muxed =
            frames?.let {
                AnimatedWebpMuxer.mux(it.webp, it.durations, it.outW, it.outH, loopCount = source.loopCount.coerceIn(0, MAX_LOOPS))
            }
        if (frames == null || muxed == null) {
            Log.i(TAG, "animation not transcoded (${source.width}x${source.height}, ${source.frames.size} frames) — a frame would not draw")
            return null
        }
        return Encoded(muxed, source.width, source.height, frames.outW, frames.outH, frames.webp.size, duration)
    }

    /** Per-frame single-frame WebP bytes + per-frame on-screen durations + the uniform output dims. */
    private class Frames(
        val webp: List<ByteArray>,
        val durations: IntArray,
        val outW: Int,
        val outH: Int,
    )

    /**
     * Where [sample] reads frames from: the picture on screen at a time offset, already at the output size. The
     * bitmap it hands back is its own, valid until the next call; [close] frees what it holds.
     */
    private interface FrameSource : AutoCloseable {
        fun at(timeMs: Int): Bitmap?
    }

    /**
     * Samples [source] at a fixed interval up to [duration] ms — at most [maxFps], and stretched when a long
     * animation would pass [MAX_FRAMES], so the full length survives at a lower rate — compressing each sample to a
     * single-frame WebP at [quality]. Null if a frame fails to draw or encode, or none were produced.
     */
    private fun sample(
        source: FrameSource,
        duration: Int,
        maxFps: Int,
        quality: Int,
    ): Frames? {
        // Keep at least 1 ms between samples; stretch the interval if a long animation would exceed the cap.
        var interval = (1000 / maxFps).coerceAtLeast(1)
        if (duration / interval + 1 > MAX_FRAMES) interval = (duration / MAX_FRAMES) + 1
        val webp = ArrayList<ByteArray>()
        val durations = ArrayList<Int>()
        var outW = 0
        var outH = 0
        var t = 0
        while (t < duration) {
            val frame = source.at(t) ?: return null
            outW = frame.width
            outH = frame.height
            val fo = ByteArrayOutputStream()
            if (!frame.compress(lossyWebpFormat(), quality, fo)) return null
            val next = t + interval
            durations.add(minOf(next, duration) - t) // this frame's share of wall-time
            webp.add(fo.toByteArray())
            t = next
        }
        return if (webp.isEmpty()) null else Frames(webp, durations.toIntArray(), outW, outH)
    }

    /** A GIF through [Movie]: one reusable buffer at the source size, drawn each step and downscaled. */
    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private class MovieSource(
        private val movie: Movie,
        private val maxDim: Int,
    ) : FrameSource {
        // Movie needs a software canvas; same src dims ⇒ uniform frame size.
        private val buffer = createBitmap(movie.width(), movie.height())
        private val canvas = Canvas(buffer)
        private var scaled: Bitmap? = null

        override fun at(timeMs: Int): Bitmap {
            buffer.eraseColor(Color.TRANSPARENT)
            movie.setTime(timeMs)
            movie.draw(canvas, 0f, 0f)
            scaled?.takeIf { it !== buffer }?.recycle()
            return downscale(buffer, maxDim).also { scaled = it }
        }

        override fun close() {
            scaled?.takeIf { it !== buffer }?.recycle()
            buffer.recycle()
        }
    }

    /**
     * An [AnimatedFrames] drawn frame by frame onto one canvas **at the output size**: each frame is decoded no
     * larger than it lands ([decodeBoundedFromBytes], bounded as every decode is, ADR 051) and drawn into its
     * scaled rectangle, so memory follows the output and never the source's canvas. A frame blends over the canvas
     * or replaces its rectangle, and once shown it stays, clears its rectangle, or puts back what was under it —
     * the APNG and WebP rules, which the next frame's draw applies before it lands.
     */
    private class Compositor(
        private val source: AnimatedFrames,
        maxDim: Int,
    ) : FrameSource {
        private val scale = min(1f, maxDim.toFloat() / max(source.width, source.height))
        private val canvasBitmap =
            createBitmap(
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
            )
        private val canvas = Canvas(canvasBitmap)
        private val over = Paint(Paint.FILTER_BITMAP_FLAG)
        private val replace = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        private var next = 0
        private var startOfNext = 0
        private var undo: (() -> Unit)? = null

        override fun at(timeMs: Int): Bitmap? {
            while (next < source.frames.size && startOfNext <= timeMs) {
                val frame = source.frames[next++]
                startOfNext += frame.durationMs
                if (!draw(frame)) return null
            }
            return canvasBitmap
        }

        private fun draw(frame: AnimatedFrames.Frame): Boolean {
            undo?.invoke()
            undo = null
            val target =
                RectF(
                    frame.x * scale,
                    frame.y * scale,
                    (frame.x + frame.width) * scale,
                    (frame.y + frame.height) * scale,
                )
            val previous = if (frame.dispose == AnimatedFrames.Dispose.Previous) canvasBitmap.copy(Bitmap.Config.ARGB_8888, false) else null
            val edge = ceil(max(frame.width, frame.height) * scale).toInt().coerceAtLeast(1)
            val pixels = decodeBoundedFromBytes(frame.still, edge) ?: return false.also { previous?.recycle() }
            canvas.drawBitmap(pixels, null, target, if (frame.blend == AnimatedFrames.Blend.Over) over else replace)
            pixels.recycle()
            undo =
                when (frame.dispose) {
                    AnimatedFrames.Dispose.None -> {
                        null
                    }

                    AnimatedFrames.Dispose.Background -> {
                        {
                            canvas.withClip(target) { drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) }
                        }
                    }

                    AnimatedFrames.Dispose.Previous -> {
                        {
                            previous?.let {
                                canvas.drawBitmap(it, 0f, 0f, replace)
                                it.recycle()
                            }
                        }
                    }
                }
            return true
        }

        override fun close() {
            canvasBitmap.recycle()
        }
    }
}
