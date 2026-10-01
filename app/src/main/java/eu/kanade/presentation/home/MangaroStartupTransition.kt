package eu.kanade.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.R

/**
 * Lightweight Branded Startup Transition overlay for MANGARO (Phase 05.5.1-I.2).
 *
 * Uses AnimatedVisibility exit transition paired with a state machine to ensure
 * 100% complete unmounting from the composition tree after fade-out completes.
 */
@Composable
fun MangaroStartupTransition(
    ready: Boolean,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibility = remember { MutableTransitionState(true) }
    visibility.targetState = !ready

    AnimatedVisibility(
        visibleState = visibility,
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x33A78BFA),
                            Color(0x1AFFB800),
                            MangaroDesignSystem.BackgroundDark,
                        ),
                        radius = 900f,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .shadow(16.dp, CircleShape, spotColor = MangaroDesignSystem.LavenderPrimary)
                        .clip(CircleShape)
                        .background(MangaroDesignSystem.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_splash_logo),
                        contentDescription = "MANGARO Logo",
                        modifier = Modifier.size(72.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "MANGARO",
                    color = MangaroDesignSystem.GoldPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "عالمك الخاص للقراءة",
                    color = MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    LaunchedEffect(ready, visibility.isIdle, visibility.currentState) {
        if (ready && visibility.isIdle && !visibility.currentState) onDismissed()
    }
}
