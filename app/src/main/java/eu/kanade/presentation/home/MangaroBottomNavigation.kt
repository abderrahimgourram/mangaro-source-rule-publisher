package eu.kanade.presentation.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.home.DownloadsTab
import eu.kanade.tachiyomi.ui.home.HomeTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MangaroNavigationBar(
    tabs: List<Tab>,
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    onTabReselected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MangaroDesignSystem.SurfaceDark,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle),
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                MangaroNavItem(
                    tab = tab,
                    selected = currentTab::class == tab::class,
                    onClick = {
                        if (currentTab::class == tab::class) {
                            onTabReselected(tab)
                        } else {
                            onTabSelected(tab)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.MangaroNavItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val animatedIconColor by animateColorAsState(
        targetValue = if (selected) MangaroDesignSystem.GoldPrimary else Color.White.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 200),
        label = "iconColor",
    )

    val animatedTextColor by animateColorAsState(
        targetValue = if (selected) MangaroDesignSystem.GoldPrimary else Color.White.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 200),
        label = "textColor",
    )

    val iconPair = getTabIconPair(tab)

    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    if (selected) MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.22f)
                    else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(
                badge = {
                    if (BrowseTab::class.isInstance(tab)) {
                        val extensionCount by produceState(initialValue = 0) {
                            Injekt.get<SourcePreferences>().extensionUpdatesCount.changes()
                                .collectLatest { value = it }
                        }
                        if (extensionCount > 0) {
                            Badge(
                                containerColor = MangaroDesignSystem.GoldPrimary,
                                contentColor = Color.Black,
                            ) {
                                val desc = pluralStringResource(
                                    MR.plurals.update_check_notification_ext_updates,
                                    count = extensionCount,
                                    extensionCount,
                                )
                                Text(
                                    text = extensionCount.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                    ),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = if (selected) iconPair.second else iconPair.first,
                    contentDescription = tab.options.title,
                    tint = animatedIconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = tab.options.title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textDirection = TextDirection.Content,
            ),
            color = animatedTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun getTabIconPair(tab: Tab): Pair<ImageVector, ImageVector> {
    return when {
        HomeTab::class.isInstance(tab) -> Icons.Outlined.Home to Icons.Filled.Home
        LibraryTab::class.isInstance(tab) -> Icons.Outlined.CollectionsBookmark to Icons.Filled.CollectionsBookmark
        BrowseTab::class.isInstance(tab) -> Icons.Outlined.Explore to Icons.Filled.Explore
        DownloadsTab::class.isInstance(tab) -> Icons.Outlined.Download to Icons.Filled.Download
        MoreTab::class.isInstance(tab) -> Icons.Outlined.MoreHoriz to Icons.Filled.MoreHoriz
        else -> Icons.Outlined.Home to Icons.Filled.Home
    }
}
