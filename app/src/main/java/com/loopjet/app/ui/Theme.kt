package com.loopjet.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopjet.app.R
import androidx.compose.ui.unit.sp

// --- brand ---------------------------------------------------------------------------------------

/**
 * The palette lives as plain ints first and `Color` second, so a JVM unit test can assert WCAG
 * contrast on the exact values the app draws without Android or Compose on the test classpath.
 * "Dark themes get light text" stops being a claim and becomes an assertion.
 */
object Palette {
    val Primary: Int = 0xFF2F6BFF.toInt()
    val OnPrimary: Int = 0xFFFFFFFF.toInt()
    val PrimaryContainer: Int = 0xFFE3EBFF.toInt()
    val OnPrimaryContainer: Int = 0xFF0A1B4A.toInt()

    val Secondary: Int = 0xFF8B5CF6.toInt()
    val Tertiary: Int = 0xFF0F8FA6.toInt()

    val LightBackground: Int = 0xFFF6F7FB.toInt()
    val LightSurface: Int = 0xFFFFFFFF.toInt()
    val LightSurfaceLow: Int = 0xFFF7F8FC.toInt()
    val LightSurfaceContainer: Int = 0xFFF1F3FA.toInt()
    val LightSurfaceHigh: Int = 0xFFE9ECF7.toInt()
    val LightSurfaceHighest: Int = 0xFFE1E5F4.toInt()
    val LightOnSurface: Int = 0xFF0D1220.toInt()
    val LightOnSurfaceVariant: Int = 0xFF4A5265.toInt()
    val LightOutline: Int = 0xFFC9CFE0.toInt()
    val LightOutlineVariant: Int = 0xFFE4E8F2.toInt()

    val DarkBackground: Int = 0xFF000000.toInt()
    val DarkSurface: Int = 0xFF0B0F17.toInt()
    val DarkSurfaceLow: Int = 0xFF0E131C.toInt()
    val DarkSurfaceContainer: Int = 0xFF131926.toInt()
    val DarkSurfaceHigh: Int = 0xFF1A2130.toInt()
    val DarkSurfaceHighest: Int = 0xFF222A3B.toInt()
    val DarkOnSurface: Int = 0xFFF2F5FF.toInt()
    val DarkOnSurfaceVariant: Int = 0xFFAEB7CC.toInt()
    val DarkOutline: Int = 0xFF3A4257.toInt()
    val DarkOutlineVariant: Int = 0xFF232A3A.toInt()

    val Success: Int = 0xFF17B26A.toInt()
    val SuccessDark: Int = 0xFF4ADE80.toInt()
    val Warning: Int = 0xFFF59E0B.toInt()
    val WarningDark: Int = 0xFFFBBF24.toInt()
}

val JetBlue = Color(Palette.Primary)
val JetViolet = Color(Palette.Secondary)
val JetCyan = Color(0xFF22D3EE)
val JetInk = Color(0xFF0B1020)
val JetGraphite = Color(0xFF141A2B)

/**
 * Depth comes from tone, not hairlines: cards sit on `surfaceContainerLow`, sheets on `High`, and a
 * pressed row on `Highest`. Two stops only in the brand gradient -- the third made the middle mud.
 */
val JetSuccess = Color(Palette.Success)
val JetWarning = Color(Palette.Warning)

val BrandGradient = Brush.linearGradient(listOf(JetBlue, JetViolet, JetCyan))

private val LightScheme: ColorScheme = lightColorScheme(
    primary = JetBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF),
    onPrimaryContainer = Color(0xFF0A1B4A),
    secondary = JetViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E9FF),
    onSecondaryContainer = Color(0xFF2A1259),
    tertiary = Color(0xFF0F8FA6),
    onTertiary = Color.White,
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF0D1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1220),
    surfaceVariant = Color(Palette.LightSurfaceContainer),
    onSurfaceVariant = Color(Palette.LightOnSurfaceVariant),
    surfaceContainerLowest = Color(Palette.LightSurface),
    surfaceContainerLow = Color(Palette.LightSurfaceLow),
    surfaceContainer = Color(Palette.LightSurfaceContainer),
    surfaceContainerHigh = Color(Palette.LightSurfaceHigh),
    surfaceContainerHighest = Color(Palette.LightSurfaceHighest),
    outline = Color(Palette.LightOutline),
    outlineVariant = Color(Palette.LightOutlineVariant),
    error = Color(0xFFB3261E),
    onError = Color.White,
    inverseSurface = Color(0xFF141A2B),
    inverseOnSurface = Color(0xFFF2F5FF)
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF7AA2FF),
    onPrimary = Color(0xFF00164D),
    primaryContainer = Color(0xFF16255C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFC0A6FF),
    onSecondary = Color(0xFF22105A),
    secondaryContainer = Color(0xFF2A1B54),
    onSecondaryContainer = Color(0xFFE9DEFF),
    tertiary = Color(0xFF57D8E8),
    onTertiary = Color(0xFF00282E),
    // True black for OLED, with surfaces lifted just enough to keep elevation readable in AA contrast.
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F5FF),
    surface = Color(0xFF0B0F17),
    onSurface = Color(0xFFF2F5FF),
    surfaceVariant = Color(Palette.DarkSurfaceContainer),
    onSurfaceVariant = Color(Palette.DarkOnSurfaceVariant),
    surfaceContainerLowest = Color(Palette.DarkBackground),
    surfaceContainerLow = Color(Palette.DarkSurfaceLow),
    surfaceContainer = Color(Palette.DarkSurfaceContainer),
    surfaceContainerHigh = Color(Palette.DarkSurfaceHigh),
    surfaceContainerHighest = Color(Palette.DarkSurfaceHighest),
    outline = Color(Palette.DarkOutline),
    outlineVariant = Color(Palette.DarkOutlineVariant),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    inverseSurface = Color(0xFFF2F5FF),
    inverseOnSurface = Color(0xFF141A2B)
)

/**
 * One bundled typeface, one spacing system.
 *
 * Public Sans (SIL OFL 1.1, shipped as a variable font, 103 KB) replaces the platform default. The
 * default looked different on every OEM -- Samsung and Xiaomi both substitute their own face -- which
 * is why the same screen could look amateurish on one phone and fine on another. Weight is applied
 * through the font's own `wght` axis, so SemiBold really is SemiBold instead of a synthesised thick
 * stroke. Every style still carries a line height so descenders are never clipped.
 */
private val JetSans = FontFamily(
    Font(R.font.public_sans, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.public_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.public_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.public_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.public_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

private val JetTypography = Typography(
    displayLarge = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.8).sp),
    displaySmall = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 39.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 29.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = (-0.1).sp),
    titleLarge = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = JetSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp)
)

/**
 * The number that carries the whole product: live speed. Large, light weight, tight tracking, and
 * tabular figures so digits do not re-shuffle their width several times a second -- proportional
 * numerals make a live readout visibly shiver.
 */
val Typography.hero: TextStyle
    get() = TextStyle(
        fontFamily = JetSans,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.2).sp,
        fontFeatureSettings = "tnum"
    )

/** Any live number (speed, bytes, percent, ETA digits) without the hero size. */
val Typography.numeral: TextStyle
    get() = TextStyle(
        fontFamily = JetSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    )

/** Small caps label used for section headers; tracked out, never shouty. */
val Typography.section: TextStyle
    get() = TextStyle(
        fontFamily = JetSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.1.sp
    )

private val JetShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

/**
 * Motion settings resolved once per composition: battery saver, low RAM and animator scale matter.
 *
 * `enabled = false` means the platform's animator scale is 0 or the user chose "System" with
 * animations off -- every spring becomes a snap. `rich = false` keeps animation but removes
 * overshoot, which is what a power-saver or a low-RAM phone should get.
 */
data class MotionSettings(val enabled: Boolean, val rich: Boolean) {
    /** Picks the spring for a job, degrading to a snap or to a non-overshooting spring. */
    fun spec(kind: JetMotion.Kind): androidx.compose.animation.core.FiniteAnimationSpec<Float> {
        if (!enabled) return androidx.compose.animation.core.snap()
        // Reduced motion keeps the distance and the stiffness, and removes only the bounce. SpringSpec
        // is immutable with no copy(), so the spring is rebuilt with a critical damping ratio.
        return if (rich) {
            JetMotion.spring(kind)
        } else {
            androidx.compose.animation.core.spring(
                dampingRatio = 1f,
                stiffness = JetMotion.stiffness(kind)
            )
        }
    }
}

val LocalMotion = compositionLocalOf { MotionSettings(enabled = true, rich = true) }

/**
 * The six springs everything is built from, using the published Material 3 Expressive constants
 * (spatial ζ 0.6/0.8, stiffness 800/380/200; effects ζ 1.0, 3800/1600/800).
 *
 * They are declared here rather than imported from material3's expressive theme because those
 * APIs are still partly internal in material3 1.4.0; our own constants compile today and cannot be
 * broken by a library bump. Spatial springs move and may overshoot; effects springs are for colour
 * and opacity and never overshoot, because a bouncing fade reads as a stutter.
 */
object JetMotion {
    enum class Kind { FastSpatial, DefaultSpatial, SlowSpatial, FastEffects, DefaultEffects, SlowEffects }

    const val FAST_SPATIAL_DAMPING = 0.6f
    const val FAST_SPATIAL_STIFFNESS = 800f
    const val DEFAULT_SPATIAL_DAMPING = 0.8f
    const val DEFAULT_SPATIAL_STIFFNESS = 380f
    const val SLOW_SPATIAL_DAMPING = 0.8f
    const val SLOW_SPATIAL_STIFFNESS = 200f
    const val FAST_EFFECTS_STIFFNESS = 3800f
    const val DEFAULT_EFFECTS_STIFFNESS = 1600f
    const val SLOW_EFFECTS_STIFFNESS = 800f

    fun damping(kind: Kind): Float = when (kind) {
        Kind.FastSpatial -> FAST_SPATIAL_DAMPING
        Kind.DefaultSpatial -> DEFAULT_SPATIAL_DAMPING
        Kind.SlowSpatial -> SLOW_SPATIAL_DAMPING
        // Colour and opacity never overshoot: critically damped, always.
        else -> 1f
    }

    fun stiffness(kind: Kind): Float = when (kind) {
        Kind.FastSpatial -> FAST_SPATIAL_STIFFNESS
        Kind.DefaultSpatial -> DEFAULT_SPATIAL_STIFFNESS
        Kind.SlowSpatial -> SLOW_SPATIAL_STIFFNESS
        Kind.FastEffects -> FAST_EFFECTS_STIFFNESS
        Kind.DefaultEffects -> DEFAULT_EFFECTS_STIFFNESS
        Kind.SlowEffects -> SLOW_EFFECTS_STIFFNESS
    }

    fun spring(kind: Kind): androidx.compose.animation.core.SpringSpec<Float> =
        androidx.compose.animation.core.spring(dampingRatio = damping(kind), stiffness = stiffness(kind))
}

/**
 * Haptics, in one place so the whole app speaks with the same voice.
 *
 * Compose maps these to the platform's constants and already refuses to buzz while a screen reader
 * is exploring by touch, which a raw `View.performHapticFeedback` call does not.
 */
class Haptics(private val feedback: HapticFeedback) {
    /** A file or app joining or leaving the tray. */
    fun toggleOn() = feedback.performHapticFeedback(HapticFeedbackType.ToggleOn)
    fun toggleOff() = feedback.performHapticFeedback(HapticFeedbackType.ToggleOff)
    /** Something irreversible and good: Send, a completed transfer, a granted permission. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    /** Something failed. Used sparingly -- a buzz for every error becomes noise. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    /** A plain button that changes state but sends nothing. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

fun readMotionSettings(context: Context): MotionSettings {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val powerSave = runCatching {
        context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
    }.getOrDefault(false)
    val lowRam = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }.getOrDefault(false)
    return MotionSettings(enabled = scale > 0.01f, rich = scale > 0.01f && !powerSave && !lowRam)
}

@Composable
fun FlowLinkTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val motion = remember(dark) { readMotionSettings(context) }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = JetTypography,
        shapes = JetShapes
    ) {
        // The root Surface is what publishes LocalContentColor. Without it every Text that does not
        // pass an explicit colour draws in the framework default, which is black — so in the dark
        // theme the copy disappeared into the near-black background. Painting the window here also
        // means the area revealed while scrolling is never the wrong colour.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            CompositionLocalProvider(LocalMotion provides motion) { content() }
        }
    }
}

// --- brand mark ----------------------------------------------------------------------------------

/**
 * The FlowLink mark drawn from vectors: a bolt that curves into a closed loop between two nodes.
 * Identical geometry to `design/loopjet-mark.svg`, so the launcher icon, splash and UI never drift.
 */
@Composable
fun FlowLinkMark(size: Int = 72, modifier: Modifier = Modifier, roundedBackground: Boolean = true) {
    Canvas(modifier = modifier.size(size.dp)) {
        val s = this.size.minDimension
        if (roundedBackground) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF0B1020), Color(0xFF131A33))
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.27f)
            )
        }
        val stroke = Stroke(width = s * 0.072f, cap = StrokeCap.Round)
        val gradient = Brush.linearGradient(
            colors = listOf(JetBlue, JetViolet, JetCyan),
            start = Offset(0f, 0f),
            end = Offset(s, s)
        )
        // Upper-left arc, opening at the bottom right.
        val arc1 = Path().apply {
            moveTo(s * 0.375f, s * 0.72f)
            cubicTo(s * 0.245f, s * 0.61f, s * 0.265f, s * 0.41f, s * 0.415f, s * 0.345f)
            cubicTo(s * 0.50f, s * 0.31f, s * 0.585f, s * 0.33f, s * 0.65f, s * 0.395f)
            lineTo(s * 0.715f, s * 0.455f)
        }
        drawPath(arc1, gradient, style = stroke)
        // Lower-right arc closing the loop.
        val arc2 = Path().apply {
            moveTo(s * 0.735f, s * 0.36f)
            cubicTo(s * 0.86f, s * 0.48f, s * 0.845f, s * 0.68f, s * 0.69f, s * 0.745f)
            cubicTo(s * 0.60f, s * 0.78f, s * 0.51f, s * 0.755f, s * 0.445f, s * 0.69f)
            lineTo(s * 0.385f, s * 0.625f)
        }
        drawPath(arc2, gradient, style = stroke)
        // The bolt.
        val bolt = Path().apply {
            moveTo(s * 0.55f, s * 0.30f)
            lineTo(s * 0.40f, s * 0.545f)
            lineTo(s * 0.49f, s * 0.545f)
            lineTo(s * 0.435f, s * 0.725f)
            lineTo(s * 0.615f, s * 0.475f)
            lineTo(s * 0.515f, s * 0.475f)
            close()
        }
        drawPath(bolt, Brush.linearGradient(listOf(JetViolet, JetCyan)))
        // Nodes.
        drawCircle(JetCyan, radius = s * 0.052f, center = Offset(s * 0.36f, s * 0.705f))
        drawCircle(JetBlue, radius = s * 0.052f, center = Offset(s * 0.745f, s * 0.375f))
    }
}

// --- motion primitives ---------------------------------------------------------------------------

@Composable
fun rememberPressScale(): Pair<Modifier, MutableInteractionSource> {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = if (motion.enabled) spring(dampingRatio = 0.55f, stiffness = 700f) else androidx.compose.animation.core.snap(),
        label = "press"
    )
    return Modifier.scale(scale) to interaction
}

/** Expanding radar rings used while searching for devices. */
@Composable
fun RadarPulse(active: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val motion = LocalMotion.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "radar")
    val phase = if (active && motion.enabled) {
        val animated: Float by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "phase"
        )
        animated
    } else {
        0f
    }
    Canvas(modifier) {
        val maxRadius = size.minDimension / 2f
        for (i in 0..2) {
            val p = ((phase + i / 3f) % 1f)
            val radius = maxRadius * (0.28f + 0.72f * p)
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.30f),
                radius = radius,
                style = Stroke(width = size.minDimension * 0.012f)
            )
        }
        drawCircle(color.copy(alpha = 0.08f), radius = maxRadius * 0.3f)
    }
}

/** Light dashes travelling along the link at a pace tied to the real throughput. */
@Composable
fun LinkDashes(progress: Float, active: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val motion = LocalMotion.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dashes")
    val offset = if (active && motion.enabled) {
        val animated: Float by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)
            ),
            label = "dash"
        )
        animated
    } else {
        0f
    }
    Canvas(modifier) {
        val y = size.height / 2f
        val dashWidth = size.width * 0.05f
        var x = -dashWidth + (size.width + dashWidth) * offset
        val filled = size.width * progress.coerceIn(0f, 1f)
        while (x < size.width) {
            if (x < filled) {
                drawLine(
                    color = color.copy(alpha = 0.85f),
                    start = Offset(x, y), end = Offset((x + dashWidth * 0.7f).coerceAtMost(filled), y),
                    strokeWidth = size.height * 0.42f, cap = StrokeCap.Round
                )
            }
            x += dashWidth * 2
        }
    }
}

/** Live speed graph with a soft gradient fill; values are bytes per second. */
@Composable
fun SpeedGraph(values: List<Long>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        if (values.size < 2) {
            drawLine(
                color.copy(alpha = 0.18f),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f
            )
            return@Canvas
        }
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value / max) * (size.height * 0.88f) - size.height * 0.06f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)))
        )
        drawPath(path, color, style = Stroke(width = size.minDimension * 0.02f, cap = StrokeCap.Round))
    }
}

/** Progress ring with a spring-animated sweep and room for a custom centre. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 18f,
    content: @Composable () -> Unit = {}
) {
    val motion = LocalMotion.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (motion.enabled) spring(dampingRatio = 0.8f, stiffness = 90f) else androidx.compose.animation.core.snap(),
        label = "ring"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val stroke = size.minDimension * (strokeWidth / 220f)
            val inset = stroke / 2
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = BrandGradient,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        content()
    }
}

// --- building blocks -----------------------------------------------------------------------------

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // Surface, not a plain Column: a hand-painted background leaves LocalContentColor on black, so
    // card text was dark-on-dark. contentColor pins it to the surface's own foreground colour.
    // Depth from tone rather than a 1 dp hairline: the card is one step above the background, so a
    // screen full of cards reads as layers instead of a grid of boxes. Padding moves onto the 4 dp
    // grid (20) and the corner onto the shared large radius.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/**
 * The one filled action button used by every screen.
 *
 * It sizes itself with `heightIn(min = …)` and pads around the label instead of pinning a fixed
 * height: at a large system font scale (or with a long translated label) a fixed-height box clips the
 * text, which is what made these buttons look like the type was broken and off-centre.
 */
@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val (pressModifier, interaction) = rememberPressScale()
    val container = if (enabled) {
        BrandGradient
    } else {
        Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        )
    }
    Box(
        modifier
            .then(pressModifier)
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple()
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "empty")
    val float = if (motion.enabled) {
        val animated: Float by transition.animateFloat(
            initialValue = -6f,
            targetValue = 6f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "float"
        )
        animated
    } else {
        0f
    }
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FlowLinkMark(size = 64, modifier = Modifier.padding(bottom = 14.dp).scale(1f + float / 400f))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Both halves get a weight and the value is end-aligned: a long peer name or path now wraps
        // inside its half instead of shoving the label off the card.
        // fill = false let the label shrink to a few characters, so "Remaining" broke into
        // "Remainin" + "g". A fixed 40/60 split with single-line labels keeps the rows aligned.
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}
