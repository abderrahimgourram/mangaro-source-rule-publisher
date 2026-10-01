package eu.kanade.presentation.browse.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val title = source?.name ?: ""
    val isLocalSource = source is LocalSource
    val isConfigurableSource = source is ConfigurableSource

    var isSearchActive by remember { mutableStateOf(!searchQuery.isNullOrBlank()) }
    var showDisplayMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        color = MangaroDesignSystem.SurfaceDark,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (isSearchActive) {
                // Expanded Search Bar
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MangaroDesignSystem.SurfaceHigh)
                        .border(
                            BorderStroke(1.dp, MangaroDesignSystem.GoldBorder),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MangaroDesignSystem.GoldPrimary,
                        modifier = Modifier.size(18.dp),
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isNullOrEmpty()) {
                            Text(
                                text = stringResource(MR.strings.action_search),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    textDirection = TextDirection.Content,
                                ),
                                color = Color.White.copy(alpha = 0.45f),
                            )
                        }

                        BasicTextField(
                            value = searchQuery ?: "",
                            onValueChange = onSearchQueryChange,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White,
                                fontSize = 13.5.sp,
                                textDirection = TextDirection.Content,
                            ),
                            cursorBrush = SolidColor(MangaroDesignSystem.GoldPrimary),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    onSearch(searchQuery ?: "")
                                    keyboardController?.hide()
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .runOnEnterKeyPressed {
                                    onSearch(searchQuery ?: "")
                                    keyboardController?.hide()
                                },
                        )
                    }

                    if (!searchQuery.isNullOrEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Close Search Button
                IconButton(
                    onClick = {
                        isSearchActive = false
                        onSearchQueryChange(null)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                        tint = MangaroDesignSystem.GoldPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            } else {
                // Regular Header Title & Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(
                        onClick = navigateUp,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.5.sp,
                                textDirection = TextDirection.Content,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isLocalSource) stringResource(MR.strings.local_source) else stringResource(MR.strings.label_sources),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                textDirection = TextDirection.Content,
                            ),
                            color = MangaroDesignSystem.LavenderPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Open Search Button
                    IconButton(
                        onClick = { isSearchActive = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(MR.strings.action_search),
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Display Mode Selector Button
                    Box {
                        IconButton(
                            onClick = { showDisplayMenu = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = when (displayMode) {
                                    LibraryDisplayMode.List -> Icons.AutoMirrored.Outlined.ViewList
                                    LibraryDisplayMode.ComfortableGrid -> Icons.Outlined.ViewModule
                                    LibraryDisplayMode.CoverOnlyGrid -> Icons.Outlined.GridView
                                    else -> Icons.Outlined.ViewModule
                                },
                                contentDescription = stringResource(MR.strings.action_display_mode),
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showDisplayMenu,
                            onDismissRequest = { showDisplayMenu = false },
                            modifier = Modifier
                                .background(MangaroDesignSystem.SurfaceDark)
                                .border(BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle), shape = RoundedCornerShape(12.dp)),
                        ) {
                            DisplayMenuItem(
                                text = stringResource(MR.strings.action_display_comfortable_grid),
                                icon = Icons.Outlined.ViewModule,
                                isSelected = displayMode == LibraryDisplayMode.ComfortableGrid,
                                onClick = {
                                    showDisplayMenu = false
                                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                                },
                            )
                            DisplayMenuItem(
                                text = stringResource(MR.strings.action_display_grid),
                                icon = Icons.Outlined.GridView,
                                isSelected = displayMode == LibraryDisplayMode.CompactGrid,
                                onClick = {
                                    showDisplayMenu = false
                                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                                },
                            )
                            DisplayMenuItem(
                                text = stringResource(MR.strings.action_display_cover_only_grid),
                                icon = Icons.Outlined.GridView,
                                isSelected = displayMode == LibraryDisplayMode.CoverOnlyGrid,
                                onClick = {
                                    showDisplayMenu = false
                                    onDisplayModeChange(LibraryDisplayMode.CoverOnlyGrid)
                                },
                            )
                            DisplayMenuItem(
                                text = stringResource(MR.strings.action_display_list),
                                icon = Icons.AutoMirrored.Outlined.ViewList,
                                isSelected = displayMode == LibraryDisplayMode.List,
                                onClick = {
                                    showDisplayMenu = false
                                    onDisplayModeChange(LibraryDisplayMode.List)
                                },
                            )
                        }
                    }

                    // Overflow Menu
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            modifier = Modifier
                                .background(MangaroDesignSystem.SurfaceDark)
                                .border(BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle), shape = RoundedCornerShape(12.dp)),
                        ) {
                            if (isLocalSource) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.label_help), color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                            contentDescription = null,
                                            tint = MangaroDesignSystem.GoldPrimary,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onHelpClick()
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_open_in_web_view), color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Public,
                                            contentDescription = null,
                                            tint = MangaroDesignSystem.GoldPrimary,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onWebViewClick()
                                    },
                                )
                            }

                            if (isConfigurableSource) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_settings), color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Settings,
                                            contentDescription = null,
                                            tint = MangaroDesignSystem.GoldPrimary,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onSettingsClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayMenuItem(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (isSelected) MangaroDesignSystem.GoldPrimary else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.5.sp,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MangaroDesignSystem.GoldPrimary else MangaroDesignSystem.LavenderPrimary,
            )
        },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MangaroDesignSystem.GoldPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        onClick = onClick,
    )
}
