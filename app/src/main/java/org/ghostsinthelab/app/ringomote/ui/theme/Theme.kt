package org.ghostsinthelab.app.ringomote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.ghostsinthelab.app.ringomote.settings.Appearance

/** Whether this Android version can provide a wallpaper-based (dynamic) colour scheme. */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Applies the colour scheme chosen by [appearance]: light or dark per [Appearance.isDark]
 * (following the system when the mode is System), dynamic colours when asked for and available
 * (Android 12+), otherwise [RingomoteLightColors] / [RingomoteDarkColors].
 *
 * Recomposes when the setting or the system dark mode changes; the activity does not need to
 * restart. With `enableEdgeToEdge()` the system bars follow the Compose background.
 */
@Composable
fun RingomoteTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val darkTheme = appearance.isDark(systemDark = isSystemInDarkTheme())
    val colorScheme = when {
        appearance.dynamicColor && supportsDynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> RingomoteDarkColors
        else -> RingomoteLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
