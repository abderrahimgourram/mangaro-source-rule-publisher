package eu.kanade.presentation.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.home.MangaroVisualTokens

/**
 * Unified Mangaro Design System tokens & visual specs for Phase 05.
 * Provides a single source of truth for color palettes, borders, surface hierarchy,
 * card shapes, typography tokens, and action highlights across Home, Discover, and Navigation.
 */
object MangaroDesignSystem {

    // Surfaces
    val BackgroundDark = Color(0xFF0F0B13)
    val SurfaceDark = Color(0xFF18121D)
    val SurfaceHigh = Color(0xFF231B2A)
    val SurfaceCardGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF231B2A),
            Color(0xFF18121D),
        ),
    )

    // Brand Accents
    val GoldPrimary = Color(0xFFFFB800)
    val LavenderPrimary = Color(0xFFA78BFA)
    val BorderSubtle = Color(0x33A78BFA)
    val BorderHighlight = Color(0x66A78BFA)
    val GoldBorder = Color(0x80FFB800)

    // Shapes
    val ShapeCard = RoundedCornerShape(14.dp)
    val ShapeBanner = RoundedCornerShape(20.dp)
    val ShapeChip = RoundedCornerShape(10.dp)
    val ShapeButton = RoundedCornerShape(12.dp)

    // Border Strokes
    val StrokeSubtle = BorderStroke(1.dp, BorderSubtle)
    val StrokeGold = BorderStroke(1.dp, GoldBorder)

    // Typography Tokens
    val SectionHeaderStyle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = Color.White,
            textDirection = TextDirection.Content,
        )

    val CardTitleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            lineHeight = 15.5.sp,
            color = Color.White,
            textDirection = TextDirection.Content,
        )

    val BadgeTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            color = GoldPrimary,
            textDirection = TextDirection.Content,
        )
}
