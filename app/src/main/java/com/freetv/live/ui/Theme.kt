package com.freetv.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freetv.live.R

// Night-sky palette with neon accents, shared with the Roku receiver (see tools/make_roku_art.py).
val Night = Color(0xFF0B0A1A)
val Indigo = Color(0xFF1A1240)
val Violet = Color(0xFF9B7BFF)
val Cyan = Color(0xFF3FE0D0)
val Pink = Color(0xFFFF5FA2)
val Accent = Violet

/** The signature violet → cyan → pink gradient. */
val NeonBrush = Brush.horizontalGradient(listOf(Violet, Cyan, Pink))

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/** Retro sign lettering for the wordmark and channel numbers. */
val Bungee = FontFamily(Font(R.font.bungee_regular))

fun numberStyle(size: TextUnit) = TextStyle(fontFamily = Bungee, fontSize = size, color = Violet)

private val Colors = darkColorScheme(
    primary = Violet,
    onPrimary = Night,
    secondary = Cyan,
    onSecondary = Night,
    tertiary = Pink,
    background = Night,
    surface = Color(0xFF15132B),
    surfaceVariant = Color(0xFF221F40),
    surfaceContainer = Color(0xFF15132B),
    surfaceContainerHigh = Color(0xFF1C1936),
    onBackground = Color(0xFFEDEBFF),
    onSurface = Color(0xFFEDEBFF),
    onSurfaceVariant = Color(0xFFA9A6CC),
    outline = Color(0xFF3A3666),
    secondaryContainer = Color(0xFF2E2766),
    onSecondaryContainer = Color(0xFFEDEBFF),
)

private val Base = Typography()
private val Type = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = Poppins),
    displayMedium = Base.displayMedium.copy(fontFamily = Poppins),
    displaySmall = Base.displaySmall.copy(fontFamily = Poppins),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Poppins),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Poppins),
    bodySmall = Base.bodySmall.copy(fontFamily = Poppins),
    labelLarge = Base.labelLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
)

@Composable
fun FreeTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}

/** Night sky gradient with soft violet / cyan / pink glows. */
fun Modifier.nightBackground(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Night, Indigo)))
    drawCircle(
        Brush.radialGradient(listOf(Violet.copy(alpha = 0.28f), Color.Transparent), center = Offset(0f, 0f), radius = size.maxDimension * 0.55f),
        radius = size.maxDimension * 0.55f, center = Offset(0f, 0f),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Cyan.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(size.width, size.height), radius = size.maxDimension * 0.5f,
        ),
        radius = size.maxDimension * 0.5f, center = Offset(size.width, size.height),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Pink.copy(alpha = 0.08f), Color.Transparent),
            center = Offset(size.width * 0.75f, 0f), radius = size.maxDimension * 0.35f,
        ),
        radius = size.maxDimension * 0.35f, center = Offset(size.width * 0.75f, 0f),
    )
}

/** Cable-box style clock: big time in Bungee over the date. */
@Composable
fun GuideClock(modifier: Modifier = Modifier, timeSize: TextUnit = 30.sp) {
    val now by rememberNow()
    val context = androidx.compose.ui.platform.LocalContext.current
    val time = remember(now / 60_000) { android.text.format.DateFormat.getTimeFormat(context).format(now) }
    val date = remember(now / 3_600_000) { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(now) }
    androidx.compose.foundation.layout.Column(modifier) {
        Text(time.uppercase(), style = TextStyle(fontFamily = Bungee, fontSize = timeSize, brush = NeonBrush))
        Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Thin progress line filled with the neon gradient. */
@Composable
fun NeonProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(NeonBrush))
    }
}

/** A 2dp neon line, used as a divider under bars. */
@Composable
fun BoxScope.NeonEdge(alignment: Alignment) {
    Box(Modifier.align(alignment).fillMaxWidth().height(2.dp).background(NeonBrush))
}

@Composable
fun NightSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().nightBackground(), content = content)
}
