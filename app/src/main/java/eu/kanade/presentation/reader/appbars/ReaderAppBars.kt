package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.mangaro.MangaroReaderBottomPanel
import eu.kanade.presentation.reader.mangaro.MangaroReaderTopBar
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    chapterNumber: Float = 0f,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    chapterNavigatorType: ChapterNavigatorType,
    verticalNavigatorHeight: Float,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    onClickChapterSelector: () -> Unit = onClickSettings,
) {
    val preferences = Injekt.get<ReaderPreferences>()
    val customBrightness by preferences.customBrightness.collectAsState()
    val customBrightnessValue by preferences.customBrightnessValue.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Top Bar Overlay
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { -it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { -it } + fadeOut(readerBarsFadeAnimationSpec),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            MangaroReaderTopBar(
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                chapterNumber = chapterNumber,
                navigateUp = navigateUp,
                bookmarked = bookmarked,
                onToggleBookmarked = onToggleBookmarked,
                onOpenInWebView = onOpenInWebView,
                onOpenInBrowser = onOpenInBrowser,
                onShare = onShare,
            )
        }

        // Bottom Controls Overlay
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { it } + fadeOut(readerBarsFadeAnimationSpec),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MangaroReaderBottomPanel(
                chapterTitle = chapterTitle,
                chapterNumber = chapterNumber,
                onNextChapter = onNextChapter,
                enabledNext = enabledNext,
                onPreviousChapter = onPreviousChapter,
                enabledPrevious = enabledPrevious,
                onClickChapterSelector = onClickChapterSelector,

                currentPage = currentPage,
                totalPages = totalPages,
                onPageIndexChange = onPageIndexChange,
                onPageIndexChangeFinished = onPageIndexChangeFinished,

                brightnessValue = customBrightnessValue,
                isCustomBrightness = customBrightness,
                onBrightnessChange = {
                    preferences.customBrightness.set(true)
                    preferences.customBrightnessValue.set(it)
                },

                readingMode = readingMode,
                onClickReadingMode = onClickReadingMode,
                orientation = orientation,
                onClickOrientation = onClickOrientation,
                cropEnabled = cropEnabled,
                onClickCropBorder = onClickCropBorder,
                onClickSettings = onClickSettings,
            )
        }
    }
}
