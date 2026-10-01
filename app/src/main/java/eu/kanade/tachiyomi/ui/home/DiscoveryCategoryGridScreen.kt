package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.home.MangaroMangaCard
import eu.kanade.presentation.home.MangaroSourceChooserSheet
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.domain.source.discovery.model.DiscoveryCategory
import tachiyomi.presentation.core.components.material.Scaffold

data class DiscoveryCategoryGridScreen(
    val categoryName: String,
    val title: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val category = when (categoryName) {
            "POPULAR" -> DiscoveryCategory.POPULAR
            "NEW" -> DiscoveryCategory.NEW
            "LATEST" -> DiscoveryCategory.LATEST
            "COMPLETED" -> DiscoveryCategory.COMPLETED
            else -> DiscoveryCategory.POPULAR
        }

        val navigator = LocalNavigator.currentOrThrow
        val gridViewModel = viewModel<DiscoveryCategoryGridViewModel>(
            factory = DiscoveryCategoryGridViewModel.Factory(category)
        )
        val state by gridViewModel.state.collectAsState()
        val gridState = rememberLazyGridState()
        var selectedMultiSourceItem by remember { mutableStateOf<HomeDiscoveryItem?>(null) }

        // Pagination Trigger when scrolling near the bottom
        val shouldLoadMore by remember {
            derivedStateOf {
                val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = gridState.layoutInfo.totalItemsCount
                lastVisibleIndex >= totalItems - 6 && totalItems > 0
            }
        }

        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && !state.isPaginationLoading && state.hasMore) {
                gridViewModel.loadNextPage()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { paddingValues ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 12.dp),
            ) {
                when {
                    state.isLoadingInitial -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(8) {
                                HomeTab.MangaCardSkeleton(width = 150.dp)
                            }
                        }
                    }
                    state.error != null && state.items.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = state.error!!,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Button(
                                    onClick = { gridViewModel.loadInitialPage(isRefresh = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MangaroDesignSystem.GoldPrimary),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("إعادة المحاولة", color = MaterialTheme.colorScheme.surface)
                                }
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = state.items,
                                key = { item -> "${item.sourceId}_${item.mangaId}" },
                            ) { item ->
                                MangaroMangaCard(
                                    item = item,
                                    onMangaClick = {
                                        if (item.availableVersions.size > 1) {
                                            selectedMultiSourceItem = item
                                        } else {
                                            navigator.push(MangaScreen(item.mangaId, true))
                                        }
                                    },
                                    cardWidth = 150.dp,
                                )
                            }

                            if (state.isPaginationLoading) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = MangaroDesignSystem.GoldPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedMultiSourceItem != null) {
                    MangaroSourceChooserSheet(
                        item = selectedMultiSourceItem!!,
                        onSelectVersion = { mangaId ->
                            navigator.push(MangaScreen(mangaId, true))
                        },
                        onDismiss = { selectedMultiSourceItem = null },
                    )
                }
            }
        }
    }
}
