package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem

@Composable
fun MangaroMangaCard(
    item: HomeDiscoveryItem,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 118.dp,
) {
    val gradientBrush = MangaroCoverColors.rememberAtmosphericGradient(
        mangaId = item.mangaId,
        title = item.title,
    )

    val accessibilityLabel = "${item.title} من ${item.sourceName}"

    Card(
        modifier = modifier
            .width(cardWidth)
            .mangaroPressAndEntranceMotion(
                key = item.mangaId,
                onClick = { onMangaClick(item.mangaId) },
            )
            .semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(5.dp),
        ) {
            // Prominent 2:3 Cover Poster
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(4.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        BorderStroke(1.dp, Color(0x26A78BFA)),
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                MangaCover.Book(
                    data = item.coverData,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (item.availableVersions.size > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE61E172A))
                            .border(BorderStroke(0.5.dp, MangaroVisualTokens.GoldAccent.copy(alpha = 0.6f)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${item.availableVersions.size} مصادر",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MangaroVisualTokens.GoldAccent,
                        )
                    }
                }

                // Bottom subtle shadow overlay over cover
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .align(Alignment.BottomCenter)
                        .background(MangaroVisualTokens.CoverCardGradient),
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // Manga Title with Content-Aware TextDirection for English / Mixed / Arabic titles in RTL
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    lineHeight = 15.5.sp,
                    textDirection = TextDirection.Content,
                ),
                color = Color.White,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )

            Spacer(modifier = Modifier.height(2.dp))

            val displaySourceName = if (item.availableVersions.size > 1) {
                "${item.sourceName} +${item.availableVersions.size - 1}"
            } else {
                item.sourceName
            }

            // Source Name (No chapter information displayed)
            Text(
                text = displaySourceName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDirection = TextDirection.Content,
                ),
                color = MangaroVisualTokens.GoldAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )
        }
    }
}
