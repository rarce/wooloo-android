package dev.herdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.herdroid.R
import dev.herdroid.herdr.AgentStatus

/*
 * "Vigía" (night watch): the screen stays quiet while the herd works. Only the two states that
 * ask something of the user carry color: amber for blocked, blue for done. Working is neutral.
 */

// Dark
private val PastureNight = Color(0xFF141D21) // ground
private val Corral = Color(0xFF1D292E) // surface
private val CorralHigh = Color(0xFF24323A)
private val Fence = Color(0xFF2C3A40) // lines
private val Wool = Color(0xFFE9EDE8) // text
private val Mist = Color(0xFF8FA09A) // secondary text
private val Lantern = Color(0xFFF59A3C) // warm accent
private val Sky = Color(0xFF67B0F0) // cool accent

// Light
private val Meadow = Color(0xFFE9EEEA)
private val Ink = Color(0xFF16212A)
private val Moss = Color(0xFF55655F)
private val Hedge = Color(0xFFCBD5D0)
private val LanternDay = Color(0xFFB85A00)
private val SkyDay = Color(0xFF1C69B4)

private val DarkColors = darkColorScheme(
    // Primary is neutral wool: brand color would compete with the status colors.
    primary = Wool,
    onPrimary = PastureNight,
    primaryContainer = Fence,
    onPrimaryContainer = Wool,
    secondary = Sky,
    onSecondary = PastureNight,
    // Material's selected state (segmented buttons, chips): neutral, so blue only ever means "done".
    secondaryContainer = Fence,
    onSecondaryContainer = Wool,
    tertiary = Lantern,
    onTertiary = PastureNight,
    tertiaryContainer = Color(0xFF4A3216),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = PastureNight,
    onBackground = Wool,
    surface = PastureNight,
    onSurface = Wool,
    surfaceVariant = CorralHigh,
    onSurfaceVariant = Mist,
    surfaceContainerLowest = Color(0xFF10181C),
    surfaceContainerLow = Color(0xFF18232A),
    surfaceContainer = Corral,
    surfaceContainerHigh = CorralHigh,
    surfaceContainerHighest = Fence,
    outline = Color(0xFF3A4A51),
    outlineVariant = Fence,
    error = Color(0xFFFF8A7A),
    onError = PastureNight,
    errorContainer = Color(0xFF4A1C17),
    onErrorContainer = Color(0xFFFFDAD4),
)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5DED9),
    onPrimaryContainer = Ink,
    secondary = SkyDay,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5DED9),
    onSecondaryContainer = Ink,
    tertiary = LanternDay,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE1C8),
    onTertiaryContainer = Color(0xFF4A2400),
    background = Meadow,
    onBackground = Ink,
    surface = Meadow,
    onSurface = Ink,
    surfaceVariant = Color(0xFFDDE4E0),
    onSurfaceVariant = Moss,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F6F4),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF3F6F4),
    surfaceContainerHighest = Color(0xFFE2E8E4),
    outline = Color(0xFFA9B6B0),
    outlineVariant = Hedge,
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Immutable
data class StatusColors(
    val blocked: Color,
    val done: Color,
    val working: Color,
    val idle: Color,
    val unknown: Color,
    /** Text and icons drawn on a solid [blocked] or [done] fill. */
    val onSolid: Color,
) {
    fun of(status: AgentStatus): Color = when (status) {
        AgentStatus.BLOCKED -> blocked
        AgentStatus.DONE -> done
        AgentStatus.WORKING -> working
        AgentStatus.IDLE -> idle
        AgentStatus.UNKNOWN -> unknown
    }

    /** Whether this state asks something of the user, and so gets a solid fill. */
    fun isSolid(status: AgentStatus) = status == AgentStatus.BLOCKED || status == AgentStatus.DONE
}

/*
 * herdr's own state colors (`status_color` in herdr's client): working → yellow, blocked → red,
 * done → teal, idle → green, unknown → overlay0, from its default Catppuccin themes.
 */
private val DarkStatus = StatusColors( // Catppuccin Mocha
    blocked = Color(0xFFF38BA8),
    done = Color(0xFF94E2D5),
    working = Color(0xFFF9E2AF),
    idle = Color(0xFFA6E3A1),
    unknown = Color(0xFF6C7086),
    onSolid = PastureNight,
)

private val LightStatus = StatusColors( // Catppuccin Latte
    blocked = Color(0xFFD20F39),
    done = Color(0xFF179299),
    working = Color(0xFFDF8E1D),
    idle = Color(0xFF40A02B),
    unknown = Color(0xFF9CA0B0),
    onSolid = Color.White,
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

/** herdr's `ui.status_indicators`: color dots (● ○ ·) or distinct symbols (× ◐ ✓ ○ ·). */
enum class StatusIndicators { DOTS, SYMBOLS }

val LocalStatusIndicators = staticCompositionLocalOf { StatusIndicators.DOTS }

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Atkinson Hyperlegible Next: built to stay legible at a glance, on small screens. */
val Sans = FontFamily(
    variable(R.font.atkinson_hyperlegible_next, 400),
    variable(R.font.atkinson_hyperlegible_next, 500),
    variable(R.font.atkinson_hyperlegible_next, 700),
)

/** Its monospaced sister, for hosts, paths and keys. The terminal canvas keeps the system mono. */
val Mono = FontFamily(
    variable(R.font.atkinson_hyperlegible_mono, 400),
    variable(R.font.atkinson_hyperlegible_mono, 500),
)

private val AppTypography = Typography().let { base ->
    fun TextStyle.sans(weight: FontWeight? = null) = copy(fontFamily = Sans, fontWeight = weight ?: fontWeight)
    base.copy(
        displayLarge = base.displayLarge.sans(FontWeight.Bold),
        displayMedium = base.displayMedium.sans(FontWeight.Bold),
        displaySmall = base.displaySmall.sans(FontWeight.Bold),
        headlineLarge = base.headlineLarge.sans(FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = base.headlineSmall.sans(FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = Sans, lineHeight = 23.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = Sans, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.sp),
    )
}

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp)

@Composable
fun HerdroidTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
