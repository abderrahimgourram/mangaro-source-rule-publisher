package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal object TachiyomiColorScheme {
    val darkScheme = darkColorScheme(
        primary = Color(0xFFA78BFA), // luminous purple
        onPrimary = Color(0xFF150A21), // very dark purple for contrast
        primaryContainer = Color(0xFF381F5C),
        onPrimaryContainer = Color(0xFFD8B4E2),
        inversePrimary = Color(0xFF6D28D9),
        secondary = Color(0xFFFFB800), // gold / amber
        onSecondary = Color(0xFF261A00),
        secondaryContainer = Color(0xFF5E4300),
        onSecondaryContainer = Color(0xFFFFDF7D),
        tertiary = Color(0xFFFFB800),
        onTertiary = Color(0xFF261A00),
        tertiaryContainer = Color(0xFF5E4300),
        onTertiaryContainer = Color(0xFFFFDF7D),
        background = Color(0xFF0F0B13), // near-black / dark violet
        onBackground = Color(0xFFF0E6F6),
        surface = Color(0xFF18121D), // dark purple-toned cards
        onSurface = Color(0xFFF0E6F6),
        surfaceVariant = Color(0xFF231B2A), // slightly lighter elevated surface
        onSurfaceVariant = Color(0xFFC7BCD1), // muted lavender text secondary
        surfaceTint = Color(0xFFA78BFA),
        inverseSurface = Color(0xFFE2D6E9),
        inverseOnSurface = Color(0xFF18121D),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF7E7389), // subtle low-contrast purple-gray
        outlineVariant = Color(0xFF453B4F),
        surfaceContainerLowest = Color(0xFF0F0B13),
        surfaceContainerLow = Color(0xFF151019),
        surfaceContainer = Color(0xFF18121D),
        surfaceContainerHigh = Color(0xFF231B2A),
        surfaceContainerHighest = Color(0xFF2E2436),
    )
}
