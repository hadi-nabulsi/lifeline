package app.getknit.knit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG 2 AA for text under 18sp (14sp bold): the floor the accessibility suite's contrast check holds. */
const val MIN_TEXT_CONTRAST = 4.5f

/** WCAG 2 contrast ratio between two opaque colours, 1 (identical) to 21 (black on white). */
fun contrastRatio(
    a: Color,
    b: Color,
): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + FLARE) / (minOf(la, lb) + FLARE)
}

/**
 * This colour, moved only as far toward black or white (whichever [background] contrasts with more) as it
 * takes to reach [minRatio] on [background] — unchanged when it already does. For an accent drawn as text
 * on a tinted fill: Knit's light coral `primary` makes 5.1:1 on the plain surface but 4.1:1 on either chat
 * bubble, and under Material You neither colour is ours to pick, so the fix has to be computed, not chosen.
 */
fun Color.readableOn(
    background: Color,
    minRatio: Float = MIN_TEXT_CONTRAST,
): Color {
    if (contrastRatio(this, background) >= minRatio) return this
    val toward = if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White
    // The ratio rises monotonically along the lerp, so bisect for the smallest step that clears the floor:
    // twelve halvings put it within 1/4096 of the edge, far finer than an 8-bit channel.
    var lo = 0f
    var hi = 1f
    repeat(BISECT_STEPS) {
        val mid = (lo + hi) / 2
        if (contrastRatio(lerp(this, toward, mid), background) >= minRatio) hi = mid else lo = mid
    }
    return lerp(this, toward, hi)
}

private const val BISECT_STEPS = 12

/** WCAG's allowance for ambient light on the screen, added to both luminances. */
private const val FLARE = 0.05f
