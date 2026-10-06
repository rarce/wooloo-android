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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.herdroid.herdr.AgentStatus

private val Night = Color(0xFF0E1116)
private val NightSurface = Color(0xFF161B22)
private val NightSurfaceHigh = Color(0xFF1C232C)
private val Herd = Color(0xFF7CE38B)

private val DarkColors = darkColorScheme(
    primary = Herd,
    onPrimary = Color(0xFF07210E),
    primaryContainer = Color(0xFF1B3B24),
    onPrimaryContainer = Color(0xFFB7F5C0),
    secondary = Color(0xFF79C0FF),
    onSecondary = Color(0xFF041A30),
    secondaryContainer = Color(0xFF15304D),
    onSecondaryContainer = Color(0xFFCDE6FF),
    tertiary = Color(0xFFF2CC60),
    tertiaryContainer = Color(0xFF3D3214),
    onTertiaryContainer = Color(0xFFFFE9A8),
    background = Night,
    onBackground = Color(0xFFE6EDF3),
    surface = Night,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Color(0xFF9DA7B3),
    surfaceContainerLowest = Color(0xFF0A0D11),
    surfaceContainerLow = Color(0xFF12171D),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = Color(0xFF242C36),
    outline = Color(0xFF3D4651),
    outlineVariant = Color(0xFF262E38),
    error = Color(0xFFFF7B72),
    errorContainer = Color(0xFF4A1612),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A7F37),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F2D0),
    onPrimaryContainer = Color(0xFF002109),
    secondary = Color(0xFF0969DA),
    secondaryContainer = Color(0xFFD6E8FF),
    tertiary = Color(0xFF9A6700),
    tertiaryContainer = Color(0xFFFFEFC2),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF2F5),
    surfaceContainerLow = Color(0xFFFFFFFF),
)

@Immutable
data class StatusColors(
    val blocked: Color,
    val done: Color,
    val working: Color,
    val idle: Color,
    val unknown: Color,
) {
    fun of(status: AgentStatus): Color = when (status) {
        AgentStatus.BLOCKED -> blocked
        AgentStatus.DONE -> done
        AgentStatus.WORKING -> working
        AgentStatus.IDLE -> idle
        AgentStatus.UNKNOWN -> unknown
    }
}

private val DarkStatus = StatusColors(
    blocked = Color(0xFFF2CC60),
    done = Color(0xFF7CE38B),
    working = Color(0xFF79C0FF),
    idle = Color(0xFF6E7681),
    unknown = Color(0xFF484F58),
)

private val LightStatus = StatusColors(
    blocked = Color(0xFFBF8700),
    done = Color(0xFF1A7F37),
    working = Color(0xFF0969DA),
    idle = Color(0xFF6E7781),
    unknown = Color(0xFF8C959F),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

val Mono = FontFamily.Monospace

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.3.sp),
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
