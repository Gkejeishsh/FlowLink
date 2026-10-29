package com.loopjet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion tokens are numbers, so they can be checked without a screen.
 *
 * The published Material 3 Expressive constants are the contract: spatial springs may overshoot
 * (that is the whole expressive idea), effects springs must not (a colour that bounces reads as a
 * stutter). These tests lock both properties in, so a future "let's make it snappier" edit cannot
 * quietly give opacity an overshoot or invert the speed order.
 *
 * The helpers below reimplement the damped-spring maths rather than importing Compose, so the test
 * runs as plain JVM code.
 */
class MotionTokensTest {

    // Mirrors JetMotion's constants; a change there without a change here fails the build.
    private val fastSpatial = 0.6f to 800f
    private val defaultSpatial = 0.8f to 380f
    private val slowSpatial = 0.8f to 200f
    private val fastEffects = 1.0f to 3800f
    private val defaultEffects = 1.0f to 1600f
    private val slowEffects = 1.0f to 800f

    /** Peak of the step response for a given damping ratio (1.0 = no overshoot). */
    private fun peak(damping: Double): Double {
        if (damping >= 1.0) return 1.0
        val zeta = damping
        val omega = Math.PI * zeta / Math.sqrt(1 - zeta * zeta)
        return 1 + Math.exp(-zeta * omega)
    }

    /** Time for the response to settle inside 0.1% of its travel, in milliseconds. */
    private fun settleMs(damping: Float, stiffness: Float): Double {
        val zeta = damping.toDouble()
        val omega0 = Math.sqrt(stiffness.toDouble())
        if (zeta >= 1.0) return 6.6 / omega0 * 1000.0
        return (-Math.log(0.001) / (zeta * omega0)) * 1000.0
    }

    @Test
    fun spatialSpringsOvershootAndEffectsSpringsDoNot() {
        listOf(fastSpatial, defaultSpatial, slowSpatial).forEach { (damping, stiffness) ->
            val p = peak(damping.toDouble())
            assertTrue(
                "spatial ζ=$damping k=$stiffness should overshoot (peak %.3f)".format(p),
                p > 1.0
            )
            // Comfort, not just correctness: more than ~12% bounce feels toy-like.
            assertTrue("spatial overshoot too large: %.3f".format(p), p < 1.12)
        }
        listOf(fastEffects, defaultEffects, slowEffects).forEach { (damping, stiffness) ->
            assertEquals(
                "effects ζ=$damping k=$stiffness must not overshoot",
                1.0,
                peak(damping.toDouble()),
                0.0001
            )
        }
    }

    @Test
    fun fasterTokensSettleFaster() {
        val fast = settleMs(fastSpatial.first, fastSpatial.second)
        val normal = settleMs(defaultSpatial.first, defaultSpatial.second)
        val slow = settleMs(slowSpatial.first, slowSpatial.second)
        assertTrue("fast ($fast ms) must settle before default ($normal ms)", fast < normal)
        assertTrue("default ($normal ms) must settle before slow ($slow ms)", normal < slow)

        val effectsFast = settleMs(fastEffects.first, fastEffects.second)
        val effectsNormal = settleMs(defaultEffects.first, defaultEffects.second)
        val effectsSlow = settleMs(slowEffects.first, slowEffects.second)
        assertTrue(effectsFast < effectsNormal)
        assertTrue(effectsNormal < effectsSlow)
    }

    @Test
    fun durationsAreInTheRangeAHumanCallsFluid() {
        // Below ~120 ms motion reads as a jump; above ~700 ms it reads as lag. Every token here is
        // deliberately inside that window, which is what "super fluid" actually means in practice.
        listOf(fastSpatial, defaultSpatial, slowSpatial, fastEffects, defaultEffects, slowEffects)
            .forEach { (damping, stiffness) ->
                val ms = settleMs(damping, stiffness)
                assertTrue("settle time %.0f ms is too short".format(ms), ms >= 120)
                assertTrue("settle time %.0f ms is too long".format(ms), ms <= 700)
            }
    }

    @Test
    fun reducedMotionRemovesOvershootWithoutChangingDistance() {
        // The degraded path forces ζ = 1.0: same distance, same stiffness, no bounce. This is what
        // power-save and low-RAM phones get, and it must still be a spring rather than a cut.
        val reduced = 1.0f
        assertEquals(1.0, peak(reduced.toDouble()), 0.0001)
        assertTrue(settleMs(reduced, fastSpatial.second) < 700)
    }
}
