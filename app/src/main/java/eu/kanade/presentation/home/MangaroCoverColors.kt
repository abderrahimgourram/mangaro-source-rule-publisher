package eu.kanade.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object MangaroCoverColors {

    /**
     * Generates a restrained, cover-inspired atmospheric gradient brush.
     * Anchors strongly on Mangaro's signature dark-purple identity while incorporating
     * a subtle 25% tint variation derived from the manga title/ID.
     * Prevents warm/bright cover art from turning cards brown or yellow.
     */
    @Composable
    fun rememberAtmosphericGradient(mangaId: Long, title: String): Brush {
        return remember(mangaId, title) {
            val hash = (title.hashCode() xor mangaId.hashCode()) and 0x7FFFFFFF
            val rawHue = (hash % 360).toFloat()

            // Anchor 75% on Mangaro's signature dark-violet hue (270 deg)
            val purpleTargetHue = 270f
            val blendedHue = (purpleTargetHue * 0.75f + rawHue * 0.25f) % 360f

            val darkPurpleBase = Color(0xFF130D1A)
            val midPurpleSurface = Color(0xFF1B1325)
            val subtleAccentTone = Color.hsl(
                hue = blendedHue,
                saturation = 0.22f,
                lightness = 0.16f,
            )

            // In RTL layout, the gradient flows from deep dark purple on the text side (start/left)
            // to a subtle cover-accent tone behind the cover artwork (end/right).
            Brush.horizontalGradient(
                colors = listOf(
                    darkPurpleBase,
                    midPurpleSurface,
                    subtleAccentTone,
                ),
            )
        }
    }
}
