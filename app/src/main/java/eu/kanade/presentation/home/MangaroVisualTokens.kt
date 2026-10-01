package eu.kanade.presentation.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object MangaroVisualTokens {
    val CardCornerRadius = 14.dp
    val FeaturedBannerRadius = 20.dp
    val ChipCornerRadius = 10.dp

    val FeaturedBannerGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0x200F0B13),
            Color(0x700F0B13),
            Color(0xEA0F0B13),
            Color(0xFF0F0B13),
        ),
    )

    val HybridBannerBackground = Brush.linearGradient(
        colors = listOf(
            Color(0xFF281C38),
            Color(0xFF1F172C),
            Color(0xFF161020),
        ),
    )

    val CoverCardGradient = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            Color(0x40000000),
            Color(0xE0000000),
        ),
    )

    val CardBorderColor = Color(0x33A78BFA)
    val GoldAccent = Color(0xFFFFB800)
    val PurplePrimary = Color(0xFFA78BFA)
    val SurfaceDark = Color(0xFF18121D)
    val SurfaceHigh = Color(0xFF231B2A)
}
