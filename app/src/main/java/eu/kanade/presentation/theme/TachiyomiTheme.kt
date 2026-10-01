package eu.kanade.presentation.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme

@Composable
fun TachiyomiTheme(
    content: @Composable () -> Unit,
) {
    BaseTachiyomiTheme(content = content)
}

@Composable
fun TachiyomiPreviewTheme(
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(content)

@Composable
private fun BaseTachiyomiTheme(
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = TachiyomiColorScheme.darkScheme,
        content = content,
    )
}
