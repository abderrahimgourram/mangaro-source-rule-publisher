package eu.kanade.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Click modifier for the Immersive Reading Card.
 * Ensures complete card interactivity across all touch regions without spawning
 * coroutine animation loops during fast list scrolling.
 */
@Composable
fun Modifier.mangaroPressAndEntranceMotion(
    key: Any?,
    onClick: () -> Unit,
): Modifier {
    return this.clickable(onClick = onClick)
}
