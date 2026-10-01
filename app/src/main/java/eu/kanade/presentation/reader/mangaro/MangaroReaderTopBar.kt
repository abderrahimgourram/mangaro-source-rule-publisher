package eu.kanade.presentation.reader.mangaro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MangaroReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    chapterNumber: Float,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    val formattedChapter = remember(chapterTitle, chapterNumber) {
        if (chapterNumber > 0f) {
            val numStr = if (chapterNumber % 1f == 0f) chapterNumber.toInt().toString() else chapterNumber.toString()
            "الفصل $numStr"
        } else if (!chapterTitle.isNullOrBlank()) {
            chapterTitle.replace(Regex("(?i)chapter\\s*"), "الفصل ")
        } else {
            "الفصل"
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF00F0B13))
            .pointerInput(Unit) {}
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            IconButton(onClick = navigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mangaTitle ?: "",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formattedChapter,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onToggleBookmarked) {
                Icon(
                    imageVector = if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (bookmarked) "إزالة الإشارة المرجعية" else "إضافة إشارة مرجعية",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (onOpenInWebView != null || onOpenInBrowser != null || onShare != null) {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "خيارات إضافية",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
                    onOpenInWebView?.let {
                        DropdownMenuItem(
                            text = { Text("فتح في العرض الداخلي") },
                            onClick = {
                                showOverflowMenu = false
                                it()
                            },
                        )
                    }
                    onOpenInBrowser?.let {
                        DropdownMenuItem(
                            text = { Text("فتح في المتصفح") },
                            onClick = {
                                showOverflowMenu = false
                                it()
                            },
                        )
                    }
                    onShare?.let {
                        DropdownMenuItem(
                            text = { Text("مشاركة") },
                            onClick = {
                                showOverflowMenu = false
                                it()
                            },
                        )
                    }
                }
            }
        }
    }
}
