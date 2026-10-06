package io.github.jordanwoodson.tinyscreen.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// destiny.gg's own palette: near-black surfaces with its blue accent
private val DggDark = darkColorScheme(
    primary = Color(0xFF4FA3FF),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF1D3B5C),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFFFC531),
    onSecondary = Color(0xFF3D2E00),
    secondaryContainer = Color(0xFF574400),
    onSecondaryContainer = Color(0xFFFFE08F),
    tertiary = Color(0xFF53FC18),
    background = Color(0xFF111111),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF151618),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF2A2C31),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceContainerLowest = Color(0xFF0B0B0C),
    surfaceContainerLow = Color(0xFF17181A),
    surfaceContainer = Color(0xFF1B1C1F),
    surfaceContainerHigh = Color(0xFF232427),
    surfaceContainerHighest = Color(0xFF2D2E32),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF3A3C42),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TinyscreenTheme(
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        DggDark
    }
    val scheme = if (pureBlack) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF0E0E0E),
        )
    } else {
        base
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
