package app.servicecalendar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DeepBlue = Color(0xFF0C688F)
private val Teal = Color(0xFF16AE7D)
private val TealContainer = Color(0xFF87D6B9)
private val OffWhite = Color(0xFFFCFCFC)
private val InkBlack = Color(0xFF1B1B1B)

private val LightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCE6F3),
    onPrimaryContainer = Color(0xFF003349),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = TealContainer,
    onSecondaryContainer = Color(0xFF00382A),
    tertiary = Color(0xFFFF8C0A),
    onTertiary = Color.White,
    background = OffWhite,
    onBackground = InkBlack,
    surface = OffWhite,
    onSurface = InkBlack,
    surfaceVariant = Color(0xFFEDEFF0),
    onSurfaceVariant = Color(0xFF5F6A70),
    outline = Color(0xFFB8BEC2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC4E8),
    onPrimary = Color(0xFF00344B),
    primaryContainer = Color(0xFF0F6B96),
    onPrimaryContainer = Color(0xFFCCE6F3),
    secondary = Color(0xFF5CD6A5),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF136B4D),
    onSecondaryContainer = Color(0xFFB6F0D8),
    tertiary = Color(0xFFFFB366),
    onTertiary = Color(0xFF4A2800),
    background = Color(0xFF121517),
    onBackground = Color(0xFFE3E5E6),
    surface = Color(0xFF121517),
    onSurface = Color(0xFFE3E5E6),
    surfaceVariant = Color(0xFF2A3033),
    onSurfaceVariant = Color(0xFFBCC3C7),
    outline = Color(0xFF6F797E),
)

@Composable
fun ServiceCalendarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
