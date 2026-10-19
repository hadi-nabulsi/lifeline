package app.getknit.knit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableColorTest {
    @Test
    fun contrastRatioMatchesWcagEndpoints() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(SurfaceLight, SurfaceLight), 0.001f)
    }

    // The FTL accessibility pass measured the light coral at 4.11:1 on an incoming bubble: the numbers this
    // helper exists to fix, pinned so a palette change that cures (or worsens) them is noticed.
    @Test
    fun lightPrimaryFallsShortOnBothBubbleFills() {
        assertEquals(4.11f, contrastRatio(CoralPrimaryLight, SurfaceVariantLight), 0.01f)
        assertEquals(4.14f, contrastRatio(CoralPrimaryLight, CoralPrimaryContainerLight), 0.01f)
    }

    @Test
    fun anAccentThatFallsShortIsDarkenedJustPastTheFloor() {
        for (bubble in listOf(SurfaceVariantLight, CoralPrimaryContainerLight)) {
            val shown = CoralPrimaryLight.readableOn(bubble)
            val ratio = contrastRatio(shown, bubble)
            assertTrue("$ratio on $bubble", ratio >= MIN_TEXT_CONTRAST)
            // Darkened, not swapped for black: barely past the floor, and still red-dominant.
            assertTrue("$ratio on $bubble", ratio < MIN_TEXT_CONTRAST + 0.05f)
            assertTrue(shown.red > shown.green && shown.red > shown.blue)
        }
    }

    @Test
    fun anAccentThatAlreadyPassesIsUntouched() {
        assertEquals(CoralPrimaryLight, CoralPrimaryLight.readableOn(SurfaceLight))
        assertEquals(CoralPrimaryDark, CoralPrimaryDark.readableOn(SurfaceVariantDark))
    }

    @Test
    fun onADarkFillTheAccentIsLightened() {
        val shown = CoralPrimaryContainerDark.readableOn(SurfaceDark)
        assertTrue(contrastRatio(shown, SurfaceDark) >= MIN_TEXT_CONTRAST)
        assertTrue(shown.red > CoralPrimaryContainerDark.red)
    }
}
