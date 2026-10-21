package info.varden.hauk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Light color scheme derived from the Hauk brand color (#D80037) using the Material "content"
 * scheme. Used on devices that do not support dynamic color.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFAA0029),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD80037),
    onPrimaryContainer = Color(0xFFFFE9E8),
    inversePrimary = Color(0xFFFFB3B3),
    secondary = Color(0xFFAB313D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFF7077),
    onSecondaryContainer = Color(0xFF710018),
    tertiary = Color(0xFF893E00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFAE5100),
    onTertiaryContainer = Color(0xFFFFEAE0),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF291717),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF291717),
    surfaceVariant = Color(0xFFFFDAD9),
    onSurfaceVariant = Color(0xFF5D3F3F),
    surfaceTint = Color(0xFFBF002F),
    inverseSurface = Color(0xFF402B2B),
    inverseOnSurface = Color(0xFFFFEDEC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF916E6E),
    outlineVariant = Color(0xFFE6BDBC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceContainer = Color(0xFFFFE9E8),
    surfaceContainerHigh = Color(0xFFFFE1E1),
    surfaceContainerHighest = Color(0xFFFCDBDA),
    surfaceContainerLow = Color(0xFFFFF0EF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF3D2D2),
    primaryFixed = Color(0xFFFFDAD9),
    primaryFixedDim = Color(0xFFFFB3B3),
    onPrimaryFixed = Color(0xFF400009),
    onPrimaryFixedVariant = Color(0xFF920022),
    secondaryFixed = Color(0xFFFFDAD9),
    secondaryFixedDim = Color(0xFFFFB3B3),
    onSecondaryFixed = Color(0xFF400009),
    onSecondaryFixedVariant = Color(0xFF8A1828),
    tertiaryFixed = Color(0xFFFFDBC8),
    tertiaryFixedDim = Color(0xFFFFB68B),
    onTertiaryFixed = Color(0xFF321200),
    onTertiaryFixedVariant = Color(0xFF753400),
)

/**
 * Dark color scheme derived from the Hauk brand color (#D80037) using the Material "content"
 * scheme. Used on devices that do not support dynamic color.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB3B3),
    onPrimary = Color(0xFF680015),
    primaryContainer = Color(0xFFD80037),
    onPrimaryContainer = Color(0xFFFFE9E8),
    inversePrimary = Color(0xFFBF002F),
    secondary = Color(0xFFFFB3B3),
    onSecondary = Color(0xFF680015),
    secondaryContainer = Color(0xFF8E1B2A),
    onSecondaryContainer = Color(0xFFFF9E9F),
    tertiary = Color(0xFFFFB68B),
    onTertiary = Color(0xFF522300),
    tertiaryContainer = Color(0xFFAE5100),
    onTertiaryContainer = Color(0xFFFFEAE0),
    background = Color(0xFF1F0F0F),
    onBackground = Color(0xFFFCDBDA),
    surface = Color(0xFF1F0F0F),
    onSurface = Color(0xFFFCDBDA),
    surfaceVariant = Color(0xFF5D3F3F),
    onSurfaceVariant = Color(0xFFE6BDBC),
    surfaceTint = Color(0xFFFFB3B3),
    inverseSurface = Color(0xFFFCDBDA),
    inverseOnSurface = Color(0xFF402B2B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFAD8887),
    outlineVariant = Color(0xFF5D3F3F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF493434),
    surfaceContainer = Color(0xFF2D1B1B),
    surfaceContainerHigh = Color(0xFF392525),
    surfaceContainerHighest = Color(0xFF442F2F),
    surfaceContainerLow = Color(0xFF291717),
    surfaceContainerLowest = Color(0xFF190A0A),
    surfaceDim = Color(0xFF1F0F0F),
    primaryFixed = Color(0xFFFFDAD9),
    primaryFixedDim = Color(0xFFFFB3B3),
    onPrimaryFixed = Color(0xFF400009),
    onPrimaryFixedVariant = Color(0xFF920022),
    secondaryFixed = Color(0xFFFFDAD9),
    secondaryFixedDim = Color(0xFFFFB3B3),
    onSecondaryFixed = Color(0xFF400009),
    onSecondaryFixedVariant = Color(0xFF8A1828),
    tertiaryFixed = Color(0xFFFFDBC8),
    tertiaryFixedDim = Color(0xFFFFB68B),
    onTertiaryFixed = Color(0xFF321200),
    onTertiaryFixedVariant = Color(0xFF753400),
)

/**
 * The Material 3 Expressive theme used throughout Hauk. Uses the system's dynamic colors where
 * available, and falls back to a color scheme based on the Hauk brand color otherwise.
 *
 * Dark mode follows the configuration of the current activity, which reflects the night mode
 * preference applied through AppCompatDelegate.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HaukTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme: ColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkColors else LightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
