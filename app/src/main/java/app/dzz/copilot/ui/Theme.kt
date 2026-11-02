package app.dzz.copilot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Aurora" palette: deep night background, glassy surfaces, violet to cyan accent.
val Ink = Color(0xFF07080F)
val InkLift = Color(0xFF0D0F1A)
val Glass = Color(0xCC131624)
val GlassSolid = Color(0xFF131624)
val GlassHigh = Color(0xE61A1E30)
val GlassEdge = Color(0x24FFFFFF)
val Paper = Color(0xFFF3F4FA)
val MutedField = Color(0xFFB3B9D1)
val Neon = Color(0xFF22D3EE)
val NeonDeep = Color(0xFF3B2A86)
val GlassLine = Color(0xFF262B42)
val NeonGlow = Color(0xFF67E8F9)
val Copper = Color(0xFF8B7BFF)
val CopperSoft = Color(0x338B7BFF)
val FieldGreen = Color(0xFF34D399)
val Danger = Color(0xFFFF7A90)
val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)

object Space {
    val xs = 6.dp
    val sm = 10.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp
    val card = 22.dp
    val radius = 24.dp
    val radiusLg = 28.dp
    val radiusXl = 32.dp
    val touch = 52.dp
}

private val Colors = darkColorScheme(
    background = Ink,
    surface = GlassSolid,
    surfaceVariant = Color(0xFF1A1E30),
    primary = Copper,
    onPrimary = Color(0xFFFFFFFF),
    secondary = FieldGreen,
    onSecondary = Color(0xFF04140D),
    onBackground = Paper,
    onSurface = Paper,
    onSurfaceVariant = MutedField,
    outline = GlassLine,
    error = Danger,
)

val AtmosphereBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF12102A),
        Ink,
        Color(0xFF080A14),
        Color(0xFF07131A),
    ),
)

val AuraGlowBrush = Brush.radialGradient(
    colors = listOf(Violet.copy(alpha = 0.20f), Color.Transparent),
    center = Offset(0.15f, 0f),
    radius = 900f,
)

val GlassBrush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = 0.10f),
        Color.White.copy(alpha = 0.03f),
        Color.Black.copy(alpha = 0.22f),
    ),
)

val CopperBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF8B5CF6),
        Color(0xFF6D7BFF),
        Color(0xFF3AA8F0),
        Color(0xFF22D3EE),
    ),
    start = Offset(0f, 0f),
    end = Offset(900f, 420f),
)

val HeroSheen = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.18f),
        Color.Transparent,
        Color.Black.copy(alpha = 0.18f),
    ),
)

@Composable
fun DzzTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = Colors,
        typography = base.copy(
            displaySmall = base.displaySmall.copy(
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                lineHeight = 40.sp,
            ),
            titleLarge = base.titleLarge.copy(
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                lineHeight = 32.sp,
            ),
            titleMedium = base.titleMedium.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp,
            ),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = base.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                fontSize = 13.sp,
            ),
            labelMedium = base.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                fontSize = 12.sp,
            ),
        ),
    ) {
        // Every text without an explicit color is light on the dark background.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Paper,
            content = content,
        )
    }
}

@Composable
fun mutedStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
