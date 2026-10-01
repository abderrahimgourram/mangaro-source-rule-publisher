package eu.kanade.tachiyomi.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object DownloadsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "التنزيلات"
            val icon = rememberVectorPainter(Icons.Outlined.Download)
            return TabOptions(
                index = 3u,
                title = title,
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Do nothing for now
    }

    @Composable
    override fun Content() {
        DownloadQueueScreen.Content()
    }
}
