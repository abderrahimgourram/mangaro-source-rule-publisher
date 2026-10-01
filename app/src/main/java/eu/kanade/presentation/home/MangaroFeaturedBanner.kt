package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem

@Composable
fun MangaroFeaturedBanner(
    item: HomeDiscoveryItem,
    canRotate: Boolean,
    onOpenManga: (Long) -> Unit,
    onNextStory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onOpenManga(item.mangaId) },
        shape = RoundedCornerShape(MangaroVisualTokens.FeaturedBannerRadius),
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceHigh),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MangaroVisualTokens.HybridBannerBackground),
        ) {
            // Force RTL layout direction for Arabic interface so Cover is RIGHT and Info is LEFT
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Right Side (Start in RTL): Complete Uncropped 2:3 Vertical Manga Cover
                    Box(
                        modifier = Modifier
                            .width(112.dp)
                            .aspectRatio(2f / 3f)
                            .shadow(8.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
                                RoundedCornerShape(12.dp),
                            ),
                    ) {
                        MangaCover.Book(
                            data = item.coverData,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Left Side (End in RTL): Information & Primary CTA
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 168.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Top Section: Source Badge & Title
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Source Badge Chip
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x28A78BFA))
                                    .border(
                                        BorderStroke(0.5.dp, MangaroVisualTokens.PurplePrimary.copy(alpha = 0.4f)),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = item.sourceName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    ),
                                    color = MangaroVisualTokens.GoldAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // Manga Title
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    lineHeight = 22.sp,
                                ),
                                color = Color.White,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Bottom Section: Single Primary Action Button ("عرض التفاصيل")
                        Button(
                            onClick = { onOpenManga(item.mangaId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MangaroVisualTokens.PurplePrimary,
                                contentColor = Color.Black,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                        ) {
                            Text(
                                text = "عرض التفاصيل",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                ),
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
