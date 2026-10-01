package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterDisplay
import tachiyomi.domain.history.model.HistoryWithRelations

@Composable
fun MangaroContinueReading(
    history: HistoryWithRelations,
    onResumeClick: () -> Unit,
    onMangaClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = MangaroCoverColors.rememberAtmosphericGradient(
        mangaId = history.mangaId,
        title = history.title,
    )

    val formattedChapter = formatChapterDisplay(history.chapterNumber)
    val accessibilityLabel = "متابعة قراءة ${history.title} $formattedChapter"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .mangaroPressAndEntranceMotion(
                key = history.mangaId to history.chapterId,
                onClick = onResumeClick,
            )
            .semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // FIRST CHILD IN ROW (In RTL layout = RIGHT side of screen): Cover Artwork
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(5.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            BorderStroke(1.dp, Color(0x40A78BFA)),
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    MangaCover.Book(
                        data = history.coverData,
                        contentDescription = history.title,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // SECOND CHILD IN ROW (In RTL layout = LEFT side of screen): Title, Chapter, and Badge
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    // Visual Affordance Badge: "متابعة القراءة" + Play Icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MangaroVisualTokens.PurplePrimary.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MangaroVisualTokens.PurplePrimary,
                        )
                        Text(
                            text = "متابعة القراءة",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                            ),
                            color = MangaroVisualTokens.PurplePrimary,
                        )
                    }

                    // Manga Title
                    Text(
                        text = history.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )

                    // Chapter Number
                    Text(
                        text = formattedChapter,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                        ),
                        color = MangaroVisualTokens.GoldAccent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
