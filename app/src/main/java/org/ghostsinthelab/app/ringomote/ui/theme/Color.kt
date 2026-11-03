package org.ghostsinthelab.app.ringomote.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Ringomote's own colour schemes, used when dynamic colour is off or unavailable (Android 9–11).
 *
 * The palette comes from the app icon: coral (the icon background, #FF9C8F) as primary, a warm
 * grey-brown as secondary and leaf green as tertiary, on cream-tinted surfaces. Tones follow the
 * Material 3 roles so that text on containers keeps WCAG AA contrast in both schemes; the remote
 * surface and buttons take their colours from these roles, never from fixed values.
 */
val RingomoteLightColors = lightColorScheme(
    primary = Color(0xFFA33E31),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF77574F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD4),
    onSecondaryContainer = Color(0xFF2C1510),
    tertiary = Color(0xFF4F6B34),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1EBB3),
    onTertiaryContainer = Color(0xFF0E2000),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDD9),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BE),
    inverseSurface = Color(0xFF392E2C),
    inverseOnSurface = Color(0xFFFFEDEA),
    inversePrimary = Color(0xFFFFB4A8),
)

val RingomoteDarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A8),
    onPrimary = Color(0xFF5F150B),
    primaryContainer = Color(0xFF7D2B1F),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFE7BDB5),
    onSecondary = Color(0xFF442A24),
    secondaryContainer = Color(0xFF5D4039),
    onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFFB5CE93),
    onTertiary = Color(0xFF223709),
    tertiaryContainer = Color(0xFF384E1E),
    onTertiaryContainer = Color(0xFFD1EBB3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDB),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDB),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    inverseSurface = Color(0xFFF1DEDB),
    inverseOnSurface = Color(0xFF392E2C),
    inversePrimary = Color(0xFFA33E31),
)
