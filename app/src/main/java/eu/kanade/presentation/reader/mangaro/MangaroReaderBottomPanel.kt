package eu.kanade.presentation.reader.mangaro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode

@Composable
fun MangaroReaderBottomPanel(
    chapterTitle: String?,
    chapterNumber: Float,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    onClickChapterSelector: () -> Unit,

    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,

    brightnessValue: Int,
    isCustomBrightness: Boolean,
    onBrightnessChange: (Int) -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formattedChapter = if (chapterNumber > 0f) {
        val numStr = if (chapterNumber % 1f == 0f) chapterNumber.toInt().toString() else chapterNumber.toString()
        "الفصل $numStr"
    } else if (!chapterTitle.isNullOrBlank()) {
        chapterTitle.replace(Regex("(?i)chapter\\s*"), "الفصل ")
    } else {
        "الفصل"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {}
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        colors = CardDefaults.cardColors(containerColor = Color(0xF218121D)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: Chapter Navigation Dropdown Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = enabledPrevious,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "الفصل السابق",
                        tint = if (enabledPrevious) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onClickChapterSelector)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = formattedChapter,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                IconButton(
                    onClick = onNextChapter,
                    enabled = enabledNext,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "الفصل التالي",
                        tint = if (enabledNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }

            // Row 2: Page Seek Control
            val safeTotalPages = totalPages.coerceAtLeast(1)
            val safeCurrentPage = currentPage.coerceIn(1, safeTotalPages)

            if (safeTotalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "$safeCurrentPage / $safeTotalPages",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Slider(
                        value = safeCurrentPage.toFloat(),
                        valueRange = 1f..safeTotalPages.toFloat(),
                        onValueChange = { onPageIndexChange(it.toInt()) },
                        onValueChangeFinished = onPageIndexChangeFinished,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )
                }
            }

            // Row 3: Brightness Quick Slider with Sun Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.WbSunny,
                    contentDescription = "سطوع منخفض",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )

                Slider(
                    value = if (isCustomBrightness) brightnessValue.toFloat() else 50f,
                    valueRange = 0f..100f,
                    onValueChange = { onBrightnessChange(it.toInt()) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.secondary,
                        activeTrackColor = MaterialTheme.colorScheme.secondary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )

                Icon(
                    imageVector = Icons.Outlined.WbSunny,
                    contentDescription = "سطوع مرتفع",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )

                Text(
                    text = if (isCustomBrightness) "$brightnessValue%" else "تلقائي",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            // Row 4: Quick Action Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClickReadingMode) {
                    Icon(
                        painter = painterResource(readingMode.iconRes),
                        contentDescription = "طريقة القراءة",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(onClick = onClickOrientation) {
                    Icon(
                        imageVector = orientation.icon,
                        contentDescription = "الاتجاه",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(onClick = onClickCropBorder) {
                    Icon(
                        painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                        contentDescription = "قص الحواف",
                        tint = if (cropEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(onClick = onClickSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "الإعدادات",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
