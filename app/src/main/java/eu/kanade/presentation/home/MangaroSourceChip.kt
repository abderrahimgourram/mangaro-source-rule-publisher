package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.home.HomeSourceItem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MangaroSourceChip(
    source: HomeSourceItem,
    onSourceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chipWidth: Dp = 142.dp,
) {
    val sourceIcon by produceState<ImageBitmap?>(initialValue = null, source.id) {
        value = withContext(Dispatchers.IO) {
            try {
                Injekt.get<ExtensionManager>().getAppIconForSource(source.id)?.toBitmap()?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Surface(
        onClick = { onSourceClick(source.id) },
        shape = RoundedCornerShape(16.dp),
        color = MangaroDesignSystem.SurfaceDark,
        border = BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle),
        tonalElevation = 4.dp,
        modifier = modifier.width(chipWidth),
    ) {
        Row(
            modifier = Modifier
                .background(MangaroDesignSystem.SurfaceCardGradient)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // Rounded-Square Source Badge
            MangaroSourceBadge(
                sourceName = source.name,
                sourceIcon = sourceIcon,
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textDirection = TextDirection.Content,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                ) {
                    Text(
                        text = if (source.lang == "ar") "العربية" else source.lang.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            textDirection = TextDirection.Content,
                        ),
                        color = MangaroDesignSystem.GoldPrimary,
                    )
                }
            }
        }
    }
}

@Composable
fun MangaroSourceBadge(
    sourceName: String,
    sourceIcon: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val badgeShape = RoundedCornerShape(12.dp)

    if (sourceIcon != null) {
        Box(
            modifier = modifier
                .size(36.dp)
                .clip(badgeShape)
                .background(MangaroDesignSystem.SurfaceHigh)
                .border(BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle), shape = badgeShape)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = sourceIcon,
                contentDescription = sourceName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(badgeShape),
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(36.dp)
                .clip(badgeShape)
                .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.15f))
                .border(BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle), shape = badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = sourceName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                color = MangaroDesignSystem.GoldPrimary,
            )
        }
    }
}
