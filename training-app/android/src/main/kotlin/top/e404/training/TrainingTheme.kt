package top.e404.training

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val lightScheme = lightColorScheme(primary = Color(0xFF236A58), onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2D6), onPrimaryContainer = Color(0xFF002117), secondary = Color(0xFF516A60),
    surface = Color(0xFFFAFCFA), background = Color(0xFFFAFCFA))
private val darkScheme = darkColorScheme(primary = Color(0xFF8ED5B6), onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF07513D), onPrimaryContainer = Color(0xFFA7F2D6), secondary = Color(0xFFB0CBBE),
    surface = Color(0xFF101512), background = Color(0xFF101512), onSurface = Color(0xFFDFE5DE),
    surfaceContainer = Color(0xFF1C211D), surfaceContainerHigh = Color(0xFF272C27), surfaceContainerHighest = Color(0xFF323832))

@Composable fun TrainingTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.LIGHT -> false; Appearance.DARK -> true }
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController((view.context as Activity).window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}
