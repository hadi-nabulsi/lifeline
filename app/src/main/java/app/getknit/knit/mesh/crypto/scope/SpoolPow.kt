package app.getknit.knit.mesh.crypto.scope

import java.security.MessageDigest

/**
 * The spool plane's stateless proof-of-work stamp (docs/SPOOL_PROTOCOL.md §8): Hashcash over
 * `scopeId ‖ utcDay`, NIP-13-style leading-zero-bit difficulty, no server challenge. A spool demands
 * a stamp only for a scope id it has never seen, so an honest client mines roughly once per scope per
 * spool; the ±1-day acceptance window bounds pre-mining. Pure JDK crypto, API-only until M2/M3.
 */
object SpoolPow {
    const val DAY_MS = 86_400_000L

    /** Default difficulty a spool advertises in HELLO (`0` disables); operator-tunable. */
    const val DEFAULT_BITS = 20

    /**
     * The most difficulty a client will attempt. `powBits` is a HELLO field, so it is the spool's claim
     * about itself: at 20 bits (§12's recommendation) a stamp costs ~1 M hashes, and each bit doubles
     * that — a spool asking for 64 guarantees the whole mining budget is burned and still fails. 24 bits
     * is 16× the recommendation and comfortably inside the budget; beyond it, refusing to mine is the
     * only sane answer, since the work is spent per scope on every heal round that re-subscribes.
     */
    const val MAX_BITS = 24

    private val LABEL = "knit/spool/v1/pow".toByteArray()

    /**
     * How long before a stamp's window closes on this phone's clock it is mined afresh: a spool whose clock runs
     * ahead of ours by less than this still takes the old one, and a refused stamp parks its scope for minutes.
     */
    const val REUSE_MARGIN_MS = 3_600_000L

    /** The UTC day bucket a stamp is minted for. */
    fun utcDay(nowMs: Long): Long = Math.floorDiv(nowMs, DAY_MS)

    /**
     * Whether a stamp minted for [stampDay] is still worth sending at [nowMs]. A spool takes `day ∈ {today − 1,
     * today, today + 1}` (S-8-1), so yesterday's stamp is good all of today: re-mining at every UTC midnight
     * spent a stamp per scope, on every phone at once, at a moment set by the clock rather than the traffic.
     * It goes stale [REUSE_MARGIN_MS] before the day after its own ends, and a stamp from ahead of the window (a
     * clock set back) is never reused.
     */
    fun reusable(
        stampDay: Long,
        nowMs: Long,
    ): Boolean = stampDay <= utcDay(nowMs) + 1 && nowMs < (stampDay + 2) * DAY_MS - REUSE_MARGIN_MS

    /** The stamp digest: SHA-256 over `label ‖ scopeId ‖ u64be(day) ‖ u64be(n)`. */
    fun digest(
        scopeId: ByteArray,
        day: Long,
        n: Long,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(LABEL + scopeId + u64be(day) + u64be(n))

    /** A stamp is valid iff its digest has at least [bits] leading zero bits ([bits] `<= 0` accepts all). */
    fun verify(
        scopeId: ByteArray,
        day: Long,
        n: Long,
        bits: Int,
    ): Boolean = bits <= 0 || leadingZeroBits(digest(scopeId, day, n)) >= bits

    /**
     * Mines the smallest counter from [startN] whose stamp meets [bits], trying at most [maxAttempts];
     * null when the budget runs out. Deterministic for fixed inputs (the vector tests pin one).
     *
     * A 20-bit stamp is about a million hashes, so the loop allocates nothing per attempt ([PowMiner]): one
     * `MessageDigest.digest` per attempt left ~200 MB of garbage per stamp, and a phone re-minting a stamp for
     * every scope at once (a fresh process, the UTC day rolling over) collected 200 MB every two seconds for
     * half a minute, its main thread stalled on the collector (device-observed 2026-10-12).
     */
    fun stamp(
        scopeId: ByteArray,
        day: Long,
        bits: Int,
        maxAttempts: Long,
        startN: Long = 0L,
    ): Long? {
        if (maxAttempts <= 0) return null
        if (bits <= 0) return startN
        val miner = PowMiner(LABEL + scopeId + u64be(day))
        var n = startN
        var attempts = 0L
        while (attempts < maxAttempts) {
            if (miner.leadingZeroBits(n) >= bits) return n
            n++
            attempts++
        }
        return null
    }

    /** Counts leading zero bits of a digest, byte-lexicographic. */
    fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += Byte.SIZE_BITS
            } else {
                bits += Integer.numberOfLeadingZeros(v) - (Integer.SIZE - Byte.SIZE_BITS)
                break
            }
        }
        return bits
    }

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 56/48/… would only obscure them
    private fun u64be(value: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { (value ushr ((Long.SIZE_BYTES - 1 - it) * 8)).toByte() }
}

/**
 * SHA-256 over `prefix ‖ u64be(n)` for one counter after another, with no allocation per counter: the padded
 * message lives in one buffer the counter is written into, and the state after the blocks ahead of the
 * counter's last byte is cached until a counter byte inside them changes (every 256 counters for §8's
 * 57-byte prefix, so an attempt compresses one block, not two). [SpoolPow.digest] stays the reference: the
 * tests hold this to it.
 */
@Suppress("MagicNumber") // FIPS 180-4's constants, rotations and word shifts; naming them would only obscure them
internal class PowMiner(
    prefix: ByteArray,
) {
    private val counterAt = prefix.size
    private val message: ByteArray
    private val lowBlock: Int
    private val lowShift: Int
    private val mid = IntArray(8)
    private val state = IntArray(8)
    private val w = IntArray(64)
    private var midKey = 0L
    private var midValid = false

    init {
        val length = counterAt + Long.SIZE_BYTES
        message = ByteArray((length + 1 + Long.SIZE_BYTES + BLOCK - 1) / BLOCK * BLOCK)
        prefix.copyInto(message)
        message[length] = 0x80.toByte()
        val bitLength = length.toLong() * Byte.SIZE_BITS
        for (i in 0 until Long.SIZE_BYTES) message[message.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        lowBlock = (length - 1) / BLOCK
        // The counter's bytes at or after the block its last byte sits in; the rest are in the cached blocks.
        val lowBytes = length - lowBlock * BLOCK
        lowShift = if (lowBytes >= Long.SIZE_BYTES) Long.SIZE_BITS else lowBytes * Byte.SIZE_BITS
    }

    /** The leading zero bits of `SHA-256(prefix ‖ u64be(n))`, as [SpoolPow.leadingZeroBits] counts them. */
    fun leadingZeroBits(n: Long): Int {
        for (i in 0 until Long.SIZE_BYTES) message[counterAt + i] = (n ushr (8 * (Long.SIZE_BYTES - 1 - i))).toByte()
        val key = if (lowShift == Long.SIZE_BITS) 0L else n ushr lowShift
        if (!midValid || key != midKey) {
            H0.copyInto(mid)
            for (b in 0 until lowBlock) compress(mid, b * BLOCK)
            midKey = key
            midValid = true
        }
        mid.copyInto(state)
        for (b in lowBlock until message.size / BLOCK) compress(state, b * BLOCK)
        var bits = 0
        for (word in state) {
            if (word != 0) return bits + Integer.numberOfLeadingZeros(word)
            bits += Int.SIZE_BITS
        }
        return bits
    }

    private fun compress(
        h: IntArray,
        at: Int,
    ) {
        for (t in 0 until 16) {
            val i = at + t * 4
            w[t] = (message[i].toInt() and 0xFF shl 24) or (message[i + 1].toInt() and 0xFF shl 16) or
                (message[i + 2].toInt() and 0xFF shl 8) or (message[i + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (t in 0 until 64) {
            val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }

    private companion object {
        const val BLOCK = 64

        // FIPS 180-4 §5.3.3 and §4.2.2, as the spec prints them: unsigned hex words.
        val H0 = words("6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19")

        val K =
            words(
                "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5",
                "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174",
                "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da",
                "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967",
                "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85",
                "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070",
                "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3",
                "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2",
            )

        fun words(vararg rows: String): IntArray = rows.flatMap { it.split(' ') }.map { it.toLong(HEX).toInt() }.toIntArray()

        const val HEX = 16
    }
}
