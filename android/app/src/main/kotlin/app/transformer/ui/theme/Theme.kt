package app.transformer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// --radius-sm / --radius-md / --radius-lg from the design system.
val TransformerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = ColorAccent,
    onPrimary = ColorBg,
    primaryContainer = Accent100,
    onPrimaryContainer = Accent800,
    secondary = ColorAccent2,
    onSecondary = ColorBg,
    secondaryContainer = Accent2_100,
    onSecondaryContainer = Accent2_800,
    background = ColorBg,
    onBackground = ColorText,
    surface = ColorSurface,
    onSurface = ColorText,
    surfaceVariant = Neutral200,
    onSurfaceVariant = Neutral700,
    outline = ColorDivider,
    error = Accent800,
    onError = ColorBg,
)

private val DarkColors = darkColorScheme(
    primary = Accent400,
    onPrimary = DarkBg,
    primaryContainer = Accent800,
    onPrimaryContainer = Accent100,
    secondary = Accent2_400,
    onSecondary = DarkBg,
    secondaryContainer = Accent2_800,
    onSecondaryContainer = Accent2_100,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = Neutral800,
    onSurfaceVariant = Neutral300,
    outline = DarkDivider,
    error = Accent300,
    onError = DarkBg,
)

@Composable
fun TransformerTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TransformerTypography,
        shapes = TransformerShapes,
        content = content,
    )
}
