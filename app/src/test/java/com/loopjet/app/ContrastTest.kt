package com.loopjet.app

import com.loopjet.app.ui.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "Dark themes get light text" used to be a claim about a screenshot. Now it is an assertion.
 *
 * The palette is plain ints precisely so this test can run on the JVM with no Android, no Compose
 * and no emulator. Every pair below is one the app actually draws; a palette change that quietly
 * drops a pair under 4.5:1 fails CI instead of looking slightly wrong on someone's phone.
 */
class ContrastTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double {
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertReadable(name: String, fg: Int, bg: Int, minimum: Double = 4.5) {
        val actual = ratio(fg, bg)
        assertTrue(
            "%s: contrast %.2f:1 is below %.1f:1".format(name, actual, minimum),
            actual >= minimum
        )
    }

    @Test
    fun lightThemeTextIsReadableOnEverySurfaceTier() {
        val tiers = listOf(
            Palette.LightBackground,
            Palette.LightSurface,
            Palette.LightSurfaceLow,
            Palette.LightSurfaceContainer,
            Palette.LightSurfaceHigh,
            Palette.LightSurfaceHighest
        )
        tiers.forEach { tier ->
            assertReadable("light onSurface on %08X".format(tier), Palette.LightOnSurface, tier)
            assertReadable(
                "light onSurfaceVariant on %08X".format(tier),
                Palette.LightOnSurfaceVariant,
                tier
            )
        }
    }

    @Test
    fun darkThemeTextIsReadableOnEverySurfaceTier() {
        // The dark theme keeps true black for OLED; text must survive every raised tier too.
        val tiers = listOf(
            Palette.DarkBackground,
            Palette.DarkSurface,
            Palette.DarkSurfaceLow,
            Palette.DarkSurfaceContainer,
            Palette.DarkSurfaceHigh,
            Palette.DarkSurfaceHighest
        )
        tiers.forEach { tier ->
            assertReadable("dark onSurface on %08X".format(tier), Palette.DarkOnSurface, tier)
            assertReadable(
                "dark onSurfaceVariant on %08X".format(tier),
                Palette.DarkOnSurfaceVariant,
                tier
            )
        }
    }

    @Test
    fun buttonLabelsAreReadableOnTheirOwnContainers() {
        assertReadable(
            "onPrimaryContainer on primaryContainer",
            Palette.OnPrimaryContainer,
            Palette.PrimaryContainer
        )
    }

    @Test
    fun semanticColoursAreVisibleAgainstBothBackgrounds() {
        // Success/warning are used for small marks (a tick, a warning glyph), so 3:1 is the floor for
        // non-text UI — but they are checked on every tier, not just the flat surface, because a mark
        // can sit on a raised card as easily as on the page.
        val lightTiers = listOf(
            Palette.LightBackground, Palette.LightSurface, Palette.LightSurfaceLow,
            Palette.LightSurfaceContainer, Palette.LightSurfaceHigh, Palette.LightSurfaceHighest
        )
        val darkTiers = listOf(
            Palette.DarkBackground, Palette.DarkSurface, Palette.DarkSurfaceLow,
            Palette.DarkSurfaceContainer, Palette.DarkSurfaceHigh, Palette.DarkSurfaceHighest
        )
        lightTiers.forEach {
            assertReadable("success mark on %08X".format(it), Palette.Success, it, 3.0)
            assertReadable("warning mark on %08X".format(it), Palette.Warning, it, 3.0)
        }
        darkTiers.forEach {
            assertReadable("success mark on %08X".format(it), Palette.SuccessDark, it, 3.0)
            assertReadable("warning mark on %08X".format(it), Palette.WarningDark, it, 3.0)
        }
        // The brand blue carries white button labels, so it is held to the text bar, not the mark bar.
        assertReadable("white label on primary", Palette.OnPrimary, Palette.Primary)
    }

    @Test
    fun theTwoThemesAreGenuinelyOpposite() {
        // A trivial but real guard: light theme text is dark, dark theme text is light. This catches
        // a copy-paste between the two schemes, which is the most common way this breaks.
        assertTrue(luminance(Palette.LightOnSurface) < 0.1)
        assertTrue(luminance(Palette.DarkOnSurface) > 0.8)
        assertTrue(luminance(Palette.DarkBackground) < 0.01)
    }
}
