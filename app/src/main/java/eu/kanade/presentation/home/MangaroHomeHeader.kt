package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R

@Composable
fun MangaroHomeHeader(
    activeDownloadsCount: Int,
    onSearchClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand Group: Official Full-color Logo + Application Name + Arabic Subtitle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_splash_logo),
                contentDescription = "MANGARO Logo",
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "MANGARO",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.5.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "عالمك الخاص للقراءة",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    ),
                    color = MangaroVisualTokens.PurplePrimary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Action Group: Search, Downloads (if active), Settings
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderActionButton(
                icon = Icons.Outlined.Search,
                contentDescription = "بحث",
                onClick = onSearchClick,
            )

            if (activeDownloadsCount > 0) {
                HeaderActionButton(
                    icon = Icons.Outlined.Download,
                    contentDescription = "التنزيلات",
                    onClick = onDownloadsClick,
                    badgeCount = activeDownloadsCount,
                )
            }

            HeaderActionButton(
                icon = Icons.Outlined.Settings,
                contentDescription = "الإعدادات",
                onClick = onSettingsClick,
            )
        }
    }
}

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MangaroVisualTokens.SurfaceHigh)
                .border(
                    BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(38.dp),
            ) {
                if (badgeCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = MangaroVisualTokens.PurplePrimary) {
                                Text(
                                    text = badgeCount.toString(),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = contentDescription,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}
