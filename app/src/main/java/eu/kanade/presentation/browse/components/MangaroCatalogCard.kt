package eu.kanade.presentation.browse.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.home.MangaroCoverColors
import eu.kanade.presentation.home.MangaroVisualTokens
import eu.kanade.presentation.home.mangaroPressAndEntranceMotion
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

/**
 * Signature Mangaro Catalog Grid Card (Comfortable Grid).
 * Follows the Title-Only Information Rule for single-source catalogs:
 * Prominent 2:3 poster cover + Title placed in a separate area BELOW the cover.
 * No source name, chapter count, or fake rating information is shown.
 */
@Composable
fun MangaroCatalogComfortableCard(
    manga: Manga,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = MangaroCoverColors.rememberAtmosphericGradient(
        mangaId = manga.id,
        title = manga.title,
    )

    val coverData = MangaCoverModel(
        mangaId = manga.id,
        sourceId = manga.source,
        isMangaFavorite = manga.favorite,
        url = manga.thumbnailUrl,
        lastModified = manga.coverLastModified,
    )

    val coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .mangaroPressAndEntranceMotion(
                key = manga.id,
                onClick = onClick,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = manga.title },
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceDark),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
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
                    data = coverData,
                    contentDescription = manga.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(coverAlpha),
                )

                // Discreet favorite badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    InLibraryBadge(enabled = manga.favorite)
                }

                // Bottom subtle gradient accent on cover
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .align(Alignment.BottomCenter)
                        .background(MangaroVisualTokens.CoverCardGradient),
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // Manga Title placed in a separate area BELOW the cover (Title-Only Rule)
            Text(
                text = manga.title,
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
                    .padding(horizontal = 2.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Signature Mangaro Compact Card (Compact Grid & Cover Only Grid).
 * In Compact Grid ([showTitle] = true), renders manga title BELOW the cover image (never overlaid).
 * In Cover Only Grid ([showTitle] = false), renders purely the cover poster artwork.
 */
@Composable
fun MangaroCatalogCompactCard(
    manga: Manga,
    showTitle: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = MangaroCoverColors.rememberAtmosphericGradient(
        mangaId = manga.id,
        title = manga.title,
    )

    val coverData = MangaCoverModel(
        mangaId = manga.id,
        sourceId = manga.source,
        isMangaFavorite = manga.favorite,
        url = manga.thumbnailUrl,
        lastModified = manga.coverLastModified,
    )

    val coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .mangaroPressAndEntranceMotion(
                key = manga.id,
                onClick = onClick,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = manga.title },
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceDark),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(4.dp),
        ) {
            // Prominent 2:3 Cover Poster
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(3.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        BorderStroke(1.dp, Color(0x26A78BFA)),
                        RoundedCornerShape(8.dp),
                    ),
            ) {
                MangaCover.Book(
                    data = coverData,
                    contentDescription = manga.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(coverAlpha),
                )

                // Discreet favorite badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    InLibraryBadge(enabled = manga.favorite)
                }

                // Bottom subtle gradient accent on cover
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .align(Alignment.BottomCenter)
                        .background(MangaroVisualTokens.CoverCardGradient),
                )
            }

            if (showTitle) {
                Spacer(modifier = Modifier.height(4.dp))

                // Manga Title in a separate text area BELOW the cover artwork
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        lineHeight = 14.5.sp,
                        textDirection = TextDirection.Content,
                    ),
                    color = Color.White,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * Signature Mangaro List Card (List Mode).
 * Renders horizontal item with poster thumbnail, title with TextDirection.Content, and favorite badge.
 */
@Composable
fun MangaroCatalogListCard(
    manga: Manga,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    modifier: Modifier = Modifier,
) {
    val coverData = MangaCoverModel(
        mangaId = manga.id,
        sourceId = manga.source,
        isMangaFavorite = manga.favorite,
        url = manga.thumbnailUrl,
        lastModified = manga.coverLastModified,
    )

    val coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .mangaroPressAndEntranceMotion(
                key = manga.id,
                onClick = onClick,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = manga.title },
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceDark),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        BorderStroke(1.dp, Color(0x26A78BFA)),
                        RoundedCornerShape(6.dp),
                    ),
            ) {
                MangaCover.Book(
                    data = coverData,
                    contentDescription = manga.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(coverAlpha),
                )
            }

            Text(
                text = manga.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textDirection = TextDirection.Content,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )

            InLibraryBadge(enabled = manga.favorite)
        }
    }
}
